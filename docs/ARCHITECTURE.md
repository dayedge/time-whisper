# Architecture

time-whisper is a pipeline of seven small steps over one line of text. Each step lives in one place:

```
text ──► tokenize ──► tag ──► lex ──► read ──► assemble ──► decide ──► build
         Tokens.kt    Tagger  Lexer   Readers  Assembly     Ranking    TaskOrEventParser
```

1. **Tokenize**: split on whitespace, keeping positions.
2. **Tag**: the tagger says what each word is for: title, wrapping words, or one of the things a text can state (a day, a time, an end, a length, a deadline, a repeat and where it starts and stops, a place, a list, a calendar, a priority, a reminder, a forced kind). Neighbouring words with one label are one part. It also says whether the text asks for a task, an event or nothing, and what urgency it states.
3. **Lex**: read each token once into a typed word, using the language's lexicon. From here on no reader looks at a string. Only a word in a part about time may be spell-corrected.
4. **Read**: each part is read by the reader for its label. A part a reader cannot read goes back into the title as it was typed; a host's date finder may still read a day or a time.
5. **Assemble**: one day and time from the day, time, end, length and deadline parts; one repeat with where it starts and stops; the metadata; the title from the title words and every part that was not read.
6. **Decide**: task, event, both in some order, or nothing.
7. **Build**: the result values.

What surrounds the pipeline:

```
Swift wrapper (Apple targets)        Kotlin callers
          |                               |
          +---------- TimeWhisper --------+
                          |
                      pipeline
                          |
          Tagger (required)          DateFinder (optional)      SpellChecker (optional)
          Apple: CoreMLTagger        none by default            macOS: system checker by default
          elsewhere: the host's      supplied by the host       elsewhere: none unless supplied
```

The tagger never supplies a value: every date, time and repeat is read by the readers. It decides what a lexicon cannot keep up with: where each thing is, which words only wrap a request ("can you remind me to", "muszę", "thanks"), which date-like words are a title ("call April", "watch Saturday Night Live"), whether a bare number is an hour or a count ("friday 11", "chapters 3-5"), and whether the line asks for a task, an event or nothing. Those are open-ended judgements, so they are learned from examples (`training/`) and kept out of the lexicon.

## Kotlin packages

The root package `org.github.dayedge.timewhisper` holds only the public API: the `TimeWhisper` lifecycle facade, configuration and result models, and the provider interfaces. Everything else is internal, in subpackages with one dependency direction:

```
text        tokens, lexicon, lexer, typed word readings           (leaf)
temporal    facts, date helpers, day rules, clock rules, readers of
            a time part, merge, finder-match mapping              -> text
taskorevent parts, the reader for each label, repeats, assembly,
            ranking, the parser                                   -> text, temporal
```

The parser reads dates and times with its own readers. A host may supply a `DateFinder` for a day or a time part they cannot read; `temporal` decides what a finder's match means, the same way for any finder, and never lets it overrule a rule. The language in `Configuration` selects the lexicon and travels to the finder and the spelling provider in each request; `"en"` and a first-draft `"pl"` have lexicons today. Platform source sets supply the default spelling factory in the root package; `AppleBridge` stays in the root package of `macosMain` because Swift calls it. Kotlin `internal` is module-wide, so the public surface is enforced by explicit API mode, not by the packages.

## Words before rules

`text` reads each token once and hands the rules typed values, so no rule inspects a string.

- `Lexicon` is one language's vocabulary as data: each surface form maps to an `Entry` of typed values (weekday, month, number, unit, direction, …) and roles (`CONNECTOR`, `AGO`, `EVERY`, …). `English` and a first-draft `Polish` are the instances.
- `Conventions` is how the language writes things down: date and time separators, am/pm, the 12- or 24-hour habit, decimals, ordinal endings, accents. Digits are read by these, in one shared place, so no rule assumes a language's habits.
- `Word` is the reading of one piece of text on its own: its entry plus what its shape says (a clock, an ISO date, a glued range, a `#tag`). Readings are facets, not one kind per word, because a word can have several: "tonight" is a day and a time.
- `Lexeme` is one unit of meaning: where it was written, what was written, and the one word every rule reads. Usually that is one token.
- `Wording` is lexicon data for the cases where it is not: several written words that are one meaning (Polish "po południu" is the single word for pm), one that is several ("eom" is "end of month"), or a wording the rules know in a shorter form ("first day of" is "start of"). The lexer replaces them before any rule runs, longest match first. Lexemes from one wording share its source range.
- Another form of a word ("wk", "tmrw", an inflected or abbreviated form) is an ordinary entry that reads exactly like its target. A corrected spelling is the corrected word.
- A number said in words is a number for every rule: "two hours", "dwie godziny". Two number words that make one ("forty five", "czterdzieści pięć") are joined by the lexer; how they join is the lexicon's `composedNumber`, since languages differ ("cuarenta y cinco", "fünfundvierzig").
- `Lexer` produces lexemes and keeps readings by text until resources are released, so while someone types only the changed token is read again.

Because every rule reads the same lexemes, a new way of saying something is added as data and reaches the phrase grammar, the time rules, repeats and reminders alike. The lexicon holds closed classes only, words a grammar book lists in full; which words wrap a request is the tagger's. A lexeme no rule claims goes into the title as it was typed. No words of any language remain outside the lexicons; the only patterns matched against raw text elsewhere are language-neutral: ISO dates and whitespace.

## Adding a language

A language is one file in `text/`, examples for the tagger, and three files in `fixtures/`. No reader is edited.

1. **Vocabulary.** A `Lexicon` object like `English` or `Polish`: each written form maps to an `Entry` of values (weekday, month, number, unit, direction, …) and a few roles. `Role` in `text/Lexicon.kt` lists every role with what the readers do with it. Most are meanings ("every", "ago", "all day"). Two mark words the readers read past inside a part: `JOINER` holds one date or time together ("the 5th of October", "friday at 5") and `HEDGE` qualifies it without changing it ("about 4", "7 sharp"). A word that only attaches a part to the sentence ("by friday", "at 3") is not in the lexicon: the tagger labels it a wrapper, outside the part.
2. **Conventions.** One `Conventions` value: which comes first in a numeric date, which characters separate dates, minutes and decimals, whether there is am/pm, whether a written time is taken as written, ordinal endings, accents.
3. **Wordings.** Phrases that are one meaning in several words, or the other way round: Polish "po południu" is the single word for pm; "we wtorki" is "co wtorek".
4. **Register it** in `text/Lexicons.kt`.
5. **Teach the tagger.** The model is multilingual, so a language it was not tuned on is read by transfer, roughly. Labelled sentences in the language (`training/<lang>/`, the markup of `training/prompts/labels.md`) tune it. Among other things they teach it that the word attaching a part ("w piątek", "do piątku") is a wrapper; until they exist, the lexicon lists those words as joiners, so the readers read past them, as Polish does now.
6. **A corpus**: `<lang>-evaluation-corpus.jsonl`, `<lang>-evaluation-context.json` (language, reference time, lists) and, once it should be a gate, `<lang>-evaluation-known-failures.txt` from `make accept-corpus`.

Where a language says something in an order a rule does not accept yet, the rule's own description says so; the rule is then extended for every language, not worked around in the lexicon.

## Readers and rules

Each label the tagger gives a part has one reader (`taskorevent/Readers.kt`): a name, examples, and one function that reads all the words of its part or none of them. Readers never look for where something is; the tagger has said that. So there is no precedence between them, no guard against reading a count as an hour, and no veto: a reader that cannot read its part says so, and the words go back into the title.

The readers of days and times are built from small rules, each one way of writing something, tried in order on the words of a part until one reads all of them:

| List | File | What it reads |
| --- | --- | --- |
| `dayRules` | `temporal/DayRules.kt` | a day, a period or a range of days: "next friday", "end of month", "mon-wed" |
| `clockRules` | `temporal/ClockRules.kt` | a time or a range of times: "at 3pm", "3-5pm", "half past 3", "in 2 hours" |

A repeat is read by `recurrence` in `taskorevent/Repeats.kt`, from the meanings of its words in any order. A deadline, an end and a series bound may hold a day and a time together; `readWhen` cuts them apart. `parseTemporal`, which has no tagger, cuts the whole text into at most three parts and reads each as a day, a time or a length.

What to offer for a text (a task, an event, both in some order, or nothing) is decided in one place, `decide` in `taskorevent/Ranking.kt`, from a plain record of what was found (`Evidence`). It looks at no words, so it reads the same for every language.

To add a rule or a reader: write the object, give it examples in the languages it has been written down for, and put it in its list. `RuleExamplesTests` checks that every rule and reader reads its own examples, and `ReaderTests` holds each reader's cases. After a change, `python3 training/generate.py … --readers` lists the labelled parts of the training data that no reader can read.

Common Kotlin owns tokenization, the readers, title extraction, metadata, intent order and semantic tests. Providers own platform handles and the model. The Swift façade is a native-value/concurrency adapter and the Core ML runner of the tagger.

## Platforms

The parser is shared Kotlin. Its only dependencies are kotlinx-datetime and kotlinx-coroutines; it bundles no third-party parser and runs no JavaScript.

| Piece | Where | Platform-specific? |
| --- | --- | --- |
| Everything in the pipeline | `src/commonMain` | No. |
| Default spell checker | `Platform.kt` in each platform source set | Yes: the system checker on macOS, none on the JVM. |
| Bridge for Swift | `src/macosMain/…/AppleBridge.kt` and `swift/Sources/TimeWhisper` | Apple targets only. It turns suspend functions and Kotlin values into `async` methods and Swift structs, and contains no parsing. |

The JVM and macOS (Apple Silicon and Intel) are built and tested. The other Kotlin/Native targets are intended but not built yet. Adding one means declaring the target in `build.gradle.kts` and giving it a `defaultSpellCheckerFactory`, which may simply be `NoSpellCheckerFactory`. Time-zone data comes from the platform through kotlinx-datetime.

Public results use the library's own value types (`CivilDate`, `Moment`), not a platform's calendar or UI types. Swift callers see Foundation dates, structs, enums and `async` methods.

## What the host application does

The library returns values and stops there. The application:

- supplies the reference time, the time zone, and the lists and calendars the user has;
- shows the candidates, lets the user confirm or correct them, and saves the result wherever it keeps tasks and events;
- converts a repeat into its own store's recurrence format;
- supplies the tagger model, and chooses whether to supply a date finder or a spell checker, and which (see [Providers](PROVIDERS.md));
- decides when to release the parser's resources during idle periods.

## Fixtures

`en-date-expressions.txt` pins the exact result of 116 date expressions. Each language has a hand-written evaluation corpus that scores `parse` by tier, run through the Swift API with the model. For English a list of its known failures turns it into a gate: no sentence may stop matching, or start matching, unnoticed. Focused tests cover each reader, assembly, the decision, providers and lifetime, with sentences tagged by hand. See `fixtures/PROVENANCE.md`. No user data is used anywhere.

## A note on the toolchain

Kotlin 2.4.20 builds both macOS slices but reports the Intel one (`macos_x64`) as deprecated. Check that it is still available before upgrading Kotlin, and do not drop the slice silently. CI defines an Intel job; local verification has been on Apple Silicon.
