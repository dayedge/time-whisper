package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.github.dayedge.timewhisper.text.English
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.Lexer
import org.github.dayedge.timewhisper.text.Polish
import org.github.dayedge.timewhisper.text.tokenize

private fun checker(suggest: (String) -> List<String>) = object : SpellChecker {
    override suspend fun suggestions(word: String, language: String): List<String> = suggest(word)
}
private suspend fun Lexer.read(text: String): List<Lexeme> = lex(tokenize(text)) { true }
private fun List<Lexeme>.words(): List<String> = map { it.word.text }

class LexerTests {
    @Test fun unambiguousPermittedSuggestionBecomesTheWord() = runTest {
        val lexer = Lexer(English) { checker { if (it == "frida") listOf("Friday", "fridge") else emptyList() } }
        val lexeme = lexer.read("Frida").single()
        assertEquals("friday", lexeme.word.text)
        assertEquals("Frida", lexeme.text, "the source stays as typed")
        assertNull(lexer.word("frida").weekday, "the text as written is not a weekday")
    }
    @Test fun ambiguousSuggestionsAreRejected() = runTest {
        val lexer = Lexer(English) { checker { listOf("Friday", "Monday") } }
        assertEquals(listOf("frida"), lexer.read("frida").words())
    }
    @Test fun correctionToAnotherFormOfAWordReadsLikeThatWord() = runTest {
        val lexer = Lexer(English) { checker { listOf("beginning") } }
        assertNotNull(lexer.read("begining").single().word.entry?.edge)
    }
    @Test fun spellingIsAskedOnlyForLongUnknownLetterWords() = runTest {
        val asked = mutableListOf<String>()
        val lexer = Lexer(English) { checker { asked += it; emptyList() } }
        lexer.read("Dentist frida next wk eom 10am tmr #work café dentist")
        assertEquals(listOf("dentist", "frida", "café"), asked, "short words, known words, abbreviations and non-letters are skipped; repeats are cached")
    }
}

class WordingTests {
    private val english = Lexer(English) { NoSpellChecker() }
    private val polish = Lexer(Polish) { NoSpellChecker() }

    @Test fun oneTokenCanBeSeveralWordsSharingItsSource() = runTest {
        val lexemes = english.read("report eom")
        assertEquals(listOf("report", "end", "of", "month"), lexemes.words())
        assertEquals(listOf(SourceRange(7, 3), SourceRange(7, 3), SourceRange(7, 3)), lexemes.drop(1).map { it.range })
    }
    @Test fun severalTokensCanBeOneWordCoveringAllOfThem() = runTest {
        val lexemes = polish.read("o piątej po południu")
        assertEquals(listOf("o", "piątej", "popołudniu"), lexemes.words())
        assertEquals(SourceRange(9, 11), lexemes.last().range)
        assertEquals("po południu", lexemes.last().text)
    }
    @Test fun wordingIsMatchedWhateverTheCasingAndPunctuation() = runTest {
        assertEquals(listOf("report", "end", "of", "month"), english.read("Report EOM.").words())
    }
    @Test fun wordsOfAWordingAreOrdinaryWordsOnTheirOwn() = runTest {
        assertEquals(listOf("day", "after", "lunch"), english.read("day after lunch").words())
    }
    @Test fun ordinalsSaidInTwoWordsAreOneNumber() = runTest {
        assertEquals(listOf("25"), polish.read("dwudziestego piątego").words())
        assertEquals(listOf("21"), english.read("twenty first").words())
    }
    @Test fun anotherFormOfAWordReadsLikeIt() = runTest {
        assertEquals(english.word("week").unit, english.read("wk").single().word.unit)
        assertEquals(english.word("tomorrow").entry?.relativeDay, english.read("tmrw").single().word.entry?.relativeDay)
    }
    @Test fun aWordingInTheTitleGoesIntoItAsTyped() = runTest {
        // "First day of" reads as "start of"; in the title it is printed as it was written.
        assertEquals("First day of school", parse("First day of school [DAY tomorrow]").title())
    }
}
