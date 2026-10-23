# time-whisper

A Kotlin Multiplatform library that turns one line of typed text into a task or an event. Kotlin package `org.github.dayedge.timewhisper`, MPL-2.0. This file is the working agreement for anyone changing it.

## What it is, and is not

- It reads what is stated outright (dates, times, repeats, reminders, priorities, tags, places, time zones) and leaves everything else in the title. A missed word is acceptable; a wrong guess is not.
- A small trained model (the tagger) says where each thing is and what it is: the title, wrapping words, a day, a time, a repeat, a place, a priority. The parser reads each part into a value. The model never supplies a value, and a part no reader can read goes back into the title.
- It does not replace general date libraries such as Chrono, Natty or Duckling, and does not chase their coverage. A host that wants one plugs it in as a date finder, which reads a day or a time the readers cannot.
- Vague words are out of scope on purpose: "dinner", "mid-October", "morning" as an hour. Do not add rules for them.
- It is a generic library. Keep the public API small and free of any one application's concepts.

## Where things go

- **Words** belong to a language's lexicon (`text/English.kt`, `text/Polish.kt`), as entries with values and a few roles. No word of any language appears in a rule. The lexicon says only what words mean inside a part; where a part is, and which words attach it, is the tagger's job.
- **Habits of writing** (date order, separators, am/pm, decimals) belong to the lexicon's `Conventions`, not to rules.
- **Several words that are one meaning**, or one that is several, are `Wording`s in the lexicon.
- **What a part means** is read by the reader for its label (`taskorevent/Readers.kt`). A way of writing a day or a time is one rule object with a name and examples in `dayRules` or `clockRules`; a repeat is read by `recurrence` in `taskorevent/Repeats.kt`. Describe what each reads in English and Polish, and say what another language would need.
- **Putting the parts together** (one day and time, a repeat and its bounds, the titles) happens in one place, `assemble` in `taskorevent/Assembly.kt`.
- **What the tagger is taught** is written down in `training/prompts/labels.md`. A label change goes there, into the generator and into `TokenLabel`, in that order.
- **Task or event** is decided in one place, `decide` in `taskorevent/Ranking.kt`.
- When a language says something in an order a rule does not accept, extend the rule for every language; do not work around it in the lexicon.
- Shared Kotlin owns all parsing. The Swift wrapper only converts values and bridges `async`.

## Constraints

- No network at run time. No bundled third-party parser, no JavaScript.
- The tagger is required; the date finder and the spell checker are optional. All three are supplied by the host, replaceable and created lazily.
- Results depend only on the text, the configuration and the context passed in. Never read the device clock, locale or default zone.

## Testing

- Tests fix the reference time, zone, date order, week rules and spelling suggestions. Never depend on host defaults, real sleeps or a system dictionary's output.
- The corpus measures the whole pipeline as an app runs it: the tagger and the system spell checker. `SPELLING=` runs it without the spell checker, for comparison.
- Kotlin tests tag their sentences with markup, as the training data is written: `parse("call Bob [DAY tmrw] [CLOCK 3pm]")`. A reader's own cases go in `ReaderTests`; whole sentences in `PipelineTests`.
- Add focused tests for changed logic, and examples to any rule or reader you add or change; `RuleExamplesTests` runs them.
- Run `make check` before finishing. It runs every language's corpus (`CORPORA` in the Makefile) through the Swift API with the model in `build/tagger-coreml` (or `MODEL=`) and fails when a result differs from that language's accepted list.
- When a corpus result changes on purpose, review the change, then run `make accept-corpus`. Never edit a known-failures list by hand.
- After changing a reader or the labels, run `make training-data`: it writes the tagger's data and lists the labelled parts no reader can read.
- The training set is `training/<lang>/sentences.txt` and `templates.txt`, kept in sections. Add to it only with `make add-sentences FILE=…`, which checks the lines and files them; never keep a batch as a file of its own.

## Housekeeping

- Do not publish or commit unless asked.
- Commit messages carry no co-author or attribution trailer.
