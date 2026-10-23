import CoreML
import Foundation
import Hub
import Tokenizers

/// A `Tagger` backed by the Core ML model that `training/export.py` writes: a folder with `TimeWhisperTagger.mlpackage`,
/// `tokenizer.json`, `tokenizer_config.json` and `labels.json`. One model serves every language it was trained for, and it
/// is loaded on first use, so creating a tagger costs nothing.
public actor CoreMLTagger: Tagger {
    private let folder: URL
    private let computeUnits: MLComputeUnits
    /// The model compiled from the package, kept after `close()` so that loading again does not compile again.
    private var compiled: URL?
    private var loaded: Loaded?

    /// - Parameters:
    ///   - folder: the folder `export.py` wrote.
    ///   - computeUnits: the CPU by default, which is fastest for a model this small (about 1.7 ms a sentence on Apple Silicon).
    public init(folder: URL, computeUnits: MLComputeUnits = .cpuOnly) {
        self.folder = folder; self.computeUnits = computeUnits
    }

    /// The labels of the words, split as the parser splits them, and the model's reading of the whole text. A word's label is
    /// that of its first piece, as in training. Words past what the model reads are left untagged; the parser treats them as title.
    public func tag(_ text: String, language: String) async throws -> Tagging {
        let model = try await load()
        let words = Self.words(text)
        guard !words.isEmpty else { return Tagging(tokens: [], task: 0, event: 0, none: 1) }
        let (ids, firsts) = model.encode(words.map(\.text))
        let shape = [1, ids.count]
        let input = try MLDictionaryFeatureProvider(dictionary: [
            "input_ids": MLMultiArray(MLShapedArray<Int32>(scalars: ids.map(Int32.init), shape: shape)),
            "attention_mask": MLMultiArray(MLShapedArray<Int32>(repeating: 1, shape: shape)),
        ])
        let output = try await model.model.prediction(from: input)
        guard let labels = output.featureValue(for: "labels")?.multiArrayValue.map(Self.scalars),
              let intent = output.featureValue(for: "intent")?.multiArrayValue.map(Self.scalars),
              let urgency = output.featureValue(for: "urgency")?.multiArrayValue.map(Self.scalars) else { throw CoreMLTaggerError.unexpectedOutput }

        // Probabilities come piece by piece, one row of label scores per piece.
        let count = model.labels.count
        let tokens = zip(words, firsts).map { word, piece -> TaggedToken in
            let row = labels[piece * count..<(piece + 1) * count]
            let best = row.indices.max { row[$0] < row[$1] }!
            return TaggedToken(range: word.range, label: model.labels[best - piece * count], confidence: row[best])
        }
        return Tagging(tokens: tokens, task: intent[0], event: intent[1], none: intent[2],
                       urgency: Dictionary(uniqueKeysWithValues: zip(model.urgencies, urgency)))
    }

    /// Lets the model, tokenizer and labels go; the next request loads them again. The parser calls it on
    /// `releaseResources()`, so an idle app does not keep the model in memory.
    public func close() async {
        loaded = nil
    }

    /// The pieces the model reads for the words, opened and closed, and where each word's first piece is.
    func encode(_ words: [String]) async throws -> (ids: [Int], firsts: [Int]) {
        try await load().encode(words)
    }

    /// Loads the model, the tokenizer and the labels once.
    private func load() async throws -> Loaded {
        if let loaded { return loaded }
        let compiled: URL
        if let known = self.compiled { compiled = known } else { compiled = try await MLModel.compileModel(at: folder.appendingPathComponent("TimeWhisperTagger.mlpackage")) }
        self.compiled = compiled
        let configuration = MLModelConfiguration()
        configuration.computeUnits = computeUnits
        let meta = try JSONDecoder().decode(Meta.self, from: Data(contentsOf: folder.appendingPathComponent("labels.json")))
        let loaded = try Loaded(model: MLModel(contentsOf: compiled, configuration: configuration), tokenizer: Self.tokenizer(folder), meta: meta)
        self.loaded = loaded
        return loaded
    }

    /// The tokenizer described by tokenizer.json. Its class is taken from the vocabulary's own type, not from
    /// tokenizer_config.json: newer versions of transformers write a generic class name there ("TokenizersBackend")
    /// that swift-transformers does not know and would read as BPE.
    private static func tokenizer(_ folder: URL) throws -> any Tokenizer {
        func json(_ name: String) throws -> [NSString: Any] {
            guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent(name))) as? [NSString: Any] else {
                throw CoreMLTaggerError.unexpectedOutput
            }
            return object
        }
        let data = try json("tokenizer.json")
        var config = try json("tokenizer_config.json")
        let classes = ["Unigram": "XLMRobertaTokenizer", "WordPiece": "BertTokenizer", "BPE": "GPT2Tokenizer"]
        if let type = (data["model"] as? [NSString: Any])?["type"] as? String, let name = classes[type] { config["tokenizer_class"] = name }
        return try AutoTokenizer.from(tokenizerConfig: Config(config), tokenizerData: Config(data))
    }

    /// The values of a model output, in order, whatever its element type and layout.
    private static func scalars(_ array: MLMultiArray) -> [Double] {
        MLShapedArray<Float>(converting: array).scalars.map(Double.init)
    }

    /// The words of the text and their ranges in UTF-16 code units, split as the parser splits text: on ASCII whitespace.
    static func words(_ text: String) -> [(text: String, range: SourceRange)] {
        let units = Array(text.utf16)
        return units.split { $0 == 0x20 || (0x09...0x0D).contains($0) }.map { word in
            (String(decoding: word, as: UTF16.self), SourceRange(offset: word.startIndex, length: word.count))
        }
    }
}

/// What a tagger loads once: the model, its tokenizer, and the names of its outputs.
private struct Loaded {
    /// The most word pieces the model reads, including the two that open and close the text.
    static let maxPieces = 128

    let model: MLModel
    let tokenizer: any Tokenizer
    let labels: [TokenLabel]
    let urgencies: [Priority]
    /// The pieces that open and close a text: what the tokenizer adds around nothing at all.
    let open: Int, close: Int

    init(model: MLModel, tokenizer: any Tokenizer, meta: Meta) throws {
        let levels: [String: Priority] = ["none": .none, "L": .low, "M": .medium, "H": .high]
        labels = try meta.labels.map { name in
            guard let label = TokenLabel(rawValue: name) else { throw CoreMLTaggerError.unknownLabel(name) }
            return label
        }
        urgencies = try meta.urgencies.map { name in
            guard let level = levels[name] else { throw CoreMLTaggerError.unknownLabel(name) }
            return level
        }
        guard !labels.isEmpty, meta.intents == ["task", "event", "none"] else { throw CoreMLTaggerError.unexpectedOutput }
        let specials = tokenizer.encode(text: "", addSpecialTokens: true)
        guard specials.count == 2 else { throw CoreMLTaggerError.unexpectedOutput }
        self.model = model; self.tokenizer = tokenizer
        open = specials[0]; close = specials[1]
    }

    /// The pieces of the words, each word encoded on its own as the model was trained, opened and closed, and where each
    /// word's first piece is. A word that would not fit is left out, with every word after it.
    func encode(_ words: [String]) -> (ids: [Int], firsts: [Int]) {
        var ids = [open]
        var firsts: [Int] = []
        for word in words {
            let pieces = tokenizer.encode(text: word, addSpecialTokens: false)
            guard !pieces.isEmpty, ids.count + pieces.count + 1 <= Self.maxPieces else { break }
            firsts.append(ids.count)
            ids += pieces
        }
        ids.append(close)
        return (ids, firsts)
    }
}

/// What `labels.json` says about the model's outputs.
private struct Meta: Decodable { let labels: [String]; let intents: [String]; let urgencies: [String] }

/// The model folder does not hold what `export.py` writes.
public enum CoreMLTaggerError: Error { case unexpectedOutput, unknownLabel(String) }
