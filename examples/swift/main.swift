import Foundation
import TimeWhisper

// Parses sentences through the whole pipeline (the tagger model, the readers, the decision) and shows each result as a
// readable spec, with how the tagger labelled the words. "Now" is the moment a sentence is read, in this Mac's time zone;
// numeric dates follow its region ("1.7" is 1 July where the day comes first).
//
//   make try TEXT="dentist tomorrow at 3pm"        one sentence (swift run TimeWhisperExample "…")
//   make swiftrepl                                 type sentences, one per line; Ctrl-D or /quit to stop
//   /lang pl                                       switch language while typing; --lang pl to start in Polish
//
// The model is the folder training/export.py wrote; build/tagger-coreml by default (--model <folder> for another).
var arguments = Array(CommandLine.arguments.dropFirst())
func option(_ name: String) -> String? {
    guard let at = arguments.firstIndex(of: name), at + 1 < arguments.count else { return nil }
    defer { arguments.removeSubrange(at...at + 1) }
    return arguments[at + 1]
}
let model = URL(fileURLWithPath: option("--model") ?? "build/tagger-coreml")
let destinations: [Destination] = [
    .init(id: "work", name: "Work", kind: .taskList), .init(id: "home", name: "Home", kind: .taskList),
    .init(id: "team", name: "Team", kind: .calendar), .init(id: "family", name: "Family", kind: .calendar),
]

/// The model, keeping the tagging of the last text it read, so it is shown without running the model twice.
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

/// Which number comes first in "1.7.2027", from how this Mac's region writes dates, as an app would set it.
let dateOrder: DateOrder? = DateFormatter.dateFormat(fromTemplate: "yMd", options: 0, locale: .autoupdatingCurrent).flatMap { format in
    guard let month = format.firstIndex(of: "M"), let day = format.firstIndex(of: "d") else { return nil }
    return month < day ? .monthDay : .dayMonth
}
func configuration(_ language: String) -> Configuration { Configuration(dateOrder: dateOrder, language: language) }

let tagger = RecordingTagger(CoreMLTagger(folder: model))
var language = option("--lang") ?? "en"
var parser = try TimeWhisper(configuration: configuration(language), taggerFactory: { tagger })

// MARK: - Showing a result

/// Dates and times as people read them: "Fri 2 Oct 2026", "14:30".
func formatter(_ format: String, _ zone: TimeZone) -> DateFormatter {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = format
    formatter.timeZone = zone
    return formatter
}
/// A Gregorian calendar in UTC, for dates that have no time zone.
let civil: Calendar = { var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "UTC")!; return calendar }()
func text(_ date: CivilDate) -> String { formatter("EEE d MMM yyyy", civil.timeZone).string(from: civil.date(from: date.components)!) }
func dayBefore(_ date: CivilDate) -> CivilDate {
    let parts = civil.dateComponents([.year, .month, .day], from: civil.date(byAdding: .day, value: -1, to: civil.date(from: date.components)!)!)
    return CivilDate(year: parts.year!, month: parts.month!, day: parts.day!)
}
func text(_ moment: Moment, withDay: Bool = true) -> String {
    let zone = TimeZone(identifier: moment.timeZoneID) ?? .current
    return formatter(withDay ? "EEE d MMM yyyy, HH:mm" : "HH:mm", zone).string(from: moment.date)
}
/// "45 min", "2 h 30 min", "1 day".
func text(minutes: Int) -> String {
    let (days, rest) = minutes.quotientAndRemainder(dividingBy: 1440)
    let (hours, mins) = rest.quotientAndRemainder(dividingBy: 60)
    let parts = [(days, days == 1 ? "day" : "days"), (hours, "h"), (mins, "min")].filter { $0.0 > 0 }.map { "\($0.0) \($0.1)" }
    return parts.isEmpty ? "0 min" : parts.joined(separator: " ")
}
func text(_ rule: RecurrenceSpec) -> String {
    let unit = ["day", "week", "month", "year"][[Frequency.daily, .weekly, .monthly, .yearly].firstIndex(of: rule.frequency)!]
    var out = rule.interval == 1 ? "every \(unit)" : "every \(rule.interval) \(unit)s"
    let names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    let ordinals = [-1: "last ", 1: "1st ", 2: "2nd ", 3: "3rd ", 4: "4th ", 5: "5th "]
    if !rule.weekdays.isEmpty { out += " on " + rule.weekdays.map { (ordinals[$0.ordinal] ?? "") + names[$0.day.rawValue - 1] }.joined(separator: ", ") }
    if !rule.daysOfMonth.isEmpty { out += " on day " + rule.daysOfMonth.map { $0 == -1 ? "last" : String($0) }.joined(separator: ", ") }
    if let until = rule.until { out += ", until \(text(until))" }
    return out
}
func text(_ reminder: ReminderSpec) -> String {
    switch reminder {
    case .before(let minutes): return "\(text(minutes: minutes)) before"
    case .at(let moment): return "at \(text(moment))"
    }
}

/// One candidate as a block of labelled lines; the lines that say nothing are left out.
func show(_ candidate: Candidate, number: Int) {
    var lines: [(String, String?)] = []
    switch candidate.content {
    case .task(let task):
        print("  \(number). task  \"\(task.title)\"")
        switch task.due {
        case .onDate(let date): lines.append(("due", text(date)))
        case .atTime(let moment): lines.append(("due", text(moment)))
        case nil: break
        }
        lines += [("repeats", task.recurrence.map(text)), ("list", task.listID),
                  ("priority", task.priority == .none ? nil : String(describing: task.priority)), ("reminder", task.reminder.map(text))]
    case .event(let event):
        print("  \(number). event \"\(event.title)\"")
        switch event.time {
        case .allDay(let start, let end):
            // The end is exclusive: the last day is the one before it.
            let through = dayBefore(end)
            lines.append(("when", through == start ? "\(text(start)), all day" : "\(text(start)) – \(text(through)), all day"))
        case .timed(let start, let end):
            let sameDay = Calendar.current.isDate(start.date, inSameDayAs: end.date)
            lines.append(("when", "\(text(start)) – \(text(end, withDay: !sameDay))"))
        }
        lines += [("repeats", event.recurrence.map(text)), ("place", event.location), ("calendar", event.calendarID),
                  ("reminder", event.reminders.first.map(text))]
    }
    for case let (name, value?) in lines { print("       \(name.padding(toLength: 9, withPad: " ", startingAt: 0)) \(value)") }
}

/// The words as the tagger labelled them: "dentist next/DAY friday/DAY at/W 2:30pm/CLOCK". Title words are left bare.
func tagged(_ text: String, _ tagging: Tagging) -> String {
    tagging.tokens.map { token in
        (text as NSString).substring(with: token.range.nsRange) + (token.label == .title ? "" : "/" + token.label.rawValue)
    }.joined(separator: " ")
}

// MARK: - Reading

/// One sentence through the pipeline: the tagging, how much it reads as a task, an event or nothing, then each candidate.
@MainActor func show(_ line: String) async {
    do {
        await tagger.forget()
        let context = ParseContext(referenceDate: Date(), timeZone: .current, destinations: destinations)
        let candidates = try await parser.parse(line, context: context)
        if let tagging = await tagger.last {
            print("  tagged   \(tagged(line, tagging))")
            print(String(format: "  reads as task %.2f  event %.2f  nothing %.2f", tagging.task, tagging.event, tagging.none))
        }
        if candidates.isEmpty { print("  nothing to create") }
        for (i, candidate) in candidates.enumerated() { show(candidate, number: i + 1) }
    } catch { print("  error: \(error)") }
}

/// "/lang pl": the parser for another language, with the same tagger.
@MainActor func switchLanguage(to code: String) async {
    do {
        let next = try TimeWhisper(configuration: configuration(code), taggerFactory: { tagger })
        try await parser.close()
        (parser, language) = (next, code)
        print("  language \(language)")
    } catch TimeWhisperError.invalidInput(let message) { print("  \(message)") } catch { print("  \(error)") }
}

if !arguments.isEmpty {
    await show(arguments.joined(separator: " "))
} else {
    print("time-whisper, language \(language). Type a sentence; /lang <code> to switch, /help, /quit.")
    while true {
        print("\n[\(language)] > ", terminator: "")
        guard let line = readLine()?.trimmingCharacters(in: .whitespaces), line != "/quit" else { break }
        switch line {
        case "": continue
        case "/help": print("  /lang <code>  switch language (now \(language))\n  /quit         stop")
        case let command where command.hasPrefix("/lang"): await switchLanguage(to: command.dropFirst(5).trimmingCharacters(in: .whitespaces))
        default: await show(line)
        }
    }
}
try await parser.close()
