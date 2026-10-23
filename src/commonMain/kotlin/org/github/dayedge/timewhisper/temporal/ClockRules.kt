package org.github.dayedge.timewhisper.temporal

import org.github.dayedge.timewhisper.temporal.Example.Companion.en
import org.github.dayedge.timewhisper.temporal.Example.Companion.pl
import kotlinx.datetime.LocalTime
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import org.github.dayedge.timewhisper.temporal.Clocks.clock
import org.github.dayedge.timewhisper.temporal.Clocks.range
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.Word

/**
 * The ways of writing a time or a range of times, in the order they are tried on the words of a part the tagger labelled a
 * time. The tagger has already said these words are a time, so a bare number is an hour: "dentist friday 11" is 11:00.
 * "morning", "afternoon" and "evening" are not times; they only qualify a day.
 *
 * In the running examples: **A** `10am` and **C** `at 2:30pm` are read by [WrittenClock].
 */
internal object ClockRules : RuleSet(listOf(WrittenClock, AtSignClock, GluedRange, ClockToClock, ElapsedTime))

/**
 * One clock, in any form the language writes or says it.
 *
 * - English: `at 5`, `2:30pm`, `5 pm`, `noon`, `half past 3`, `quarter to 5`, `eight thirty`, `3pm EST`.
 * - Polish: `o 17`, `15:30`, `o piątej po południu`, `około 17:00`, `ósma trzydzieści`, `kwadrans po piątej`, `wpół do piątej`.
 * - Other languages: German "um 17 Uhr", French "à 17h", Spanish "a las 5". Named clocks are lexicon entries ("Mittag", "midi");
 *   French "17h" needs the language's clock reader to accept that form.
 */
internal object WrittenClock : PhraseRule {
    override val name = "written-clock"
    override val examples = en("at 5", "2:30pm", "5 pm", "noon", "half past 3", "eight thirty", "3pm EST") +
        pl("o 17", "15:30", "o piątej po południu", "około 17:00", "ósma trzydzieści")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val (time, used) = clock(words, 0) ?: return null
        return if (used == words.size) Fact(start = time.time(), zone = time.zone) else null
    }
}

/**
 * A clock glued to "@": `@5pm`, `@17:30`. The sign is not a word of any language, so this reads the same everywhere.
 */
internal object AtSignClock : PhraseRule {
    override val name = "at-sign-clock"
    override val examples = en("@5pm", "@17:30")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val time = words.singleOrNull()?.atClock ?: return null
        return Fact(start = time.time(), zone = time.zone)
    }
}

/**
 * Two clocks written as one token with a range sign between them, with the half of the day or a zone after them:
 * `10am-11am`, `9:30-10:15`, `3-5pm`, `7:30-8 am`, `11-12 EST`, `9->10`. Each side is read by the language's clock reader.
 */
internal object GluedRange : PhraseRule {
    override val name = "glued-range"
    override val examples = en("10am-11am", "9:30-10:15", "3-5pm", "7:30-8 am", "11-12 EST", "17:00-24") + pl("10:00-10:45", "18-24")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val (first, second) = words[0].clockRanges.firstOrNull() ?: return null
        // A word for the half of the day right after the pair belongs to it ("7:30-8 am"), and so does a time zone ("11-12 EST").
        val half = words.getOrNull(1)?.meridiem
        val zone = words.getOrNull(if (half != null) 2 else 1)?.zone
        val taken = 1 + (if (half != null) 1 else 0) + (if (zone != null) 1 else 0)
        if (taken != words.size) return null
        val end = second.withMeridiem(half) ?: second
        return range(first, end.copy(zone = zone ?: end.zone))
    }
}

/**
 * Two clocks with a range word or "and" between them. The word in front of "and" ("between") attaches the part, so the
 * tagger leaves it outside: the part is "2 and 4".
 *
 * - English: `from 3 to 5pm`, `3pm to 5`, `10am until noon`, `2 and 4`. A bare end hour is read against the start:
 *   "3pm to 5" ends at 17:00.
 * - Polish: `od 14 do 16`, `15:30 do 16`, `między 14 a 16`.
 * - Other languages: German "von 14 bis 16", "zwischen 2 und 4"; French "de 14h à 16h". Spanish puts an article before each
 *   hour ("entre las 2 y las 4"), which a lexicon would give the role of a small word.
 */
internal object ClockToClock : PhraseRule {
    override val name = "clock-to-clock"
    override val examples = en("from 3 to 5pm", "3pm to 5", "10am until noon", "2 and 4") + pl("od 14 do 16", "między 14 a 16")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        val (first, used) = clock(words, 0) ?: return null
        val joiner = words.getOrNull(used) ?: return null
        if (Role.RANGE !in joiner && Role.AND !in joiner) return null
        val (second, used2) = clock(words, used + 1) ?: return null
        return if (used + 1 + used2 == words.size) range(first, second) else null
    }
}

/**
 * Time that passes from this moment, so it is counted on the clock and not on the calendar.
 * Two hours across a change of offset are still two hours.
 *
 * - English: `in 2 hours`, `30 minutes from now`.
 * - Polish: `za 2 godziny`, `za godzinę`. Polish drops the count when it is one; a wording puts it back ("za 1 godzinę").
 * - Other languages: German "in 2 Stunden", French "dans 2 heures", Spanish "en 2 horas".
 */
internal object ElapsedTime : PhraseRule {
    override val name = "elapsed-time"
    override val examples = en("in 2 hours", "30 minutes from now", "in 2 hours and 15 minutes", "in an hour and a half", "+2h", "now + 3 hours") +
        pl("za 2 godziny", "za godzinę", "za godzinę i kwadrans")
    override fun read(words: List<Word>, scope: Scope): Fact? {
        // Forward only, hours and minutes only: an amount with days in it is a day.
        val minutes = Amounts.anchored(words)?.takeIf { it.onlyClock }?.minutes?.takeIf { it in 1..999 * 60 } ?: return null
        val context = scope.context
        val instant = context.referenceInstant + minutes.minutes
        val local = instant.toLocalDateTime(context.zone)
        return Fact(local.date, start = LocalTime(local.hour, local.minute), offsetMinutes = context.zone.offsetAt(instant).totalSeconds / 60)
    }
}
