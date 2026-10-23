package org.github.dayedge.timewhisper

import kotlin.time.Instant
import kotlin.test.assertIs

internal val fixtureContext = ParseContext(Instant.parse("2026-09-23T12:00:00Z"), "UTC", listOf(
    Destination("work", "Work", DestinationKind.TASK_LIST), Destination("personal", "Personal", DestinationKind.TASK_LIST), Destination("home", "Home", DestinationKind.TASK_LIST),
))

/** How the text reads as a whole: as a task, an event, nothing, or neither more than the other (what the tests assume unless they say). */
internal val asTask = Triple(0.8, 0.15, 0.05)
internal val asEvent = Triple(0.1, 0.85, 0.05)
internal val asNothing = Triple(0.02, 0.03, 0.95)
internal val undecided = Triple(0.4, 0.4, 0.2)

private val item = Regex("""\[([A-Za-z]+) ([^\]]*)\](\S*)|(\S+)""")

/**
 * The text a line of markup stands for, and the label of each of its words, as the training data is written:
 * `[W remind me to] call Bob [DAY tmrw] [W at] [CLOCK 3pm]`. Words outside brackets are title. `T` and `W` are title and wrapper;
 * any other label is a [TokenLabel] name. An upper-case label is sure (0.95), a lower-case one is not (0.6).
 */
internal fun markup(text: String): Pair<String, List<Pair<TokenLabel, Double>>> {
    val words = mutableListOf<Pair<String, String>>()
    for (m in item.findAll(text)) {
        val (label, inside, glue, bare) = m.destructured
        if (bare.isNotEmpty()) words += bare to "T"
        else inside.split(' ').let { parts -> parts.forEachIndexed { i, w -> words += (if (i == parts.lastIndex) w + glue else w) to label } }
    }
    val labels = words.map { (_, label) ->
        val known = when (label.uppercase()) { "T" -> TokenLabel.TITLE; "W" -> TokenLabel.WRAPPER; else -> TokenLabel.valueOf(label.uppercase()) }
        known to if (label == label.uppercase()) 0.95 else 0.6
    }
    return words.joinToString(" ") { it.first } to labels
}

/** A tagger that labels the words of whatever text it is given in order, with [labels]; words beyond them are title. */
internal class MarkupTagger(
    private val labels: List<Pair<TokenLabel, Double>>,
    private val intent: Triple<Double, Double, Double> = undecided,
    private val urgency: Map<Priority, Double> = emptyMap(),
) : Tagger {
    override suspend fun tag(text: String, language: String): Tagging {
        val tokens = Regex("\\S+").findAll(text).toList().mapIndexedNotNull { i, m ->
            labels.getOrNull(i)?.let { (label, confidence) -> TaggedToken(SourceRange(m.range.first, m.value.length), label, confidence) }
        }
        return Tagging(tokens, intent.first, intent.second, intent.third, urgency)
    }
}

/** A tagger that says every word is title: for what needs no tagger, such as [TimeWhisper.parseTemporal]. */
internal val untagged = TaggerFactory { MarkupTagger(emptyList()) }

/** A parser with no tagging, no spell checker and no date finder. */
internal fun timeWhisper(configuration: Configuration = Configuration(), finder: DateFinderFactory = NoDateFinderFactory) =
    TimeWhisper(configuration, untagged, finder, NoSpellCheckerFactory)

/** What [text], written in markup, gives. [text] may also be plain text with [labels] given apart. */
internal suspend fun parse(
    text: String,
    language: String = "en",
    intent: Triple<Double, Double, Double> = undecided,
    urgency: Map<Priority, Double> = emptyMap(),
    configuration: Configuration = Configuration(dateOrder = if (language == "pl") DateOrder.DAY_MONTH else null, language = language),
    checker: SpellCheckerFactory = NoSpellCheckerFactory,
    finder: DateFinderFactory = NoDateFinderFactory,
    context: ParseContext = fixtureContext,
): List<Candidate> {
    val (plain, labels) = markup(text)
    val parser = TimeWhisper(configuration, { MarkupTagger(labels, intent, urgency) }, finder, checker)
    try { return parser.parse(plain, context) } finally { parser.close() }
}

internal fun List<Candidate>.event(): EventSpec = filterIsInstance<Candidate.Event>().first().spec
internal fun List<Candidate>.task(): TaskSpec = filterIsInstance<Candidate.Task>().first().spec
internal fun List<Candidate>.title(): String = first().let { (it as? Candidate.Task)?.spec?.title ?: (it as Candidate.Event).spec.title }
internal fun List<Candidate>.kinds(): List<String> = map { if (it is Candidate.Task) "task" else "event" }
internal fun at(time: String): Long = Instant.parse("${time}:00Z").toEpochMilliseconds()
internal fun EventSpec.start(): Long = assertIs<EventTime.Timed>(time).start.epochMilliseconds
internal fun EventSpec.end(): Long = assertIs<EventTime.Timed>(time).endExclusive.epochMilliseconds
internal fun EventSpec.span(): Pair<Long, Long> = start() to end()
