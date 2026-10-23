package org.github.dayedge.timewhisper.temporal

import kotlinx.datetime.*
import kotlin.time.Instant
import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.text.Clock

/**
 * What a part says about when: a day or a period, a time, an end, a length, or several of these. The readers make one for
 * each part, and [merge] folds those of one entry into one.
 */
internal data class Fact(
    /** The day, or the first day of the period ("next week" is its first day). */
    val day: LocalDate? = null,
    /** How wide [day] is: a day, or the week, month or year it starts. */
    val granularity: Granularity = Granularity.DAY,
    /** A weekday said alone, to be placed by a week named elsewhere: "friday" beside "next week". */
    val weekday: DayOfWeek? = null,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    /** The last day of a range of days: "friday to sunday". */
    val endDay: LocalDate? = null,
    /** How long, in minutes: "for 45 min". */
    val duration: Int? = null,
    /** The whole day: "all day". */
    val allDay: Boolean = false,
    /** Whether the day was stated. A date finder may fill in a day the text never gave; such a day ranks last when days are merged. */
    val certainDay: Boolean = true,
    /** The offset from UTC a date finder reported, in minutes. */
    val offsetMinutes: Int? = null,
    /** The time zone the text named for its clock times: "3pm EST". They are times there, not where the user is. */
    val zone: TimeZone? = null,
    /** An end stated apart from any start ("until 4pm"), kept as written until the start it belongs to is known. */
    val detachedEnd: Clock? = null,
    /** The date was written without its year, which was taken to be the next time it comes round: "March 5". A year named beside it replaces that guess. */
    val yearImplied: Boolean = false,
    /** The weekday the words named, with or without a direction: "friday", "next friday". A weekly repeat with no day of its own takes it. */
    val namedWeekday: DayOfWeek? = null,
    /** The first day of the week this date lands in, when it was counted in weeks: "in 2 weeks". A weekday named beside it is the one in that week. */
    val weekStart: LocalDate? = null,
    /** The words name a date that does not exist ("February 30"). Nothing may be created from them. */
    val impossible: Boolean = false,
) {
    /** This fact with the time of [clock]: its start, end and zone. */
    fun withTimeOf(clock: Fact): Fact = copy(start = clock.start, end = clock.end, zone = clock.zone)

    /** The last day of what this names: the day itself, the last day of a range, or the last day of a week, month or year. */
    fun lastDay(): LocalDate? {
        val first = day ?: return null
        return endDay ?: first.shift(1, granularity).shift(-1)
    }

    /**
     * The last day of this as the end of something that starts on [start]. A weekday is the one on or after the start, not
     * the next from today: "friday 22:00 until saturday", "from next friday to next tuesday".
     */
    fun lastDayFrom(start: LocalDate?): LocalDate? {
        val last = lastDay() ?: return null
        return if (start != null && last < start && namedWeekday != null) start.onOrAfter(namedWeekday) else last
    }

    /** Whether this can end something that starts on [start]: not on a day before it. */
    fun endsOnOrAfter(start: LocalDate?): Boolean = start == null || (lastDayFrom(start) ?: start) >= start

    /**
     * The moment [time] on [day] is: in the zone the text named ("3pm EST"), at the offset a date finder reported,
     * or else in the caller's own zone. A named zone gives the offset it has on that day, so summer time is right.
     */
    fun at(day: LocalDate, time: LocalTime, context: ParseContext): Instant = day.atTime(time).toInstant(timeZone(context))

    /** Today where this time is said to be: "01:00 UTC+14" may already be tomorrow there. */
    fun today(context: ParseContext): LocalDate = context.today(timeZone(context))

    /** The zone the times are in: the one written, the offset they were counted with, or the user's. */
    private fun timeZone(context: ParseContext): TimeZone =
        zone ?: offsetMinutes?.let { UtcOffset(seconds = it * 60).asTimeZone() } ?: context.zone

    /**
     * The public form: from the start to the end, or over the whole day or period. An end at or before the start is on the
     * next day ("22-01"). Null without a day.
     */
    fun temporal(context: ParseContext): TemporalSpec? {
        val date = day ?: return null
        val from = if (start != null) at(date, start, context) else date.start(context)
        val until = when {
            end != null -> at(endDay ?: date, end, context).let { if (it <= from) at((endDay ?: date).shift(1), end, context) else it }
            start != null -> from
            endDay != null -> endDay.shift(1).start(context)
            else -> date.shift(1, granularity).start(context)
        }
        return TemporalSpec(from.moment(context.zone), until.moment(context.zone), granularity, start != null)
    }

    companion object {
        /** A length of whole days, which covers days rather than hours when no time is given: "for 3 days" is 3. */
        fun wholeDays(minutes: Int): Int? = (minutes / MINUTES_PER_DAY).takeIf { it > 0 && minutes % MINUTES_PER_DAY == 0 }

        private const val MINUTES_PER_DAY = 24 * 60

        /**
         * Folds the readings of one entry into one fact: its day or days, its time, its end and its length.
         *
         * - **Day**: a range of days wins outright. Otherwise a date and a year named apart combine ("January 1" + "next year"),
         *   and so do a week and a weekday ("next week" + "friday"). Otherwise the first day of one day, then a wider period.
         * - **Time**: the first time read, else the rough hour a day carries ("tonight"). An end or a length said apart is added
         *   to it: `10:00` + `for 45 min` → 10:00 for 45 minutes; `15:30` + `until 4pm` → 15:30 to 16:00. A time read with its
         *   own day ("in 2 hours") gives that day when nothing else does.
         *
         * - **A** `tmrw`, `10am`, `for 30 minutes` → 24 Sep, 10:00, duration 30.
         * - **B** `next Friday` → 2 Oct, no time.
         * - **C** `next Friday`, `at 2:30pm` → 2 Oct, 14:30.
         */
        fun merge(days: List<Fact>, clocks: List<Fact>, ends: List<Fact> = emptyList(), lengths: List<Int> = emptyList()): Fact =
            Merge(days, clocks, ends.firstOrNull(), lengths.firstOrNull()).fact()

        /**
         * Whether facts read side by side are one date: a single day ("next week"), or parts [merge] knows how to put
         * together ("January 1 next year", "next week friday"). Two days side by side ("fri tues") are not one date.
         */
        fun oneDate(days: List<Fact>): Boolean = days.size <= 1 || days.size == 2 && (
            days.any { it.granularity == Granularity.WEEK || it.weekStart != null } && days.any { it.weekday != null } ||
                days.any { it.granularity == Granularity.YEAR } && days.any { it.yearImplied })
    }
}

/** The steps of [Fact.merge], one property or function each. */
private class Merge(private val days: List<Fact>, clocks: List<Fact>, private val end: Fact?, private val length: Int?) {
    private val dated = days.filter { it.day != null }
    /** The time: the first one read, else the hour a day carries. */
    private val chosen = clocks.firstOrNull() ?: days.firstOrNull { it.start != null }?.let { Fact(start = it.start) } ?: Fact()
    /** The day ranked most certain: a day before a wider period, and a day the text stated before one a finder filled in. */
    private val first = dated.minByOrNull { if (!it.certainDay) 2 else if (it.granularity != Granularity.DAY) 1 else 0 }
    private val allDay = days.any { it.allDay }
    /** A length of whole days with no time to start from: "today for 3 days" covers today and the next two. */
    private val daysLong = length?.takeIf { chosen.start == null }?.let(Fact::wholeDays)

    fun fact(): Fact {
        // A range of days decides the day outright, and the event is all-day unless a time was given too.
        dated.firstOrNull { it.endDay != null }?.let { range ->
            val time = time(end, laterDay = false)
            return time.copy(day = range.day, endDay = range.endDay, allDay = time.start == null || allDay)
        }
        val (day, granularity) = day()
        // Whole days with no time cover those days: "today for 3 days" ends the day after tomorrow.
        val coversDays = day != null && daysLong != null
        val endDay = endDayAfter(day) ?: daysLong?.takeIf { it > 1 }?.let { day?.shift(it - 1) }
        // An end on a day before the start is no end of it, and lends the start no hour.
        return time(end?.takeIf { it.endsOnOrAfter(day) }, laterDay = endDay != null).copy(
            day = day, endDay = endDay, granularity = granularity, allDay = allDay || coversDays,
            weekday = dated.firstNotNullOfOrNull { it.weekday }, namedWeekday = dated.firstNotNullOfOrNull { it.namedWeekday },
            offsetMinutes = chosen.offsetMinutes ?: first?.offsetMinutes,
            impossible = days.any { it.impossible },
        )
    }

    /** The time with the [end] said apart ("until 4pm"), on the same day or a [laterDay], or else with the length said apart ("for 45 min"). */
    private fun time(end: Fact?, laterDay: Boolean): Fact {
        val start = chosen.start
        val endClock = end?.detachedEnd
        return when {
            start == null || chosen.end != null -> chosen
            endClock != null -> chosen.copy(end = Clocks.endAfter(start, endClock, laterDay), zone = chosen.zone ?: endClock.zone)
            else -> chosen.copy(duration = length)
        }
    }

    /** The day and how wide it is. */
    private fun day(): Pair<LocalDate?, Granularity> {
        // A date written without its year, and a year named beside it: "January 1 next year" is that day in that year.
        // A month moved to its year stays a month: "nov next year" is November 2027.
        val year = dated.firstOrNull { it.granularity == Granularity.YEAR }?.day?.year
        val open = dated.firstOrNull { it.yearImplied }
        val openDay = open?.day
        val inYear = if (year != null && openDay != null) dateOrNull(year, openDay.month.number, openDay.day) else null
        if (inYear != null) return inYear to open!!.granularity
        // "next week" in one place and a bare "friday" in another: that Friday in that week. The week may also come from a
        // count: "saturday in 2 weeks" is the Saturday of the week two weeks from now.
        val week = dated.firstOrNull { it.granularity == Granularity.WEEK }?.day ?: dated.firstNotNullOfOrNull { it.weekStart }
        val weekday = dated.firstNotNullOfOrNull { it.weekday }
        if (week != null && weekday != null) return week.onOrAfter(weekday) to Granularity.DAY
        return (first?.day ?: chosen.day) to (first?.granularity ?: Granularity.DAY)
    }

    /** An end on another day: "from friday 22:00 until saturday at 6", "monday until friday". A weekday is the one on or after the start. */
    private fun endDayAfter(day: LocalDate?): LocalDate? = end?.lastDayFrom(day)?.takeIf { day != null && it > day }
}
