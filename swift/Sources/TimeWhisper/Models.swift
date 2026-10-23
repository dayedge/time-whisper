import Foundation

/// Which number comes first in a numeric date such as "5/10".
public enum DateOrder: Sendable { case monthDay, dayMonth }
/// A day of the week, numbered as ISO 8601 does: Monday is 1.
public enum Weekday: Int, CaseIterable, Sendable { case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday }
/// What a `Destination` holds: tasks or events.
public enum DestinationKind: Sendable { case taskList, calendar }
/// How urgent a task is: "!" low, "!!" medium, "!!!" or "urgent" high.
public enum Priority: Sendable, Hashable { case none, low, medium, high }
/// The unit a repeat counts in.
public enum Frequency: Sendable { case daily, weekly, monthly, yearly }
/// How wide a stretch of time an expression names: "tomorrow" is a day, "next week" a week.
public enum Granularity: Sendable { case day, week, month, year }
/// What recognised a part of the text: a stated marker such as "#work", a date or time rule, or the host's date finder.
public enum Source: Sendable { case explicit, grammar, finder }
/// The two kinds of entry a text can ask for.
public enum CandidateKind: Sendable { case task, event }

/// Settings that hold for every request: the language and the calendar habits that are not the language's defaults.
public struct Configuration: Sendable, Equatable {
    /// Which comes first in a numeric date such as "5/10". Left nil, the language's own habit applies: month first in English, day first in Polish.
    public var dateOrder: DateOrder?
    /// The first day of a week, for "next week" and week numbers.
    public var weekStartsOn: Weekday
    /// The days "the weekend" names: one to six of them.
    public var weekendDays: Set<Weekday>
    /// How long an event with a start and no stated end lasts.
    public var defaultEventMinutes: Int
    /// The language of the text, as a code such as "en". It selects the vocabulary and is passed to the date finder and spelling provider.
    public var language: String
    /// The time of a reminder that names a day and no time: "remind me on the 23rd".
    public var reminderTime: CivilTime
    public init(dateOrder: DateOrder? = nil, weekStartsOn: Weekday = .monday, weekendDays: Set<Weekday> = [.saturday, .sunday], defaultEventMinutes: Int = 60, language: String = "en",
                reminderTime: CivilTime = CivilTime(hour: 9, minute: 0)) {
        self.dateOrder = dateOrder; self.weekStartsOn = weekStartsOn; self.weekendDays = weekendDays; self.defaultEventMinutes = defaultEventMinutes
        self.language = language; self.reminderTime = reminderTime
    }
}
/// A list or calendar the user has. "#work" in the text matches the destination named "Work", and its `id` is returned.
public struct Destination: Sendable, Equatable {
    public let id: String
    public let name: String
    public let kind: DestinationKind
    public init(id: String, name: String, kind: DestinationKind) { self.id = id; self.name = name; self.kind = kind }
}
/// What a request is relative to: the moment "now" is, the user's time zone, and the lists and calendars a tag may name.
/// Passed with every request, so results never depend on the device.
public struct ParseContext: Sendable {
    public let referenceDate: Date
    public let timeZone: TimeZone
    public let destinations: [Destination]
    public init(referenceDate: Date, timeZone: TimeZone, destinations: [Destination] = []) {
        self.referenceDate = referenceDate; self.timeZone = timeZone; self.destinations = destinations
    }
}
/// A part of the input text, in UTF-16 code units.
public struct SourceRange: Sendable, Equatable {
    public let offset: Int
    public let length: Int
    public init(offset: Int, length: Int) { self.offset = offset; self.length = length }
    /// The same part as an `NSRange`, for `NSString` and attributed text.
    public init(_ range: NSRange) { self.init(offset: range.location, length: range.length) }
    public var nsRange: NSRange { NSRange(location: offset, length: length) }
}
/// A part of the text the parser read as something other than the title, for highlighting.
public struct RecognizedSpan: Sendable, Equatable {
    public let range: SourceRange
    public let source: Source
    public init(range: SourceRange, source: Source) { self.range = range; self.source = source }
}
/// A calendar date with no time and no time zone.
public struct CivilDate: Sendable, Equatable {
    public let year: Int
    public let month: Int
    public let day: Int
    public init(year: Int, month: Int, day: Int) { self.year = year; self.month = month; self.day = day }
    public var components: DateComponents { DateComponents(year: year, month: month, day: day) }
}
/// A time of day with no date and no time zone.
public struct CivilTime: Sendable, Equatable {
    public let hour: Int
    public let minute: Int
    public init(hour: Int, minute: Int) { self.hour = hour; self.minute = minute }
}
/// An instant, with the request's time zone and the offset that zone has at that instant.
public struct Moment: Sendable, Equatable {
    public let date: Date
    public let timeZoneID: String
    public let offsetSeconds: Int
    public init(date: Date, timeZoneID: String, offsetSeconds: Int) { self.date = date; self.timeZoneID = timeZoneID; self.offsetSeconds = offsetSeconds }
}
/// When a task is due: on a date, or at a moment when a time was given.
public enum Due: Sendable, Equatable { case onDate(CivilDate), atTime(Moment) }
/// A weekday in a repeat. `ordinal` is 0 for every such weekday; in a monthly repeat 1 to 5 counts from the start of the month
/// ("the second Tuesday") and -1 is the last ("the last Friday").
public struct WeekdayRule: Sendable, Equatable {
    public let day: Weekday
    public let ordinal: Int
    public init(day: Weekday, ordinal: Int = 0) { self.day = day; self.ordinal = ordinal }
}
/// A repeat: every `interval` days, weeks, months or years, optionally on named weekdays or days of the month,
/// up to and including `until` when the text named a last day.
public struct RecurrenceSpec: Sendable, Equatable {
    public let frequency: Frequency
    public let interval: Int
    public let weekdays: [WeekdayRule]
    public let daysOfMonth: [Int]
    public let until: CivilDate?
    public init(frequency: Frequency, interval: Int = 1, weekdays: [WeekdayRule] = [], daysOfMonth: [Int] = [], until: CivilDate? = nil) {
        self.frequency = frequency; self.interval = interval; self.weekdays = weekdays; self.daysOfMonth = daysOfMonth; self.until = until
    }
}
/// When to remind: so many minutes before, or at a moment.
public enum ReminderSpec: Sendable, Equatable { case before(minutes: Int), at(Moment) }
/// When an event is: whole days, or a start and an end. Ends are exclusive.
public enum EventTime: Sendable, Equatable {
    case allDay(startDate: CivilDate, endDateExclusive: CivilDate)
    case timed(start: Moment, endExclusive: Moment)
}
/// A task as read from the text. Everything but the title is optional.
public struct TaskSpec: Sendable, Equatable {
    public let title: String
    public let due: Due?
    public let listID: String?
    public let priority: Priority
    public let recurrence: RecurrenceSpec?
    public let reminder: ReminderSpec?
    public init(title: String, due: Due? = nil, listID: String? = nil, priority: Priority = .none, recurrence: RecurrenceSpec? = nil, reminder: ReminderSpec? = nil) {
        self.title = title; self.due = due; self.listID = listID; self.priority = priority; self.recurrence = recurrence; self.reminder = reminder
    }
}
/// An event as read from the text. Without a stated end it lasts the configured default length; without a time it is all-day.
public struct EventSpec: Sendable, Equatable {
    public let title: String
    public let time: EventTime
    public let calendarID: String?
    public let location: String?
    public let recurrence: RecurrenceSpec?
    public let reminders: [ReminderSpec]
    public init(title: String, time: EventTime, calendarID: String? = nil, location: String? = nil, recurrence: RecurrenceSpec? = nil, reminders: [ReminderSpec] = []) {
        self.title = title; self.time = time; self.calendarID = calendarID; self.location = location; self.recurrence = recurrence; self.reminders = reminders
    }
}
/// One thing the text may be asking for: a task or an event, with the parts of the text that were recognised.
public struct Candidate: Sendable, Equatable {
    public enum Content: Sendable, Equatable { case task(TaskSpec), event(EventSpec) }
    public let content: Content
    public let spans: [RecognizedSpan]
    public init(content: Content, spans: [RecognizedSpan] = []) { self.content = content; self.spans = spans }
}
/// A stretch of time named by text. A point in time has equal start and end.
public struct TemporalSpec: Sendable, Equatable {
    public let start: Moment
    public let endExclusive: Moment
    public let granularity: Granularity
    /// Whether the text named a time of day, not only a day or a period.
    public let hasExplicitTime: Bool
    public init(start: Moment, endExclusive: Moment, granularity: Granularity, hasExplicitTime: Bool = false) {
        self.start = start; self.endExclusive = endExclusive; self.granularity = granularity; self.hasExplicitTime = hasExplicitTime
    }
}
/// A date or time found inside longer text, and that text with the date words taken out.
public struct FoundTemporal: Sendable, Equatable {
    public let temporal: TemporalSpec
    public let remainingText: String
    public init(temporal: TemporalSpec, remainingText: String) { self.temporal = temporal; self.remainingText = remainingText }
}
/// What can go wrong: a value the parser cannot accept, or a provider that failed. "Nothing found" is never an error.
public enum TimeWhisperError: Error, Sendable, Equatable { case invalidInput(String), backend(String) }
