import Foundation
import TimeWhisperCore

/// The parser: reads one line of text into task and event candidates, with the shared Kotlin core doing all the reading.
/// It is safe to call from any task: the core runs one request at a time, and cancelling a task cancels its request.
public final class TimeWhisper: @unchecked Sendable {
    private let bridge: AppleBridge

    /// A parser that reads with the model in `model`, the folder `training/export.py` wrote. The model is loaded on first use.
    /// `dateFinderFactory` is optional: a reader of a day or a time no rule could read.
    public convenience init(model: URL, configuration: Configuration = Configuration(), dateFinderFactory: (@Sendable () -> any DateFinder)? = nil,
                            spellCheckerFactory: (@Sendable () -> any SpellChecker)? = nil) throws {
        let tagger = CoreMLTagger(folder: model)
        try self.init(configuration: configuration, taggerFactory: { tagger }, dateFinderFactory: dateFinderFactory, spellCheckerFactory: spellCheckerFactory)
    }

    /// A parser with a tagger of the host's own, such as a stand-in in tests. Each factory is called the first time its
    /// provider is needed, and again after `releaseResources()`. Without a date finder only the parser's rules read dates;
    /// without a spell checker the system's is used.
    public init(configuration: Configuration = Configuration(), taggerFactory: @escaping @Sendable () -> any Tagger,
                dateFinderFactory: (@Sendable () -> any DateFinder)? = nil, spellCheckerFactory: (@Sendable () -> any SpellChecker)? = nil) throws {
        try configuration.validate()
        bridge = AppleBridge(configuration, tagger: taggerFactory, dateFinder: dateFinderFactory, spellChecker: spellCheckerFactory)
    }

    deinit { bridge.dispose() }

    /// What the text asks to be created: task and event candidates, most likely first. Empty when there is nothing to offer.
    public func parse(_ text: String, context: ParseContext) async throws -> [Candidate] {
        let now = try context.epochMilliseconds()
        return try await request({ try ($0 ?? []).map(Candidate.init) }) { done in
            bridge.parse(text: text, epochMilliseconds: now, zone: context.timeZone.identifier, destinations: context.destinations.map(\.core), completion: done)
        }
    }

    /// The first date in the text and the text without it. A bare month name means the one in the current year.
    public func findTemporal(_ text: String, context: ParseContext) async throws -> FoundTemporal? {
        let now = try context.epochMilliseconds()
        return try await request({ try $0.map(FoundTemporal.init) }) { done in
            bridge.findTemporal(text: text, epochMilliseconds: now, zone: context.timeZone.identifier, completion: done)
        }
    }

    /// The date or period the text means when all of it is a date expression: "next week", "tomorrow at 9". Nil otherwise.
    public func parseTemporal(_ text: String, context: ParseContext) async throws -> TemporalSpec? {
        let now = try context.epochMilliseconds()
        return try await request({ try $0.map(TemporalSpec.init) }) { done in
            bridge.parseTemporal(text: text, epochMilliseconds: now, zone: context.timeZone.identifier, completion: done)
        }
    }

    /// Closes the providers and forgets cached readings. They are created again when next needed.
    public func releaseResources() async throws {
        try await request({ (_: Void?) in }) { done in bridge.releaseResources { done(nil, $0) } }
    }

    /// Releases everything for good. Further requests throw.
    public func close() async throws {
        try await request({ (_: Void?) in }) { done in bridge.close { done(nil, $0) } }
    }
}

/// One request to the core as an `async` call: `submit` starts it with a completion, `convert` turns the core's result into
/// this module's. Cancelling the calling task cancels the request.
private func request<Core, T: Sendable>(_ convert: @escaping (Core?) throws -> T,
                                        _ submit: (@escaping (Core?, BridgeFailure?) -> Void) -> ParseHandle) async throws -> T {
    let state = RequestState<T>()
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { continuation in
            state.begin(continuation)
            state.install(submit { result, failure in
                state.finish(Result {
                    if let failure { throw failure.error }
                    return try convert(result)
                })
            })
        }
    } onCancel: { state.cancel() }
}

/// The meeting point of a request's completion and its cancellation, which may come in any order and on any thread, even
/// before the request has started. Whichever comes first resumes the caller, once.
private final class RequestState<T>: @unchecked Sendable {
    private let lock = NSLock()
    /// The caller waiting for the result; nil once it has been resumed.
    private var continuation: CheckedContinuation<T, Error>?
    private var handle: ParseHandle?
    private var cancelled = false

    func begin(_ continuation: CheckedContinuation<T, Error>) {
        let cancelled = lock.withLock { self.continuation = continuation; return self.cancelled }
        if cancelled { finish(.failure(CancellationError())) }
    }

    func install(_ handle: ParseHandle) {
        let cancelled = lock.withLock {
            if continuation != nil { self.handle = handle }
            return self.cancelled
        }
        if cancelled { handle.cancel() }
    }

    func finish(_ result: Result<T, Error>) {
        let waiting = lock.withLock {
            defer { continuation = nil; handle = nil }
            return continuation
        }
        waiting?.resume(with: result)
    }

    /// A core call that waits on a host provider cannot be interrupted, so the caller is resumed now, not when it ends.
    func cancel() {
        let handle = lock.withLock { cancelled = true; return self.handle }
        handle?.cancel()
        finish(.failure(CancellationError()))
    }
}
