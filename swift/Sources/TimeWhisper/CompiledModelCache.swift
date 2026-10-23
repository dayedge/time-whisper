import CoreML
import CryptoKit
import Foundation

/// Compiled Core ML models kept in a folder the host chooses, so a model is compiled once rather than on every launch.
///
/// A copy is named after a hash of the package's contents and the system's build. A new model, or a system update that may
/// bring a new Core ML compiler, compiles again; the copies made for anything else are then removed. A copy is written
/// under a temporary name and renamed into place, so a crash or a second process compiling at the same moment never leaves
/// a half-written model behind.
struct CompiledModelCache {
    let folder: URL

    /// The compiled model for `package`: the copy in the folder, or one compiled now and kept there.
    func compiled(_ package: URL) async throws -> URL {
        let files = FileManager.default
        let name = Self.name(package)
        let cached = folder.appendingPathComponent("\(name)-\(try Self.key(package)).mlmodelc")
        if files.fileExists(atPath: cached.path) { return cached }

        try files.createDirectory(at: folder, withIntermediateDirectories: true)
        let compiled = try await MLModel.compileModel(at: package)
        let staging = folder.appendingPathComponent(".\(name)-\(UUID().uuidString).partial")
        try files.moveItem(at: compiled, to: staging)
        do {
            try files.moveItem(at: staging, to: cached)
        } catch {
            // Another process got there first; its copy is as good as this one.
            try? files.removeItem(at: staging)
            guard files.fileExists(atPath: cached.path) else { throw error }
        }
        removeCopies(of: name, except: cached)
        return cached
    }

    /// Removes a copy that could not be loaded, so the next request compiles it again.
    func discard(_ compiled: URL) {
        try? FileManager.default.removeItem(at: compiled)
    }

    /// Copies of the same model made for other contents or another system, and partial ones a crash left behind.
    private func removeCopies(of name: String, except kept: URL) {
        let files = FileManager.default
        guard let items = try? files.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return }
        for item in items where item.lastPathComponent != kept.lastPathComponent {
            let file = item.lastPathComponent
            let isCopy = file.hasPrefix("\(name)-") && file.hasSuffix(".mlmodelc")
            let isPartial = file.hasPrefix(".\(name)-") && file.hasSuffix(".partial") && Self.isStale(item)
            if isCopy || isPartial { try? files.removeItem(at: item) }
        }
    }

    /// A partial copy older than any compile takes: one being written by another process right now is left alone.
    private static func isStale(_ item: URL) -> Bool {
        let modified = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
        return modified.timeIntervalSinceNow < -600
    }

    private static func name(_ package: URL) -> String { package.deletingPathExtension().lastPathComponent }

    /// A hash of every file in the package, by path and contents, and of the system's version and build.
    static func key(_ package: URL) throws -> String {
        var hash = SHA256()
        hash.update(data: Data(ProcessInfo.processInfo.operatingSystemVersionString.utf8))
        let root = package.standardizedFileURL.path
        guard let walk = FileManager.default.enumerator(at: package, includingPropertiesForKeys: [.isRegularFileKey]) else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        let files = try walk.compactMap { $0 as? URL }
            .filter { try $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true }
            .map { (path: String($0.standardizedFileURL.path.dropFirst(root.count)), url: $0) }
            .sorted { $0.path < $1.path }
        for file in files {
            hash.update(data: Data(file.path.utf8))
            hash.update(data: Data([0]))
            let handle = try FileHandle(forReadingFrom: file.url)
            defer { try? handle.close() }
            while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hash.update(data: chunk) }
        }
        return hash.finalize().prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}
