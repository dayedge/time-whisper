package org.github.dayedge.timewhisper.taskorevent

import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.temporal.*
import org.github.dayedge.timewhisper.temporal.Example.Companion.en
import org.github.dayedge.timewhisper.temporal.Example.Companion.pl
import org.github.dayedge.timewhisper.text.Clock
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.Word
import org.github.dayedge.timewhisper.text.words
import org.github.dayedge.timewhisper.text.edgePunctuation
import org.github.dayedge.timewhisper.text.namePunctuation

/**
 * One reader for each label the tagger gives a part, and nothing else: the tagger says where a day, a repeat or a place
 * is, and these say what it is. A reader reads all the words of its part or none of them; a part it cannot read goes back
 * into the title as it was typed.
 *
 * - **A** `Standup tmrw 10am for 30 minutes every weekday @Office`: [DayReader] reads `tmrw`, [ClockReader] `10am`,
 *   [LengthReader] `for 30 minutes`, [RepeatReader] `every weekday`, [PlaceReader] `@Office`.
 * - **B** `Pay rent next Friday #home !! remind me 1 day before`: [DayReader] reads `next Friday`, [ListReader] `#home`,
 *   [PriorityReader] `!!`, [ReminderReader] `remind me 1 day before`.
 * - **C** `Dentist next Friday at 2:30pm`: [DayReader] and [ClockReader].
 */
internal object Readers {
    val all: List<Reader> = listOf(
        DayReader, ClockReader, EndReader, LengthReader, DueReader, RepeatReader, StartsReader, EndsReader,
        PlaceReader, ListReader, CalendarReader, PriorityReader, ReminderReader, KindReader,
    )
    private val byLabel = all.associateBy { it.label }

    /** The reader for parts with [label], or null for title and wrapping words. */
    operator fun get(label: TokenLabel): Reader? = byLabel[label]
}

/** The day or days the entry is on: [DayRules]. */
internal object DayReader : Reader {
    override val label = TokenLabel.DAY
    override val name = "day"
    override val examples = en("friday", "tomorrow morning", "all day saturday", "mon-wed", "the 15th") + pl("piątek", "jutro rano")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? = TimeWords.day(lexemes.words, scope)?.let { Read.When(it) }
}

/** When on that day: [ClockRules]. */
internal object ClockReader : Reader {
    override val label = TokenLabel.CLOCK
    override val name = "clock"
    override val examples = en("3pm", "11", "10 to 11", "2 and 4", "in 2 hours") + pl("17", "14 do 16")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? = TimeWords.clock(lexemes.words, scope)?.let { Read.When(it) }
}

/**
 * An end said apart from the start: a time ("until 5", "do 16"), a day ("till friday"), or both ("until saturday at 6").
 * A time keeps how it was written, so a bare hour ends after the start: "3pm … until 5" ends at 17:00.
 */
internal object EndReader : Reader {
    override val label = TokenLabel.END
    override val name = "end"
    override val examples = en("5", "4pm", "friday", "saturday at 6") + pl("16")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        val words = lexemes.words
        // A time alone, as written.
        val said = TimeWords.meaningful(words).ifEmpty { return null }
        Clocks.clock(said, 0)?.takeIf { it.second == said.size }?.first?.let { return Read.When(Fact(detachedEnd = it)) }
        val fact = TimeWords.dayOrTime(words, scope)?.takeIf { it.day != null } ?: return null
        // The clock as written, not yet moved to the afternoon, so that on a later day "6:00" is the morning ("until nov 4
        // at 6:00"); the merge decides the half of the day once it knows the start.
        val end = fact.start?.let { start ->
            val written = said.indices.firstNotNullOfOrNull { at -> Clocks.clock(said, at)?.first?.takeIf { it.time() == start } }
            (written ?: Clock(start.hour, start.minute, exact = true)).copy(zone = fact.zone)
        }
        return Read.When(Fact(day = fact.day, endDay = fact.endDay, granularity = fact.granularity, namedWeekday = fact.namedWeekday, detachedEnd = end))
    }
}

/**
 * How long the event lasts: "for 45 min", "1h30m", "a 30-minute", "przez godzinę". After a time it gives the end; whole days
 * beside a day with no time cover those days: "today for 3 days", "dziś na 3 dni" ([Fact.merge]).
 */
internal object LengthReader : Reader {
    override val label = TokenLabel.LENGTH
    override val name = "length"
    override val examples = en("45 min", "1h30m", "a 30-minute", "an hour", "3 days") + pl("45 minut", "pół godziny", "godzinę", "3 dni")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? = TimeWords.length(lexemes.words)?.let { Read.When(Fact(duration = it)) }
}

/** A deadline: a day, a time or both by which the task is done: "by friday", "before 5pm", "due eow", "within 30 days". */
internal object DueReader : Reader {
    override val label = TokenLabel.DUE
    override val name = "due"
    override val examples = en("friday", "5pm", "eow", "friday 5pm", "friday at 5pm", "the 14th", "within 30 days") + pl("piątku", "18:00")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? = TimeWords.dayOrTime(lexemes.words, scope)?.let { Read.When(it) }
}

/**
 * Where a repeat starts: a day ("starting next week", "from oct 5", "od listopada"). A month alone is its first day, or today
 * if that month is now. The weekday it names is kept: "every 2 weeks starting next wednesday" repeats on Wednesdays.
 * A range of days gives both bounds: "from 1 nov to 1 dec" starts on the first day and stops after the last.
 */
internal object StartsReader : Reader {
    override val label = TokenLabel.STARTS
    override val name = "series-start"
    override val examples = en("next week", "oct 5", "tomorrow", "monday", "november", "1 nov to 1 dec") +
        pl("listopada", "5 października", "1 listopada do 1 grudnia")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        val fact = TimeWords.dayOrTime(lexemes.words, scope) ?: return null
        val day = fact.day?.let { if (it < scope.context.today) scope.context.today else it } ?: return null
        return Read.SeriesStart(day, fact)
    }
}

/**
 * Where a repeat stops: on a day ("may", "march", "end of may 2028": the last day of what it names; the words before it, "until",
 * "through", are wrappers), the day before one ("before dec 1": 30 November), after so long ("6 weeks", "a month"), or after
 * so many times ("6 times", "4 sessions", "the next six").
 */
internal object EndsReader : Reader {
    override val label = TokenLabel.ENDS
    override val name = "series-end"
    override val examples = en("may", "march", "6 weeks", "6 times", "the next six", "december 15", "before dec 1", "3 months and 2 weeks") +
        pl("marca", "15 grudnia", "przed 1 grudnia", "2 miesiące i tydzień")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        val words = lexemes.words
        // "before dec 1" stops the day before what it names begins.
        if (words.size > 1 && Role.BEFORE in words[0]) {
            return TimeWords.dayOrTime(words.drop(1), scope)?.day?.let { Read.SeriesEnd(until = it.shift(-1)) }
        }
        TimeWords.dayOrTime(words, scope)?.lastDay()?.let { return Read.SeriesEnd(until = it) }
        // "for 6 weeks", "for a month", "for 3 months and 2 weeks": so long on the calendar.
        val said = TimeWords.meaningful(words).filter { it.direction == null }
        // An amount on the clock ("for 3 hours") is not how long a repeat runs, nor a count of times.
        Amounts.read(said)?.let { return if (it.minutes == 0) Read.SeriesEnd(span = it) else null }
        // "6 times", "for 4 sessions", "for the next six": a count, and the thing counted or nothing.
        val count = said.firstOrNull()?.number?.takeIf { it in 1..999 } ?: return null
        return if (said.size <= 2) Read.SeriesEnd(count = count) else null
    }
}

/**
 * Where the event happens, as written: "Café Nero", "@Office" is "Office", `"the corner table"` is "the corner table".
 * An "@" and quotes around the name are taken off; the word that attaches the place ("at", "w") is a wrapper, outside it.
 * A place only concerns an event; a task keeps the words in its title.
 */
internal object PlaceReader : Reader {
    override val label = TokenLabel.PLACE
    override val name = "place"
    override val examples = en("@Office", "Café Nero", "\"the corner table\"", "Zoom") + pl("Main Office")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        val name = lexemes.joinToString(" ") { it.text }.removePrefix("@").trim { it in namePunctuation }
        val open = name.firstOrNull()
        val closers = quotes[open]
        val unquoted = if (closers != null && name.length > 1 && name.last() in closers) name.substring(1, name.length - 1).trim() else name
        return unquoted.takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }?.let { Read.Place(it) }
    }

    /** The quotes a place may be written in, opening mark to closing marks: straight, curly, the low Polish and German one, single. */
    private val quotes = mapOf('"' to "\"”“", '“' to "”\"", '„' to "”“\"", '\'' to "'’", '‘' to "’'")
}

/**
 * A list or a calendar the caller supplied: by tag (`#work`), or by name among the words of the part ("to my work list",
 * "on the team calendar"). Matching ignores letter case and spaces, and comes from the caller's names, not from a lexicon.
 * A tag names whichever of the caller's lists or calendars has that name, whatever the tagger called it. A name that is
 * both a list and a calendar is the [kind] the tagger said.
 */
internal abstract class DestinationReader(private val kind: DestinationKind) : Reader {
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        val destinations = scope.context.destinations
        lexemes.mapNotNull { it.word.tag }.singleOrNull()?.let { tag ->
            val named = destinations.filter { key(it.name) == tag }
            val list = named.firstOrNull { it.kind == DestinationKind.TASK_LIST }?.id
            val calendar = named.firstOrNull { it.kind == DestinationKind.CALENDAR }?.id
            return if (list != null || calendar != null) Read.Destination(list, calendar) else null
        }
        // A name made of one or more neighbouring words: "work", "Team Alpha".
        val keys = lexemes.map { key(it.text.trim { c -> c in edgePunctuation }) }
        val runs = keys.indices.flatMap { from -> (from + 1..keys.size).map { keys.subList(from, it).joinToString("") } }.toSet()
        val named = destinations.filter { key(it.name) in runs }
        val chosen = named.firstOrNull { it.kind == kind } ?: named.singleOrNull() ?: return null
        return if (chosen.kind == DestinationKind.TASK_LIST) Read.Destination(chosen.id, null) else Read.Destination(null, chosen.id)
    }

    private fun key(text: String) = text.lowercase().filterNot(Char::isWhitespace)
}

/** A task list: [DestinationReader]. */
internal object ListReader : DestinationReader(DestinationKind.TASK_LIST) {
    override val label = TokenLabel.LIST
    override val name = "list"
    override val examples = en("#work", "to my work list", "on the home list")
}

/** A calendar: [DestinationReader]. */
internal object CalendarReader : DestinationReader(DestinationKind.CALENDAR) {
    override val label = TokenLabel.CALENDAR
    override val name = "calendar"
    override val examples = en("#team", "on the team calendar", "in the Team cal")
}

/**
 * Stated urgency. Exclamation marks say it by themselves: one is low, two medium, three or more high. In words ("urgent",
 * "no rush", "p2", "pilne") the level is the tagger's reading of the whole text, which has to be clear: of low, medium and
 * high, the one it reads must have at least [CLEAR_LEVEL] of the three together.
 */
internal object PriorityReader : Reader {
    override val label = TokenLabel.PRIORITY
    override val name = "priority"
    override val examples = en("!", "!!", "!!!")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        lexemes.mapNotNull { it.word.bangs }.maxOrNull()?.let { return Read.Importance(Priority.entries[minOf(it, 3)]) }
        val levels = listOf(Priority.LOW, Priority.MEDIUM, Priority.HIGH).associateWith { scope.urgency[it] ?: 0.0 }
        val total = levels.values.sum()
        val (level, p) = levels.maxBy { it.value }
        return if (total > 0 && p / total >= CLEAR_LEVEL) Read.Importance(level) else null
    }

    /** The share of low, medium and high the level read must have. */
    const val CLEAR_LEVEL = 0.6
}

/**
 * A reminder. After the asking words ("remind me", "set a reminder for"), the rest must read whole, in one of four shapes:
 *
 * - how long before the entry: `a day before`, `10 min before`, `2 weeks before`, `a month before`;
 * - a day, a time or both: `thursday at 9am`, `at 1`, `on the 12th`, `in 2 hours`;
 * - a time, then how long before the entry: `at 9 the day before`;
 * - how long before a date: `a month before end of year`;
 * - a length alone among the asking words is before the entry: `with a 10 min reminder`.
 *
 * Polish says the same with "wcześniej" or "przed": `10 minut wcześniej`, `w czwartek o 9`. Anything else is not read
 * ("2 hours after"). [Assembler] completes the reminder with the entry's day and time.
 */
internal object ReminderReader : Reader {
    override val label = TokenLabel.REMINDER
    override val name = "reminder"
    override val examples = en("remind me 10 min before", "alert me an hour before", "remind me the day before", "ping me 2 hours before",
        "remind me a month before", "remind me thursday at 9am", "ping me at 1", "alert me on the 12th", "remind me at 9 the day before",
        "remind me a month before end of year") +
        pl("przypomnij 10 minut wcześniej", "powiadom godzinę wcześniej", "przypomnij w czwartek o 9")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? {
        val words = lexemes.words
        return words.indices.firstNotNullOfOrNull { shape(words.drop(it), scope) } ?: alone(words)
    }

    /** A length with every other word meaning nothing, only asking: "with a 10 min reminder". "2 hours after" is not one. */
    private fun alone(words: List<Word>): Read.Remind? {
        for (from in words.indices) for (to in from + 1..words.size) {
            val lead = lead(words.subList(from, to)) ?: continue
            val rest = words.subList(0, from) + words.subList(to, words.size)
            if (rest.none { it.means || it.number != null }) return Read.Remind(lead = lead)
        }
        return null
    }

    private fun shape(words: List<Word>, scope: Scope): Read.Remind? {
        val before = words.indexOfLast { Role.BEFORE in it }
        if (before < 0) return TimeWords.dayOrTime(words, scope)?.takeIf { it.day != null || it.start != null }?.let { Read.Remind(at = it) }
        val head = words.subList(0, before)
        val tail = words.subList(before + 1, words.size)
        if (tail.isNotEmpty()) {
            val date = TimeWords.dayOrTime(tail, scope)?.takeIf { it.day != null } ?: return null
            return lead(head)?.let { Read.Remind(at = date, lead = it) }
        }
        lead(head)?.let { return Read.Remind(lead = it) }
        return (1 until head.size).firstNotNullOfOrNull { at ->
            val time = TimeWords.clock(head.subList(0, at), scope)?.takeIf { it.start != null && it.day == null }
            time?.let { lead(head.subList(at, head.size))?.let { lead -> Read.Remind(at = time, lead = lead) } }
        }
    }

    /** How long: an amount ([Amounts]): "10 min", "an hour", "the day", "2 weeks", "a month", "1 day 2 hours". */
    private fun lead(words: List<Word>): Amount? {
        val amount = TimeWords.amount(words)?.takeIf { (it.fixedMinutes ?: 1) > 0 } ?: return null
        // A count stands right before its unit: "the day", "2 weeks"; not "9 the day".
        return amount.takeIf { it.onlyClock || words.dropWhile { word -> word.readPast }.size == TimeWords.meaningful(words).size }
    }
}

/** A mark that forces task or event: "todo", "t:task", "type:event", "task:", "+", Polish "zadanie", "typ:wydarzenie". */
internal object KindReader : Reader {
    override val label = TokenLabel.KIND
    override val name = "kind"
    override val examples = en("todo", "t:task", "type:event", "task:") + pl("zadanie", "typ:wydarzenie")
    override fun read(lexemes: List<Lexeme>, scope: Scope): Read? =
        (lexemes.mapNotNull { it.word.kind } + lexemes.filter { it.text == "+" }.map { CandidateKind.TASK }).distinct().singleOrNull()?.let { Read.Kind(it) }
}
