package org.github.dayedge.timewhisper.example

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.github.dayedge.timewhisper.*
import org.github.dayedge.timewhisper.taskorevent.Readers
import org.github.dayedge.timewhisper.taskorevent.Span
import org.github.dayedge.timewhisper.temporal.TemporalReader
import org.github.dayedge.timewhisper.text.Lexer
import org.github.dayedge.timewhisper.text.Lexicon
import org.github.dayedge.timewhisper.text.tokenize

/**
 * For the training-data generator (`training/generate.py --readers`): which labelled parts of each row no reader can read.
 * The row's own labels stand in for the tagger, so a part listed here is either labelled wrongly or written in a way the
 * readers do not know yet. Arguments: a context file in the corpus format, the rows (JSON lines with `text`, `labels` and
 * `urgency`), and the output, one JSON line per row: `{"unread": [["DAY", "sometime soon"], ...]}`.
 */
object ReadSpans {
    private val levels = mapOf("L" to Priority.LOW, "M" to Priority.MEDIUM, "H" to Priority.HIGH)

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val (contextFile, input, output) = args
        // corpusSetup finds a context beside its corpus, so it is given the corpus this context would sit beside.
        val setup = corpusSetup(File(contextFile.removeSuffix("-context.json") + "-corpus.jsonl"))
        val lexer = Lexer(Lexicon.forLanguage(setup.configuration.language)!!) { NoSpellChecker() }
        val temporal = TemporalReader(setup.configuration, lexer) { NoDateFinder() }
        File(output).printWriter().use { out ->
            File(input).readLines().filter(String::isNotBlank).forEach { line ->
                val row = Json.parseToJsonElement(line).jsonObject
                val text = row.getValue("text").jsonPrimitive.content
                val labels = row.getValue("labels").jsonArray.map { it.jsonPrimitive.content }
                var at = 0
                val tokens = text.split(" ").zip(labels).map { (word, label) ->
                    val known = when (label) { "T" -> TokenLabel.TITLE; "W" -> TokenLabel.WRAPPER; else -> TokenLabel.valueOf(label) }
                    TaggedToken(SourceRange(at, word.length), known, 1.0).also { at += word.length + 1 }
                }
                val urgency = row["urgency"]?.jsonPrimitive?.contentOrNull?.let { mapOf(levels.getValue(it) to 1.0) } ?: emptyMap()
                val scope = temporal.scope(setup.context, urgency)
                val unread = Span.of(lexer.lex(tokenize(text)) { false }, Tagging(tokens, 0.0, 0.0, 0.0, urgency)).filter { span ->
                    val reader = Readers[span.label] ?: return@filter false
                    reader.read(span.lexemes, scope) == null
                }
                out.println(buildJsonObject {
                    putJsonArray("unread") { unread.forEach { span -> addJsonArray { add(span.label.name); add(span.lexemes.distinctBy { it.range }.joinToString(" ") { it.text }) } } }
                })
            }
        }
    }
}
