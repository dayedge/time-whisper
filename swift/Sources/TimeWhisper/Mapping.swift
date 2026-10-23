import Foundation
import TimeWhisperCore

// Conversions between this module's values and the core's: `init(_:)` reads a core value, `core()` builds one. A core value
// this module does not know is a backend error, never a guess.

// MARK: - From the core

extension SourceRange {
    init(_ value: TimeWhisperCore.SourceRange) { self.init(offset: Int(value.offset), length: Int(value.length)) }
}

extension CivilDate {
    init(_ value: TimeWhisperCore.CivilDate) { self.init(year: Int(value.year), month: Int(value.month), day: Int(value.day)) }
}

extension Moment {
    init(_ value: TimeWhisperCore.Moment) {
        self.init(date: Date(epochMilliseconds: value.epochMilliseconds), timeZoneID: value.timeZoneId, offsetSeconds: Int(value.offsetSeconds))
    }
}

extension Frequency {
    init(_ value: TimeWhisperCore.Frequency) throws {
        switch value {
        case .daily: self = .daily
        case .weekly: self = .weekly
        case .monthly: self = .monthly
        case .yearly: self = .yearly
        default: throw TimeWhisperError.backend("Unknown frequency")
        }
    }
}

extension Priority {
    init(_ value: TimeWhisperCore.Priority) throws {
        switch value {
        case TimeWhisperCore.Priority.none: self = .none
        case .low: self = .low
        case .medium: self = .medium
        case .high: self = .high
        default: throw TimeWhisperError.backend("Unknown priority")
        }
    }
}

extension Granularity {
    init(_ value: TimeWhisperCore.Granularity) throws {
        switch value {
        case .day: self = .day
        case .week: self = .week
        case .month: self = .month
        case .year: self = .year
        default: throw TimeWhisperError.backend("Unknown granularity")
        }
    }
}

extension Source {
    init(_ value: TimeWhisperCore.Source) throws {
        switch value {
        case .explicit_: self = .explicit
        case .grammar: self = .grammar
        case .finder: self = .finder
        default: throw TimeWhisperError.backend("Unknown source")
        }
    }
}

extension Weekday {
    /// kotlinx-datetime numbers its weekdays from Monday, as ISO 8601 and `Weekday` do.
    init(_ value: Kotlinx_datetimeDayOfWeek) throws {
        guard let day = Weekday(rawValue: Int(value.ordinal) + 1) else { throw TimeWhisperError.backend("Unknown weekday") }
        self = day
    }
}

extension RecurrenceSpec {
    init(_ value: TimeWhisperCore.RecurrenceSpec) throws {
        self.init(
            frequency: try Frequency(value.frequency), interval: Int(value.interval),
            weekdays: try value.weekdays.map { WeekdayRule(day: try Weekday($0.day), ordinal: Int($0.ordinal)) },
            daysOfMonth: value.daysOfMonth.map(\.intValue), until: value.until.map(CivilDate.init))
    }
}

extension ReminderSpec {
    init(_ value: TimeWhisperCore.ReminderSpec) throws {
        switch value {
        case let before as TimeWhisperCore.ReminderSpec.Before: self = .before(minutes: Int(before.minutes))
        case let at as TimeWhisperCore.ReminderSpec.At: self = .at(Moment(at.moment))
        default: throw TimeWhisperError.backend("Unknown reminder")
        }
    }
}

extension Due {
    init(_ value: TimeWhisperCore.Due) throws {
        switch value {
        case let day as TimeWhisperCore.Due.OnDate: self = .onDate(CivilDate(day.date))
        case let time as TimeWhisperCore.Due.AtTime: self = .atTime(Moment(time.moment))
        default: throw TimeWhisperError.backend("Unknown due value")
        }
    }
}

extension EventTime {
    init(_ value: TimeWhisperCore.EventTime) throws {
        switch value {
        case let allDay as TimeWhisperCore.EventTime.AllDay:
            self = .allDay(startDate: CivilDate(allDay.startDate), endDateExclusive: CivilDate(allDay.endDateExclusive))
        case let timed as TimeWhisperCore.EventTime.Timed:
            self = .timed(start: Moment(timed.start), endExclusive: Moment(timed.endExclusive))
        default: throw TimeWhisperError.backend("Unknown event time")
        }
    }
}

extension TaskSpec {
    init(_ value: TimeWhisperCore.TaskSpec) throws {
        self.init(title: value.title, due: try value.due.map(Due.init), listID: value.listId, priority: try Priority(value.priority),
                  recurrence: try value.recurrence.map(RecurrenceSpec.init), reminder: try value.reminder.map(ReminderSpec.init))
    }
}

extension EventSpec {
    init(_ value: TimeWhisperCore.EventSpec) throws {
        self.init(title: value.title, time: try EventTime(value.time), calendarID: value.calendarId, location: value.location,
                  recurrence: try value.recurrence.map(RecurrenceSpec.init), reminders: try value.reminders.map(ReminderSpec.init))
    }
}

extension Candidate {
    init(_ value: TimeWhisperCore.Candidate) throws {
        let spans = try value.spans.map { RecognizedSpan(range: SourceRange($0.range), source: try Source($0.source)) }
        switch value {
        case let task as TimeWhisperCore.Candidate.Task: self.init(content: .task(try TaskSpec(task.spec)), spans: spans)
        case let event as TimeWhisperCore.Candidate.Event: self.init(content: .event(try EventSpec(event.spec)), spans: spans)
        default: throw TimeWhisperError.backend("Unknown candidate")
        }
    }
}

extension TemporalSpec {
    init(_ value: TimeWhisperCore.TemporalSpec) throws {
        self.init(start: Moment(value.start), endExclusive: Moment(value.endExclusive), granularity: try Granularity(value.granularity),
                  hasExplicitTime: value.hasExplicitTime)
    }
}

extension FoundTemporal {
    init(_ value: TimeWhisperCore.FoundTemporal) throws { self.init(temporal: try TemporalSpec(value.temporal), remainingText: value.remainingText) }
}

extension DateFinderRequest {
    init(_ value: TimeWhisperCore.DateFinderRequest) throws {
        guard let zone = TimeZone(identifier: value.timeZoneId) else { throw TimeWhisperError.backend("Unknown time zone \(value.timeZoneId)") }
        self.init(language: value.language, referenceDate: Date(epochMilliseconds: value.referenceEpochMilliseconds), timeZone: zone)
    }
}

extension BridgeFailure {
    /// The Swift error for a failed request: a cancellation, a value the parser cannot accept, or a backend failure.
    var error: Error {
        switch kind {
        case .cancelled: return CancellationError()
        case .invalidInput: return TimeWhisperError.invalidInput(message)
        default: return TimeWhisperError.backend(message)
        }
    }
}

// MARK: - To the core

extension Date {
    init(epochMilliseconds: Int64) { self.init(timeIntervalSince1970: Double(epochMilliseconds) / 1000) }

    /// Milliseconds since 1970, within the range the core accepts.
    func epochMilliseconds() throws -> Int64 {
        let value = timeIntervalSince1970 * 1000
        guard value.isFinite, value >= -8_640_000_000_000_000, value <= 8_640_000_000_000_000 else { throw TimeWhisperError.invalidInput("Reference date is out of range") }
        return Int64(value.rounded(.towardZero))
    }
}

extension Configuration {
    /// Checks what the core would refuse, so the refusal is an error here and not a failure inside the core.
    func validate() throws {
        guard defaultEventMinutes > 0, defaultEventMinutes <= Int(Int32.max),
              !weekendDays.isEmpty, weekendDays.count < 7,
              (0...23).contains(reminderTime.hour), (0...59).contains(reminderTime.minute) else {
            throw TimeWhisperError.invalidInput("Invalid duration, weekend or reminder time configuration")
        }
        guard TimeWhisperCore.Configuration.companion.supportedLanguages.contains(language) else {
            throw TimeWhisperError.invalidInput("Unsupported language: \(language)")
        }
    }
}

extension AppleBridge {
    /// The core's parser for a validated configuration and the host's providers.
    convenience init(_ configuration: Configuration, tagger: @escaping @Sendable () -> any Tagger,
                     dateFinder: (@Sendable () -> any DateFinder)?, spellChecker: (@Sendable () -> any SpellChecker)?) {
        self.init(
            dayFirst: configuration.dateOrder.map { KotlinBoolean(bool: $0 == .dayMonth) }, weekStartIso: Int32(configuration.weekStartsOn.rawValue),
            weekendIso: configuration.weekendDays.map { KotlinInt(int: Int32($0.rawValue)) },
            defaultEventMinutes: Int32(configuration.defaultEventMinutes), language: configuration.language,
            reminderHour: Int32(configuration.reminderTime.hour), reminderMinute: Int32(configuration.reminderTime.minute),
            taggerFactory: TaggerFactoryAdapter(tagger), dateFinderFactory: dateFinder.map(DateFinderFactoryAdapter.init),
            spellingFactory: spellChecker.map(SpellCheckerFactoryAdapter.init))
    }
}

extension ParseContext {
    /// "Now" as the core takes it.
    func epochMilliseconds() throws -> Int64 { try referenceDate.epochMilliseconds() }
}

extension Destination {
    var core: TimeWhisperCore.Destination { .init(id: id, name: name, kind: kind == .taskList ? .taskList : .calendar) }
}

extension SourceRange {
    /// The range as the core's 32-bit numbers, or a backend error saying `who` returned it.
    func core(from who: String) throws -> (offset: Int32, length: Int32) {
        guard let offset = Int32(exactly: offset), let length = Int32(exactly: length) else { throw TimeWhisperError.backend("\(who) returned an invalid source range") }
        return (offset, length)
    }
}

extension DateMatchComponents {
    func core() throws -> TimeWhisperCore.DateMatchComponents {
        func number(_ value: Int?) throws -> KotlinInt? {
            guard let value else { return nil }
            guard let narrow = Int32(exactly: value) else { throw TimeWhisperError.backend("Date finder value out of range") }
            return KotlinInt(int: narrow)
        }
        func flag(_ value: Bool?) -> KotlinBoolean? { value.map { KotlinBoolean(bool: $0) } }
        let built = BridgeValues.shared.components(
            year: try number(date?.year), month: try number(date?.month), day: try number(date?.day),
            hour: try number(time?.hour), minute: try number(time?.minute),
            dayStated: flag(dayStated), monthStated: flag(monthStated), yearStated: flag(yearStated), offsetMinutes: try number(offsetMinutes))
        guard let built else { throw TimeWhisperError.backend("Date finder returned an invalid date or time") }
        return built
    }
}

extension DateMatch {
    func core() throws -> TimeWhisperCore.DateMatch {
        let (offset, length) = try range.core(from: "Date finder")
        guard let built = BridgeValues.shared.match(offset: offset, length: length, start: try start.core(), end: try end?.core()) else {
            throw TimeWhisperError.backend("Date finder returned an invalid source range")
        }
        return built
    }
}

extension Tagging {
    func core() throws -> TimeWhisperCore.Tagging {
        let words = try tokens.map { token -> TimeWhisperCore.TaggedToken in
            let (offset, length) = try token.range.core(from: "Tagger")
            guard let built = BridgeValues.shared.taggedToken(offset: offset, length: length, label: token.label.rawValue, confidence: token.confidence) else {
                throw TimeWhisperError.backend("Tagger returned an invalid source range")
            }
            return built
        }
        return BridgeValues.shared.tagging(tokens: words, task: task, event: event, none: none, unstated: urgency[.none] ?? 0,
                                           low: urgency[.low] ?? 0, medium: urgency[.medium] ?? 0, high: urgency[.high] ?? 0)
    }
}
