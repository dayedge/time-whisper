package org.github.dayedge.timewhisper.example

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.github.dayedge.timewhisper.*

/** One candidate reduced to comparable text fields, the same way for the corpus and for the parser's output. */
private typealias Fields = Map<String, String>

private enum class Verdict { MATCH, RANKING, FAIL }

private class Outcome(val verdict: Verdict, val detail: List<String>)
private class Row(val id: String, val tier: String, val input: String, val outcome: Outcome, val tagged: String)

private val fieldOrder = listOf("kind", "title", "due", "start", "end", "allDay", "allDayEnd", "list", "calendar", "location", "priority", "repeat", "reminder")
private val weekdayNames = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

/** A weekday in a repeat as the corpus writes it: "fri", or with its place in the month "-1fri" (the last) and "2tue". */
private fun weekdayKey(day: String): Int = weekdayNames.indexOf(day.trimStart('-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'))

private fun repeatText(every: String, interval: Int, weekdays: List<String>, monthDays: List<Int>, until: String?): String =
    listOfNotNull(
        "every " + (if (interval == 1) every else "$interval ${every}s"),
        weekdays.sortedBy(::weekdayKey).takeIf { it.isNotEmpty() }?.joinToString(",", "on "),
        monthDays.sorted().takeIf { it.isNotEmpty() }?.joinToString(",", "on day "),
        until?.let { "until $it" },
    ).joinToString(" ")

private fun expected(candidate: JsonObject): Fields = buildMap {
    for ((key, value) in candidate) when (key) {
        "repeat" -> {
            val rule = value.jsonObject
            put(key, repeatText(
                rule.getValue("every").jsonPrimitive.content, rule["interval"]?.jsonPrimitive?.int ?: 1,
                rule["weekdays"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                rule["monthDays"]?.jsonArray?.map { it.jsonPrimitive.int } ?: emptyList(),
                rule["until"]?.jsonPrimitive?.content,
            ))
        }
        else -> put(key, value.jsonPrimitive.content)
    }
}

private fun kinds(candidates: List<Fields>): String = if (candidates.isEmpty()) "nothing" else candidates.joinToString(", ") { it.getValue("kind") }

/** Compares the parser's ranked candidates with one acceptable answer. */
private fun compare(wanted: List<Fields>, got: List<Fields>): Outcome {
    if (wanted == got) return Outcome(Verdict.MATCH, emptyList())
    if (wanted.isEmpty()) return Outcome(Verdict.FAIL, listOf("should be rejected, got ${kinds(got)}: " + got.joinToString(" | ") { it.getValue("title") }))
    if (got.isEmpty()) return Outcome(Verdict.FAIL, listOf("rejected, expected ${kinds(wanted)}"))
    val differences = mutableListOf<String>()
    var shared = 0
    for (want in wanted) {
        val kind = want.getValue("kind")
        val have = got.firstOrNull { it["kind"] == kind } ?: continue
        shared++
        for (field in fieldOrder) if (want[field] != have[field])
            differences += "$kind.$field: expected ${want[field]?.let { "\"$it\"" } ?: "none"}, got ${have[field]?.let { "\"$it\"" } ?: "none"}"
    }
    val order = "expected ${kinds(wanted)}; got ${kinds(got)}"
    if (shared == 0) return Outcome(Verdict.FAIL, listOf("wrong kind: $order"))
    // Every candidate both sides have is right; only which candidates are offered, or their order, differs.
    if (differences.isEmpty()) return Outcome(Verdict.RANKING, listOf(order + if (wanted[0] == got[0]) " (first choice is right)" else " (first choice is wrong)"))
    if (wanted.map { it["kind"] } != got.map { it["kind"] }) differences += "candidates: $order"
    return Outcome(Verdict.FAIL, differences)
}

object Evaluate {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        // Usage: corpus.jsonl --observed results.jsonl [--label name] [--check known-failures.txt] [--accept known-failures.txt]
        // The results the Swift evaluator recorded with the tagger are judged here, so every platform is judged by the same code.
        // --check fails the run when the sentences that do not match differ from the listed ones; --accept rewrites the list.
        val option = { name: String -> args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } }
        val corpus = File(args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: corpusPath)
        val label = option("--label")
        val observed: Map<String, JsonObject> = (option("--observed") ?: error("--observed is required: run the Swift evaluator first (make evaluate)")).let { path ->
            File(path).readLines().filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }.associateBy { it.getValue("input").jsonPrimitive.content }
        }
        val context = corpusSetup(corpus).context
        val rows = mutableListOf<Row>()
        for (group in corpusGroups(corpus)) {
            val id = group.getValue("id").jsonPrimitive.content
            val tier = group.getValue("tier").jsonPrimitive.content
            val acceptable = listOf(group.getValue("expect")) + (group["alternatives"]?.jsonArray ?: emptyList())
            val answers = acceptable.map { answer -> answer.jsonArray.map { expected(it.jsonObject) } }
            for (input in group.inputs) {
                val record = observed[input] ?: error("No recorded result for: $input")
                val got = record["candidates"]?.jsonArray?.map { candidate -> candidate.jsonObject.mapValues { it.value.jsonPrimitive.content } }
                // With several acceptable answers, the sentence is judged against the one it comes closest to.
                val outcome = if (got == null) Outcome(Verdict.FAIL, listOf("parser error"))
                else answers.map { compare(it, got) }.minWith(compareBy<Outcome> { it.verdict.ordinal }.thenBy { it.detail.size })
                rows += Row(id, tier, input, outcome, record["tagged"]?.jsonPrimitive?.content ?: "")
            }
        }

        fun percent(part: Int, whole: Int) = if (whole == 0) "-" else "${part * 100 / whole}%"
        val summary = table(
            listOf("Tier", "Sentences", "Matched", "Wrong ranking", "Failed", "Matched %"),
            (rows.groupBy { it.tier }.toSortedMap() + ("all" to rows)).map { (tier, of) ->
                val count = Verdict.entries.associateWith { verdict -> of.count { it.outcome.verdict == verdict } }
                listOf(tier, of.size, count[Verdict.MATCH], count[Verdict.RANKING], count[Verdict.FAIL], percent(count.getValue(Verdict.MATCH), of.size))
            },
        )
        val failed = rows.filter { it.outcome.verdict == Verdict.FAIL }
        // "event.title: expected …" counts under "title"; whole-sentence problems count under their own name.
        fun problem(detail: String): String = when {
            detail.startsWith("should be rejected") -> "should have been rejected"
            detail.startsWith("rejected") -> "rejected by the parser"
            detail.startsWith("wrong kind") -> "task and event swapped"
            detail.startsWith("candidates") -> "candidates offered or their order"
            detail.startsWith("parser error") -> "parser error"
            else -> detail.substringBefore(':').substringAfter('.')
        }
        val problems = failed.map { row -> row.outcome.detail.map(::problem).distinct() }
        val byField = problems.flatten().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val titleOnly = problems.count { it == listOf("title") }
        val fields = table(
            listOf("What was wrong", "Sentences"),
            byField.map { (field, count) -> listOf(field, count) } + listOf(listOf("*title was the only difference*", titleOnly)),
        )
        val groups = rows.groupBy { it.id }
        val report = buildString {
            appendLine("# Corpus evaluation: ${corpus.name}${label?.let { " ($it)" } ?: ""}")
            appendLine()
            appendLine("Reference time ${context.referenceInstant} in ${context.timeZoneId}. ${rows.size} sentences in ${groups.size} groups; " +
                "${groups.count { (_, of) -> of.all { it.outcome.verdict == Verdict.MATCH } }} groups have every sentence matched.")
            appendLine()
            appendLine(summary)
            appendLine("## What failed sentences got wrong")
            appendLine()
            appendLine("A sentence is counted once for each kind of difference it has.")
            appendLine()
            appendLine(fields)
            appendLine("## Failed (${failed.size})")
            appendLine()
            for (row in failed) {
                appendLine("- `${row.id}` ${row.tier} — ${row.input}")
                row.outcome.detail.forEach { appendLine("  - $it") }
                appendLine("  - tagged: ${row.tagged}")
            }
            appendLine()
            val ranking = rows.filter { it.outcome.verdict == Verdict.RANKING }
            appendLine("## Wrong ranking (${ranking.size})")
            appendLine()
            appendLine("Every candidate the parser and the corpus share is right; the candidates offered, or their order, differ.")
            appendLine()
            for (row in ranking) appendLine("- `${row.id}` ${row.tier} — ${row.input}\n  - ${row.outcome.detail.single()}")
            appendLine()
            val matched = rows.filter { it.outcome.verdict == Verdict.MATCH }
            appendLine("## Matched 100% (${matched.size})")
            appendLine()
            for (row in matched) appendLine("- `${row.id}` ${row.tier} — ${row.input}")
        }
        val output = File("build/reports/corpus-${corpus.nameWithoutExtension}${label?.let { "-$it" } ?: ""}.md").apply { parentFile.mkdirs(); writeText(report) }
        label?.let { println("== $it ==") }
        println(summary)
        println(fields)
        println("Every sentence is listed in $output")

        // The corpus is a scoreboard, not a test: a quarter of it does not match yet. The accepted list of those
        // sentences, each with how it fails, makes it a gate.
        val unmatched = rows.filter { it.outcome.verdict != Verdict.MATCH }.associate { it.input to it.outcome.verdict.name }
        option("--accept")?.let { path ->
            File(path).writeText(unmatched.entries.sortedBy { it.key }.joinToString("\n", postfix = "\n") { "${it.value}\t${it.key}" })
            println("Wrote ${unmatched.size} known failures to $path")
        }
        option("--check")?.let { path ->
            val known = File(path).readLines().filter(String::isNotBlank).associate { it.substringAfter('\t') to it.substringBefore('\t') }
            val inCorpus = rows.map { it.input }.toSet()
            val changes = (known.keys + unmatched.keys).sorted().mapNotNull { input ->
                val was = known[input]
                val now = unmatched[input]
                when {
                    was == now -> null
                    was == null -> "NO LONGER MATCHES ($now): $input"
                    input !in inCorpus -> "NOT IN THE CORPUS ANY MORE: $input"
                    now == null -> "NOW MATCHES: $input"
                    else -> "WAS $was, NOW $now: $input"
                }
            }
            changes.forEach(::println)
            if (changes.isNotEmpty()) {
                println("\nThe corpus result changed. If that is intended, run `make accept-corpus` and review the change to $path.")
                kotlin.system.exitProcess(1)
            }
            println("Corpus check passed: the ${known.size} known failures are unchanged.")
        }
    }
}
