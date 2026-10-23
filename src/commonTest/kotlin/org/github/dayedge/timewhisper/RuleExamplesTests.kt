package org.github.dayedge.timewhisper

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.github.dayedge.timewhisper.taskorevent.Readers
import org.github.dayedge.timewhisper.temporal.*
import org.github.dayedge.timewhisper.text.Lexer
import org.github.dayedge.timewhisper.text.Word
import org.github.dayedge.timewhisper.text.Lexicon
import org.github.dayedge.timewhisper.text.tokenize

/** Every rule and every reader carries examples of what it must read. Each is checked here, in the language it is given in. */
class RuleExamplesTests {
    private val context = fixtureContext.copy(destinations = fixtureContext.destinations + Destination("team", "Team", DestinationKind.CALENDAR))
    private val lexers = Configuration.supportedLanguages.associateWith { Lexer(Lexicon.forLanguage(it)!!) { NoSpellChecker() } }
    private suspend fun words(example: Example): List<Word> = lexers.getValue(example.language).lex(tokenize(example.text)) { false }.map { it.word }
    private fun scope(example: Example) = Scope(context, Configuration(language = example.language), DateOrder.MONTH_DAY,
        mapOf(Priority.HIGH to 1.0))

    @Test fun everyRuleHasANameOfItsOwnAndExamples() {
        val all: List<Rule> = DayRules.rules + ClockRules.rules + Readers.all
        assertEquals(all.size, all.map { it.name }.toSet().size, "rule names must differ")
        assertEquals(emptyList(), all.filter { it.examples.isEmpty() }.map { it.name }, "rules without examples")
        assertEquals(TokenLabel.entries.toSet() - TokenLabel.TITLE - TokenLabel.WRAPPER, Readers.all.map { it.label }.toSet(), "a reader for every label that states something")
    }
    @Test fun dayRulesReadTheirExamples() = runTest {
        for (rule in DayRules.rules) for (example in rule.examples) {
            assertEquals(rule.name, DayRules.read(TimeWords.meaningful(words(example)), scope(example))?.first?.name, "${example.language}: ${example.text}")
        }
    }
    @Test fun clockRulesReadTheirExamples() = runTest {
        for (rule in ClockRules.rules) for (example in rule.examples) {
            assertEquals(rule.name, ClockRules.read(TimeWords.meaningful(words(example)), scope(example))?.first?.name, "${example.language}: ${example.text}")
        }
    }
    @Test fun readersReadTheirExamples() = runTest {
        for (reader in Readers.all) for (example in reader.examples) {
            val lexemes = lexers.getValue(example.language).lex(tokenize(example.text)) { false }
            assertNotNull(reader.read(lexemes, scope(example)), "${reader.name}, ${example.language}: ${example.text}")
        }
    }
}
