package org.github.dayedge.timewhisper.text

import kotlinx.datetime.DayOfWeek
import org.github.dayedge.timewhisper.CandidateKind
import org.github.dayedge.timewhisper.DateOrder
import org.github.dayedge.timewhisper.Frequency
import org.github.dayedge.timewhisper.Granularity

/**
 * What a word does inside a part, when that is not a value. A word can hold several roles. Each role says what the
 * readers do with it, with the English and Polish words that hold it.
 */
internal enum class Role {
    /**
     * Holds the words of one date or time together and carries no value of its own: "the 12th", "first of the month",
     * "every week on monday", "friday at 5", "2 hours before", Polish "o piątej", "w piątek". Readers read past it, unless
     * another role of the word counts there ("in" before a count).
     *
     * A word that only attaches a part to the sentence ("by friday", "at 3", "starting monday") is not a joiner and is not
     * listed: the tagger labels it a wrapper, outside the part.
     */
    JOINER,
    /**
     * Qualifies a time without changing it: "about 4", "7 sharp", "3 o'clock", "later today", "coming friday", Polish
     * "około 17". Readers read past it; the value is the same with or without it.
     */
    HEDGE,
    /**
     * Opening where a repeat stops, the day it names is not included: "before dec 1" stops on 30 November, "przed
     * 1 grudnia" too. Elsewhere it is read past, as a joiner: "2 hours before", "before friday".
     */
    BEFORE,
    /** Counts the other way: "remind me 2 hours after". No reminder after is read; the word keeps a length from passing for one before. */
    AFTER,
    /** An article that counts as one but is never part of a larger number or a clock: "a", "an" ("an hour", "a week"). */
    ARTICLE,
    /** Before a count, forward from now: "in 3 days", "within 30 days", "za 3 dni". Elsewhere ("first monday in May") a joiner. */
    IN,
    /** After a count and its unit, back from today: "3 days ago", "3 dni temu". */
    AGO,
    /** After a count and its unit, forward from now: "now" in "3 days from now", "today" in "3 days from today" ("from" is a joiner). */
    FROM_NOW,
    /** After a fraction of an hour, counts forward: "half past 3", Polish "kwadrans po piątej". */
    PAST,
    /** After a fraction of an hour, counts back: "quarter to 5", "wpół do piątej". */
    TO,
    /** Between two clocks or two days: "to", "until", "-", "do". German "bis". */
    RANGE,
    /** Joins the two clocks of "between 2 and 4", the days of a repeat, and the parts of an amount: "and", "&", "i", "a". */
    AND,
    /** Adds the next part of an amount of time: "3 weeks plus 2 days", "3 weeks + 2 days". */
    PLUS,
    /** Takes the next part of an amount away: "3 weeks minus a day", "3 weeks without a day", "3 tygodnie bez jednego dnia". */
    MINUS,
    /** Says a part repeats: "every", "each", "co", "każdy". German "jeden", "alle". */
    EVERY,
    /** After "every", every second one: "every other friday", "every alternate week". */
    OTHER,
    /** Monday to Friday: "weekday" in "every weekday", "working day", "robocze" in "dni robocze". */
    WEEKDAYS,
    /** The weekend, whichever days the host configures: "weekend". */
    WEEKEND,
    /** With a word for "day", a whole day: "all day", "cały dzień". */
    ALL,
    /** After a day it belongs to that day and names no hour: "morning", "evening", "rano", "wieczorem". German "Abend". */
    PART_OF_DAY,
}

/** The first or the last day of a period: "start of month", "end of week". */
internal enum class Edge { START, END }

/**
 * Everything a lexicon knows about one written form. Several fields can be set: "last" is a direction and an ordinal,
 * Polish "rano" is a part of day and a half of the day.
 */
internal data class Entry(
    val roles: Set<Role> = emptySet(),
    /** "friday", "fri", "piątek", "piątku": the form does not matter, only the day. */
    val weekday: DayOfWeek? = null,
    /** 1 to 12: "october", "oct", "października", "październiku". */
    val month: Int? = null,
    /** A count said as a word: "two" is 2, "a" is 1, "dwie" is 2. */
    val number: Int? = null,
    /** A position, as in a day of the month: "fifth", "piątego" are 5; "last", "ostatni" are -1. */
    val ordinal: Int? = null,
    /** The hour the word names when hours are said as words: "eight", Polish "piąta", "o piątej". It reads like a bare number. */
    val hour: Int? = null,
    /** A calendar unit: "day", "week", "month", "year" and their forms. */
    val unit: Granularity? = null,
    /** The unit is in the plural, so it is counted and never a period by itself: "weeks", "tygodnie". */
    val plural: Boolean = false,
    /** Which one: "this" 0, "next" 1, "last" -1; "ten", "przyszły", "zeszły" in all their forms. */
    val direction: Int? = null,
    /** Days from today: "today" 0, "tomorrow" 1, "yesterday" -1, "pojutrze" 2. */
    val relativeDay: Int? = null,
    /** The first or last day of a period: "start", "end", "początek", "koniec". */
    val edge: Edge? = null,
    /** The hour a word that is also a day roughly means: "tonight" is about 20. */
    val approximateHour: Int? = null,
    /** An hour with a name, exact: "noon" 12, "midnight" 0, "południe", "północ". */
    val namedClock: Int? = null,
    /** Minutes in one of this length of time under a day: "min" 1, "hour" 60, "kwadrans" 15. Days and weeks come from [unit]. */
    val minutes: Int? = null,
    /** A repeat in one word: "daily", "weekly", "codziennie". */
    val recurrence: Frequency? = null,
    /** Minutes named by a spoken fraction of an hour: "half" 30, "quarter" 15, "wpół", "kwadrans". */
    val fraction: Int? = null,
    /** Which half of the day, as a word after an hour: "am", "pm", "rano", "wieczorem". */
    val meridiem: Meridiem? = null,
    /** A time zone by its abbreviation, as the name of the zone it means: "est" is America/New_York. Read only right after a clock. */
    val zone: String? = null,
    /** The kind a mark forces: "todo", "task" in "t:task", "event", Polish "zadanie", "wydarzenie". */
    val candidateKind: CandidateKind? = null,
) {
    /** A number, an ordinal or an hour: what the lexer may compose with the next word ("twenty five", "dwudziestej drugiej"). */
    val numeral: Int? get() = number ?: ordinal ?: hour
}

/**
 * Written words that mean other lexicon words: two words that are one ("po południu" is "popołudniu"), or one that is
 * several ("eom" is "end of month"). The lexer replaces them before any reader sees the words; the title keeps what was typed.
 */
internal class Wording(val written: List<String>, val meaning: List<String>) {
    constructor(written: String, meaning: String) : this(written.split(' '), meaning.split(' '))
}

/**
 * How a language writes things down, as opposed to the words it uses. One value per language.
 * Everything here is a habit of writing that would otherwise be an assumption inside a reader.
 */
internal class Conventions(
    /** Which comes first in a numeric date such as "5/10". A host's own setting overrides it. */
    val dateOrder: DateOrder,
    /** Characters that separate the parts of a numeric date: "/" in English; "/" and "." in Polish and German. */
    val dateSeparators: String = "/",
    /** Characters that separate hours from minutes: ":" and "." in English ("9.30pm"). */
    val minuteSeparators: String = ":",
    /** Endings glued to the number that say which half of the day: "5pm", "10a". None where the 24-hour clock is the only one. */
    val meridiemSuffixes: Map<String, Meridiem> = emptyMap(),
    /** Whether a time written with minutes is taken as written unless a word says otherwise: "7:30" is 07:30 where the 24-hour clock is normal. */
    val minutesAreExact: Boolean = false,
    /** Bare hours read as afternoon when nothing says otherwise ("at 5" is 17:00); null means every hour is as written. */
    val afternoonHours: IntRange? = null,
    /** Characters of a decimal number: "." in English; "." and "," in most of Europe ("1,5h"). */
    val decimalSeparators: String = ".",
    /** What may follow the digits of a day of the month, as a pattern: "st|nd|rd|th", Polish "-go". */
    val ordinalMarks: String? = null,
    /**
     * Whether "half" before an hour, with nothing between, is half an hour before it: Polish "pół ósmej" and German
     * "halb acht" are 7:30. In English "half seven" is half past seven, 7:30 too, so English counts from the hour named.
     */
    val halfCountsBack: Boolean = false,
    /** Letters typed without their accents, each as a pair: "ąa ćc" means "ą" is typed "a". Words are then found either way. */
    unaccented: String = "",
) {
    val plainLetters: Map<Char, Char> = unaccented.split(' ').filter { it.length == 2 }.associate { it[0] to it[1] }
}

/**
 * A language, for the parser, is three things, all in one `Lexicon` object such as `English` or `Polish`:
 *
 * - **Entries**: what each written form means. "friday" is a weekday, "tygodnia" is the unit week, "of" joins the words
 *   of a date ("first of the month"). The tagger has already said where each part of the text is and what it is for (a day, a time, a repeat); the
 *   lexicon only says what the words inside a part mean. It never says where a date starts or which words attach it.
 * - **Conventions**: how the language writes numbers, dates and clocks: date order, separators, am/pm, accents.
 * - **Wordings**: set phrases that are other words: "eom" is "end of month", "po południu" is the one word for pm.
 *
 * Writing a language is filling in those three; no reader is edited. `English` and `Polish` are written section by
 * section in the same order, so either one is the template for the next.
 */
internal abstract class Lexicon {
    /** The language code it serves, as in `Configuration.language`: "en". */
    abstract val language: String

    /** The language tag a spelling provider is asked about: "en_US". */
    abstract val spellingLanguage: String

    abstract val conventions: Conventions

    /** What the lexer replaces before any reader sees the words; see [Wording]. The longest wording that fits wins. */
    open val wordings: List<Wording> = emptyList()

    /** The entries of the language, written with [LexiconBuilder]. */
    protected abstract fun LexiconBuilder.define()

    private val entries: Map<String, Entry> by lazy { LexiconBuilder().apply { define() }.build() }

    // Entries by their accent-free spelling, where that spelling is not a word of its own.
    private val folded: Map<String, Entry> by lazy {
        entries.mapNotNull { (text, entry) -> fold(text).takeIf { it != text && it !in entries }?.let { it to entry } }.toMap()
    }

    /** What the lexicon knows about [text], typed with or without its accents. */
    operator fun get(text: String): Entry? = entries[text] ?: folded[text]

    /** [text] as someone who skips accents would type it. */
    fun fold(text: String): String {
        val letters = conventions.plainLetters
        if (letters.isEmpty() || text.none { it in letters }) return text
        return buildString(text.length) { text.forEach { append(letters[it] ?: it) } }
    }

    // How digits are read. These follow the conventions alone, so no language needs to change them.

    private val suffixes by lazy { conventions.meridiemSuffixes.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } }
    private val clockShape by lazy {
        Regex("(\\d{1,2})(?:([" + Regex.escape(conventions.minuteSeparators) + "])(\\d{2}))?" + (if (suffixes.isEmpty()) "()" else "($suffixes)?"))
    }
    private val dateShapes by lazy {
        conventions.dateSeparators.map { it to Regex("(\\d{1,2})" + Regex.escape(it.toString()) + "(\\d{1,2})(?:" + Regex.escape(it.toString()) + "(\\d{4}))?") }
    }
    private val ordinalMark by lazy { conventions.ordinalMarks?.let { Regex("(?:$it)$") } }
    private val dayShape by lazy { Regex("\\d{1,2}" + (conventions.ordinalMarks?.let { "(?:$it)?" } ?: "")) }

    /** A day-of-month written with digits, such as "4" or "4th". */
    fun numericOrdinal(text: String): Int? = (ordinalMark?.let { text.replace(it, "") } ?: text).toIntOrNull()?.takeIf { it in 1..31 }

    /** Whether the text has the shape of a day-of-month in digits, valid or not. */
    fun looksLikeDayOfMonth(text: String): Boolean = dayShape.matches(text)

    /**
     * A clock written with digits, read by the language's [conventions]: "9", "09", "9:30", "9.30pm".
     *
     * - English: "5pm" and "10a" carry their half of the day; "09" and nothing else is taken as written; "at 5" is left to
     *   the afternoon convention. Polish: "7:30" is taken as written, because the 24-hour clock is the normal one.
     */
    fun digitalClock(text: String): Clock? {
        val (digits, separator, minuteDigits, suffix) = clockShape.matchEntire(text)?.destructured ?: return null
        val hour = digits.toInt()
        val minute = minuteDigits.toIntOrNull() ?: 0
        val written = minuteDigits.isNotEmpty()
        val meridiem = conventions.meridiemSuffixes[suffix]
        // "24" and "24:00" are the midnight that ends a day: "17:00-24" ends at 00:00 the next day.
        if (hour == 24 && minute == 0 && meridiem == null) return Clock(0, 0, exact = true, minutes = written)
        if (minute !in 0..59 || hour !in (if (meridiem != null) 1..12 else 0..23)) return null
        // A leading zero marks a 24-hour reading, so "09" is never moved to the evening.
        val leadingZero = digits.length == 2 && digits.startsWith('0')
        val exact = meridiem == null && leadingZero
        // Where the 24-hour clock is normal, a time with minutes is as written unless a word says otherwise ("7:30 wieczorem").
        val afternoon = conventions.afternoonHours.takeUnless { written && conventions.minutesAreExact }
        return Clock(hour, minute, meridiem, exact, minutes = written, afternoonHours = afternoon)
    }

    /**
     * A date written in digits with one of the language's separators, as its two leading numbers in the order written:
     * "5/10", "5/10/2026", and "05.10" where the dot is used. Which number is the day is decided later, by date order.
     * Where the separator is also a decimal point, the second number must have two digits: "05.10" is a date, "1.5" is a number.
     * With a four-digit year any of "/", "." and "-" will do, in every language: "11-11-2026", "11.11.2026". Without the year
     * a dash is a range ("3-4") and a dot may be minutes ("9.30"), so those stay the language's habit.
     */
    fun numericDate(text: String): SlashDate? = dateShapes.firstNotNullOfOrNull { (separator, shape) ->
        val (first, second, year) = shape.matchEntire(text)?.destructured ?: return@firstNotNullOfOrNull null
        if (separator in conventions.decimalSeparators && second.length != 2) null else SlashDate(first.toInt(), second.toInt(), year.toIntOrNull())
    } ?: fullDateShape.matchEntire(text)?.destructured?.let { (first, _, second, year) -> SlashDate(first.toInt(), second.toInt(), year.toInt()) }

    /**
     * The number two adjacent words make together, or null when they are two numbers: "forty five" is 45, "five forty" is
     * not one number. The same for ordinals and hours: "twenty fifth" and "dwudziestego piątego" are 25, "dwudziestej
     * drugiej" is 22. The lexer then hands the readers a single number, as if it had been typed in digits.
     *
     * Tens are followed by units, which is how English, Polish and Italian ("quaranta cinque") say it. An article is never
     * the units: "twenty a day" is not 21. Spanish and French put a word between ("cuarenta y cinco", "vingt et un"),
     * which a wording would join; German writes one word ("fünfundvierzig"), which is an ordinary entry.
     */
    fun composedNumber(first: Word, second: Word): Int? {
        if (Role.ARTICLE in second) return null
        val tens = first.entry?.numeral ?: return null
        val units = second.entry?.numeral ?: return null
        return if (tens in 20..90 && tens % 10 == 0 && units in 1..9) tens + units else null
    }

    companion object {
        /** The languages there is a lexicon for. */
        val languages: Set<String> = setOf("en", "pl")

        /** The lexicon of [language], or null. Each one is built the first time it is asked for. */
        fun forLanguage(language: String): Lexicon? = when (language) {
            "en" -> English
            "pl" -> Polish
            else -> null
        }

        // With a four-digit year the separator is no habit of a language: "11-11-2026" and "11.11.2026" are dates everywhere.
        private val fullDateShape = Regex("(\\d{1,2})([./-])(\\d{1,2})\\2(\\d{4})")
    }
}

/**
 * How a lexicon writes its entries. Every function takes the forms of one meaning as one space-separated string, so a
 * row reads as "this meaning: these words": `weekdays("monday mon", …)`, `set(Frequency.DAILY to "daily codziennie")`.
 * A form given twice gets both meanings: "last" is an ordinal and a direction.
 */
internal class LexiconBuilder {
    private val entries = mutableMapOf<String, Entry>()
    private val aliases = mutableListOf<Pair<String, String>>()

    /** Changes the entry of every one of the [forms]. The other functions are written with it. */
    fun each(forms: String, change: Entry.() -> Entry) = forms.split(' ').filter(String::isNotEmpty).forEach {
        entries[it] = (entries[it] ?: Entry()).change()
    }

    /** Gives each value its forms: `set(1 to "next coming", -1 to "last previous") { copy(direction = it) }`. */
    fun <T> set(vararg rows: Pair<T, String>, change: Entry.(T) -> Entry) = rows.forEach { (value, forms) -> each(forms) { change(value) } }

    /** Gives a run of values, counting up from [first], one row of forms each: the first row is [first], the next one more. */
    fun counted(first: Int, vararg rows: String, change: Entry.(Int) -> Entry) =
        rows.forEachIndexed { index, forms -> each(forms) { change(first + index) } }

    /** Gives the [role] to every one of the [forms]. */
    fun role(role: Role, forms: String) = each(forms) { copy(roles = roles + role) }

    /** The seven weekdays, Monday first, each with all its forms. */
    fun weekdays(vararg rows: String) {
        require(rows.size == 7) { "seven weekdays, Monday first" }
        rows.forEachIndexed { index, forms -> each(forms) { copy(weekday = DayOfWeek.entries[index]) } }
    }

    /** The twelve months, January first, each with all its forms. */
    fun months(vararg rows: String) {
        require(rows.size == 12) { "twelve months, January first" }
        counted(1, *rows) { copy(month = it) }
    }

    /** A calendar unit: its singular forms, which also name the period ("next week"), and its plural forms, which only count. */
    fun unit(unit: Granularity, singular: String, plural: String) {
        each(singular) { copy(unit = unit) }
        each(plural) { copy(unit = unit, plural = true) }
    }

    /** Makes each of the [forms] read exactly like [target], keeping roles of its own: "wk" is "week", "tmrw" is "tomorrow". */
    fun alias(target: String, forms: String) = forms.split(' ').forEach { aliases += it to target }

    /** The entries, with aliases resolved and the marks every language shares added: time zones and the kinds in "t:task". */
    fun build(): Map<String, Entry> {
        for ((form, target) in aliases) {
            val meaning = entries[target] ?: error("\"$form\" stands for \"$target\", which has no entry")
            entries[form] = meaning.copy(roles = meaning.roles + (entries[form]?.roles ?: emptySet()))
        }
        // A shared mark is left out where the language has a word of that spelling: Polish "pt" is Friday, not Pacific time.
        for ((mark, zone) in timeZones) if (mark !in entries) each(mark) { copy(zone = zone) }
        for ((marks, kind) in kindMarks) marks.split(' ').filter { it !in entries }.forEach { each(it) { copy(candidateKind = kind) } }
        return entries
    }

    private companion object {
        /**
         * Time zone abbreviations people write after a time, with the zone each one means. They are the same in every
         * language. An abbreviation names a zone, not a fixed offset: "3pm EST" on a summer date is Eastern time as it is
         * then. "CST" is US Central and "BST" British time, their usual meanings in English text. "IST" is left out: India,
         * Israel and Ireland all use it.
         */
        val timeZones = mapOf(
            "utc" to "UTC", "gmt" to "UTC",
            "est" to "America/New_York", "edt" to "America/New_York", "et" to "America/New_York",
            "cst" to "America/Chicago", "cdt" to "America/Chicago", "ct" to "America/Chicago",
            "mst" to "America/Denver", "mdt" to "America/Denver", "mt" to "America/Denver",
            "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles", "pt" to "America/Los_Angeles",
            "akst" to "America/Anchorage", "akdt" to "America/Anchorage", "hst" to "Pacific/Honolulu",
            "bst" to "Europe/London", "cet" to "Europe/Berlin", "cest" to "Europe/Berlin",
            "eet" to "Europe/Helsinki", "eest" to "Europe/Helsinki",
            "jst" to "Asia/Tokyo", "aest" to "Australia/Sydney", "aedt" to "Australia/Sydney",
        )

        /** The kinds a mark forces, in the English forms every language accepts: "todo", "t:task", "type:event". */
        val kindMarks = listOf("task tasks t todo to-do" to CandidateKind.TASK, "event events e" to CandidateKind.EVENT)
    }
}
