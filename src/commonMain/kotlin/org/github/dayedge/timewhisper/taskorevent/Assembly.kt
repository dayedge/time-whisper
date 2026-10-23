package org.github.dayedge.timewhisper.taskorevent

import kotlinx.datetime.*
import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.temporal.*
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.edgePunctuation
import org.github.dayedge.timewhisper.text.source
import org.github.dayedge.timewhisper.text.words
import kotlin.time.Duration.Companion.minutes

/** Everything a sentence says, put together, before it is turned into a task or an event. */
internal data class Draft(
    val title: String, val eventTitle: String, val fact: Fact, val listId: String?, val calendarId: String?,
    val location: String?, val priority: Priority,
    val recurrence: RecurrenceSpec?, val reminder: ReminderSpec?, val spans: List<RecognizedSpan>,
    /** What [Ranking.decide] needs to say whether to offer a task, an event, both or nothing. */
    val evidence: Evidence,
)

/**
 * Step 5. Puts the parts that were read together: one day and time, one repeat with where it starts and stops, the
 * details, a reminder, and the titles. A part that says nothing in the whole sentence goes back into the title, as it
 * was typed, like a part no reader could read. What was found is written down as [Evidence] for [Ranking.decide].
 *
 * - **A** 24 Sep 10:00 for 30 min, weekly Mon–Fri; task title "Standup @Office", event title "Standup".
 * - **B** 2 Oct, reminder 1 Oct at the reminder time; "Pay rent".
 * - **C** 2 Oct 14:30; "Dentist".
 */
internal class Assembler(private val context: ParseContext, private val reminderTime: CivilTime) {

    fun assemble(found: List<Reading>, force: Boolean, tagging: Tagging): Draft {
        val parts = Parts(found).joinRepeats().seriesWithoutRepeat().timesWithoutTheirDay().oneOfEach()
        val schedule = schedule(parts)
        // The reminder is set against the entry as stated, before a time alone is put on today or tomorrow.
        val reminder = parts.remind?.let { reminder(it, schedule.fact) }
        val fact = onItsDay(schedule.fact)
        val kept = parts.timesNotUsed(fact)
        val details = Details(kept)
        val titles = Titles(kept)
        return Draft(
            title = titles.task, eventTitle = titles.event, fact = fact,
            listId = details.list, calendarId = details.calendar, location = details.place, priority = details.priority,
            recurrence = schedule.recurrence, reminder = reminder,
            spans = kept.read.map { RecognizedSpan(it.span.range, source(it)) },
            evidence = evidence(kept, fact, schedule, details, titles, reminder, force, tagging),
        )
    }

    /**
     * One day and time from the days, times, ends and lengths that were read ([Fact.merge]); a deadline gives a day and a time
     * like any other. Then the repeat, which may move the day to its first occurrence, and where the repeat stops.
     */
    private fun schedule(parts: Parts): Schedule {
        val start = parts.values<Read.SeriesStart>().firstOrNull()
        // Where the repeat stops: said on its own ("until june"), or as the last day of the range it starts with ("from 1 nov to 1 dec").
        val end = parts.values<Read.SeriesEnd>().lastOrNull() ?: start?.fact?.endDay?.let { Read.SeriesEnd(until = it) }
        var fact = Fact.merge(parts.days, parts.clocks, parts.ends, parts.lengths)
        // Where the repeat starts is the day, if nothing else gave one.
        if (start != null && fact.endDay == null && parts.days.none { it.day != null }) fact = fact.copy(day = start.day)
        val rule = parts.values<Read.Repeat>().firstOrNull()?.rule?.let { withWeekdayNamedElsewhere(it, fact, start) }
            ?: return Schedule(fact, null)
        // A repeat on named days starts on the first such day on or after the day that was read, or from today: "every
        // tuesday starting next week" is the Tuesday of next week.
        if (fact.endDay == null) fact = fact.copy(day = Occurrences(rule).first(fact.day ?: context.today) ?: fact.day)
        val until = end?.let { it.until ?: Occurrences(rule).last(fact.day ?: context.today, it) }
        return Schedule(fact, if (until != null) rule.copy(until = until.civil()) else rule)
    }

    /** A weekly repeat every so many weeks that names no day takes the weekday named elsewhere: "every 2 weeks … on friday". */
    private fun withWeekdayNamedElsewhere(rule: RecurrenceSpec, fact: Fact, start: Read.SeriesStart?): RecurrenceSpec {
        val named = fact.namedWeekday ?: start?.fact?.namedWeekday
        val open = rule.frequency == Frequency.WEEKLY && rule.interval > 1 && rule.weekdays.isEmpty() && rule.daysOfMonth.isEmpty()
        return if (named != null && open) rule.copy(weekdays = listOf(WeekdayRule(named))) else rule
    }

    /** A time alone is today if it is still ahead, and tomorrow if not; both counted where the time is. */
    private fun onItsDay(fact: Fact): Fact {
        if (fact.day != null || fact.start == null) return fact
        val today = fact.today(context)
        val stillAhead = fact.at(today, fact.start, context) >= context.referenceInstant
        return fact.copy(day = if (stillAhead) today else today.shift(1))
    }

    /**
     * A reminder made whole from what was read and the entry it belongs to.
     *
     * - Before a timed entry by a fixed length it stays relative, so it moves with the entry: "an hour before", "a day before".
     * - Otherwise it is a moment: the named day or the entry's, less the lead, at the named time, the entry's time, or the
     *   reminder time for a day alone. "a day before" a date with no time is that day before at the reminder time, not at midnight.
     */
    private fun reminder(read: Read.Remind, entry: Fact): ReminderSpec? {
        val (at, lead) = read
        if (lead != null && at == null && (entry.start != null || entry.day == null || lead.onlyClock)) {
            return lead.fixedMinutes?.let { ReminderSpec.Before(it) }
        }
        val from = at?.day ?: entry.day ?: context.today
        val time = at?.start ?: entry.start.takeIf { at?.day == null } ?: reminderTime.local()
        val fact = at ?: entry
        // The calendar part of the lead moves the day; its hours and minutes come off the moment.
        val moment = if (lead == null) fact.at(from, time, context) else fact.at(lead.before(from), time, context).minus(lead.minutes.minutes)
        return ReminderSpec.At(moment.moment(context.zone))
    }

    /** Where a part's value came from: the host's date finder, the readers of time, or the other readers. */
    private fun source(reading: Reading) = when {
        reading.byFinder -> Source.FINDER
        reading.label in Labels.time -> Source.GRAMMAR
        else -> Source.EXPLICIT
    }

    /** What [Ranking.decide] needs, from the pieces put together. */
    private fun evidence(
        parts: Parts, fact: Fact, schedule: Schedule, details: Details, titles: Titles, reminder: ReminderSpec?, force: Boolean, tagging: Tagging,
    ) = Evidence(
        title = titles.hasWords,
        impossibleDate = fact.impossible,
        statedKind = details.kind,
        taskMark = force,
        intent = Intent(tagging.task, tagging.event, tagging.none),
        dateRead = parts.read.any { it.label in Labels.time },
        day = fact.day != null,
        time = fact.start != null,
        span = fact.end != null || fact.duration != null || fact.endDay != null || fact.allDay,
        due = parts.dues.isNotEmpty(),
        priority = details.priority != Priority.NONE,
        list = details.list != null, calendar = details.calendar != null, place = details.place != null,
        repeat = schedule.recurrence != null, reminder = reminder != null,
    )
}

/** The entry's day and time, before a time alone is put on a day, and its repeat with where it stops. */
private class Schedule(val fact: Fact, val recurrence: RecurrenceSpec?)

/**
 * The parts of the sentence, each with what it says. Each rule below finds parts that say nothing in the whole sentence
 * and marks them unread, so they go back into the title.
 */
private class Parts(val all: List<Reading>, timesDropped: Boolean = false) {
    val read: List<Reading> = all.filter { it.value != null }
    val dues: List<Fact> = facts(TokenLabel.DUE)
    /** The days: a day, where a repeat starts, and the day of a deadline. */
    val days: List<Fact> = facts(TokenLabel.DAY, TokenLabel.STARTS) + dues.filter { it.day != null }
    /** The times: a time, and the time of a deadline. None once they were dropped for a day that could not be read. */
    val clocks: List<Fact> = if (timesDropped) emptyList() else facts(TokenLabel.CLOCK) + dues.filter { it.start != null }.map { Fact().withTimeOf(it) }
    val ends: List<Fact> = facts(TokenLabel.END, TokenLabel.ENDS)
    val lengths: List<Int> = facts(TokenLabel.LENGTH).mapNotNull { it.duration }
    val remind: Read.Remind? get() = values<Read.Remind>().lastOrNull()

    inline fun <reified T : Read> values(): List<T> = all.mapNotNull { it.value as? T }

    /**
     * Several repeat parts are one repeat when they read as one, even where a part means nothing alone ("each … monday",
     * "weekly … on fridays"). If they do not, the first that reads counts and the others are left unread.
     */
    fun joinRepeats(): Parts {
        val repeats = all.filter { it.label == TokenLabel.REPEAT }
        if (repeats.size < 2) return this
        val together = RepeatReader.recurrence(TimeWords.meaningful(repeats.flatMap { it.span.lexemes.words }))
        val first = repeats.firstOrNull { it.value != null }
        return Parts(all.map { part ->
            when {
                part.label != TokenLabel.REPEAT -> part
                together != null -> part.saying(Read.Repeat(together))
                part === first -> part
                else -> part.unread()
            }
        })
    }

    /** Where a repeat starts and stops only counts with a repeat. Without one a start is the entry's day, a last day is its end, and a count of times means nothing. */
    fun seriesWithoutRepeat(): Parts {
        if (values<Read.Repeat>().isNotEmpty()) return this
        return Parts(all.map { part ->
            when (val value = part.value) {
                is Read.SeriesStart -> part.saying(Read.When(value.fact))
                is Read.SeriesEnd -> part.saying(value.until?.let { Read.When(Fact(day = it)) })
                else -> part
            }
        })
    }

    /**
     * A day that could not be read leaves a time without its day, and the time is not guessed onto today: it goes back
     * into the title with the day. "lunch someday at 1" keeps "someday at 1". The time of a deadline is dropped too.
     */
    fun timesWithoutTheirDay(): Parts {
        val dayMissed = all.any { it.value == null && it.label in Labels.day }
        if (!dayMissed || days.any { it.day != null } || clocks.any { it.day != null }) return this
        return Parts(all.map { if (it.label == TokenLabel.CLOCK) it.unread() else it }, timesDropped = true)
    }

    /**
     * An entry has one day, one time, one end and one length. When a second one cannot join the first, the first counts and
     * the others go back into the title, where they are seen unread rather than dropped unseen: "from 11/11 11 to 12/11 13"
     * read as two days and two times keeps the first day and time. Days that make one date stay ("next week friday").
     */
    fun oneOfEach(): Parts {
        fun read(label: TokenLabel) = all.filter { it.label == label && it.value != null }
        val dayParts = read(TokenLabel.DAY)
        val extraDays = if (Fact.oneDate(dayParts.mapNotNull { it.fact })) emptyList() else dayParts.drop(1)
        val extra = listOf(TokenLabel.CLOCK, TokenLabel.END, TokenLabel.LENGTH).flatMap { read(it).drop(1) } + extraDays
        if (extra.isEmpty()) return this
        return Parts(all.map { part -> if (extra.any { it === part }) part.unread() else part })
    }

    /**
     * The times the schedule could not use go back into the title. This comes after the schedule, which is the only reader of
     * the times, so the times are not carried over.
     *
     * - A length or an end time with no start to attach to says nothing: "study for 2 hours tomorrow", "work until 5pm".
     *   Whole days beside a day do: "today for 3 days" is those days ([Fact.merge]).
     * - An end on a day before the start is no end of it ([Fact.merge] does not use it).
     */
    fun timesNotUsed(fact: Fact): Parts {
        val noStart = fact.start == null
        return Parts(all.map { part ->
            val said = part.fact ?: return@map part
            val unused = when (part.label) {
                TokenLabel.LENGTH -> noStart && (fact.day == null || said.duration?.let(Fact::wholeDays) == null)
                TokenLabel.END -> noStart && said.day == null || !said.endsOnOrAfter(fact.day)
                else -> false
            }
            if (unused) part.unread() else part
        })
    }

    private fun facts(vararg labels: TokenLabel) = all.filter { it.label in labels }.mapNotNull { it.fact }
}

/** The list, calendar, place, priority and stated kind. Where one is given twice the last wins, except priority, where the highest does. */
private class Details(parts: Parts) {
    private val destinations = parts.values<Read.Destination>()
    val list: String? = destinations.lastOrNull { it.list != null }?.list
    val calendar: String? = destinations.lastOrNull { it.calendar != null }?.calendar
    val place: String? = parts.values<Read.Place>().lastOrNull()?.name
    val priority: Priority = parts.values<Read.Importance>().maxOfOrNull { it.level } ?: Priority.NONE
    val kind: CandidateKind? = parts.values<Read.Kind>().lastOrNull()?.kind
}

/**
 * The titles: the title words and every part that was not read, never the wrapping words, except the ones right before a
 * part that goes back ("by" in an unread "by someday"). A place or a calendar only concerns an event, so the task keeps
 * those words in its title ("at the gym") and the event does not.
 */
private class Titles(parts: Parts) {
    private val all = parts.all
    private val eventOnly = all.filter { part ->
        part.value is Read.Place || (part.value as? Read.Destination)?.let { it.list == null && it.calendar != null } == true
    }
    private val taskWords = words(goBack = eventOnly)
    val task: String = title(taskWords)
    val event: String = title(words(goBack = emptyList()))
    /** Whether the task's title has a word in it, not only marks or numbers. */
    val hasWords: Boolean = taskWords.any { lexeme -> lexeme.text.any(Char::isLetter) }

    /** The words of the title, with the parts in [goBack] put back as well as the unread ones. */
    private fun words(goBack: List<Reading>): List<Lexeme> {
        fun returns(part: Reading) = part.label != TokenLabel.WRAPPER && (part.value == null || part in goBack)
        return all.filterIndexed { i, part ->
            if (part.label != TokenLabel.WRAPPER) returns(part)
            else all.getOrNull(i + 1)?.let { it.label != TokenLabel.TITLE && returns(it) } == true
        }.flatMap { it.span.lexemes }
    }

    /** What is left, as typed, without punctuation at its ends, and with a capital first letter. */
    private fun title(lexemes: List<Lexeme>): String =
        lexemes.source().trim { it.isWhitespace() || it in edgePunctuation }
            .replaceFirstChar { it.uppercase() }
}
