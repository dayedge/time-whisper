package org.github.dayedge.timewhisper.text

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import org.github.dayedge.timewhisper.CandidateKind
import org.github.dayedge.timewhisper.Granularity
import org.github.dayedge.timewhisper.SourceRange

/** The two leading numbers of a slash date in the order written, and its year when given. */
internal data class SlashDate(val first: Int, val second: Int, val year: Int?)

/** An amount with the unit glued to it, if any: "1.5h", "30min", "90". A number word is an amount too: "two", "dwie". */
internal class Measure(val amount: Double, val unit: Word?)

/**
 * The reading of one piece of text on its own: what the lexicon says about it and what its shape says.
 * It carries no position and no context, so one instance serves every occurrence of the text.
 */
internal class Word(val text: String, private val lexicon: Lexicon) {
    val entry: Entry? = lexicon[text]
    operator fun contains(role: Role): Boolean = entry != null && role in entry.roles
    /** The lexicon gives the word a meaning, not only a joining or hedging role. A misspelling may be corrected to it. */
    val means: Boolean = entry != null && entry.copy(roles = entry.roles - Role.JOINER - Role.HEDGE - Role.BEFORE) != Entry()
    /** Readers read past it: a [Role.JOINER] or a [Role.HEDGE] with no meaning of its own. */
    val readPast: Boolean = (Role.JOINER in this || Role.HEDGE in this) && !means

    val weekday: DayOfWeek? get() = entry?.weekday
    val month: Int? get() = entry?.month
    val unit: Granularity? get() = entry?.unit
    val direction: Int? get() = entry?.direction
    val plural: Boolean get() = entry?.plural == true
    fun isSingular(unit: Granularity): Boolean = entry?.unit == unit && !plural

    /** A number word or digits; digits may carry a sign. */
    val number: Int? by once { entry?.number ?: text.toIntOrNull() }
    /** An ordinal word, or a day-of-month in digits. */
    val ordinal: Int? by once { entry?.ordinal ?: lexicon.numericOrdinal(text) }

    /** A date written in digits year first: "2026-10-01", "2026/11/11". */
    val isoDate: LocalDate? by once { Companion.isoDate(text) ?: yearFirstDate(text) }
    val slashDate: SlashDate? by once { lexicon.numericDate(text) }
    /** How the language of this word writes things down. */
    val conventions: Conventions get() = lexicon.conventions
    /** One or two digits, as a week number or day is written. */
    val shortNumber: Int? by once { if (shortDigits.matches(text)) text.toInt() else null }
    /** Exactly four digits. */
    val yearNumber: Int? by once { if (yearDigits.matches(text)) text.toInt() else null }
    /** Looks like a day-of-month ("4", "4th"), whether or not the number is a valid day. */
    val looksLikeDayOfMonth: Boolean by once { lexicon.looksLikeDayOfMonth(text) }
    /** Letters, digits and underscores only: the shape a counted quantity takes. */
    val isPlain: Boolean by once { plainWord.matches(text) }
    /** +1 or -1 when the text starts with a sign. */
    val sign: Int? get() = when (text.firstOrNull()) { '+' -> 1; '-' -> -1; else -> null }
    /** The text after a leading sign, read on its own. */
    val unsigned: Word? by once { if (sign != null && text.length > 1) Word(text.drop(1), lexicon) else null }

    val clock: Clock? by once {
        entry?.namedClock?.let { Clock(it, exact = true) }
            ?: entry?.hour?.let { Clock(it, afternoonHours = lexicon.conventions.afternoonHours) }
            ?: lexicon.digitalClock(text)
    }
    /**
     * A time zone: an abbreviation the lexicon lists ("est", "cet"), or an offset from UTC written out ("utc+2", "gmt+0900", "+02:00").
     * Rules read it only right after a clock, so the same letters elsewhere are just a word.
     */
    val zone: TimeZone? by once {
        entry?.zone?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: offsetShape.matchEntire(text)?.groupValues?.let {
            val sign = if ((it[1] + it[4]) == "-") -1 else 1
            val hours = (it[2] + it[5]).toInt()
            val minutes = (it[3] + it[6]).toIntOrNull() ?: 0
            if (hours > 14 || minutes > 59) null else UtcOffset(seconds = sign * (hours * 3600 + minutes * 60)).asTimeZone()
        }
    }
    /** The clock in "@5pm". */
    val atClock: Clock? by once { if (text.startsWith('@')) read(text.drop(1)).clock else null }
    /** Morning or afternoon marker, however the dots were typed: "am", "p.m". */
    val meridiem: Meridiem? by once { lexicon[text.replace(".", "")]?.meridiem }
    /** Says only which half of the day: "am", "pm", and what a wording makes of "in the morning". */
    val onlyHalfOfDay: Boolean by once { meridiem != null && (entry == null || entry.copy(meridiem = null, roles = emptySet()) == Entry()) }
    /** Two clocks glued by a range sign, one reading per sign that splits the text in two: "10am-11am", "9->10". */
    val clockRanges: List<Pair<Clock, Clock>> by once {
        clockRangeSeparators.mapNotNull { separator ->
            val parts = text.split(separator).takeIf { it.size == 2 } ?: return@mapNotNull null
            val first = read(parts[0]).clock ?: return@mapNotNull null
            read(parts[1]).clock?.let { first to it }
        }
    }
    /** The two sides of a single dash: "fri-sun", "3-5". */
    val dashParts: Pair<Word, Word>? by once {
        text.split(dashes).takeIf { it.size == 2 }?.let { read(it[0]) to read(it[1]) }
    }
    val measure: Measure? by once {
        val amount = text.takeWhile { it.isDigit() || it in lexicon.conventions.decimalSeparators }
        amount.replace(',', '.').toDoubleOrNull()?.let { Measure(it, text.drop(amount.length).takeIf(String::isNotEmpty)?.let(::read)) }
            ?: entry?.number?.let { Measure(it.toDouble(), null) }
    }

    /** The name in "#work". */
    val tag: String? get() = if (text.startsWith('#')) text.drop(1) else null
    /** The count of exclamation marks when the text is nothing else. */
    val bangs: Int? get() = if (text.isNotEmpty() && text.all { it == '!' }) text.length else null
    /** The kind a mark forces: "todo", "task", and the part after a colon in "t:task", "type:event", "typ:zadanie". */
    val kind: CandidateKind? by once {
        entry?.candidateKind ?: text.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let { lexicon[it]?.candidateKind }
    }
    val allDigits: Boolean get() = text.all(Char::isDigit)

    /** The minutes in one of this unit, for units shorter than a day ("hour", "min"). A day or more is a date, not a length of time. */
    val clockUnitMinutes: Int? get() = entry?.minutes

    /** Reads other text with the same lexicon, for a part of this word or text derived from it. */
    fun read(other: String): Word = Word(other, lexicon)

    override fun toString(): String = text

    companion object {
        /** A reading worked out the first time it is asked for. A word is only read on one thread at a time. */
        private fun <T> once(read: () -> T): Lazy<T> = lazy(LazyThreadSafetyMode.NONE, read)

        /** A valid calendar date written as yyyy-mm-dd, or null. */
        fun isoDate(text: String): LocalDate? = if (isoShape.matches(text)) runCatching { LocalDate.parse(text) }.getOrNull() else null

        /** A valid date written year first with a slash or a dot, as "2026/11/11" or "2026.11.11": always year, month, day. */
        private fun yearFirstDate(text: String): LocalDate? = yearFirstShape.matchEntire(text)?.destructured?.let { (year, _, month, day) ->
            runCatching { LocalDate(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
        }

        private val isoShape = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val yearFirstShape = Regex("(\\d{4})([./])(\\d{1,2})\\2(\\d{1,2})")
        private val shortDigits = Regex("\\d{1,2}")
        private val yearDigits = Regex("\\d{4}")
        private val plainWord = Regex("\\w+")
        private val offsetShape = Regex("(?:(?:utc|gmt)([+-])(\\d{1,2})(?::?(\\d{2}))?|([+-])(\\d{2}):(\\d{2}))")
        private val dashes = Regex("[-–—]")
        private val clockRangeSeparators = listOf("->", "–", "—", "~", "-")
    }
}

/**
 * One unit of meaning in the input: where it was written, what was written, and the word every rule reads.
 * Usually that is one token. A [Wording] can make several tokens one lexeme, or one token several lexemes;
 * lexemes that come from the same wording share its [range] and [text].
 */
internal class Lexeme(
    val range: SourceRange,
    /** The source as typed, with its casing and punctuation. */
    val text: String,
    val word: Word,
)

/** The words the lexemes read as. */
internal val List<Lexeme>.words: List<Word> get() = map { it.word }

/** The source of the lexemes, as typed. Lexemes that came from one wording share their source, which is printed once. */
internal fun List<Lexeme>.source(): String = distinctBy { it.range }.joinToString(" ") { it.text }

/** `Role.X in word` for a word that may be missing. */
internal operator fun Word?.contains(role: Role): Boolean = this != null && role in this
