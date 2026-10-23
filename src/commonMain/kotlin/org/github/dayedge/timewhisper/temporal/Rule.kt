package org.github.dayedge.timewhisper.temporal

import kotlinx.datetime.LocalDate
import org.github.dayedge.timewhisper.CandidateKind
import org.github.dayedge.timewhisper.Configuration
import org.github.dayedge.timewhisper.DateOrder
import org.github.dayedge.timewhisper.Granularity
import org.github.dayedge.timewhisper.ParseContext
import org.github.dayedge.timewhisper.Priority
import org.github.dayedge.timewhisper.RecurrenceSpec
import org.github.dayedge.timewhisper.ReminderSpec
import org.github.dayedge.timewhisper.TokenLabel
import org.github.dayedge.timewhisper.text.Lexeme
import org.github.dayedge.timewhisper.text.Word

/**
 * One way of writing something the parser understands, and nothing else: "next friday", "3-5pm", "every other monday".
 * Rules know no words of any language; they ask each [Word] what it means, and the lexicon decides that.
 *
 * Rules never look for where a date or a repeat is: the tagger has already said that. A rule is handed the words of one
 * part and either reads all of them or none.
 */
internal interface Rule {
    /** Shown wherever the parser explains itself: "weekday-phrase". */
    val name: String

    /** Inputs this rule must read, in the languages it has been written down for. A test checks every one. */
    val examples: List<Example>
}

/** A rule that reads a whole run of words, such as "next friday" or "3 to 5pm", or does not read it at all. */
internal interface PhraseRule : Rule {
    fun read(words: List<Word>, scope: Scope): Fact?
}

/**
 * What the parser does with one part the tagger labelled: it reads the words of a [TokenLabel] into a value, or says it
 * cannot. A part that cannot be read goes back into the title as it was typed: a missed date is acceptable, a wrong one is not.
 */
internal interface Reader : Rule {
    val label: TokenLabel
    fun read(lexemes: List<Lexeme>, scope: Scope): Read?
}

/** The value of one part. */
internal sealed class Read {
    /** A day, a time, an end, a length or a deadline, all as one [Fact]. */
    data class When(val fact: Fact) : Read()
    /**
     * Where a repeat starts: a day, and what was read for it, with the weekday the words named ("starting next wednesday").
     * A range of days says where it stops as well: the fact's last day ("from 1 nov to 1 dec").
     */
    data class SeriesStart(val day: LocalDate, val fact: Fact) : Read()
    /** Where a repeat stops: on a day, after so long ("for 6 weeks", "for 3 months and 2 weeks"), or after so many times ("6 times"). */
    data class SeriesEnd(val until: LocalDate? = null, val count: Int? = null, val span: Amount? = null) : Read()
    /** How often: "every other monday". */
    data class Repeat(val rule: RecurrenceSpec) : Read()
    /** Where the event happens, as written: "Café Nero". */
    data class Place(val name: String) : Read()
    /** A list or a calendar of the caller's, by its id. */
    data class Destination(val list: String?, val calendar: String?) : Read()
    /** A stated priority: "!!", "urgent". */
    data class Importance(val level: Priority) : Read()
    /** A kind forced by a mark: "todo", "t:event". */
    data class Kind(val kind: CandidateKind) : Read()
    /**
     * A reminder: at a day or time ([at]), how long before the entry or before [at] ([lead]: "10 min", "a day", "1 day
     * 2 hours"), or both. [Assembler] completes it with the entry's day and time.
     */
    data class Remind(val at: Fact? = null, val lead: Amount? = null) : Read()
}

/** A text a rule must read, in its language. */
internal class Example(val language: String, val text: String) {
    companion object {
        /** English examples: `en("next friday", "3pm")`. */
        fun en(vararg texts: String): List<Example> = texts.map { Example("en", it) }

        /** Polish examples. */
        fun pl(vararg texts: String): List<Example> = texts.map { Example("pl", it) }
    }
}

/** What a rule may know beyond the words in front of it. */
internal class Scope(
    val context: ParseContext,
    val configuration: Configuration,
    /** Which comes first in a numeric date: the host's setting, or else the language's own habit. */
    val dateOrder: DateOrder,
    /** How likely the text states each priority, from the tagger. */
    val urgency: Map<Priority, Double> = emptyMap(),
)

/** Rules tried in order on the words of one part: the first that reads all of them wins. */
internal abstract class RuleSet(val rules: List<PhraseRule>) {
    /** The first rule that reads all of [words], with what it read. */
    fun read(words: List<Word>, scope: Scope): Pair<PhraseRule, Fact>? {
        if (words.isEmpty()) return null
        for (rule in rules) rule.read(words, scope)?.let { return rule to it }
        return null
    }

    /** What the first rule that reads all of [words] read. */
    fun fact(words: List<Word>, scope: Scope): Fact? = read(words, scope)?.second
}
