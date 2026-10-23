import Foundation
import XCTest
@testable import TimeWhisper

/// Checks the Core ML tagger against what PyTorch said for the same sentences (`reference.json`, written by
/// `training/export.py`). The model is not in git: these tests run when `TIME_WHISPER_TAGGER` names its folder,
/// or when it is at `build/tagger-coreml`, and are skipped otherwise.
final class CoreMLTaggerTests: XCTestCase {
    private struct Case: Decodable { let text: String; let inputIds: [Int]; let wordIds: [Int?]; let labels: [[Double]]; let intent: [Double]; let urgency: [Double] }
    private struct Meta: Decodable { let labels: [String] }

    private func folder() throws -> URL {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let folder = ProcessInfo.processInfo.environment["TIME_WHISPER_TAGGER"].map { URL(fileURLWithPath: $0) } ?? root.appendingPathComponent("build/tagger-coreml")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: folder.appendingPathComponent("reference.json").path), "no tagger model at \(folder.path)")
        return folder
    }
    private func cases(_ folder: URL) throws -> [Case] {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode([Case].self, from: Data(contentsOf: folder.appendingPathComponent("reference.json")))
    }

    func testSplitsLikeTheParserWithUTF16Ranges() {
        let words = CoreMLTagger.words("  📅 call\tBob  ")
        XCTAssertEqual(words.map(\.text), ["📅", "call", "Bob"])
        XCTAssertEqual(words.map(\.range), [SourceRange(offset: 2, length: 2), SourceRange(offset: 5, length: 4), SourceRange(offset: 10, length: 3)])
    }
    func testEncodesAsTheModelWasTrained() async throws {
        let folder = try folder()
        let tagger = CoreMLTagger(folder: folder)
        for reference in try cases(folder) {
            let encoded = try await tagger.encode(reference.text.split(separator: " ").map(String.init))
            XCTAssertEqual(encoded.ids, reference.inputIds, reference.text)
        }
    }
    func testLabelsMatchPyTorch() async throws {
        let folder = try folder()
        let tagger = CoreMLTagger(folder: folder)
        let names = try JSONDecoder().decode(Meta.self, from: Data(contentsOf: folder.appendingPathComponent("labels.json"))).labels
        for reference in try cases(folder) {
            let tagging = try await tagger.tag(reference.text, language: "en")
            // The first piece of each word speaks for it, as in training.
            var firsts: [Int] = []
            var previous: Int? = nil
            for (piece, word) in reference.wordIds.enumerated() { if let word, word != previous { firsts.append(piece) }; previous = word }
            let expected = firsts.map { piece -> TokenLabel? in
                let scores = reference.labels[piece]
                let best = scores.indices.max { scores[$0] < scores[$1] } ?? 0
                return TokenLabel(rawValue: names[best])
            }
            XCTAssertEqual(tagging.tokens.map(\.label), expected, reference.text)
            XCTAssertEqual(tagging.event, reference.intent[1], accuracy: 0.02, reference.text)
            XCTAssertEqual(tagging.urgency[.high] ?? 0, reference.urgency[3], accuracy: 0.02, reference.text)
        }
    }
}
