package org.github.dayedge.timewhisper.temporal

import kotlinx.datetime.*
import kotlin.time.Instant
import org.github.dayedge.timewhisper.CivilDate
import org.github.dayedge.timewhisper.CivilTime
import org.github.dayedge.timewhisper.Granularity
import org.github.dayedge.timewhisper.Moment
import org.github.dayedge.timewhisper.ParseContext

// Calendar arithmetic shared by the rules and the assembly, as extensions of kotlinx-datetime's own types, and the
// conversions between those types and the public ones.

/** The public form of a date. */
internal fun LocalDate.civil(): CivilDate = CivilDate(year, month.number, day)

/** A public date as a kotlinx-datetime one. */
internal fun CivilDate.local(): LocalDate = LocalDate(year, month, day)

/** A public time of day as a kotlinx-datetime one. */
internal fun CivilTime.local(): LocalTime = LocalTime(hour, minute)

/** The public form of an instant, with the zone it is shown in and that zone's offset then. */
internal fun Instant.moment(zone: TimeZone): Moment = Moment(toEpochMilliseconds(), zone.id, zone.offsetAt(this).totalSeconds)

/** Midnight at the start of the day, where the user is. */
internal fun LocalDate.start(context: ParseContext): Instant = atStartOfDayIn(context.zone)

/** So many days later, or earlier when [days] is negative. */
internal fun LocalDate.shift(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

/** So many days, weeks, months or years later, or earlier when [n] is negative. */
internal fun LocalDate.shift(n: Int, unit: Granularity): LocalDate = when (unit) {
    Granularity.DAY -> shift(n)
    Granularity.WEEK -> shift(n * 7)
    Granularity.MONTH -> plus(n, DateTimeUnit.MONTH)
    Granularity.YEAR -> plus(n, DateTimeUnit.YEAR)
}

/** The first [weekday] on or after this day. */
internal fun LocalDate.onOrAfter(weekday: DayOfWeek): LocalDate = shift((weekday.isoDayNumber - dayOfWeek.isoDayNumber + 7) % 7)

/** The first day of the week this day is in, for a week that starts on [starts]. */
internal fun LocalDate.weekStart(starts: DayOfWeek): LocalDate = shift(-((dayOfWeek.isoDayNumber - starts.isoDayNumber + 7) % 7))

/** The last day of this day's month. */
internal fun LocalDate.lastOfMonth(): LocalDate = LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).shift(-1)

/** The year of this day's ISO week, which differs from its calendar year in the first and last days of a year. */
internal val LocalDate.isoYear: Int get() = shift(4 - dayOfWeek.isoDayNumber).year

/** The date, or null when the month has no such day: February 30. */
internal fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate(year, month, day) }.getOrNull()

/** The month and day in [from]'s year, or a year later when that date has already passed. */
internal fun upcoming(month: Int, day: Int, from: LocalDate): LocalDate? =
    dateOrNull(from.year, month, day)?.let { if (it < from) it.plus(1, DateTimeUnit.YEAR) else it }
