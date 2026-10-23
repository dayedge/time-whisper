package org.github.dayedge.timewhisper.temporal

import kotlinx.datetime.LocalDate
import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.Lexer
import org.github.dayedge.timewhisper.text.Role
import org.github.dayedge.timewhisper.text.Word
import org.github.dayedge.timewhisper.text.tokenize
import org.github.dayedge.timewhisper.text.words

/** How the words of a part about time are read: as a day, a time, a length, or a day and a time together. */
internal object TimeWords {
    /**
     * The words of a part that say something: joiners and hedges are read past ("the 15th of October" → "15th October",
     * "7 sharp" → "7"). Two roles count only in their place: "in" counts forward only before a count or a unit ("in 3 days",
     * "in half an hour"; "first monday in may" only joins), and a range word only between two things ("3 to 5"; "do piątku"
     * is "piątku").
     */
    fun meaningful(words: List<Word>): List<Word> {
        val said = words.filterNot { it.readPast }
        return said.filterIndexed { i, word ->
            when {
                Role.IN in word && Role.JOINER in word -> startsAmount(said.getOrNull(i + 1))
                // A minus sign that opens an amount is its sign, not a range: "- 3 days".
                Role.RANGE in word && Role.MINUS in word && i == 0 -> startsAmount(said.getOrNull(1))
                Role.RANGE in word -> i > 0 && i < said.lastIndex
                else -> true
            }
        }
    }

    /** Whether an amount of time can start with [word]: a count, a number with its unit, a fraction or a unit. */
    private fun startsAmount(word: Word?): Boolean = word != null &&
        (word.number != null || word.measure != null || word.entry?.fraction != null || word.unit != null || word.clockUnitMinutes != null)

    /**
     * A day: the words of a part labelled DAY, read by [DayRules]. A part of day after it is dropped ("tomorrow morning" is
     * tomorrow), and "all day" makes the event all-day, with or without a day beside it ("all day saturday").
     */
    fun day(words: List<Word>, scope: Scope): Fact? {
        val said = meaningful(words)
        // "this morning" is today.
        if (said.size == 2 && said[0].direction == 0 && Role.PART_OF_DAY in said[1]) return Fact(scope.context.today)
        // A part of the day, or the half of the day a wording made of it ("in the morning" is "am"), names no hour beside a day.
        val day = said.filterNot { Role.PART_OF_DAY in it || it.onlyHalfOfDay }.takeIf { it.isNotEmpty() } ?: return null
        // "all day": two words that say nothing about which day.
        val all = day.indexOfFirst { Role.ALL in it }.takeIf { it >= 0 && day.getOrNull(it + 1)?.isSingular(Granularity.DAY) == true }
        if (all != null) {
            val rest = day.filterIndexed { i, _ -> i != all && i != all + 1 }
            if (rest.isEmpty()) return Fact(allDay = true)
            return DayRules.fact(rest, scope)?.copy(allDay = true)
        }
        return DayRules.fact(day, scope)
            // Two readings that are one date: "May 4 this year", "January 1 next year", "next week friday".
            ?: (1 until day.size).firstNotNullOfOrNull { at ->
                val first = DayRules.fact(day.subList(0, at), scope) ?: return@firstNotNullOfOrNull null
                val second = DayRules.fact(day.subList(at, day.size), scope) ?: return@firstNotNullOfOrNull null
                if (Fact.oneDate(listOf(first, second))) Fact.merge(listOf(first, second), emptyList()) else null
            }
    }

    /** A time or a range of times: the words of a part labelled CLOCK, read by [ClockRules]. */
    fun clock(words: List<Word>, scope: Scope): Fact? {
        val said = meaningful(words)
        // "a quarter to five": the article before a fraction of an hour says nothing.
        val clock = if (said.size > 1 && Role.ARTICLE in said[0] && said[1].entry?.fraction != null) said.drop(1) else said
        return ClockRules.fact(clock, scope)
    }

    /**
     * An amount of time in the words of a part ([Amounts]): "45 min", "1h30m", "an hour and a half", "2 days 3 hours".
     * A word with a dash is read as its parts ("30-minute"), and an article before it says nothing ("a 30-minute").
     */
    fun amount(words: List<Word>): Amount? {
        val said = meaningful(words)
        val parts = said.flatMap { word -> if ('-' in word.text.drop(1)) word.text.split('-').filter(String::isNotEmpty).map(word::read) else listOf(word) }
        return Amounts.read(parts, LONGEST) ?: parts.takeIf { it.size > 1 && Role.ARTICLE in it[0] }?.let { Amounts.read(it.drop(1), LONGEST) }
    }

    /** How long, in minutes: "for 45 min", "1h30m", "an hour", "a 30-minute", "for 1 day and 6 hours". Not in months. */
    fun length(words: List<Word>): Int? = amount(words)?.fixedMinutes?.takeIf { it > 0 }


    /**
     * A day, a time, or both, in one part: what a deadline, an end or a series bound says ("by friday 5pm", "until saturday at 6").
     * The words are read as a day, as a time, or split once into a day and a time in either order.
     */
    fun dayOrTime(words: List<Word>, scope: Scope): Fact? {
        val said = meaningful(words)
        return day(said, scope) ?: clock(said, scope) ?: (1 until said.size).firstNotNullOfOrNull { at ->
            val head = said.subList(0, at)
            val tail = said.subList(at, said.size)
            dayAndClock(day(head, scope)) { clock(tail, scope) } ?: clock(head, scope)?.let { time -> dayAndClock(day(tail, scope)) { time } }
        }
    }

    /** A day with the time read beside it, or null when either is missing. The time is read only when there is a day. */
    private inline fun dayAndClock(day: Fact?, clock: () -> Fact?): Fact? {
        if (day == null) return null
        return clock()?.let { day.withTimeOf(it) }
    }

    /** The largest count a length may have, in its unit. */
    private const val LONGEST = 999_999.0
}

/** What the tagger labelled a time, read; and the date finder, for a day or a time the readers could not read. */
internal class TemporalReader(private val configuration: Configuration, private val lexer: Lexer, private val finder: () -> DateFinder) {
    private val dateOrder = configuration.dateOrder ?: lexer.lexicon.conventions.dateOrder

    /** What a rule may know beyond its words, for one parse. */
    fun scope(context: ParseContext, urgency: Map<Priority, Double> = emptyMap()): Scope = Scope(context, configuration, dateOrder, urgency)

    /**
     * What [text] means when all of it is one date expression, or null: "next week", "tomorrow at 9", "friday to sunday",
     * "10am for 30 minutes", "January 1 next year". No tagger is needed: the text is cut into at most three parts, each read
     * as a day, a time or a length, and the cut that reads is taken. Several days must be one date ([Fact.oneDate]).
     */
    suspend fun resolve(text: String, context: ParseContext): TemporalSpec? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // "A..B": from the start of A to the end of B.
        val bounds = trimmed.split("..")
        if (bounds.size == 2) {
            val a = resolve(bounds[0], context) ?: return null
            val b = resolve(bounds[1], context) ?: return null
            if (a.start.epochMilliseconds >= b.endExclusive.epochMilliseconds) return null
            return TemporalSpec(a.start, b.endExclusive, Granularity.DAY)
        }
        // Invalid ISO input must not be read as anything else.
        if (isoPrefixed.matches(trimmed) && Word.isoDate(trimmed.take(10)) == null) return null
        // Punctuation that ends a sentence is not part of the date: "tomorrow!".
        val words = lexer.lex(tokenize(trimmed.trim { it in SENTENCE_PUNCTUATION })) { true }.words.filter { it.text.isNotEmpty() }
        val scope = scope(context)
        val fact = cuts(words.size).firstNotNullOfOrNull { cut ->
            val days = mutableListOf<Fact>(); val clocks = mutableListOf<Fact>(); val lengths = mutableListOf<Int>()
            for (part in cut.map { (from, to) -> words.subList(from, to) }) {
                TimeWords.day(part, scope)?.let { days += it; continue }
                TimeWords.clock(part, scope)?.let { clocks += it; continue }
                TimeWords.length(part)?.let { lengths += it; continue }
                return@firstNotNullOfOrNull null
            }
            if (clocks.size > 1 || lengths.size > 1 || !Fact.oneDate(days) || days.any { it.impossible }) null
            // One day is the answer as it stands, period and all: "next week" is the whole week.
            else if (days.size == 1 && clocks.isEmpty() && lengths.isEmpty()) days[0]
            // A length says how long from a time; with no time it says nothing, and the text is not one date expression.
            else Fact.merge(days, clocks, lengths = lengths).takeIf { (it.day != null || it.start != null) && (lengths.isEmpty() || it.start != null) }
        } ?: return null
        return fact.temporal(context)
    }

    /**
     * A host's date finder, asked about a part labelled a day or a time that no rule read: what it found over exactly those
     * words, or null. A finder also misreads, so it is asked only where the tagger said there is a date, and only a match
     * that states a day, a month, a year or a time counts.
     */
    suspend fun fallback(part: List<Lexeme>, context: ParseContext): Fact? {
        val text = part.joinToString(" ") { it.text }
        val match = matches(text, context).singleOrNull() ?: return null
        val stated = match.start
        if (!stated.statesDay && !stated.statesMonth && !stated.statesYear && stated.time == null && match.end == null) return null
        // It must cover the part, give or take the small words around it.
        val covered = text.substring(match.range.offset, match.range.end).trim().lowercase()
        val said = TimeWords.meaningful(part.words)
        if (said.isEmpty() || said.any { word -> word.text !in covered }) return null
        return match.fact()
    }

    /** What the finder finds in [text], checked to lie inside it. */
    private suspend fun matches(text: String, context: ParseContext): List<DateMatch> =
        finder().find(text, DateFinderRequest(configuration.language, context.referenceInstant.toEpochMilliseconds(), context.timeZoneId)).onEach {
            if (it.range.end > text.length) throw TimeWhisperException("Date finder returned an invalid source range")
        }

    // A finder that cannot say whether a part was written in the text is taken to mean it was.
    private val DateMatchComponents.statesDay: Boolean get() = dayStated ?: true
    private val DateMatchComponents.statesMonth: Boolean get() = monthStated ?: true
    private val DateMatchComponents.statesYear: Boolean get() = yearStated ?: true

    /**
     * What a date finder's match means to the parser.
     *
     * - **A** `'tmrw 10am'`: date 24 Sep and time 10:00, both stated → day 24 Sep, start 10:00.
     * - **C** `'next Friday at 2:30pm'`: date 2 Oct, time 14:30 → day 2 Oct, start 14:30.
     * - A match that states a month but no day ("March") becomes a month-long period, and one that states
     *   neither is marked as an implied day, which ranks last when days are merged.
     */
    private fun DateMatch.fact(): Fact = Fact(
        day = start.date?.local(),
        granularity = if (start.statesMonth && !start.statesDay) Granularity.MONTH else Granularity.DAY,
        start = start.time?.local(),
        end = end?.time?.local(),
        endDay = end?.date?.takeIf { it != start.date }?.local(),
        certainDay = start.statesDay || start.statesMonth,
        offsetMinutes = start.offsetMinutes,
    )

    private companion object {
        val isoPrefixed = Regex("\\d{4}-\\d{2}-\\d{2}.*")
        const val SENTENCE_PUNCTUATION = ".,!?;:"

        /** Every way to cut [n] words into one to three runs, fewest runs first: each run as its start and end. */
        fun cuts(n: Int): Sequence<List<Pair<Int, Int>>> = sequence {
            if (n == 0) return@sequence
            yield(listOf(0 to n))
            for (a in 1 until n) yield(listOf(0 to a, a to n))
            for (a in 1 until n) for (b in a + 1 until n) yield(listOf(0 to a, a to b, b to n))
        }
    }
}
