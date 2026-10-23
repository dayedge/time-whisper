package org.github.dayedge.timewhisper.text

import org.github.dayedge.timewhisper.SourceRange

/** Punctuation that surrounds a word without being part of it. */
internal const val edgePunctuation = ".,;:?()[]\"'"

/** Punctuation trimmed from a name; unlike [edgePunctuation] it keeps quotes and drops exclamation marks. */
internal const val namePunctuation = ",;:.!?()"

internal data class Token(val text: String, val range: SourceRange) {
    val key: String = text.trim { it in edgePunctuation }.lowercase()
}

private val nonWhitespace = Regex("\\S+")

/**
 * Step 1. Splits the text on whitespace. Each token keeps its original text, its position in the input in UTF-16 units
 * (what the UI highlights), and a key: lowercase with surrounding punctuation trimmed, which is what vocabulary lookups use.
 *
 * - **A** `Standup tmrw 10am for 30 minutes every weekday @Office`
 *   → `standup@0 tmrw@8 10am@13 for@18 30@22 minutes@25 every@33 weekday@39 @office@47`
 * - **B** `Pay rent next Friday #home !! remind me 1 day before`
 *   → `pay@0 rent@4 next@9 friday@14 #home@21 !!@27 remind@30 me@37 1@40 day@42 before@46`
 * - **C** `Dentist next Friday at 2:30pm` → `dentist@0 next@8 friday@13 at@20 2:30pm@23`
 */
internal fun tokenize(text: String): List<Token> =
    nonWhitespace.findAll(text).map { Token(it.value, SourceRange(it.range.first, it.value.length)) }.toList()
