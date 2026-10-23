package org.github.dayedge.timewhisper

import kotlinx.datetime.*
import kotlin.time.Instant
import org.github.dayedge.timewhisper.text.Lexicon

/** Which number comes first in a numeric date such as "5/10". */
public enum class DateOrder { MONTH_DAY, DAY_MONTH }
/** What a [Destination] holds: tasks or events. */
public enum class DestinationKind { TASK_LIST, CALENDAR }
/** How wide a stretch of time an expression names: "tomorrow" is a day, "next week" a week. */
public enum class Granularity { DAY, WEEK, MONTH, YEAR }
/** What recognised a part of the text: a stated marker such as "#work" or "!!", a date or time rule, or the host's date finder. */
public enum class Source { EXPLICIT, GRAMMAR, FINDER }
/** Settings that hold for every request: the language and the calendar habits that are not the language's defaults. */
public data class Configuration(
    /** Which comes first in a numeric date such as "5/10". Left unset, the language's own habit applies: month first in English, day first in Polish. */
    val dateOrder: DateOrder? = null,
    val weekStartsOn: DayOfWeek = DayOfWeek.MONDAY,
    val weekendDays: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    val defaultEventMinutes: Int = 60,
    /** The language of the text, as a code such as "en". It selects the vocabulary and is passed to the date finder and spelling provider. */
    val language: String = "en",
    /** The time of a reminder that names a day and no time: "remind me on the 23rd". */
    val reminderTime: CivilTime = CivilTime(9, 0),
) {
    init {
        require(defaultEventMinutes > 0) { "Event duration must be positive" }
        require(weekendDays.isNotEmpty() && weekendDays.size < 7) { "Weekend must contain one to six weekdays" }
        require(language in supportedLanguages) { "Unsupported language: $language" }
    }

    public companion object {
        /** Languages the parser has a vocabulary for. */
        public val supportedLanguages: Set<String> get() = Lexicon.languages
    }
}

/** A list or calendar the user has. "#work" in the text matches the destination named "Work", and its [id] is returned. */
public data class Destination(val id: String, val name: String, val kind: DestinationKind)
/**
 * What a request is relative to: the moment "now" is, the time zone the user is in, and the lists and calendars a tag may name.
 * Passed with every request, so results never depend on the device.
 */
public data class ParseContext(
    val referenceInstant: Instant,
    val timeZoneId: String,
    val destinations: List<Destination> = emptyList(),
) {
    internal val zone: TimeZone = TimeZone.of(timeZoneId)
    internal val today: LocalDate = today(zone)

    /** Today in [zone], which may be another day than where the user is. */
    internal fun today(zone: TimeZone): LocalDate = referenceInstant.toLocalDateTime(zone).date
}

/** A part of the input text, in UTF-16 code units, as text views count them. */
public data class SourceRange(val offset: Int, val length: Int) {
    init {
        require(offset >= 0 && length >= 0)
    }

    internal val end: Int get() = offset + length
    internal fun overlaps(other: SourceRange): Boolean = offset < other.end && other.offset < end

    /** From the start of this range to the end of [other]. */
    internal fun through(other: SourceRange): SourceRange = SourceRange(offset, other.end - offset)
}

/** A part of the text the parser read as something other than the title, for highlighting. */
public data class RecognizedSpan(val range: SourceRange, val source: Source)
/** A calendar date with no time and no time zone: 2 October 2026. */
public data class CivilDate(val year: Int, val month: Int, val day: Int) {
    init {
        LocalDate(year, month, day)
    }
}

/** A time of day with no date and no time zone: 14:30. */
public data class CivilTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23 && minute in 0..59)
    }
}

/** An instant, reported in the request's time zone with the offset that zone has at that instant. */
public data class Moment(val epochMilliseconds: Long, val timeZoneId: String, val offsetSeconds: Int) {
    internal val instant: Instant get() = Instant.fromEpochMilliseconds(epochMilliseconds)
}

/** A provider failed, or the parser was used after [TimeWhisper.close]. "Nothing found" is never an exception. */
public class TimeWhisperException(message: String, cause: Throwable? = null) : Exception(message, cause)
