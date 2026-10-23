package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlin.test.*
import kotlin.time.Instant
import org.github.dayedge.timewhisper.text.English
import org.github.dayedge.timewhisper.text.Lexer
import org.github.dayedge.timewhisper.text.tokenize

private class RecordingFinder(private val answer: (String) -> List<DateMatch> = { emptyList() }) : DateFinder {
    val requests = mutableListOf<DateFinderRequest>(); var closed = 0
    override suspend fun find(text: String, request: DateFinderRequest): List<DateMatch> { requests += request; return answer(text) }
    override suspend fun close() { closed++ }
}
/** "call Bob soonish": the tagger says "soonish" is a day, which no rule reads, so a date finder is asked about it. */
private val soonish = TaggerFactory { MarkupTagger(markup("call Bob [DAY soonish]").second) }

class ProviderTests {
    @Test fun closeIsTerminalAndIdempotent() = runTest {
        val parser = timeWhisper()
        parser.close(); parser.close()
        assertFailsWith<TimeWhisperException> { parser.parse("buy milk", fixtureContext) }
    }
    @Test fun unambiguousPermittedSuggestionIsAccepted() = runTest {
        val checker = object : SpellChecker { override suspend fun suggestions(word: String, language: String): List<String> = if (word == "frida") listOf("Friday", "fridge") else emptyList() }
        val parser = TimeWhisper(taggerFactory = untagged, spellCheckerFactory = SpellCheckerFactory { checker })
        try { assertEquals("2026-09-25", parser.date("frida")) } finally { parser.close() }
    }
    @Test fun ambiguousTemporalSuggestionsAreRejected() = runTest {
        val checker = object : SpellChecker { override suspend fun suggestions(word: String, language: String): List<String> = listOf("Friday", "Monday") }
        assertEquals(listOf("frida"), Lexer(English) { checker }.lex(tokenize("frida")) { true }.map { it.word.text })
    }
    @Test fun noSpellCheckerPreservesUnknownWord() = runTest { assertEquals(listOf("frida"), Lexer(English) { NoSpellChecker() }.lex(tokenize("frida")) { true }.map { it.word.text }) }
    @Test fun spellingIsReleasedWithResources() = runTest {
        var closed = 0
        val checker = object : SpellChecker {
            override suspend fun suggestions(word: String, language: String): List<String> = emptyList()
            override suspend fun close() { closed++ }
        }
        val parser = TimeWhisper(taggerFactory = untagged, spellCheckerFactory = SpellCheckerFactory { checker })
        parser.parseTemporal("frida", fixtureContext); parser.releaseResources(); parser.close()
        assertEquals(1, closed)
    }
    @Test fun withoutADateFinderADayNoRuleReadsStaysInTheTitle() = runTest {
        val task = parse("call Bob [DAY soonish] [W at] [CLOCK 5]", intent = asTask).task()
        assertEquals("Call Bob soonish at 5", task.title)
        assertNull(task.due)
    }
    @Test fun theTaggerIsCreatedWhenFirstNeededAndClosedWithTheResources() = runTest {
        var created = 0; var closed = 0
        val parser = TimeWhisper(taggerFactory = { created++; object : Tagger {
            override suspend fun tag(text: String, language: String) = Tagging(emptyList(), 0.0, 0.0, 1.0)
            override suspend fun close() { closed++ }
        } }, spellCheckerFactory = NoSpellCheckerFactory)
        assertEquals(0, created)
        parser.parse("buy milk", fixtureContext); parser.releaseResources(); parser.close()
        assertEquals(1 to 1, created to closed)
    }
    @Test fun dateFinderIsNotCreatedUntilUsed() = runTest {
        var created = 0
        val parser = TimeWhisper(taggerFactory = soonish, dateFinderFactory = { created++; RecordingFinder() }, spellCheckerFactory = NoSpellCheckerFactory)
        assertEquals(0, created); parser.close(); assertEquals(0, created)
    }
    @Test fun dateFinderReceivesLanguageReferenceAndZone() = runTest {
        val finder = RecordingFinder()
        val parser = TimeWhisper(taggerFactory = soonish, dateFinderFactory = { finder }, spellCheckerFactory = NoSpellCheckerFactory)
        try { parser.parse("call Bob soonish", fixtureContext) } finally { parser.close() }
        assertEquals(listOf(DateFinderRequest("en", fixtureContext.referenceInstant.toEpochMilliseconds(), "UTC")), finder.requests)
    }
    @Test fun dateFinderMatchBecomesTheDate() = runTest {
        val finder = RecordingFinder { text ->
            val at = text.indexOf("soonish")
            listOf(DateMatch(SourceRange(at, 7), DateMatchComponents(CivilDate(2026, 10, 3), CivilTime(9, 30))))
        }
        val parser = TimeWhisper(taggerFactory = soonish, dateFinderFactory = { finder }, spellCheckerFactory = NoSpellCheckerFactory)
        try {
            val event = assertIs<Candidate.Event>(parser.parse("call Bob soonish", fixtureContext).first())
            assertEquals("Call Bob", event.spec.title)
            assertEquals(Instant.parse("2026-10-03T09:30:00Z").toEpochMilliseconds(), assertIs<EventTime.Timed>(event.spec.time).start.epochMilliseconds)
            assertEquals(listOf(RecognizedSpan(SourceRange(9, 7), Source.FINDER)), event.spans)
        } finally { parser.close() }
    }
    @Test fun dateFinderIsClosedOnReleaseAndRecreatedLazily() = runTest {
        val created = mutableListOf<RecordingFinder>()
        val parser = TimeWhisper(taggerFactory = soonish, dateFinderFactory = { RecordingFinder().also { created += it } }, spellCheckerFactory = NoSpellCheckerFactory)
        try {
            assertEquals(0, created.size)
            parser.parse("call Bob soonish", fixtureContext); parser.releaseResources()
            assertEquals(listOf(1), created.map { it.closed })
            parser.parse("call Bob soonish", fixtureContext)
            assertEquals(2, created.size)
        } finally { parser.close() }
        assertEquals(listOf(1, 1), created.map { it.closed })
    }
    @Test fun failingDateFinderIsNotNoMatch() = runTest {
        val parser = TimeWhisper(taggerFactory = soonish, dateFinderFactory = { RecordingFinder { error("finder down") } }, spellCheckerFactory = NoSpellCheckerFactory)
        try { assertFailsWith<TimeWhisperException> { parser.parse("call Bob soonish", fixtureContext) } } finally { parser.close() }
    }
    @Test fun dateFinderRangeOutsideTheTextIsRejected() = runTest {
        val finder = RecordingFinder { listOf(DateMatch(SourceRange(10, 20), DateMatchComponents(CivilDate(2026, 10, 3), null))) }
        val parser = TimeWhisper(taggerFactory = soonish, dateFinderFactory = { finder }, spellCheckerFactory = NoSpellCheckerFactory)
        try { assertFailsWith<TimeWhisperException> { parser.parse("call Bob soonish", fixtureContext) } } finally { parser.close() }
    }
    @Test fun unsupportedLanguageIsRejected() {
        assertEquals(setOf("en", "pl"), Configuration.supportedLanguages)
        assertFailsWith<IllegalArgumentException> { Configuration(language = "xx") }
    }
    @Test fun concurrentRequestsHaveIndependentResults() = runTest {
        val parser = timeWhisper()
        try {
            val today = async { parser.date("today") }; val tomorrow = async { parser.date("tomorrow") }
            assertEquals("2026-09-23", today.await()); assertEquals("2026-09-24", tomorrow.await())
        } finally { parser.close() }
    }
}
