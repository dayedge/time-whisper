package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.test.*

internal suspend fun TimeWhisper.date(text: String, context: ParseContext = fixtureContext): String? = parseTemporal(text, context)?.start?.instant?.toLocalDateTime(context.zone)?.date?.toString()

class DateExpressionFixtureTests {
    @Test fun everyDateExpressionHasItsExactResult() = runTest {
        val parser = timeWhisper()
        try {
            val failures = mutableListOf<String>()
            for (line in dateExpressionFixture.lineSequence().filter(String::isNotBlank)) {
                val (input, expected) = line.split(" → ")
                val actual = parser.parseTemporal(input, fixtureContext)?.let {
                    val prefix = if (it.granularity in setOf(Granularity.MONTH, Granularity.YEAR)) "M" else "D"
                    "$prefix ${it.start.instant.toLocalDateTime(fixtureContext.zone).date}"
                } ?: "—"
                if (expected != actual) failures += "$input: expected $expected, got $actual"
            }
            assertEquals(emptyList(), failures)
        } finally { parser.close() }
    }
    @Test fun nextWeekUsesConfiguredWeekStart() = runTest {
        val parser = timeWhisper(Configuration(weekStartsOn = DayOfWeek.SUNDAY))
        try { assertEquals("2026-09-27", parser.date("next week")) } finally { parser.close() }
    }
    @Test fun nextFridayMeansNextCalendarWeek() = runTest {
        val parser = timeWhisper()
        try { assertEquals("2026-10-02", parser.date("next Friday")) } finally { parser.close() }
    }
    @Test fun invalidIsoDoesNotRollOver() = runTest {
        val parser = timeWhisper()
        try { assertNull(parser.parseTemporal("2026-02-31", fixtureContext)) } finally { parser.close() }
    }
    @Test fun inclusiveInputRangeHasExclusiveOutputEnd() = runTest {
        val parser = timeWhisper()
        try {
            val result = assertNotNull(parser.parseTemporal("2026-10-01..2026-10-07", fixtureContext))
            assertEquals("2026-10-08", result.endExclusive.instant.toLocalDateTime(fixtureContext.zone).date.toString())
        } finally { parser.close() }
    }
    @Test fun englishGrammarDoesNotDependOnHostLocale() = runTest {
        val parser = timeWhisper()
        try { assertEquals("2026-10-01", parser.date("beginning of next month")) } finally { parser.close() }
    }
    @Test fun numericDateOrderCanBeDayFirst() = runTest {
        val parser = timeWhisper(Configuration(dateOrder = DateOrder.DAY_MONTH))
        try { assertEquals("2026-11-10", parser.date("10/11")) } finally { parser.close() }
    }
    @Test fun namedZoneDefinesTodayNearMidnight() = runTest {
        val parser = timeWhisper()
        val context = ParseContext(Instant.parse("2026-09-23T01:00:00Z"), "America/Los_Angeles")
        try { assertEquals("2026-09-22", parser.date("today", context)) } finally { parser.close() }
    }
    @Test fun springDayHasTwentyThreeHours() = runTest {
        val parser = timeWhisper()
        val context = ParseContext(Instant.parse("2026-03-28T12:00:00Z"), "Europe/Warsaw")
        try {
            val result = assertNotNull(parser.parseTemporal("tomorrow", context))
            assertEquals(23 * 3_600_000L, result.endExclusive.epochMilliseconds - result.start.epochMilliseconds)
        } finally { parser.close() }
    }
}

/** A whole text that is one date, read without a tagger: cut into a day, a time and a length, and put together. */
class WholeTextTests {
    @Test fun signedAndAnchoredAmountsCountFromTheirDay() = runTest {
        // Now is Wednesday 23 September 2026, 12:00 UTC.
        val parser = timeWhisper()
        try {
            mapOf(
                "+3 days" to "2026-09-26", "- 3 days" to "2026-09-20", "+3d" to "2026-09-26", "-1w2d" to "2026-09-14",
                "+3 weeks - 2 days" to "2026-10-12", "in 3 weeks - 2 days" to "2026-10-12", "3 weeks minus 2 days ago" to "2026-09-04",
                "tomorrow + 2 days" to "2026-09-26", "yesterday - 3 days" to "2026-09-19", "today + 3 days" to "2026-09-26",
                // A sign sets the direction until the next one; a day the words name can be the start.
                "- 2 weeks + 1 day" to "2026-09-10", "-3 weeks 2 days" to "2026-08-31", "friday + 2 weeks" to "2026-10-09",
                "next tue - 3 days" to "2026-09-26", "tomorrow plus 2 days" to "2026-09-26",
            ).forEach { (text, day) -> assertEquals(day, parser.date(text), text) }
            assertEquals(Instant.parse("2026-09-23T15:00:00Z").toEpochMilliseconds(), parser.parseTemporal("now + 3 hours", fixtureContext)?.start?.epochMilliseconds)
            // An amount with no marker is a length, not a date; a length with no time to count from says nothing.
            for (text in listOf("3 days", "3 weeks + 2 days", "3 weeks - 2 days")) assertNull(parser.parseTemporal(text, fixtureContext), text)
        } finally { parser.close() }
    }
    @Test fun severalExpressionsTogetherAreOneDate() = runTest {
        val parser = timeWhisper()
        try {
            assertEquals(Instant.parse("2026-10-02T12:00:00Z").toEpochMilliseconds(), parser.parseTemporal("next Friday at noon", fixtureContext)?.start?.epochMilliseconds)
            assertNull(parser.parseTemporal("dentist next Friday at noon", fixtureContext), "a word that is not part of the date")
            assertNull(parser.parseTemporal("fri tues", fixtureContext), "two days are not one date")
            assertEquals("2026-01-01", parser.date("January 1 this year"))
            assertEquals("2027-01-01", parser.date("January 1 next year"))
            assertEquals("2026-09-25", parser.date("on friday"), "a joiner is read past")
            assertNull(parser.parseTemporal("by friday", fixtureContext), "a word that attaches a date to a sentence is not part of the date")
            val range = assertNotNull(parser.parseTemporal("friday to sunday", fixtureContext))
            assertEquals(Instant.parse("2026-09-25T00:00:00Z").toEpochMilliseconds() to Instant.parse("2026-09-28T00:00:00Z").toEpochMilliseconds(),
                range.start.epochMilliseconds to range.endExclusive.epochMilliseconds)
            val week = assertNotNull(parser.parseTemporal("next week", fixtureContext))
            assertEquals(7 * 86_400_000L, week.endExclusive.epochMilliseconds - week.start.epochMilliseconds, "one reading keeps its period")
            assertNull(parser.parseTemporal("tomorrow next year", fixtureContext), "a day and a year that do not belong together")
            assertNull(parser.parseTemporal("friday eom", fixtureContext))
            assertEquals("2026-09-23", parser.date("next three days"), "a count in words")
            assertNull(parser.date("2 hours"), "a length alone is not a time from now")
            assertEquals(Instant.parse("2026-09-23T12:45:00Z").toEpochMilliseconds(), parser.parseTemporal("45 minutes from now", fixtureContext)?.start?.epochMilliseconds)
        } finally { parser.close() }
    }
}
