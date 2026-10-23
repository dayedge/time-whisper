import Foundation
import XCTest
@testable import TimeWhisper

final class TimeWhisperTests: XCTestCase {
    private var context: ParseContext {
        ParseContext(referenceDate: Date(timeIntervalSince1970: 1_790_164_800), timeZone: .gmt, destinations: [.init(id: "work", name: "Work", kind: .taskList)])
    }
    /// The words of the sentences below that are not title, as the model would label them.
    private static let labels: [String: TokenLabel] = ["tomorrow": .day, "at": .clock, "10": .clock, "fri-sun": .day, "march": .day, "soonish": .day]
    private func parser() throws -> TimeWhisper { try TimeWhisper(taggerFactory: { WordTagger(Self.labels) }, spellCheckerFactory: { NoSpellChecker() }) }
    func testEventAndTaskAreNativeSwiftValues() async throws {
        let parser = try parser()
        let result = try await parser.parse("Dentist tomorrow at 10", context: context)
        try await parser.close()
        XCTAssertEqual(result.count, 2)
        guard case .event(let event) = result[0].content, case .task(let task) = result[1].content else { return XCTFail("Wrong candidate order") }
        XCTAssertEqual(event.title, "Dentist"); XCTAssertEqual(task.title, "Dentist")
        guard case .timed(let start, let end) = event.time else { return XCTFail("Expected timed event") }
        XCTAssertEqual(end.date.timeIntervalSince(start.date), 3600)
    }
    func testAllDayUsesCivilDates() async throws {
        let parser = try parser()
        let result = try await parser.parse("Holiday fri-sun", context: context)
        try await parser.close()
        guard case .event(let event) = result[0].content, case .allDay(let start, let end) = event.time else { return XCTFail("Expected all-day event") }
        XCTAssertEqual(start.day, 25); XCTAssertEqual(end.day, 28)
    }
    func testFindTemporalReturnsRangeAndRemainingText() async throws {
        let parser = try parser()
        let found = try await parser.findTemporal("📅 review March", context: context)
        let nothing = try await parser.findTemporal("quarterly review", context: context)
        try await parser.close()
        XCTAssertEqual(found?.remainingText, "📅 review")
        XCTAssertEqual(found?.temporal.start.date, Date(timeIntervalSince1970: 1_772_323_200))
        XCTAssertEqual(found?.temporal.granularity, .month)
        XCTAssertNil(nothing)
    }
    func testReleaseAllowsParsingAgain() async throws {
        let parser = try parser()
        let first = try await parser.parse("Buy milk tomorrow", context: context)
        try await parser.releaseResources()
        let second = try await parser.parse("Buy milk tomorrow", context: context)
        try await parser.close()
        XCTAssertEqual(first, second)
    }
    func testCloseRejectsFurtherRequests() async throws {
        let parser = try parser()
        try await parser.close(); try await parser.close()
        do { _ = try await parser.parse("Buy milk", context: context); XCTFail("Closed parser accepted request") }
        catch { XCTAssertTrue(error is TimeWhisperError) }
    }
    func testInjectedSpellingProviderCorrectsTemporalWord() async throws {
        let parser = try TimeWhisper(taggerFactory: { WordTagger([:]) }, spellCheckerFactory: { FakeSpeller() })
        let result = try await parser.parseTemporal("frida", context: context)
        try await parser.close()
        XCTAssertEqual(result?.start.date, Date(timeIntervalSince1970: 1_790_294_400))
    }
    func testInjectedTaggerTakesWrappingWordsOffTheTitle() async throws {
        let parser = try TimeWhisper(taggerFactory: { WordTagger(["remind": .wrapper, "me": .wrapper, "to": .wrapper, "tomorrow": .day], task: 0.9) },
                                     spellCheckerFactory: { NoSpellChecker() })
        let result = try await parser.parse("remind me to call Bob tomorrow", context: context)
        try await parser.close()
        guard case .task(let task) = result.first?.content else { return XCTFail("Expected a task first") }
        XCTAssertEqual(task.title, "Call Bob")
    }
    func testInvalidConfigurationThrowsWithoutEnteringKotlin() throws {
        XCTAssertThrowsError(try TimeWhisper(configuration: .init(defaultEventMinutes: 0), taggerFactory: { WordTagger([:]) }))
        XCTAssertThrowsError(try TimeWhisper(configuration: .init(language: "xx"), taggerFactory: { WordTagger([:]) }))
    }
    func testInjectedDateFinderReceivesRequestAndSuppliesDate() async throws {
        let finder = FakeFinder()
        let parser = try TimeWhisper(taggerFactory: { WordTagger(Self.labels) }, dateFinderFactory: { finder }, spellCheckerFactory: { NoSpellChecker() })
        let result = try await parser.parse("call Bob soonish", context: context)
        try await parser.close()
        let request = await finder.requests.first
        XCTAssertEqual(request, DateFinderRequest(language: "en", referenceDate: context.referenceDate, timeZone: context.timeZone))
        guard case .event(let event) = result.first?.content, case .timed(let start, _) = event.time else { return XCTFail("Expected a timed event") }
        XCTAssertEqual(event.title, "Call Bob")
        XCTAssertEqual(start.date, Date(timeIntervalSince1970: 1_791_019_800))
        let closed = await finder.closed
        XCTAssertEqual(closed, 1)
    }
    func testInvalidDateFromFinderSurfacesAsError() async throws {
        let parser = try TimeWhisper(taggerFactory: { WordTagger(Self.labels) }, dateFinderFactory: { FakeFinder(day: 31, month: 2) }, spellCheckerFactory: { NoSpellChecker() })
        do { _ = try await parser.parse("call Bob soonish", context: context); XCTFail("Expected provider failure") }
        catch { XCTAssertTrue(error is TimeWhisperError) }
        try await parser.close()
    }
    func testCancelledRequestReleasesParserForNextRequest() async throws {
        let checker = BlockingSpeller()
        let parser = try TimeWhisper(taggerFactory: { WordTagger([:]) }, spellCheckerFactory: { checker })
        let context = context
        let task = Task { try await parser.parseTemporal("frida", context: context) }
        await checker.waitUntilEntered()
        task.cancel()
        do { _ = try await task.value; XCTFail("Expected cancellation") }
        catch { XCTAssertTrue(error is CancellationError) }
        await checker.release()
        let next = try await parser.parseTemporal("tomorrow", context: context)
        XCTAssertNotNil(next)
        try await parser.close()
    }
    func testInjectedSpellingFailureSurfacesAsError() async throws {
        let parser = try TimeWhisper(taggerFactory: { WordTagger([:]) }, spellCheckerFactory: { FailingSpeller() })
        do { _ = try await parser.parseTemporal("frida", context: context); XCTFail("Expected provider failure") }
        catch { XCTAssertTrue(error is TimeWhisperError) }
        try await parser.close()
    }

}
private actor FakeFinder: DateFinder {
    private let day: Int, month: Int
    private(set) var requests: [DateFinderRequest] = []
    private(set) var closed = 0
    init(day: Int = 3, month: Int = 10) { self.day = day; self.month = month }
    func find(_ text: String, request: DateFinderRequest) async throws -> [DateMatch] {
        requests.append(request)
        guard let found = text.range(of: "soonish") else { return [] }
        return [DateMatch(range: SourceRange(NSRange(found, in: text)),
                          start: DateMatchComponents(date: CivilDate(year: 2026, month: month, day: day), time: CivilTime(hour: 9, minute: 30)))]
    }
    func close() async { closed += 1 }
}
private struct FakeSpeller: SpellChecker {
    func suggestions(for word: String, language: String) async throws -> [String] { word == "frida" ? ["Friday"] : [] }
}
private struct FailingSpeller: SpellChecker {
    func suggestions(for word: String, language: String) async throws -> [String] { throw TimeWhisperError.backend("Injected spelling failure") }
}
private actor BlockingSpeller: SpellChecker {
    private var entered = false
    private var enteredWaiter: CheckedContinuation<Void, Never>?
    private var releaseWaiter: CheckedContinuation<Void, Never>?
    func suggestions(for word: String, language: String) async throws -> [String] {
        entered = true; enteredWaiter?.resume(); enteredWaiter = nil
        await withCheckedContinuation { releaseWaiter = $0 }
        return []
    }
    func waitUntilEntered() async {
        if !entered { await withCheckedContinuation { enteredWaiter = $0 } }
    }
    func release() { releaseWaiter?.resume(); releaseWaiter = nil }
}

/// Labels each word by its lowercased text, title when it is not listed, and reads the text as a task as much as `task` says.
private struct WordTagger: Tagger {
    let labels: [String: TokenLabel]
    let task: Double
    init(_ labels: [String: TokenLabel], task: Double = 0.4) { self.labels = labels; self.task = task }
    func tag(_ text: String, language: String) async throws -> Tagging {
        let tokens = CoreMLTagger.words(text).map { word in
            TaggedToken(range: word.range, label: labels[word.text.lowercased()] ?? .title, confidence: 0.95)
        }
        return Tagging(tokens: tokens, task: task, event: (1 - task) / 2, none: (1 - task) / 2)
    }
    func testFoundationConversions() throws {
        XCTAssertEqual(Weekday(calendarWeekday: 1), .sunday)
        XCTAssertEqual(Weekday(calendarWeekday: 2), .monday)
        XCTAssertEqual(Weekday.saturday.calendarWeekday, 7)
        XCTAssertEqual(Weekday.allCases.map { Weekday(calendarWeekday: $0.calendarWeekday) }, Weekday.allCases, "round trip")
        XCTAssertEqual(DateOrder(locale: Locale(identifier: "en_US")), .monthDay)
        XCTAssertEqual(DateOrder(locale: Locale(identifier: "pl_PL")), .dayMonth)
        XCTAssertEqual(DateOrder(locale: Locale(identifier: "ja_JP")), .monthDay, "year, month, day")
        XCTAssertEqual(try CivilDate(year: 2026, month: 9, day: 24).date(in: .gmt), Date(timeIntervalSince1970: 1_790_208_000))
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_GB")
        calendar.firstWeekday = 2
        XCTAssertEqual(Configuration(calendar: calendar, language: "en"), Configuration(dateOrder: .dayMonth, weekStartsOn: .monday, language: "en"))
    }
}
