package org.github.dayedge.timewhisper

import kotlinx.coroutines.*
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant

/**
 * What the Swift module calls: the parser behind callbacks, so Swift can bridge `async` and cancel a request. Each call
 * returns a [ParseHandle]; the completion gets a result or a [BridgeFailure], never both.
 */
public class AppleBridge(
    dayFirst: Boolean?,
    weekStartIso: Int,
    weekendIso: List<Int>,
    defaultEventMinutes: Int,
    language: String,
    reminderHour: Int,
    reminderMinute: Int,
    taggerFactory: TaggerFactory,
    dateFinderFactory: DateFinderFactory?,
    spellingFactory: SpellCheckerFactory?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val parser = TimeWhisper(
        Configuration(dayFirst?.let { if (it) DateOrder.DAY_MONTH else DateOrder.MONTH_DAY }, DayOfWeek(weekStartIso), weekendIso.map { DayOfWeek(it) }.toSet(), defaultEventMinutes, language,
            CivilTime(reminderHour, reminderMinute)),
        taggerFactory, dateFinderFactory ?: NoDateFinderFactory, spellingFactory ?: defaultSpellCheckerFactory(),
    )
    private fun context(epochMilliseconds: Long, zone: String, destinations: List<Destination>) = ParseContext(Instant.fromEpochMilliseconds(epochMilliseconds), zone, destinations)
    private fun <T> submit(completion: (T?, BridgeFailure?) -> Unit, block: suspend () -> T): ParseHandle {
        var result: T? = null
        var failure: BridgeFailure? = null
        val job = scope.launch {
            try { result = block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { failure = BridgeFailure(if (error is IllegalArgumentException) BridgeFailureKind.INVALID_INPUT else BridgeFailureKind.BACKEND, error.message ?: "Parsing failed") }
        }
        job.invokeOnCompletion { cause ->
            completion(result, if (cause is CancellationException) BridgeFailure(BridgeFailureKind.CANCELLED, "Request cancelled") else failure)
        }
        return ParseHandle(job)
    }
    public fun parse(text: String, epochMilliseconds: Long, zone: String, destinations: List<Destination>, completion: (List<Candidate>?, BridgeFailure?) -> Unit): ParseHandle =
        submit(completion) { parser.parse(text, context(epochMilliseconds, zone, destinations)) }
    public fun findTemporal(text: String, epochMilliseconds: Long, zone: String, completion: (FoundTemporal?, BridgeFailure?) -> Unit): ParseHandle =
        submit(completion) { parser.findTemporal(text, context(epochMilliseconds, zone, emptyList())) }
    public fun parseTemporal(text: String, epochMilliseconds: Long, zone: String, completion: (TemporalSpec?, BridgeFailure?) -> Unit): ParseHandle =
        submit(completion) { parser.parseTemporal(text, context(epochMilliseconds, zone, emptyList())) }
    public fun releaseResources(completion: (BridgeFailure?) -> Unit): ParseHandle = submit<Unit>({ _, error -> completion(error) }) { parser.releaseResources() }
    public fun close(completion: (BridgeFailure?) -> Unit): ParseHandle = submit<Unit>({ _, error -> completion(error) }) { parser.close() }
    public fun dispose() {
        scope.cancel()
        CoroutineScope(Dispatchers.Default).launch { parser.close() }
    }
}
/** A request in flight. Cancelling it completes it with a [BridgeFailureKind.CANCELLED] failure. */
public class ParseHandle internal constructor(private val job: Job) { public fun cancel() { job.cancel() } }
/** Builds the library's values from the plain ones Swift passes in. A value the core would reject comes back as null instead of failing. */
public object BridgeValues {
    public fun components(
        year: Int?, month: Int?, day: Int?, hour: Int?, minute: Int?,
        dayStated: Boolean?, monthStated: Boolean?, yearStated: Boolean?, offsetMinutes: Int?,
    ): DateMatchComponents? = runCatching {
        DateMatchComponents(
            if (year != null && month != null && day != null) CivilDate(year, month, day) else null,
            if (hour != null && minute != null) CivilTime(hour, minute) else null,
            dayStated, monthStated, yearStated, offsetMinutes,
        )
    }.getOrNull()

    public fun match(offset: Int, length: Int, start: DateMatchComponents, end: DateMatchComponents?): DateMatch? =
        runCatching { DateMatch(SourceRange(offset, length), start, end) }.getOrNull()

    /** One tagged word; [label] is the label's name as the model writes it: "T", "W", or a [TokenLabel] name such as "DAY". */
    public fun taggedToken(offset: Int, length: Int, label: String, confidence: Double): TaggedToken? = runCatching {
        val known = when (label) { "T" -> TokenLabel.TITLE; "W" -> TokenLabel.WRAPPER; else -> TokenLabel.valueOf(label) }
        TaggedToken(SourceRange(offset, length), known, confidence)
    }.getOrNull()

    /** A tagging, with how likely the text states no priority, a low, a medium or a high one. */
    public fun tagging(tokens: List<TaggedToken>, task: Double, event: Double, none: Double, unstated: Double, low: Double, medium: Double, high: Double): Tagging =
        Tagging(tokens, task, event, none, mapOf(Priority.NONE to unstated, Priority.LOW to low, Priority.MEDIUM to medium, Priority.HIGH to high))
}
/** Why a bridged request failed: it was cancelled, its input was not valid, or a provider or the parser failed. */
public enum class BridgeFailureKind { CANCELLED, INVALID_INPUT, BACKEND }

/** A failed request, with a message for the log. */
public data class BridgeFailure(val kind: BridgeFailureKind, val message: String)
