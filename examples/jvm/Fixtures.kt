package org.github.dayedge.timewhisper.example

import java.io.File
import kotlinx.serialization.json.*
import kotlin.time.Instant
import org.github.dayedge.timewhisper.*

internal const val corpusPath = "fixtures/en-evaluation-corpus.jsonl"

/**
 * The fixed world a corpus is written for, read from the file beside it: "pl-evaluation-corpus.jsonl" has
 * "pl-evaluation-context.json". It names the language, the reference time and zone, the lists and calendars, and the
 * date order if it is not the language's own. Nothing about any language is written in the tools.
 */
internal class CorpusSetup(val configuration: Configuration, val context: ParseContext)

internal fun corpusSetup(corpus: File): CorpusSetup {
    val file = File(corpus.path.removeSuffix("-corpus.jsonl") + "-context.json")
    val setup = Json.parseToJsonElement(file.readText()).jsonObject
    fun text(key: String) = setup[key]?.jsonPrimitive?.content
    val order = when (text("dateOrder")) { "dayMonth" -> DateOrder.DAY_MONTH; "monthDay" -> DateOrder.MONTH_DAY; else -> null }
    val destinations = setup.getValue("destinations").jsonArray.map { it.jsonObject }.map {
        val kind = if (it.getValue("kind").jsonPrimitive.content == "calendar") DestinationKind.CALENDAR else DestinationKind.TASK_LIST
        Destination(it.getValue("id").jsonPrimitive.content, it.getValue("name").jsonPrimitive.content, kind)
    }
    return CorpusSetup(
        Configuration(dateOrder = order, language = text("language")!!),
        ParseContext(Instant.parse(text("reference")!!), text("timeZone")!!, destinations),
    )
}

/** The groups of a corpus file, one JSON object per line. */
internal fun corpusGroups(file: File): List<JsonObject> = file.readLines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }

internal val JsonObject.inputs: List<String> get() = getValue("inputs").jsonArray.map { it.jsonPrimitive.content }

/**
 * A Markdown table laid out so that it also reads as plain text in a terminal: every column is padded to its widest cell.
 * A column whose cells are all numbers (digits, with an optional "%", or "-" for none) is right-aligned.
 */
internal fun table(header: List<String>, rows: List<List<Any?>>): String {
    val cells = rows.map { row -> row.map { it?.toString() ?: "" } }
    val numeric = Regex("\\d+%?|-")
    return buildString {
        val columns = header.indices.map { column ->
            val body = cells.map { it[column] }
            val width = maxOf(3, (body + header[column]).maxOf { it.length })
            val right = body.isNotEmpty() && body.all(numeric::matches)
            Triple(width, right, if (right) "-".repeat(width - 1) + ":" else "-".repeat(width))
        }
        fun line(texts: List<String>) = appendLine(texts.indices.joinToString(" | ", "| ", " |") { column ->
            val (width, right) = columns[column]
            if (right) texts[column].padStart(width) else texts[column].padEnd(width)
        })
        line(header)
        appendLine(columns.joinToString(" | ", "| ", " |") { it.third })
        cells.forEach(::line)
    }
}
