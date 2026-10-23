package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

class FindTemporalTests {
    private suspend fun find(text: String): FoundTemporal? {
        val (plain, labels) = markup(text)
        val parser = TimeWhisper(Configuration(), { MarkupTagger(labels) }, spellCheckerFactory = NoSpellCheckerFactory)
        try { return parser.findTemporal(plain, fixtureContext) } finally { parser.close() }
    }
    private fun day(date: String): Long = Instant.parse("${date}T00:00:00Z").toEpochMilliseconds()
    private fun assertRange(start: String, endExclusive: String, found: FoundTemporal?) {
        assertEquals(day(start) to day(endExclusive), found?.temporal?.let { it.start.epochMilliseconds to it.endExclusive.epochMilliseconds })
    }

    @Test fun dateIsFoundAndTakenOutOfTheText() = runTest {
        val found = find("standup [DAY tomorrow]")
        assertEquals("standup", found?.remainingText)
        assertRange("2026-09-24", "2026-09-25", found)
    }
    @Test fun textThatIsOnlyADateLeavesNothing() = runTest {
        val found = find("[DAY next week]")
        assertEquals("", found?.remainingText)
        assertRange("2026-09-28", "2026-10-05", found)
    }
    @Test fun bareMonthMeansTheCurrentYear() = runTest {
        assertRange("2026-03-01", "2026-04-01", find("[DAY March]"))
        val inside = find("review [DAY March]")
        assertEquals("review", inside?.remainingText)
        assertRange("2026-03-01", "2026-04-01", inside)
    }
    @Test fun qualifierMovesAMonthAndIsTakenOutWithIt() = runTest {
        assertRange("2027-03-01", "2027-04-01", find("[DAY next March]"))
        assertRange("2025-11-01", "2025-12-01", find("[DAY last November]"))
        val inside = find("review [DAY coming March] notes")
        assertEquals("review notes", inside?.remainingText)
        assertRange("2027-03-01", "2027-04-01", inside)
    }
    @Test fun datePhraseInsideTextIsFoundWithItsSmallWord() = runTest {
        val counted = find("budget [DAY in two weeks]")
        assertEquals("budget", counted?.remainingText)
        assertRange("2026-10-07", "2026-10-08", counted)
        val weekday = find("standup [W on] [DAY friday] with the team")
        assertEquals("standup with the team", weekday?.remainingText)
        assertRange("2026-09-25", "2026-09-26", weekday)
    }
    @Test fun timeAfterTheDayIsTakenOutWithItButTheRangeIsTheDay() = runTest {
        val found = find("lunch with Anna [DAY next friday] [W at] [CLOCK noon]")
        assertEquals("lunch with Anna", found?.remainingText)
        assertRange("2026-10-02", "2026-10-03", found)
    }
    @Test fun aDeadlineAndARangeOfDaysAreFoundToo() = runTest {
        assertEquals("report", find("report [W by] [DUE friday]")?.remainingText)
        assertEquals("trip", find("trip [W from] [DAY friday to sunday]")?.remainingText)
        assertRange("2026-09-25", "2026-09-28", find("trip [W from] [DAY friday to sunday]"))
    }
    @Test fun textWithoutADateFindsNothing() = runTest {
        assertNull(find("quarterly review"))
        assertNull(find("   "))
        assertNull(find("call May"))
    }
}
