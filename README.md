# time-whisper

A Kotlin Multiplatform library that turns one line of typed text into a task or an event.

Give it "Dentist next Friday at 2:30pm" and it returns a title, a start and an end, and whether it looks more like an event or a task. It runs on the device, gives the same answer every time, and never touches the network. English is supported, and Polish as a first draft.

A small trained model (the tagger, about 70 MB, about 1.5 ms a sentence on the CPU) says what each part of the text is for: "Dentist" is the title, "next Friday" a day, "at 2:30pm" a time. The parser then reads each part. The parser is written entirely in common Kotlin, so it can go wherever Kotlin Multiplatform goes; the only platform code is the bridge to Swift and the default spell checker on macOS. The JVM and macOS are built and tested today (see [Platforms](#platforms)). On Apple platforms the Swift package runs the model with Core ML; on the JVM the host supplies a tagger.

```kotlin
val parser = TimeWhisper(taggerFactory = myTagger)
val context = ParseContext(Instant.parse("2026-09-23T12:00:00Z"), "America/New_York")
try {
    println(parser.parse("Lunch tomorrow at 12:15 for 1 hour", context))
    // [Event(spec=EventSpec(title=Lunch, time=Timed(start=…12:15, endExclusive=…13:15), …), spans=[…])]
    println(parser.findTemporal("review notes tomorrow", context))
    // FoundTemporal(temporal=TemporalSpec(…24 Sep, granularity=DAY), remainingText=review notes)
} finally { parser.close() }
```

```swift
let parser = try TimeWhisper(model: URL(fileURLWithPath: "TimeWhisperModel"))  // the folder training/export.py writes
let context = ParseContext(referenceDate: Date(), timeZone: .current)
let choices = try await parser.parse("Lunch tomorrow at 12:15 for 1 hour", context: context)
let found = try await parser.findTemporal("review notes tomorrow", context: context)
try await parser.close()
```

## What it reads

Asked on Wednesday 23 September 2026, with lists "Work" and "Home" and a calendar "Team" supplied by the caller:

| Text | Result |
| --- | --- |
| Dentist next Friday at 2:30pm | event "Dentist", Fri 2 Oct 14:30–15:30; also offered as a task |
| Standup tomorrow 10am for 30 minutes every weekday @Office | event "Standup", 10:00–10:30, weekly Mon–Fri, at "Office" |
| Pay rent Friday #home !! remind me 1 day before | task "Pay rent", due Fri 25 Sep, list Home, medium priority, reminder a day before |
| Lunch with Sam tomorrow 12-1pm at "The Corner Table" | event "Lunch with Sam", 12:00–13:00, at "The Corner Table" |
| Call with Anna tomorrow at 3pm EST | event at 3 pm New York time, reported in the caller's zone |
| Conference Friday to Sunday | all-day event, 25–27 Sep |
| yoga Tuesdays at 6pm starting next week | weekly on Tuesday, first on 29 Sep at 18:00 |
| call May tomorrow | task "Call May", due tomorrow: a month name alone is a person, not a date |
| buy milk | task "Buy milk", no date |
| groceries | nothing: there is no date and nothing that asks for a task |

The same in Polish, with `Configuration(language = "pl")`:

| Text | Result |
| --- | --- |
| Dentysta w przyszły piątek o 14:30 | event "Dentysta", Fri 2 Oct 14:30–15:30 |
| Planowanie jutro o 10 przez 45 minut #zespół | event "Planowanie", 10:00–10:45, calendar Zespół |
| Zapłać czynsz do piątku #dom !! przypomnij dzień wcześniej | task "Zapłać czynsz", due Fri 25 Sep, list Dom, reminder a day before |
| Lekcja języka we wtorki o 18:00 od przyszłego tygodnia | weekly on Tuesday, first on 29 Sep at 18:00 |
| Wysłać fakturę 21.10 o 15:45 | 21 Oct at 15:45 |
| Spotkanie jutro o piątej po południu | tomorrow at 17:00 |
| zadzwoń do Maja jutro | task "Zadzwoń do Maja", due tomorrow |

Every row above is what the parser returns today.

## Goals and non-goals

**Goals**

- Read what is stated outright: dates, times, ranges, durations, repeats, reminders, priorities, list and calendar tags, places, time zones.
- Leave everything else in the title. A wrong guess is worse than a missed word, because the user sees a title they can fix.
- Give the same answer on every platform, for the same text, reference time and zone.
- Grow by data. Where each thing is in a sentence is learned by the tagger from labelled examples; a language is then only a vocabulary of what words mean and a set of writing conventions. The readers contain no words of any language.

**Non-goals**

- **It does not replace general date libraries** such as Chrono, Natty or Duckling, and does not try to read everything they read. Those are broad date extractors. This is a narrow entry parser that also decides the title, the kind and the metadata. Where broader date coverage is wanted, such a library can be plugged in as a [date finder](docs/PROVIDERS.md).
- **It does not guess vague words.** "dinner", "mid-October" and "morning" name no hour or day that people agree on, so they stay in the title.
- **It does not understand intent** beyond choosing between a task and an event, and offering both when the text does not say.
- **It has no user interface and writes nowhere.** It returns values; the application shows, confirms and saves them.

## Using it

One `TimeWhisper` serves a whole application. It has three methods:

| Method | Returns |
| --- | --- |
| `parse(text, context)` | task and event candidates, in order of preference; empty when there is nothing to offer |
| `parseTemporal(text, context)` | the date or period the whole text means ("next week", "tomorrow at 9"), or nothing |
| `findTemporal(text, context)` | the first date inside longer text, and the text without it; made for search and filtering |

The caller says everything the answer depends on. `ParseContext` carries the reference time, the time zone and the lists and calendars a `#tag` may name. `Configuration` carries the language, and optionally the numeric date order, the first day of the week, the weekend days and the default length of an event. Nothing is read from the device's clock or locale.

Call `releaseResources()` to drop what the providers hold during idle periods, and `close()` when done.

Details are in [API behavior](docs/API.md).

## Platforms

The parser is shared Kotlin with two dependencies, kotlinx-datetime and kotlinx-coroutines.

- **Built and tested:** the JVM, and macOS on Apple Silicon and Intel, where a Swift package wraps the Kotlin core with native types and `async` methods.
- **Intended:** the other Kotlin/Native targets. Nothing in the shared code is specific to a platform; a new target needs to be declared in the build and given a default spell checker, which can be none. These targets are not built or tested yet.

Coordinates: `org.github.dayedge:time-whisper` (Kotlin Multiplatform) and `org.github.dayedge:time-whisper-jvm`; Kotlin package `org.github.dayedge.timewhisper`; Swift module `TimeWhisper`.

## Providers

Three things come from the host application.

- **The tagger** is required. On Apple platforms it is the Core ML model `TimeWhisper(model:)` loads; it is trained with the scripts in `training/` and is not in git. On the JVM a host supplies one.
- **A spell checker** lets a misspelt date word be read ("frida" as Friday); only words the tagger puts in a part about time are asked about. On macOS the system checker is used by default; elsewhere the default is none.
- **A date finder** reads a day or a time the parser's readers cannot. None is used by default. On macOS it can be the system data detector.

[Providers](docs/PROVIDERS.md) explains what each is for, how the parser treats what they return, and shows examples.

## Building

JDK 21 and the Gradle wrapper are required; macOS builds also need Xcode with its command-line tools.

```sh
make check       # JVM, macOS and Swift tests, and the corpus gate of every language (needs the model in build/tagger-coreml)
make package     # local Maven publication and a self-contained Swift package ZIP
swift run TimeWhisperExample build/tagger-coreml
```

On Linux, `sh ./gradlew jvmTest` runs the JVM tests, which tag their sentences by hand. `make package` publishes to `~/.m2/repository` only and writes the Swift package to `build/distributions`; nothing is uploaded.

## Checking behavior

Each language has a hand-written corpus in `fixtures/`: everyday sentences with the full expected result, in tiers from plain to deliberately tricky. It runs through the Swift API with the model (`MODEL=` another folder).

```sh
make evaluate                                             # English: scores by tier, every sentence listed in build/reports/
make evaluate CORPUS=fixtures/pl-evaluation-corpus.jsonl  # Polish
make evaluate SPELLING=                                   # without the system spell checker
make accept-corpus                                        # accept the current results as the new lists of known failures
make accept-corpus CORPORA=pl                             # for one language
```

Each corpus is a scoreboard and a gate at once. Beside it sits a list of the sentences that do not match yet (`fixtures/<lang>-evaluation-known-failures.txt`); `make check` fails when any language's list would change in either direction, and `make accept-corpus` rewrites the lists once a change has been reviewed. A new language joins the gate by adding its code to `CORPORA` in the Makefile. `fixtures/en-date-expressions.txt` additionally pins the exact result of 116 bare date expressions.

The tagger is trained from generated and hand-written sentences: `training/prompts/labels.md` says how each word is labelled, `training/en/sentences.txt` and `templates.txt` hold the set, `make add-sentences FILE=…` adds to it, and `make training-data` writes the data and lists the labelled parts the readers cannot read.

Training and tuning the tagger (`training/train.py`) needs PyTorch and, in practice, an accelerator it supports: an Apple M-series Mac or a laptop or desktop NVIDIA RTX card is plenty for a model this small. It also runs on the CPU, only much more slowly. Exporting it to Core ML (`training/export.py`) needs PyTorch and coremltools but no accelerator, and everything else in `training/` needs only Python. Using the library needs none of this: the tagger runs on the CPU, in about 1.5 ms a sentence.

Install the Python training and export dependencies in a virtual environment:

```sh
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -r requirements.txt
```

The packaging scripts in `scripts/` and the training-data generator use only Python's standard
library. Model execution and system-spelling evaluation on macOS use the Swift API, not Python.

How the parser is put together, and how to add a rule or a language, is in [Architecture](docs/ARCHITECTURE.md).

## Contributing

Contributions are very welcome: a new language, more training sentences, a rule for a way of writing dates we missed, or a corpus sentence that trips the parser up. Small pull requests are great, and so is opening an issue to talk an idea through first.

### Adding a language

Parsing lives in shared Kotlin, so a new language is mostly words and examples rather than code. A rough path:

1. **Lexicon.** Add `text/<Language>.kt`, following `English.kt` section by section: the words and their forms as entries, writing habits (date order, separators, 12- or 24-hour clock, accents) in `Conventions`, and set phrases as `Wording`s. Register it in `Lexicon.forLanguage` and `Lexicon.languages`. Rules never name words, so they should work as they are; when the language orders things in a way a rule doesn't expect, the fix is to widen the rule for every language.
2. **Examples and tests.** Give the rules and readers a few examples in the new language (`Example.<lang>(…)`, checked by `RuleExamplesTests`), and add cases to `ReaderTests` and `PipelineTests`.
3. **An evaluation corpus.** `fixtures/<lang>-evaluation-corpus.jsonl` with its context file: everyday sentences and what they should give, in the same tiers as English. Written by a native speaker if at all possible: they know how people really type, which is what the corpus is there to measure. Writing it before the training data, and keeping the two apart, keeps the scores honest (the generator refuses any training row that matches a corpus sentence).
4. **Training data.** `training/<lang>/sentences.txt`, `templates.txt` and `pools.json`. The prompts in `training/prompts/` help draft them; `python3 training/add.py --lang <lang> new.txt` checks and files new lines. Add the language's letters to `LETTERS` in `training/export.py` so the exported vocabulary keeps them.
5. **The model.** One model serves every language, so it is retrained on all of them together (best with an M-series Mac or an RTX card), then exported and checked with `training/coreml_check.py`.

Stuck on any step? Open a draft pull request early; it's easier to help with something we can see.

### Keeping existing languages where they are

The one thing we ask of every change is that languages that already work keep working as well as before. A few numbers in the pull request make that easy to see:

- `make check` passing. If the list of known failures changes, the diff from `make accept-corpus`, with a word on any sentence that newly fails.
- The tier tables from `make evaluate` for each language, before and after your change (core shouldn't drop; a dip elsewhere is fine to discuss).
- If you retrained the model: the end of the training log and the new `labels.json` beside the current one, and the `coreml_check.py` result. Your machine won't reproduce our model exactly, so "before" is best measured on your machine too.
- The reader-check line from `make training-data` (labelled parts no reader can read), so it's clear nothing new went unread.

## Acknowledgements

time-whisper stands on the work of others, with thanks:
[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html), kotlinx-datetime and kotlinx-coroutines by JetBrains;
[PyTorch](https://pytorch.org); Hugging Face [Transformers](https://github.com/huggingface/transformers),
[Tokenizers](https://github.com/huggingface/tokenizers) and [swift-transformers](https://github.com/huggingface/swift-transformers);
the tagger's base model, [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2)
from [Sentence Transformers](https://www.sbert.net), built on Microsoft's [MiniLM](https://github.com/microsoft/unilm/tree/master/minilm);
and Apple's [Core ML](https://developer.apple.com/documentation/coreml) and [coremltools](https://github.com/apple/coremltools).

## License

MPL-2.0. Dependencies are listed in `THIRD_PARTY_NOTICES.txt`.

## Preparing GitHub releases

The intended repositories are `dayedge/time-whisper` (this library and training) and
`dayedge/time-whisper-model` (independent Swift model products). Neither model weights nor
XCFrameworks belong in Git. macOS 15+, Intel and Apple Silicon are the initial Swift targets.
JVM publication is a separate task.

`make package` stages a local package in `build/distributions/package` and writes a versioned
`TimeWhisperCore-<version>.xcframework.zip`, preserving framework symlinks. It does not publish
to Maven Local. Run `python3 scripts/smoke_package.py build/distributions/package` to compile
and run a fresh consumer. Dependencies may be fetched during builds; inference stays offline.

The root `Package.swift` remains a local-development manifest. Release preparation generates
a remote-artifact manifest with the real ZIP checksum. The workflow commits that manifest
on a release-only commit and tags it; it does not change `main`. Release tags keep the Kotlin,
training and evaluation sources, so a model can be qualified against that exact library tag.
Use `main` for Kotlin development rather than editing a published tag's downloaded core.

After reviewing and committing the preparation locally, the following are **manual future steps**;
they are not run by the packaging tools. Authenticate with `gh auth login` first. Create the
public repository only if it does not already exist, then push the reviewed source separately:

```sh
gh repo create dayedge/time-whisper --public --description 'On-device natural-language tasks and events for Kotlin and Swift'
git remote add origin https://github.com/dayedge/time-whisper.git
# Push the reviewed main branch yourself when ready.
```

To prepare library `0.7.0`, use a clean source checkout whose Gradle version is `0.7.0`:

```sh
python3 scripts/release.py qualify --version 0.7.0 --model build/tagger-coreml
gh release create 0.7.0 --repo dayedge/time-whisper --draft --target "<pushed-source-commit>" --title 'TimeWhisper 0.7.0' --notes 'Library release; model distributed separately.'
gh release upload 0.7.0 build/release/qualification.json --repo dayedge/time-whisper
gh workflow run release.yml --repo dayedge/time-whisper -f version=0.7.0 -f source_ref="<pushed-source-commit>" -f qualification_sha256="<printed-sha256>"
```

Qualification runs `make check` with both language corpora and the supplied model, including
Core ML parity tests. Corpus gates force system spelling on, even if `SPELLING=` is inherited
or supplied on the command line. Only `make evaluate SPELLING=` is a spelling-off diagnostic
comparison; it cannot qualify or accept a release. The qualification report records system
spelling explicitly and is a local attestation bound to the exact source commit and
model hashes; it is not a substitute for CI's independent library checks. Never hand-edit it.
The workflow requires that report, an existing draft release and an unused version tag. It
builds and smoke-tests the local package, uploads the binary ZIP/checksum and prepares the
release-only tag. Uploads may replace assets only while the release is still a draft.

The draft's binary download URL cannot be tested as a normal anonymous SwiftPM dependency
until publication. Before publishing, the workflow tests the same artifact through a local
manifest and validates the generated remote manifest. After publication, verify a clean
consumer against the actual remote tag before announcing it.

Review the workflow and draft, then publish explicitly:

```sh
gh release edit 0.7.0 --repo dayedge/time-whisper --draft=false
```

Never overwrite a published asset or move its tag. If a preparation run fails before a tag
exists, it can be retried against the draft. If it fails after pushing the tag, finish/review
that draft manually; the workflow deliberately refuses to retag. GitHub Actions must have
permission to write repository contents. No cross-repository write token is needed.

For model releases, `training/export.py` also writes `export.json`, recording languages,
sequence length and quantization. Export each variant separately; `--languages en,pl` is
currently the All product, `--languages en` the English product. `--all-pieces` controls
vocabulary trimming, not the name of the All product. Model qualification and release commands
live in the sibling model repository. Publish the library first, then qualify the models
against its exact tag. Library and model version numbers may diverge afterward.
