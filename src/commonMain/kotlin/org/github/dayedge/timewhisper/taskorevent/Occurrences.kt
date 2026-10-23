package org.github.dayedge.timewhisper.taskorevent

import kotlinx.datetime.*
import org.github.dayedge.timewhisper.Frequency
import org.github.dayedge.timewhisper.RecurrenceSpec
import org.github.dayedge.timewhisper.temporal.Read
import org.github.dayedge.timewhisper.temporal.lastOfMonth
import org.github.dayedge.timewhisper.temporal.shift
import org.github.dayedge.timewhisper.temporal.weekStart

/** The days a repeat falls on: where it starts, and where it stops when it runs so long or so many times. */
internal class Occurrences(private val rule: RecurrenceSpec) {
    /**
     * The first day the repeat falls on, from [from] on. "every day" and "every 2 weeks" name no day, so they start on
     * [from]. Otherwise it is the first of the named weekdays and days of the month: a negative day of the month counts
     * from the end (-1 is the last day), and a weekday with an ordinal is that one of the month (the second Tuesday, or
     * with -1 the last Friday).
     */
    fun first(from: LocalDate): LocalDate? {
        if (rule.weekdays.isEmpty() && rule.daysOfMonth.isEmpty()) return from
        return (0 until 400).asSequence().map(from::shift).firstOrNull { falls(it, null) }
    }

    /** The last day of a repeat that stops after so long ("for 6 weeks") or so many times ("6 times"), from its first day. */
    fun last(first: LocalDate, end: Read.SeriesEnd): LocalDate? {
        end.span?.let { return it.after(first).shift(-1) }
        val count = end.count ?: return null
        return all(first).drop(count - 1).firstOrNull()
    }

    /** Every day the repeat falls on from [first], which is its first occurrence, for up to five years. */
    private fun all(first: LocalDate): Sequence<LocalDate> {
        val weekStart = first.weekStart(DayOfWeek.MONDAY)
        fun monthsFrom(day: LocalDate) = (day.year - first.year) * 12 + (day.month.number - first.month.number)
        return (0 until 5 * 366).asSequence().map(first::shift).filter { day ->
            when (rule.frequency) {
                Frequency.DAILY -> (day.toEpochDays() - first.toEpochDays()) % rule.interval == 0L
                Frequency.WEEKLY -> ((day.toEpochDays() - weekStart.toEpochDays()) / 7) % rule.interval == 0L &&
                    (if (rule.weekdays.isEmpty()) day.dayOfWeek == first.dayOfWeek else rule.weekdays.any { it.day == day.dayOfWeek })
                Frequency.MONTHLY -> monthsFrom(day) % rule.interval == 0 &&
                    falls(day, if (rule.weekdays.isEmpty() && rule.daysOfMonth.isEmpty()) first.day else null)
                Frequency.YEARLY -> (day.year - first.year) % rule.interval == 0 && day.month == first.month && day.day == first.day
            }
        }
    }

    /** Whether [day] is one of the named weekdays and days of the month, or the day [same] of the month when the repeat names none. */
    private fun falls(day: LocalDate, same: Int?): Boolean {
        if (same != null) return day.day == same
        return (rule.weekdays.isEmpty() || rule.weekdays.any { it.day == day.dayOfWeek && isNth(day, it.ordinal) }) &&
            (rule.daysOfMonth.isEmpty() || rule.daysOfMonth.any { if (it > 0) it == day.day else day.lastOfMonth().day + it + 1 == day.day })
    }

    /** Whether [day] is the [ordinal]th of its weekday in the month: 0 is any, -1 the last. */
    private fun isNth(day: LocalDate, ordinal: Int) = when {
        ordinal == 0 -> true
        ordinal > 0 -> (day.day - 1) / 7 + 1 == ordinal
        else -> day.day + 7 > day.lastOfMonth().day
    }
}
