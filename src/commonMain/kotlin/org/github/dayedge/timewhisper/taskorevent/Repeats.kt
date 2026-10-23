package org.github.dayedge.timewhisper.taskorevent

import kotlinx.datetime.DayOfWeek
import org.github.dayedge.timewhisper.Frequency
import org.github.dayedge.timewhisper.Granularity
import org.github.dayedge.timewhisper.RecurrenceSpec
import org.github.dayedge.timewhisper.WeekdayRule
import org.github.dayedge.timewhisper.TokenLabel
import org.github.dayedge.timewhisper.temporal.Example.Companion.en
import org.github.dayedge.timewhisper.temporal.Example.Companion.pl
import org.github.dayedge.timewhisper.temporal.Read
import org.github.dayedge.timewhisper.temporal.Reader
import org.github.dayedge.timewhisper.temporal.Scope
import org.github.dayedge.timewhisper.temporal.TimeWords
import org.github.dayedge.timewhisper.text.Edge
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.Word
import org.github.dayedge.timewhisper.text.words

/** How often: the words of a part labelled a repeat, read by [recurrence]. */
internal object RepeatReader : Reader {
    override val label = TokenLabel.REPEAT
    override val name = "repeat"
    override val examples = en("every other monday", "daily", "tuesdays", "the last friday of every month", "mon wed fri", "every month on the 15th") +
        pl("co poniedziałek", "we wtorki", "co 2 tygodnie", "w dni robocze", "w każdy ostatni piątek miesiąca")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? = recurrence(TimeWords.meaningful(lexemes.words))?.let { Read.Repeat(it) }

    /**
     * How often, from the words of a part the tagger labelled a repeat. The words may come in any order; each says one thing,
     * and what they say together is the repeat. A word that says nothing a repeat can hold ("except", "twice") leaves the part
     * unread: a missed repeat is acceptable, a wrong one is not.
     *
     * - Every day, week, month or year, with a count: `daily`, `every day`, `every 2 weeks`, `every other month`, `fortnightly`,
     *   `every sixth week`, `co 2 tygodnie`, `co trzeci dzień`, `codziennie`.
     * - Named weekdays, every week or every other: `every monday`, `mon wed fri`, `mon-fri`, `tuesdays`, `every other friday`,
     *   `every 2 weeks on sunday`, `every weekday`, `co poniedziałek`, `we wtorki`, `w dni robocze`, `co drugi piątek`.
     * - Days of the month: `every month on the 15th`, `monthly 1st`, `the last day of every month`, `on the first and fifteenth
     *   of each month`, `każdego ostatniego dnia miesiąca`.
     * - Weekdays of the month: `the last friday of every month`, `first thu of the month`, `every first and third wednesday of
     *   the month`, `w każdy ostatni piątek miesiąca`, `co miesiąc w pierwszy poniedziałek`.
     *
     * "every third sunday" is not read: it may be that Sunday of each month or every three weeks, and neither is guessed.
     * "every second friday" is every other Friday, as people mostly mean it; "every first sunday" and "every last friday" are of each month.
     */
    fun recurrence(words: List<Word>): RecurrenceSpec? {
        if (words.isEmpty()) return null
        var interval: Int? = null
        var frequency: Frequency? = null
        var workdays = false
        var partOfDay = false
        // An ordinal right before "day": "the 10th day of the month", or "every third day".
        var ordinalDay = false
        val weekdays = mutableListOf<DayOfWeek>()
        val ordinals = mutableListOf<Int>()
        fun set(f: Frequency): Boolean {
            if (frequency != null && frequency != f) return false
            frequency = f; return true
        }
        for ((i, word) in words.withIndex()) {
            val next = words.getOrNull(i + 1)
            val ordinal = word.ordinal
            when {
                Role.EVERY in word || Role.AND in word -> {}
                Role.PART_OF_DAY in word -> partOfDay = true
                Role.OTHER in word -> interval = 2
                Role.WEEKDAYS in word -> workdays = true
                word.weekday != null -> weekdays += word.weekday!!
                // "mon-fri": every weekday from the first to the second.
                word.dashParts?.let { (from, to) -> from.weekday != null && to.weekday != null } == true -> {
                    val (from, to) = word.dashParts!!
                    val days = DayOfWeek.entries
                    var at = days.indexOf(from.weekday!!)
                    while (true) { weekdays += days[at]; if (days[at] == to.weekday) break; at = (at + 1) % 7 }
                }
                word.entry?.recurrence != null -> if (!set(word.entry.recurrence)) return null
                word.unit != null -> when (word.unit) {
                    Granularity.DAY -> if (ordinals.isNotEmpty() && words.getOrNull(i - 1)?.ordinal != null) ordinalDay = true
                        else if (!set(Frequency.DAILY)) return null
                    Granularity.WEEK -> if (!set(Frequency.WEEKLY)) return null
                    Granularity.MONTH -> if (!set(Frequency.MONTHLY)) return null
                    Granularity.YEAR -> if (!set(Frequency.YEARLY)) return null
                    null -> return null
                }
                // A count before a week, a month or a year: "every 2 weeks", "every sixth week", "every 3rd week". Before "day" it is read as an
                // ordinal, which a day of the month needs ("the 10th day of the month"): "every 3 days" comes out the same.
                next?.unit != null && (next.unit != Granularity.DAY || ordinal == null) && (word.number ?: ordinal) != null -> interval = word.number ?: ordinal
                ordinal != null -> ordinals += ordinal
                word.entry?.edge != null -> ordinals += if (word.entry.edge == Edge.START) 1 else -1
                else -> return null
            }
        }
        val every = interval ?: 1
        if (every < 1) return null
        if (workdays) {
            // "codziennie w dni robocze", "daily on weekdays": every weekday.
            // "every other working day" is not every other week of them.
            if (weekdays.isNotEmpty() || ordinals.isNotEmpty() || interval != null || frequency !in setOf(null, Frequency.WEEKLY, Frequency.DAILY)) return null
            return RecurrenceSpec(Frequency.WEEKLY, every, DayOfWeek.entries.take(5).map(::WeekdayRule))
        }
        if (weekdays.isNotEmpty()) {
            if (ordinals.isEmpty()) {
                if (frequency !in setOf(null, Frequency.WEEKLY)) return null
                return RecurrenceSpec(Frequency.WEEKLY, every, weekdays.distinct().map(::WeekdayRule))
            }
            // "the last friday of every month": which ones of the month.
            if (frequency == Frequency.MONTHLY) {
                if (ordinals.any { it !in 1..5 && it != -1 }) return null
                return RecurrenceSpec(Frequency.MONTHLY, every, weekdays.distinct().flatMap { day -> ordinals.distinct().map { WeekdayRule(day, it) } })
            }
            // "every first sunday", "every last friday": which one of each month, as only a month has a first and a last.
            if (ordinals.all { it == 1 || it == -1 } && frequency == null && interval == null)
                return RecurrenceSpec(Frequency.MONTHLY, weekdays = weekdays.distinct().flatMap { day -> ordinals.distinct().map { WeekdayRule(day, it) } })
            // "every second friday" is every other Friday; a higher one ("every third sunday") is not guessed.
            return if (ordinals == listOf(2) && frequency == null && interval == null) RecurrenceSpec(Frequency.WEEKLY, 2, weekdays.distinct().map(::WeekdayRule)) else null
        }
        if (ordinals.isNotEmpty()) {
            // "every third day": an interval in days.
            if (ordinalDay && frequency == null && ordinals.size == 1 && ordinals[0] > 0) return RecurrenceSpec(Frequency.DAILY, ordinals[0])
            if (frequency !in setOf(null, Frequency.MONTHLY) || ordinals.any { it !in 1..31 && it != -1 }) return null
            return RecurrenceSpec(Frequency.MONTHLY, every, daysOfMonth = ordinals.distinct())
        }
        // "every morning": every day.
        return RecurrenceSpec(frequency ?: (if (partOfDay) Frequency.DAILY else return null), every)
    }
}
