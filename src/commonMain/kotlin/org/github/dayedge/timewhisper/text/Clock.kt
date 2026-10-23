package org.github.dayedge.timewhisper.text

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Which half of a 12-hour clock: before or after noon. */
internal enum class Meridiem { AM, PM }

/** A clock reading as written, before context settles whether a bare hour means morning or afternoon. */
internal data class Clock(
    /** The hour as written: 5 in "5pm", 17 in "17:00". */
    val hour: Int,
    val minute: Int = 0,
    /** The half of the day written with it: "5pm", "5 pm". */
    val meridiem: Meridiem? = null,
    /** Taken as written, never moved to the afternoon: "09", "noon", a 24-hour reading. */
    val exact: Boolean = false,
    /** Written with its minutes: "5:30", "eight thirty". */
    val minutes: Boolean = false,
    /** Said as words with a fraction or in full: "half past 3", "eight thirty". */
    val spoken: Boolean = false,
    /** Bare hours the language reads as afternoon when nothing else says which half of the day: 1 to 7 in English. */
    val afternoonHours: IntRange? = null,
    /** The time zone named right after the clock: "3pm EST", "15:00 UTC+2". The time is then a time there, not where the user is. */
    val zone: TimeZone? = null,
) {
    /** An hour and nothing else: "5", "five". */
    val bare: Boolean get() = meridiem == null && !exact && !minutes && !spoken

    /** An hour with at most a half of the day: what a fraction may be said before ("half past 3", "quarter to 5pm"). */
    val plainHour: Boolean get() = !minutes && !exact && !spoken

    /** The hour before this one on the clock face: 12 before 1, 23 before midnight. */
    val hourBefore: Int get() = when (hour) { 1 -> 12; 0 -> 23; else -> hour - 1 }

    /** This clock with the half of the day [meridiem], where it has none of its own and is an hour from 1 to 12; else null. */
    fun withMeridiem(meridiem: Meridiem?): Clock? =
        if (this.meridiem == null && !exact && meridiem != null && hour in 1..12) copy(meridiem = meridiem) else null

    /** The hour on a 24-hour clock. A bare hour [alone] in the text is moved to the afternoon where the language does that. */
    fun hour24(alone: Boolean): Int = when (meridiem) {
        Meridiem.AM -> hour % 12; Meridiem.PM -> hour % 12 + 12
        null -> if (!exact && alone && afternoonHours != null && hour in afternoonHours) hour + 12 else hour
    }

    /** The time of day this clock names on its own. */
    fun time(): LocalTime = LocalTime(hour24(true), minute)
}
