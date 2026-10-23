package org.github.dayedge.timewhisper.text

import org.github.dayedge.timewhisper.SourceRange
import org.github.dayedge.timewhisper.SpellChecker

/**
 * Reads tokens once. Readings depend only on the text, so they are kept between requests:
 * while someone types, only the token that changed is read again.
 */
internal class Lexer(val lexicon: Lexicon, private val checker: suspend () -> SpellChecker) {
    private val words = mutableMapOf<String, Word>()
    private val readings = mutableMapOf<String, Word>()
    // Wordings by their first written word, longest first, so input that contains none costs one lookup per token.
    // A written word matches as it is, or as typed without accents. A token typed with accents is never folded:
    // "niedzielę" (Sunday) must not become "niedziele" (Sundays).
    private val wordings = lexicon.wordings.sortedByDescending { it.written.size }
        .flatMap { wording -> setOf(wording.written[0], lexicon.fold(wording.written[0])).map { it to wording } }
        .groupBy({ it.first }, { it.second })
    private val unaccented = lexicon.wordings.associateWith { wording -> wording.written.map(lexicon::fold) }
    private fun says(key: String, wording: Wording, at: Int): Boolean = key == wording.written[at] || key == unaccented.getValue(wording)[at]

    /** The reading of [text] exactly as written, without spelling correction. */
    fun word(text: String): Word = words[text] ?: Word(text, lexicon).also {
        // The cache is emptied when full rather than trimmed: readings are cheap to make again.
        if (words.size >= CACHED_TEXTS) {
            words.clear(); readings.clear()
        }
        words[text] = it
    }

    /** The word a token stands for: itself, or its unambiguous spelling correction. */
    suspend fun reading(text: String): Word = readings[text] ?: run {
        val own = word(text)
        // Only something that could be a misspelt word of the lexicon goes to the spelling provider.
        val result = if (mayBeMisspelt(own)) corrected(own) else own
        readings[text] = result
        result
    }

    private fun mayBeMisspelt(word: Word): Boolean =
        // Short words have too many neighbours to correct safely, a word the lexicon knows needs no correction,
        // and anything with a digit or a symbol is not a word to spell.
        word.text.length >= 4 && word.entry == null && word.text.all(Char::isLetter)

    /** Providers only suggest; a correction is taken when exactly one suggestion is a word the lexicon gives a meaning. */
    private suspend fun corrected(own: Word): Word =
        checker().suggestions(own.text, lexicon.spellingLanguage).map { word(it.lowercase()) }
            .filter { it.means }.distinctBy { it.text }.singleOrNull() ?: own

    /**
     * Step 3. Turns tokens into [Lexeme]s: units of meaning, each with the one word every rule reads.
     * After this step no rule looks at a string; rules ask a word what it is.
     *
     * Most tokens are one lexeme. Some readings from the running examples:
     *
     * - `friday` → weekday FRIDAY. `next` → direction +1. `day` → unit DAY, and 1440 minutes as a duration unit.
     * - `10am` → clock 10:00 am. `2:30pm` → clock 2:30 pm. `30` and `1` → numbers.
     * - `every` → role EVERY. `weekday` → role WEEKDAYS. `for`, `at`, `on` → small words that only attach their part.
     * - `#home` → tag "home". `!!` → two exclamation marks.
     * - `tmrw` → the same reading as `tomorrow`: the lexicon lists it as another form of that word.
     * - `standup`, `rent`, `dentist` → nothing; they are not in the lexicon.
     *
     * Three things make a lexeme differ from its token, and every rule sees the result:
     *
     * - A misspelling: `frida` reads as `friday` if that is the spelling provider's only suggestion the lexicon knows, and if
     *   [mayCorrect] allows the token: only a word the tagger puts in a part about time is asked about.
     * - Several tokens that are one meaning ([Wording]): Polish `po południu` → the one word for "pm".
     * - One token, or one wording, that is several words: `eom` → `end` `of` `month`; `first day of` → `start` `of`.
     * - A number said in two words ([Lexicon.composedNumber]): `forty five` → `45`, `dwudziestego piątego` → `25`.
     *
     * A lexeme keeps the source it was written in, so an unclaimed one goes into the title exactly as typed.
     */
    suspend fun lex(tokens: List<Token>, mayCorrect: (Token) -> Boolean): List<Lexeme> {
        val result = ArrayList<Lexeme>(tokens.size)
        var at = 0
        while (at < tokens.size) {
            val wording = wordings[tokens[at].key]?.firstOrNull { wording ->
                at + wording.written.size <= tokens.size && wording.written.indices.all { says(tokens[at + it].key, wording, it) }
            }
            if (wording == null) {
                val token = tokens[at++]
                val word = if (mayCorrect(token)) reading(token.key) else word(token.key)
                // A number said in two words is one number: "forty five" reads as "45" does.
                val number = tokens.getOrNull(at)?.takeIf { word.entry?.numeral != null }?.let { lexicon.composedNumber(word, word(it.key)) }
                if (number != null) {
                    val second = tokens[at++]
                    val range = token.range.through(second.range)
                    result += Lexeme(range, token.text + " " + second.text, word(number.toString()))
                } else result += Lexeme(token.range, token.text, word)
                continue
            }
            val written = tokens.subList(at, at + wording.written.size)
            val range = written.first().range.through(written.last().range)
            val text = written.joinToString(" ") { it.text }
            wording.meaning.forEach { result += Lexeme(range, text, word(it)) }
            at += written.size
        }
        return result
    }

    private companion object {
        /** How many readings are kept before the cache starts again. */
        const val CACHED_TEXTS = 1024
    }
}
