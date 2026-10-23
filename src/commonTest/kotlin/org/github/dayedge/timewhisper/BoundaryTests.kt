package org.github.dayedge.timewhisper

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

class BoundaryTests {
    @Test fun relativeHoursUseElapsedTimeAcrossSpringTransition() = runTest {
        val parser = timeWhisper()
        val context = ParseContext(Instant.parse("2026-03-29T00:30:00Z"), "Europe/Warsaw")
        try { assertEquals(Instant.parse("2026-03-29T02:30:00Z").toEpochMilliseconds(), parser.parseTemporal("in 2 hours", context)?.start?.epochMilliseconds) }
        finally { parser.close() }
    }
    @Test fun relativeHoursUseElapsedTimeAcrossAutumnTransition() = runTest {
        val parser = timeWhisper()
        val context = ParseContext(Instant.parse("2026-10-25T00:30:00Z"), "Europe/Warsaw")
        try { assertEquals(Instant.parse("2026-10-25T02:30:00Z").toEpochMilliseconds(), parser.parseTemporal("in 2 hours", context)?.start?.epochMilliseconds) }
        finally { parser.close() }
    }
    @Test fun writtenRelativeHoursUseElapsedTimeAcrossSpringTransition() = runTest {
        val parser = timeWhisper()
        val context = ParseContext(Instant.parse("2026-03-29T00:30:00Z"), "Europe/Warsaw")
        try { assertEquals(Instant.parse("2026-03-29T02:30:00Z").toEpochMilliseconds(), parser.parseTemporal("in two hours", context)?.start?.epochMilliseconds) }
        finally { parser.close() }
    }
    @Test fun spellingFailureIsReportedAsProviderError() = runTest {
        val parser = TimeWhisper(taggerFactory = untagged, spellCheckerFactory = SpellCheckerFactory { object : SpellChecker {
            override suspend fun suggestions(word: String, language: String): List<String> = error("spelling failed")
        } })
        try { assertFailsWith<TimeWhisperException> { parser.parseTemporal("frida", fixtureContext) } }
        finally { parser.close() }
    }
    @Test fun cancelledSpellingRequestDoesNotBlockNextRequest() = runTest {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val parser = TimeWhisper(taggerFactory = untagged, spellCheckerFactory = SpellCheckerFactory { object : SpellChecker {
            override suspend fun suggestions(word: String, language: String): List<String> { entered.complete(Unit); gate.await(); return emptyList() }
        } })
        try {
            val pending = async { parser.parseTemporal("frida", fixtureContext) }
            entered.await(); pending.cancelAndJoin()
            assertEquals("2026-09-24", parser.date("tomorrow"))
        } finally { gate.complete(Unit); parser.close() }
    }
}
