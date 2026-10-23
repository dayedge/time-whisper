package org.github.dayedge.timewhisper.taskorevent

import kotlinx.datetime.*
import kotlin.time.Duration.Companion.minutes
import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.temporal.*
import org.github.dayedge.timewhisper.text.*

/**
 * Turns one line of text into ranked task and event candidates.
 *
 * The steps, in order, with the file that holds each:
 *
 * 1. **Tokenize** — split on whitespace, keep source positions. `text/Tokens.kt`
 * 2. **Tag** — the tagger says what each word is for: title, wrapping words, or a part such as a day, a time or a repeat. [Tagger]
 * 3. **Lex** — read each token once into typed values; only a word in a part about time may be spell-corrected. `text/Lexer.kt`
 * 4. **Read** — each part is read by the reader for its label; one that cannot be read goes back into the title. A host's
 *    date finder may read a day or a time no rule could. `taskorevent/Readers.kt`
 * 5. **Assemble** — one date and time, one repeat, the details, a reminder and the titles. [Assembler]
 * 6. **Decide** — task, event, both or nothing, and in which order. [Ranking.decide] in `taskorevent/Ranking.kt`
 * 7. **Build** — turn the draft into public `TaskSpec` / `EventSpec` values. [task], [event]
 *
 * Three sentences are followed through every step in the comments on those functions.
 * "Now" is Wednesday 2026-09-23 12:00 UTC and the task lists are Work, Personal and Home.
 *
 * - **A** `Standup tmrw 10am for 30 minutes every weekday @Office`
 *   → one event: "Standup", Thu 24 Sep 10:00–10:30, weekly Mon–Fri, at Office.
 * - **B** `Pay rent next Friday #home !! remind me 1 day before`
 *   → one task: "Pay rent", due Fri 2 Oct, list home, medium priority, reminder Thu 1 Oct at the reminder time (09:00).
 * - **C** `Dentist next Friday at 2:30pm`
 *   → an event first ("Dentist", Fri 2 Oct 14:30–15:30), then a task due at the same time.
 */
internal class TaskOrEventParser(
    private val config: Configuration, private val lexer: Lexer, private val temporal: TemporalReader, private val tagger: () -> Tagger,
) {
    /** What steps 1 to 4 found in a text: its parts, each with what it says, whether a task was forced, and the tagging. */
    private class Found(val readings: List<Reading>, val force: Boolean, val tagging: Tagging)

    /** Steps 1 to 4: the parts of [input], each with what it says. A leading `+` forces a task and is taken off first. */
    private suspend fun read(input: String, context: ParseContext): Found {
        val tokens = tokenize(input)
        // "+ buy milk", "+buy milk": the mark that forces a task. A "+" glued to a number is its sign: "+3 days".
        val force = tokens.firstOrNull()?.text?.let { it.startsWith('+') && it.getOrNull(1)?.isDigit() != true } == true
        // The tagger reads the text as typed, mark included; its words are matched by position.
        val tagging = tagger().tag(input, config.language)
        val lexemes = lexer.lex(if (force) unmarked(tokens) else tokens) { tagging.mayCorrect(it) }
        val scope = temporal.scope(context, tagging.urgency)
        val readings = Span.of(lexemes, tagging).map { span ->
            val reader = Readers[span.label] ?: return@map Reading(span, null)
            reader.read(span.lexemes, scope)?.let { return@map Reading(span, it) }
            // A day or a time the readers could not read, which a host's date finder may.
            if (span.label in Labels.finder) temporal.fallback(span.lexemes, context)?.let { return@map Reading(span, Read.When(it), byFinder = true) }
            Reading(span, null)
        }
        return Found(readings, force, tagging)
    }

    /** "+ buy milk" or "+buy milk" without the mark, keeping the positions of what remains. */
    private fun unmarked(tokens: List<Token>): List<Token> {
        val first = tokens.first()
        if (first.text == "+") return tokens.drop(1)
        return listOf(Token(first.text.drop(1), SourceRange(first.range.offset + 1, first.range.length - 1))) + tokens.drop(1)
    }

    /** Steps 1 to 5: everything up to, but not including, the task-or-event decision. */
    suspend fun draft(input: String, context: ParseContext): Draft {
        val found = read(input, context)
        return Assembler(context, config.reminderTime).assemble(found.readings, found.force, found.tagging)
    }

    /**
     * The first day inside longer text, and the text without it, for filtering by date: "standup tomorrow" → tomorrow,
     * "standup". A deadline counts as its day ("report by friday"). A time right after the day leaves the text with it
     * ("lunch next friday at noon" leaves "lunch") but is not part of the answer. A month alone is the one in the current
     * year here, as a filter means it: "March" in September is last March, while "coming March" and "in March" are the next one.
     */
    suspend fun find(text: String, context: ParseContext): FoundTemporal? {
        val readings = read(text, context).readings
        val at = readings.indexOfFirst { it.label in Labels.day && it.fact?.day != null }.takeIf { it >= 0 } ?: return null
        val read = readings[at].fact!!
        if (read.impossible) return null
        val month = readings[at].span.lexemes.singleOrNull()?.word?.month
        val day = if (month != null) Fact(LocalDate(context.today.year, month, 1), Granularity.MONTH) else read
        val spec = day.copy(start = null, end = null).temporal(context) ?: return null
        // The date, and a time right after it, go out with the wrapping words that attach them: "standup on friday at noon".
        fun attached(i: Int) = listOfNotNull(readings.getOrNull(i - 1)?.takeIf { it.label == TokenLabel.WRAPPER }, readings[i])
        val next = if (readings.getOrNull(at + 1)?.label == TokenLabel.WRAPPER) at + 2 else at + 1
        val time = readings.getOrNull(next)?.takeIf { it.label == TokenLabel.CLOCK && it.value != null }
        val taken = attached(at) + if (time != null) attached(next) else emptyList()
        val rest = readings.filter { reading -> taken.none { it === reading } }.flatMap { it.span.lexemes }.source()
        return FoundTemporal(spec, rest)
    }

    /**
     * The whole pipeline. Returns nothing when the text holds an impossible ISO date, or when [Ranking.decide] finds nothing worth offering.
     */
    suspend fun parse(text: String, context: ParseContext): List<Candidate> {
        // "2026-02-30" looks like a date and is not one. Nothing sensible can be offered.
        if ('-' in text && isoToken.findAll(text).any { Word.isoDate(it.value) == null }) return emptyList()
        val d = draft(text, context)
        return Ranking.decide(d.evidence).map { kind ->
            if (kind == CandidateKind.TASK) Candidate.Task(task(d, context), d.spans)
            else Candidate.Event(event(d, context), d.spans)
        }
    }

    /**
     * Step 7 for a task. The due value is a date, or an instant when a time was given.
     *
     * - **B** → `TaskSpec("Pay rent", due = 2026-10-02, list = home, priority = MEDIUM, reminder = 2026-10-01 09:00)`: a day before a date with no time is at the reminder time.
     * - **C** → `TaskSpec("Dentist", due = 2026-10-02 14:30)`.
     */
    private fun task(d: Draft, context: ParseContext): TaskSpec {
        val due = d.fact.day?.let { day ->
            d.fact.start?.let { Due.AtTime(d.fact.at(day, it, context).moment(context.zone)) } ?: Due.OnDate(day.civil())
        }
        return TaskSpec(d.title, due, d.listId, d.priority, d.recurrence, d.reminder)
    }

    /**
     * Step 7 for an event. Without a time it is all-day. With a time and no end, it lasts the stated duration,
     * or the configured default (60 minutes). An end at or before the start rolls to the next day ("22-01").
     *
     * - **A** → start Thu 24 Sep 10:00, duration 30 → end 10:30; location "Office"; weekly Mon–Fri.
     * - **C** → start Fri 2 Oct 14:30, no end or duration → end 15:30.
     */
    private fun event(d: Draft, context: ParseContext): EventSpec {
        val f = d.fact
        val first = f.day ?: context.today
        val last = f.endDay ?: first
        // All-day events end on the day after the last day, exclusive.
        val time = if (f.start == null) EventTime.AllDay(first.civil(), last.shift(1).civil()) else {
            val start = f.at(first, f.start, context)
            // No end stated: the duration, or the default length, after the start.
            val length = f.duration ?: config.defaultEventMinutes
            val end = if (f.end == null) f.at(last, f.start, context) + length.minutes else {
                val candidate = f.at(last, f.end, context)
                // "22-01": an end at or before the start is on the next day.
                if (candidate <= start) f.at(last.shift(1), f.end, context) else candidate
            }
            EventTime.Timed(start.moment(context.zone), end.moment(context.zone))
        }
        return EventSpec(d.eventTitle.ifEmpty { d.title }, time, d.calendarId, d.location, d.recurrence, listOfNotNull(d.reminder))
    }

    private companion object {
        /** Anything written as a yyyy-mm-dd date, valid or not. */
        val isoToken = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    }
}
