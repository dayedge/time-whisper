package org.github.dayedge.timewhisper

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.github.dayedge.timewhisper.taskorevent.TaskOrEventParser
import org.github.dayedge.timewhisper.temporal.TemporalReader
import org.github.dayedge.timewhisper.text.Lexicon
import org.github.dayedge.timewhisper.text.Lexer

/**
 * Turns typed text into task and event candidates, or into a date.
 * 
 * One instance serves a whole application. It holds no state between requests beyond a cache of word readings and
 * the providers, which are created on first use. Requests run one at a time; it is safe to call from several coroutines.
 * 
 * Everything a result depends on is passed in: the [configuration] here, and a [ParseContext] with each request.
 * The device clock, locale and default time zone are never read.
 * 
 * @param taggerFactory the reader of what each part of the text is for, supplied by the host: a trained model.
 * @param dateFinderFactory an optional reader of a day or a time no rule could read, supplied by the host; none by default.
 * @param spellCheckerFactory an optional source of spelling suggestions for misspelt date words; the platform default if not given.
 */
public class TimeWhisper(
    public val configuration: Configuration = Configuration(),
    private val taggerFactory: TaggerFactory,
    private val dateFinderFactory: DateFinderFactory = NoDateFinderFactory,
    private val spellCheckerFactory: SpellCheckerFactory = defaultSpellCheckerFactory(),
) {
    private val mutex = Mutex()
    private var finder: DateFinder? = null
    private var spelling: SpellChecker? = null
    private var tagger: Tagger? = null
    private var lexer: Lexer? = null
    private var temporal: TemporalReader? = null
    private var parser: TaskOrEventParser? = null
    private var closed = false
    private fun finder(): DateFinder = finder ?: dateFinderFactory.create().also { finder = it }
    private fun tagger(): Tagger = tagger ?: taggerFactory.create().also { tagger = it }
    private fun lexer(): Lexer = lexer ?: Lexer(Lexicon.forLanguage(configuration.language)!!) {
        spelling ?: spellCheckerFactory.create().also { spelling = it }
    }.also { lexer = it }

    // The temporal reader and the parser hold the lexer, so they live and are released with it.
    private fun temporal(): TemporalReader = temporal ?: TemporalReader(configuration, lexer(), ::finder).also { temporal = it }
    private fun parser(): TaskOrEventParser = parser ?: TaskOrEventParser(configuration, lexer(), temporal(), ::tagger).also { parser = it }
    private fun checkOpen() {
        if (closed) throw TimeWhisperException("Parser is closed")
    }

    /**
     * What [text] asks to be created: task and event candidates, most likely first. Empty when there is nothing to offer.
     * 
     * "Dentist next Friday at 2:30pm" gives an event and then a task; "buy milk" gives a task; "groceries" gives nothing.
     * Words that are not read stay in the title. A failing provider throws [TimeWhisperException].
     */
    @Throws(TimeWhisperException::class, CancellationException::class)
    public suspend fun parse(text: String, context: ParseContext): List<Candidate> = request {
        if (text.isBlank()) emptyList() else parser().parse(text, context)
    }

    /**
     * The date or period [text] means when all of it is a date expression: "next week", "tomorrow at 9", "friday to sunday".
     * Null when any word is not part of the date.
     */
    @Throws(TimeWhisperException::class, CancellationException::class)
    public suspend fun parseTemporal(text: String, context: ParseContext): TemporalSpec? =
        request { temporal().resolve(text, context) }

    /**
     * The first date inside longer text, and the text without it: "standup tomorrow" gives tomorrow and "standup".
     * Made for search and filtering. A month name alone counts as a date here. Null when the text holds no date.
     */
    @Throws(TimeWhisperException::class, CancellationException::class)
    public suspend fun findTemporal(text: String, context: ParseContext): FoundTemporal? =
        request { if (text.isBlank()) null else parser().find(text, context) }

    /** Serializes one request, honouring cancellation on both sides of the work and wrapping provider failures. */
    private suspend fun <T> request(block: suspend () -> T): T = try {
        mutex.withLock {
            checkOpen(); currentCoroutineContext().ensureActive()
            block().also { currentCoroutineContext().ensureActive() }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: TimeWhisperException) {
        throw failure
    } catch (failure: Exception) {
        throw TimeWhisperException("Parsing provider failed", failure)
    }

    /** Closes the providers and forgets cached readings. They are created again when next needed. Use it during idle periods. */
    @Throws(TimeWhisperException::class, CancellationException::class)
    public suspend fun releaseResources(): Unit = mutex.withLock {
        checkOpen()
        withContext(NonCancellable) { release() }
    }

    /** Releases everything for good. Further requests throw. May be called more than once. */
    @Throws(TimeWhisperException::class, CancellationException::class)
    public suspend fun close(): Unit = mutex.withLock {
        if (!closed) {
            closed = true
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun release() {
        try {
            finder?.close()
        } finally {
            finder = null
            try {
                spelling?.close()
            } finally {
                spelling = null; lexer = null; temporal = null; parser = null
                try {
                    tagger?.close()
                } finally {
                    tagger = null
                }
            }
        }
    }
}
