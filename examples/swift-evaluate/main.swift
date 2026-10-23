#if compiler(>=6.2) && canImport(DataDetection)
import DataDetection
#endif
import Foundation
import TimeWhisper

// Runs every corpus sentence through the Swift API and writes what came back, one JSON line per sentence.
// The comparison with the expected answers is done once, by the JVM evaluator, so both platforms are judged by the same code.
// Usage: TimeWhisperEvaluate <corpus.jsonl> <output.jsonl> --model <folder> [--no-spelling] [--apple-finder]
// The language, reference time and lists come from the context file beside the corpus.
// --model is the folder training/export.py wrote. --apple-finder adds the system data detector as the date finder for a day or
// a time the readers cannot read (macOS 26 or later).
// Each line also records how the tagger labelled the words, for the report on what failed.

let arguments = Array(CommandLine.arguments.dropFirst())
guard arguments.count >= 2, let modelAt = arguments.firstIndex(of: "--model") else {
    fatalError("Usage: TimeWhisperEvaluate <corpus.jsonl> <output.jsonl> --model <folder> [--no-spelling] [--apple-finder]")
}
let spelling = !arguments.contains("--no-spelling")

/// The system data detector as a date finder. It reports an instant, which is turned back into a local date and time here.
/// It cannot say which parts the text stated, so those flags are left unset.
#if compiler(>=6.2) && canImport(DataDetection)
@available(macOS 26.0, *)
struct SystemDateFinder: DateFinder {
    func find(_ text: String, request: DateFinderRequest) async throws -> [DateMatch] {
        var options = DataDetector.Options()
        options.documentDate = request.referenceDate
        options.documentTimeZone = request.timeZone
        options.documentLanguageCode = Locale.LanguageCode(request.language)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = request.timeZone
        func parts(_ date: Date, allDay: Bool) -> DateMatchComponents {
            let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            return DateMatchComponents(date: CivilDate(year: c.year!, month: c.month!, day: c.day!), time: allDay ? nil : CivilTime(hour: c.hour!, minute: c.minute!))
        }
        var found: [DateMatch] = []
        for await match in text.dataDetectorMatches([.calendarEvent], options: options) {
            guard let range = match.range, case let .calendarEvent(event) = match.details, let start = event.startDate else { continue }
            found.append(DateMatch(range: SourceRange(NSRange(range, in: text)),
                                   start: parts(start, allDay: event.allDay), end: event.endDate.map { parts($0, allDay: event.allDay) }))
        }
        return found
    }
}

#endif

// How results are written down: as the JVM evaluator expects them.
let weekdayNames = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
let utc = TimeZone(identifier: "UTC")!
/// A Gregorian calendar in a zone, for taking dates apart.
func calendar(_ zone: TimeZone) -> Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = zone
    return calendar
}
func text(_ date: CivilDate) -> String { String(format: "%04d-%02d-%02d", date.year, date.month, date.day) }
func text(_ moment: Moment) -> String {
    let parts = calendar(TimeZone(identifier: moment.timeZoneID) ?? utc).dateComponents([.year, .month, .day, .hour, .minute], from: moment.date)
    return String(format: "%04d-%02d-%02dT%02d:%02d", parts.year!, parts.month!, parts.day!, parts.hour!, parts.minute!)
}
func dayBefore(_ date: CivilDate) -> String {
    let utcCalendar = calendar(utc)
    let previous = utcCalendar.date(byAdding: .day, value: -1, to: utcCalendar.date(from: date.components)!)!
    let parts = utcCalendar.dateComponents([.year, .month, .day], from: previous)
    return text(CivilDate(year: parts.year!, month: parts.month!, day: parts.day!))
}
func text(_ rule: RecurrenceSpec) -> String {
    let every: String
    switch rule.frequency { case .daily: every = "day"; case .weekly: every = "week"; case .monthly: every = "month"; case .yearly: every = "year" }
    var parts = ["every " + (rule.interval == 1 ? every : "\(rule.interval) \(every)s")]
    if !rule.weekdays.isEmpty {
        parts.append("on " + rule.weekdays.sorted { $0.day.rawValue < $1.day.rawValue }
            .map { ($0.ordinal != 0 ? String($0.ordinal) : "") + weekdayNames[$0.day.rawValue - 1] }.joined(separator: ","))
    }
    if !rule.daysOfMonth.isEmpty { parts.append("on day " + rule.daysOfMonth.sorted().map(String.init).joined(separator: ",")) }
    if let until = rule.until { parts.append("until " + text(until)) }
    return parts.joined(separator: " ")
}
func text(_ reminder: ReminderSpec) -> String {
    switch reminder { case .before(let minutes): return String(minutes); case .at(let moment): return text(moment) }
}
func fields(_ candidate: Candidate) -> [String: String] {
    var out: [String: String] = [:]
    switch candidate.content {
    case .task(let task):
        out["kind"] = "task"; out["title"] = task.title
        switch task.due { case .onDate(let date): out["due"] = text(date); case .atTime(let moment): out["due"] = text(moment); case nil: break }
        out["list"] = task.listID
        if task.priority != .none { out["priority"] = String(describing: task.priority) }
        out["repeat"] = task.recurrence.map(text)
        out["reminder"] = task.reminder.map(text)
    case .event(let event):
        out["kind"] = "event"; out["title"] = event.title
        switch event.time {
        case .timed(let start, let end): out["start"] = text(start); out["end"] = text(end)
        case .allDay(let start, let endExclusive):
            out["allDay"] = text(start)
            let last = dayBefore(endExclusive)
            if last != text(start) { out["allDayEnd"] = last }
        }
        out["calendar"] = event.calendarID; out["location"] = event.location
        out["repeat"] = event.recurrence.map(text)
        out["reminder"] = event.reminders.first.map(text)
    }
    return out
}

/// The fixed world the corpus is written for, from the file beside it ("pl-evaluation-context.json").
struct Setup: Decodable {
    struct Place: Decodable { let id: String; let name: String; let kind: String }
    let reference: Date
    let timeZone: String
    let language: String
    let dateOrder: String?
    let destinations: [Place]
}
let decoder = JSONDecoder()
decoder.dateDecodingStrategy = .iso8601
let setupPath = arguments[0].replacingOccurrences(of: "-corpus.jsonl", with: "-context.json")
let setup = try decoder.decode(Setup.self, from: Data(contentsOf: URL(fileURLWithPath: setupPath)))
let context = ParseContext(
    referenceDate: setup.reference, timeZone: TimeZone(identifier: setup.timeZone)!,
    destinations: setup.destinations.map { .init(id: $0.id, name: $0.name, kind: $0.kind == "calendar" ? .calendar : .taskList) }
)
let configuration = Configuration(dateOrder: setup.dateOrder.map { $0 == "dayMonth" ? .dayMonth : .monthDay }, language: setup.language)
let dateFinder: (@Sendable () -> any DateFinder)?
if arguments.contains("--apple-finder") {
#if compiler(>=6.2) && canImport(DataDetection)
    guard #available(macOS 26.0, *) else { fatalError("--apple-finder needs macOS 26 or later") }
    dateFinder = { SystemDateFinder() }
#else
    fatalError("--apple-finder needs Xcode 26 or later")
#endif
} else { dateFinder = nil }
// Without system spelling, nothing is corrected, so the run is the same on every Mac.
let spellChecker: (@Sendable () -> any SpellChecker)? = spelling ? nil : { @Sendable in NoSpellChecker() as any SpellChecker }
let model = URL(fileURLWithPath: arguments[modelAt + 1])

/// The model, keeping the tagging of the last text the parser asked about, so it is recorded without tagging twice.
actor RecordingTagger: Tagger {
    private let tagger: CoreMLTagger
    private(set) var last: Tagging?
    init(_ tagger: CoreMLTagger) { self.tagger = tagger }
    func tag(_ text: String, language: String) async throws -> Tagging {
        let tagging = try await tagger.tag(text, language: language)
        last = tagging
        return tagging
    }
    func forget() { last = nil }
}
// One tagger for the whole run, so the model is compiled once.
let tagger = RecordingTagger(CoreMLTagger(folder: model))
let parser = try TimeWhisper(configuration: configuration, taggerFactory: { tagger }, dateFinderFactory: dateFinder, spellCheckerFactory: spellChecker)
var lines: [String] = []
for line in try String(contentsOfFile: arguments[0], encoding: .utf8).split(separator: "\n") where !line.isEmpty {
    let group = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
    for input in group["inputs"] as! [String] {
        var record: [String: Any] = ["input": input]
        do {
            await tagger.forget()
            record["candidates"] = try await parser.parse(input, context: context).map(fields)
            // A text the parser turns down before tagging (an impossible ISO date) is tagged here.
            let tagging = if let last = await tagger.last { last } else { try await tagger.tag(input, language: configuration.language) }
            record["tagged"] = tagging.tokens.map { (input as NSString).substring(with: $0.range.nsRange) + "/" + $0.label.rawValue }.joined(separator: " ")
        } catch { record["error"] = String(describing: error) }
        lines.append(String(decoding: try JSONSerialization.data(withJSONObject: record, options: [.sortedKeys]), as: UTF8.self))
    }
}
try await parser.close()
try (lines.joined(separator: "\n") + "\n").write(toFile: arguments[1], atomically: true, encoding: .utf8)
print("Wrote \(lines.count) sentences to \(arguments[1]) (system spelling \(spelling ? "on" : "off"), model \(model.path))")
