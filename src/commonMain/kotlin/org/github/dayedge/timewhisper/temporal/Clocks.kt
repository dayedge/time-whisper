package org.github.dayedge.timewhisper.temporal

import kotlinx.datetime.LocalTime
import org.github.dayedge.timewhisper.text.Clock
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.Word
import org.github.dayedge.timewhisper.text.contains

/** Clocks, ranges of clocks and lengths of time: the pieces the clock rules in `ClockRules.kt` are built from. */
internal object Clocks {
    /** A clock at [index] and the number of words it takes: "5pm", "5 pm", "half past 3", "eight thirty", "5". */
    fun clock(words: List<Word>, index: Int): Pair<Clock, Int>? {
        val word = words.getOrNull(index) ?: return null
        val joiner = words.getOrNull(index + 1)
        val fraction = word.entry?.fraction
        // Spoken: "half past 3", "quarter to 5". The clock that follows is moved by the fraction.
        if (fraction != null && (Role.PAST in joiner || Role.TO in joiner)) {
            val (base, used) = clock(words, index + 2) ?: return null
            // Only a plain hour, or one with am/pm, can follow: "half past 3:15" means nothing.
            if (!base.plainHour) return null
            // "to" counts back from the hour: quarter to 5 is 4:45, and quarter to 1 is 12:45.
            val past = Role.PAST in joiner
            val hour = if (past) base.hour else base.hourBefore
            return base.copy(hour = hour, minute = if (past) fraction else 60 - fraction, minutes = true, spoken = true) to (used + 2)
        }
        // "half" right before an hour: English "half seven" is half past seven; Polish "pół ósmej" and German "halb acht"
        // are half an hour before eight. Both are 7:30; which hour is named is the language's convention.
        if (fraction == 30 && joiner != null && Role.PAST !in joiner && Role.TO !in joiner) {
            val (base, used) = clock(words, index + 1) ?: return null
            if (!base.plainHour) return null
            val hour = if (word.conventions.halfCountsBack) base.hourBefore else base.hour
            return base.copy(hour = hour, minute = 30, minutes = true, spoken = true) to (used + 1)
        }
        // Said in full ("eight thirty"), or written: "5", "5pm", "17:30", "noon", and an hour word ("eight", "piątej").
        val (base, used) = saidInFull(words, index) ?: ((word.clock ?: return null) to 1)
        // am/pm as a word of its own: "5 pm". It can only attach to a plain hour from 1 to 12.
        val (timed, taken) = base.withMeridiem(words.getOrNull(index + used)?.meridiem)?.let { it to (used + 1) } ?: (base to used)
        // A time zone right after the clock belongs to it: "3pm EST", "15:00 UTC+2". Only here are those letters a zone.
        val zone = words.getOrNull(index + taken)?.zone ?: return timed to taken
        return timed.copy(zone = zone) to (taken + 1)
    }

    /**
     * A time said in full: the hour, then its minutes, the way it is read aloud or dictated. It is one rule for every
     * language; the lexicon only says which words are numbers and which are hours.
     *
     * - English: `eight thirty` → 8:30. Hour and minutes are plain number words.
     * - Polish: `ósma trzydzieści`, `szóstej trzydzieści` → 8:30, 6:30. The hour is an ordinal, which the lexicon reads as an hour.
     * - German "acht Uhr dreißig" puts a word between the two, which a wording would drop.
     *
     * The hour must be a word ("5 30" is not a time), and minutes under ten are not said this way ("eight five").
     * Whether the result is a 12-hour or a 24-hour reading is the language's own convention, asked of the lexicon exactly as
     * if the digits had been typed: "8:30".
     */
    private fun saidInFull(words: List<Word>, index: Int): Pair<Clock, Int>? {
        val word = words.getOrNull(index) ?: return null
        if (word.allDigits) return null
        val hour = word.clock?.takeIf { it.bare }?.hour ?: word.entry?.number ?: return null
        val said = words.getOrNull(index + 1) ?: return null
        // The minutes as a number word, or as two digits composed by the lexer from "forty five".
        val minute = said.entry?.number?.takeIf { !said.allDigits } ?: said.text.takeIf { it.length == 2 && said.allDigits }?.toInt() ?: return null
        if (minute !in 10..59) return null
        val clock = word.read("$hour:$minute").clock ?: return null
        return clock.copy(spoken = word.clock != null) to 2
    }

    /** From one clock to another: "3-5pm", "10am until noon". */
    fun range(first: Clock, second: Clock): Fact {
        // "3-5pm": the first hour has no am/pm of its own, so it takes the second's,
        // unless that would put it after the end ("11-1pm" starts at 11 in the morning).
        val start = first.withMeridiem(second.meridiem)?.takeIf { it.hour24(false) <= second.hour24(false) } ?: first
        val from = start.time()
        // A zone is said once, usually at the end: "11-12 EST", "3pm to 5pm EST".
        return Fact(start = from, end = endAfter(from, second), zone = second.zone ?: first.zone)
    }

    /**
     * The end clock as a time of day, given where the span starts: a bare "5" after 3 pm means 5 pm. On a [laterDay] the
     * hour is as written: "from 22:00 until tomorrow at 6" ends at 6 in the morning.
     */
    fun endAfter(start: LocalTime, end: Clock, laterDay: Boolean = false): LocalTime {
        var hour = end.hour24(false)
        if (laterDay) return LocalTime(hour, end.minute)
        // A bare end hour that would not come after the start is read as afternoon: "3pm to 5" ends at 17:00.
        if (end.meridiem == null && !end.exact && hour * 60 + end.minute <= start.hour * 60 + start.minute && hour < 12) hour += 12
        return LocalTime(hour, end.minute)
    }
}
