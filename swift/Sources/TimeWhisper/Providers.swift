import Foundation
import TimeWhisperCore

// What the host supplies: the tagger (required), a date finder and a spell checker (both optional). Each is made by a
// factory the first time it is needed, and again after `releaseResources()`.

// MARK: - Tagger

/// The reader of what each part of the text is for: `CoreMLTagger`, or a stand-in in tests. It labels each word and says
/// whether the text asks for a task, an event or nothing, and what urgency it states. It never supplies a value: the
/// parser reads each part, and a part it cannot read goes back into the title.
public protocol Tagger: Sendable {
    func tag(_ text: String, language: String) async throws -> Tagging
    func close() async
}

public extension Tagger { func close() async {} }

/// What a word is for, by the name the model gives it. Neighbouring words with the same label are one part.
public enum TokenLabel: String, Sendable, CaseIterable {
    case title = "T", wrapper = "W", day = "DAY", clock = "CLOCK", end = "END", length = "LENGTH", due = "DUE", `repeat` = "REPEAT"
    case starts = "STARTS", ends = "ENDS", place = "PLACE", list = "LIST", calendar = "CALENDAR", priority = "PRIORITY"
    case reminder = "REMINDER", kind = "KIND"
}

/// One word: its range in UTF-16 code units of the text given to the tagger, its label and how sure the tagger is, 0 to 1.
public struct TaggedToken: Sendable, Equatable {
    public let range: SourceRange
    public let label: TokenLabel
    public let confidence: Double
    public init(range: SourceRange, label: TokenLabel, confidence: Double) { self.range = range; self.label = label; self.confidence = confidence }
}

/// The words of a text with their labels; how much the text reads as a request for a task, an event or nothing, each 0 to 1;
/// and how likely it states each priority (together 1).
public struct Tagging: Sendable, Equatable {
    public let tokens: [TaggedToken]
    public let task: Double
    public let event: Double
    public let none: Double
    public let urgency: [Priority: Double]
    public init(tokens: [TaggedToken], task: Double, event: Double, none: Double, urgency: [Priority: Double] = [:]) {
        self.tokens = tokens; self.task = task; self.event = event; self.none = none; self.urgency = urgency
    }
}

// MARK: - Date finder

/// An optional second reader of dates and times, such as the system data detector, for a day or a time the parser's own
/// rules cannot read. It must depend only on the request, never on the device clock, zone or locale, and returns no
/// matches for a language it does not know.
public protocol DateFinder: Sendable {
    func find(_ text: String, request: DateFinderRequest) async throws -> [DateMatch]
    func close() async
}

public extension DateFinder { func close() async {} }

/// What a date finder may depend on: the language of the text, the moment "now" is, and the user's time zone.
public struct DateFinderRequest: Sendable, Equatable {
    public let language: String
    public let referenceDate: Date
    public let timeZone: TimeZone
    public init(language: String, referenceDate: Date, timeZone: TimeZone) {
        self.language = language; self.referenceDate = referenceDate; self.timeZone = timeZone
    }
}

/// One date or time found in the text. `range` is in UTF-16 code units of the text given to the finder.
public struct DateMatch: Sendable, Equatable {
    public let range: SourceRange
    public let start: DateMatchComponents
    public let end: DateMatchComponents?
    public init(range: SourceRange, start: DateMatchComponents, end: DateMatchComponents? = nil) { self.range = range; self.start = start; self.end = end }
}

/// One end of a match, as a local date and time in the request's zone.
/// The `Stated` flags say whether the text wrote that part ("at 5" states no day); nil means the finder cannot tell.
/// `offsetMinutes` is a UTC offset written in the text.
public struct DateMatchComponents: Sendable, Equatable {
    public let date: CivilDate?
    public let time: CivilTime?
    public let dayStated: Bool?
    public let monthStated: Bool?
    public let yearStated: Bool?
    public let offsetMinutes: Int?
    public init(date: CivilDate?, time: CivilTime?, dayStated: Bool? = nil, monthStated: Bool? = nil, yearStated: Bool? = nil, offsetMinutes: Int? = nil) {
        self.date = date; self.time = time; self.dayStated = dayStated; self.monthStated = monthStated; self.yearStated = yearStated
        self.offsetMinutes = offsetMinutes
    }
}

// MARK: - Spell checker

/// Suggests spellings for a word. The parser decides whether a suggestion is safe to take; title words are never rewritten.
public protocol SpellChecker: Sendable {
    func suggestions(for word: String, language: String) async throws -> [String]
    func close() async
}

public extension SpellChecker { func close() async {} }

/// Suggests nothing, so no misspelling is corrected. Use it for fully reproducible results.
public struct NoSpellChecker: SpellChecker {
    public init() {}
    public func suggestions(for word: String, language: String) async throws -> [String] { [] }
}

// MARK: - Adapters for the core

/// Makes the host's tagger, as the core asks for it.
final class TaggerFactoryAdapter: NSObject, TimeWhisperCore.TaggerFactory {
    private let factory: @Sendable () -> any Tagger
    init(_ factory: @escaping @Sendable () -> any Tagger) { self.factory = factory }
    func create() -> any TimeWhisperCore.Tagger { TaggerAdapter(factory()) }
}

/// The host's tagger as the core calls it.
final class TaggerAdapter: NSObject, TimeWhisperCore.Tagger {
    private let tagger: any Tagger
    init(_ tagger: any Tagger) { self.tagger = tagger }
    func tag(text: String, language: String) async throws -> TimeWhisperCore.Tagging { try await tagger.tag(text, language: language).core() }
    func close() async throws { await tagger.close() }
}

/// Makes the host's date finder, as the core asks for it.
final class DateFinderFactoryAdapter: NSObject, TimeWhisperCore.DateFinderFactory {
    private let factory: @Sendable () -> any DateFinder
    init(_ factory: @escaping @Sendable () -> any DateFinder) { self.factory = factory }
    func create() -> any TimeWhisperCore.DateFinder { DateFinderAdapter(factory()) }
}

/// The host's date finder as the core calls it.
final class DateFinderAdapter: NSObject, TimeWhisperCore.DateFinder {
    private let finder: any DateFinder
    init(_ finder: any DateFinder) { self.finder = finder }
    func find(text: String, request: TimeWhisperCore.DateFinderRequest) async throws -> [TimeWhisperCore.DateMatch] {
        try await finder.find(text, request: DateFinderRequest(request)).map { try $0.core() }
    }
    func close() async throws { await finder.close() }
}

/// Makes the host's spell checker, as the core asks for it.
final class SpellCheckerFactoryAdapter: NSObject, TimeWhisperCore.SpellCheckerFactory {
    private let factory: @Sendable () -> any SpellChecker
    init(_ factory: @escaping @Sendable () -> any SpellChecker) { self.factory = factory }
    func create() -> any TimeWhisperCore.SpellChecker { SpellCheckerAdapter(factory()) }
}

/// The host's spell checker as the core calls it.
final class SpellCheckerAdapter: NSObject, TimeWhisperCore.SpellChecker {
    private let checker: any SpellChecker
    init(_ checker: any SpellChecker) { self.checker = checker }
    func suggestions(word: String, language: String) async throws -> [String] { try await checker.suggestions(for: word, language: language) }
    func close() async throws { await checker.close() }
}
