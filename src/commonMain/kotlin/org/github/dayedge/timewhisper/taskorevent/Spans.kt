package org.github.dayedge.timewhisper.taskorevent

import org.github.dayedge.timewhisper.SourceRange
import org.github.dayedge.timewhisper.Tagging
import org.github.dayedge.timewhisper.TokenLabel
import org.github.dayedge.timewhisper.temporal.Fact
import org.github.dayedge.timewhisper.temporal.Read
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.Token

/** Groups of labels the parser treats alike. */
internal object Labels {
    /** The parts that say when: their words may be spell-corrected, and they count as a date read. */
    val time = setOf(
        TokenLabel.DAY, TokenLabel.CLOCK, TokenLabel.END, TokenLabel.LENGTH, TokenLabel.DUE, TokenLabel.REPEAT, TokenLabel.STARTS, TokenLabel.ENDS,
    )

    /** The parts that name the entry's day: a day, or a deadline. */
    val day = setOf(TokenLabel.DAY, TokenLabel.DUE)

    /** The parts a host's date finder is asked about when no reader could read them. */
    val finder = setOf(TokenLabel.DAY, TokenLabel.CLOCK)
}

/** The tagger's label for the word written at [range]: the label of the first tagged word there, or title when there is none. */
internal fun Tagging.labelAt(range: SourceRange): TokenLabel = tokens.firstOrNull { it.range.overlaps(range) }?.label ?: TokenLabel.TITLE

/** Whether the spell checker may be asked about [token]: only a word in a part about time. */
internal fun Tagging.mayCorrect(token: Token): Boolean = labelAt(token.range) in Labels.time

/**
 * A run of neighbouring words with one label: one thing the text states, or a run of title or wrapping words.
 * A lexeme made of several words (a wording such as Polish "po południu") takes the label of its first word.
 */
internal class Span(val label: TokenLabel, val lexemes: List<Lexeme>) {
    val range: SourceRange get() = lexemes.first().range.through(lexemes.last().range)
    override fun toString(): String = "$label(${lexemes.joinToString(" ") { it.text }})"

    companion object {
        /** The parts of the text, in order: each run of lexemes with one label. */
        fun of(lexemes: List<Lexeme>, tagging: Tagging): List<Span> {
            // Lexemes of one wording share their range, and so their label.
            val labels = mutableListOf<TokenLabel>()
            lexemes.forEachIndexed { i, lexeme ->
                labels += if (i > 0 && lexemes[i - 1].range == lexeme.range) labels[i - 1] else tagging.labelAt(lexeme.range)
            }
            val spans = mutableListOf<Span>()
            var from = 0
            for (i in 1..lexemes.size) {
                if (i == lexemes.size || labels[i] != labels[from]) {
                    spans += Span(labels[from], lexemes.subList(from, i)); from = i
                }
            }
            return spans
        }
    }
}

/** A part and what it says, or null when no reader could read it and it goes back into the title. */
internal class Reading(val span: Span, val value: Read?, val byFinder: Boolean = false) {
    val label: TokenLabel get() = span.label
    /** The day, time, end or length the part says, if that is what it says. */
    val fact: Fact? get() = (value as? Read.When)?.fact

    /** The same part, saying [value] instead. */
    fun saying(value: Read?): Reading = Reading(span, value, byFinder)

    /** The same part, unread: it goes back into the title. */
    fun unread(): Reading = saying(null)
}
