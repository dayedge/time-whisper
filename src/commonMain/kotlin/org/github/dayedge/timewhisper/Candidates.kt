package org.github.dayedge.timewhisper

import kotlinx.datetime.DayOfWeek

/** How urgent a task is: "!" low, "!!" medium, "!!!" or "urgent" high. */
public enum class Priority { NONE, LOW, MEDIUM, HIGH }
/** The unit a repeat counts in. */
public enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }
/** The two things a line of text can ask for. */
public enum class CandidateKind { TASK, EVENT }
/** When a task is due: on a date, or at a moment when a time was given. */
public sealed class Due {
    public data class OnDate(val date: CivilDate) : Due()
    public data class AtTime(val moment: Moment) : Due()
}

/**
 * A weekday in a repeat. [ordinal] is 0 for every such weekday ("every friday"). In a monthly repeat it can name one of them:
 * 1 to 5 counts from the start of the month ("the second tuesday"), -1 is the last ("the last friday").
 */
public data class WeekdayRule(val day: DayOfWeek, val ordinal: Int = 0) {
    init {
        require(ordinal in -1..5)
    }
}
/**
 * A repeat: every [interval] days, weeks, months or years, optionally on named [weekdays] or [daysOfMonth], up to and
 * including [until] when the text named a last day ("every tuesday until December 15").
 * "every 2 weeks on friday" is weekly, interval 2, Friday. A negative day of the month counts from the end: -1 is the last day.
 */
public data class RecurrenceSpec(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: List<WeekdayRule> = emptyList(),
    val daysOfMonth: List<Int> = emptyList(),
    val until: CivilDate? = null,
) {
    init {
        require(interval > 0); require(daysOfMonth.all { it in -31..-1 || it in 1..31 })
    }
}

/** When to remind: so many minutes before, or at a moment. */
public sealed class ReminderSpec {
    public data class Before(val minutes: Int) : ReminderSpec()
    public data class At(val moment: Moment) : ReminderSpec()
}

/** When an event is: whole days, or a start and an end. Ends are exclusive. */
public sealed class EventTime {
    public data class AllDay(val startDate: CivilDate, val endDateExclusive: CivilDate) : EventTime()
    public data class Timed(val start: Moment, val endExclusive: Moment) : EventTime()
}

/** A task as read from the text. Everything but the title is optional. */
public data class TaskSpec(
    val title: String,
    val due: Due? = null,
    val listId: String? = null,
    val priority: Priority = Priority.NONE,
    val recurrence: RecurrenceSpec? = null,
    val reminder: ReminderSpec? = null,
)

/** An event as read from the text. Without a stated end it lasts the configured default length; without a time it is all-day. */
public data class EventSpec(
    val title: String,
    val time: EventTime,
    val calendarId: String? = null,
    val location: String? = null,
    val recurrence: RecurrenceSpec? = null,
    val reminders: List<ReminderSpec> = emptyList(),
)

/**
 * One thing the text may be asking for. [TimeWhisper.parse] returns these in order of preference; when it returns
 * both a task and an event for the same text, the caller decides which to show or lets the user choose.
 */
public sealed class Candidate {
    public abstract val spans: List<RecognizedSpan>

    public data class Task(val spec: TaskSpec, override val spans: List<RecognizedSpan> = emptyList()) :
        Candidate()

    public data class Event(val spec: EventSpec, override val spans: List<RecognizedSpan> = emptyList()) :
        Candidate()
}
