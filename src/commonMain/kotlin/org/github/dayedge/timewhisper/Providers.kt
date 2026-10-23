package org.github.dayedge.timewhisper

/**
 * An optional second reader of dates and times, supplied by the host application: a system data detector
 * or a library such as Chrono. It is asked about the words of a part the tagger labelled a day or a time that the
 * parser's own readers could not read.
 * Everything a finder may depend on arrives in the request; it must not read the device clock, zone or locale.
 * A finder that does not know the requested language returns no matches.
 */
public interface DateFinder {
    public suspend fun find(text: String, request: DateFinderRequest): List<DateMatch>
    public suspend fun close() {}
}

/** Creates the date finder when it is first needed, and again after [TimeWhisper.releaseResources]. */
public fun interface DateFinderFactory {
    public fun create(): DateFinder
}

/** What a date finder may depend on: the language of the text, the moment "now" is, and the user's time zone. */
public data class DateFinderRequest(val language: String, val referenceEpochMilliseconds: Long, val timeZoneId: String)

/** One date or time found in the text. [range] is in UTF-16 code units of the text given to the finder. */
public data class DateMatch(
    val range: SourceRange,
    val start: DateMatchComponents,
    val end: DateMatchComponents? = null
)

/**
 * One end of a match, as a local date and time in the request's zone.
 * The `Stated` flags say whether the text wrote that part ("at 5" states no day); null means the finder cannot tell.
 * [offsetMinutes] is a UTC offset written in the text.
 */
public data class DateMatchComponents(
    val date: CivilDate?,
    val time: CivilTime?,
    val dayStated: Boolean? = null,
    val monthStated: Boolean? = null,
    val yearStated: Boolean? = null,
    val offsetMinutes: Int? = null,
)

/** Finds nothing, so the parser's own readers decide alone. This is the default. */
public class NoDateFinder : DateFinder {
    override suspend fun find(text: String, request: DateFinderRequest): List<DateMatch> = emptyList()
}

/** Makes a [NoDateFinder]. */
public object NoDateFinderFactory : DateFinderFactory {
    override fun create(): DateFinder = NoDateFinder()
}

/** Providers return suggestions; the parser alone decides whether a correction is safe. */
public interface SpellChecker {
    public suspend fun suggestions(word: String, language: String): List<String>
    public suspend fun close() {}
}

/** Creates the spell checker when it is first needed, and again after [TimeWhisper.releaseResources]. */
public fun interface SpellCheckerFactory {
    public fun create(): SpellChecker
}

/** Suggests nothing, so no misspelling is corrected. Use it for fully reproducible results. */
public class NoSpellChecker : SpellChecker {
    override suspend fun suggestions(word: String, language: String): List<String> = emptyList()
}

/** Makes a [NoSpellChecker]. */
public object NoSpellCheckerFactory : SpellCheckerFactory {
    override fun create(): SpellChecker = NoSpellChecker()
}

/** The spell checker used when none is given: the system's on macOS, none on the JVM. */
public expect fun defaultSpellCheckerFactory(): SpellCheckerFactory

/**
 * The reader of what each part of the text is for, supplied by the host application: a small trained model. It says which
 * words are the title, which only wrap the request ("can you remind me to"), and where each thing the text states is and
 * what it is: a day, a time, an end, a length, a deadline, a repeat and where it starts and stops, a place, a list, a
 * calendar, a priority, a reminder, a forced kind. It also says whether the text asks for a task, an event or nothing,
 * and what urgency it states.
 *
 * It never supplies a value: the parser reads each part it labels, and a part it cannot read goes back into the title.
 * The parser cannot work without one.
 */
public interface Tagger {
    public suspend fun tag(text: String, language: String): Tagging
    public suspend fun close() {}
}

/** Creates the tagger when it is first needed, and again after [TimeWhisper.releaseResources]. */
public fun interface TaggerFactory {
    public fun create(): Tagger
}

/** What a word is for. Neighbouring words with the same label are one part. */
public enum class TokenLabel {
    /** What the user wants done or attended. */
    TITLE,
    /** Words around the request that are not its content: "can you remind me to", "please", "thanks". */
    WRAPPER,
    /** The day or days the entry is on: "on friday", "tmrw", "mon-wed". */
    DAY,
    /** When on that day: "at 3pm", "3-5pm", "in 2 hours". */
    CLOCK,
    /** An end said apart from the start: "until 5". */
    END,
    /** How long the event lasts: "for 45 min". */
    LENGTH,
    /** A deadline: "by friday". */
    DUE,
    /** How often: "every other monday". */
    REPEAT,
    /** When a repeat starts: "starting next week". */
    STARTS,
    /** When a repeat stops, or how many times it runs: "until may", "for 6 weeks". */
    ENDS,
    /** Where the event happens: "at Café Nero". */
    PLACE,
    /** A task list: "#work", "to my errands list". */
    LIST,
    /** A calendar: "on the team calendar". */
    CALENDAR,
    /** Stated urgency: "urgent", "!!", "no rush". */
    PRIORITY,
    /** A reminder setting: "remind me 10 min before". */
    REMINDER,
    /** A mark that forces task or event: "todo", "t:task". */
    KIND,
}

/** One word of the text: [range] in UTF-16 code units of the text given to the tagger, its label and how sure the tagger is, 0 to 1. */
public data class TaggedToken(val range: SourceRange, val label: TokenLabel, val confidence: Double)

/**
 * The words of a text, each with its label; how much the whole text reads as a request for a task, an event or nothing at
 * all, each 0 to 1; and how likely it states each priority ([urgency], NONE to HIGH, together 1). A word with no label,
 * for instance past the length the tagger reads, is title.
 */
public data class Tagging(
    val tokens: List<TaggedToken>,
    val task: Double,
    val event: Double,
    val none: Double,
    val urgency: Map<Priority, Double> = emptyMap(),
)
