# Providers

time-whisper reads dates and times with its own readers and corrects nothing by itself. Three things come from the platform, from another library or from a trained model:

- a **tagger**, which says what each part of the text is for: the title, wrapping words, a day, a time, a repeat, a place. It is required;
- a **spell checker**, so that a misspelt date word can still be read;
- a **date finder**, a second reader for a day or a time the readers cannot read.

All are the host application's responsibility: the library defines a small interface, the application decides what stands behind it, and the library never ships or downloads one.

## Spell checker

### What it is for

Someone types "dentist frida at 10". The lexicon knows "friday" and not "frida". With a spell checker, the parser asks for suggestions for the unknown word, and if they point to exactly one word the lexicon knows, it reads that word.

### How the parser uses it

The provider only suggests. The parser decides, conservatively:

- Only a word the tagger puts in a part about time (a day, a time, an end, a length, a deadline, a repeat or its bounds) is asked about, and only when it could be a misspelt word: letters only, four or more of them, not already known. A name in a title never is: "Marc" is never turned into "March".
- A correction is taken only when exactly one of the suggestions is a word the lexicon gives a meaning.
- Title words are never rewritten. The title is always what was typed.
- Each word is asked about once; the answer is kept until `releaseResources`.

A provider that fails makes the request fail. It is never silently treated as "no suggestions".

### What to use

| Platform | Default | Alternatives |
| --- | --- | --- |
| macOS | The system spell checker (`NSSpellChecker`), in the language of the configuration. Nothing to set up. | Turn it off with `NoSpellChecker`, or supply your own. |
| JVM and other targets | None. | Supply one: Morfologik (strong for Polish), LanguageTool, or a Hunspell binding. |

The interface is one method:

```kotlin
public interface SpellChecker {
    public suspend fun suggestions(word: String, language: String): List<String>
    public suspend fun close() {}
}
```

**macOS, turning it off or replacing it (Swift):**

```swift
// No spelling correction at all.
let strict = try TimeWhisper(spellCheckerFactory: { NoSpellChecker() })

// Your own checker.
struct MyChecker: SpellChecker {
    func suggestions(for word: String, language: String) async throws -> [String] { /* … */ [] }
}
let custom = try TimeWhisper(spellCheckerFactory: { MyChecker() })
```

**Kotlin/JVM, a sketch over Morfologik.** Morfologik's speller suggests replacements from a compiled dictionary, and such dictionaries exist for Polish. The adapter is a few lines. This shows the shape of an adapter; it is not shipped or tested with the library, so check the calls against the Morfologik version you use.

```kotlin
class MorfologikChecker(private val dictionaries: Map<String, Path>) : SpellChecker {
    // One speller per language, loaded when first asked for.
    private val spellers = mutableMapOf<String, Speller?>()

    override suspend fun suggestions(word: String, language: String): List<String> = withContext(Dispatchers.IO) {
        val speller = spellers.getOrPut(language) { dictionaries[language]?.let { Speller(Dictionary.read(it), 1) } }
        speller?.findReplacements(word).orEmpty()
    }
}

val parser = TimeWhisper(
    Configuration(language = "pl"),
    spellCheckerFactory = { MorfologikChecker(mapOf("pl" to Path.of("pl_PL.dict"))) },
)
```

The `language` argument is the one the vocabulary declares for spelling ("en_US", "pl"). A checker that does not know a language returns an empty list.

### One limit worth knowing

Inflected languages often produce several suggestions that are forms of one word: for Polish "środe" a checker offers "środa", "środę" and "środy". The parser sees three different suggestions and takes none. Typos of this kind are not corrected today.

## Date finder

### What it is for

A date finder is any component that can find dates in text: the operating system's data detector, or a general-purpose library such as Chrono, Natty or Duckling. time-whisper does not try to match their coverage. It deliberately leaves alone vague words, holidays and the long tail of rare phrasings. A host that wants some of that can plug one of those readers in for the parts the tagger labels a day or a time and the readers cannot read, while the title, the task-or-event decision and all the metadata still come from time-whisper.

Typical reasons to add one:

- **Broader coverage on purpose:** holidays, "end of the month after next", phrasings in a language's long tail.
- **A stop-gap** for a construction that has not been written as a rule yet.

### How the parser treats it

The finder is asked only about a part the tagger labelled a day or a time, and only when no reader could read it. It gets the words of that part, not the whole text, so it cannot read a date out of a title:

- One match that covers the part's words is taken; anything else is dropped, and the words stay in the title.
- A match that states no day, month, year or time says nothing and is dropped.
- A match must lie inside the text it was given; a range outside it is a provider failure.
- A finder that does not know the requested language returns no matches.

Everything a finder may depend on arrives in the request: the language, the reference time and the time zone. It must not read the device's clock or locale, or results stop being reproducible.

### When not to add one

General date finders are generous readers: given "sometime soon" or "after the trip" they may still return a date. Since they now see only parts the tagger says are about time, and only those no reader could read, they can no longer read dates out of titles; but a vague part they turn into a date is a guess. Add a finder for phrasings your users write and the readers demonstrably miss, and check the result on your own examples.

### What to use

| Platform | Options |
| --- | --- |
| macOS | The system data detector: `DataDetection` on macOS 26 and later, `NSDataDetector` before that. |
| JVM | A Java date library such as Natty; Chrono run in an embedded JavaScript engine (GraalJS, QuickJS). |
| Any | Your own, for a handful of phrases specific to your product. |

The interface:

```kotlin
public interface DateFinder {
    public suspend fun find(text: String, request: DateFinderRequest): List<DateMatch>
    public suspend fun close() {}
}
```

A `DateMatch` is a range in the text and, for its start and optional end, a local date and time in the request's zone. The `dayStated`, `monthStated` and `yearStated` flags say whether the text wrote that part ("at 5" states no day). A finder that cannot tell leaves them unset, and the parser then takes the part as stated.

**macOS, the system data detector (Swift).** This is working code; `examples/swift-evaluate/main.swift` uses it to run the corpus (`make evaluate-apple`).

```swift
@available(macOS 26.0, *)
struct SystemDateFinder: DateFinder {
    func find(_ text: String, request: DateFinderRequest) async throws -> [DateMatch] {
        var options = DataDetector.Options()
        options.documentDate = request.referenceDate
        options.documentTimeZone = request.timeZone
        options.documentLanguageCode = Locale.LanguageCode(request.language)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = request.timeZone
        // The detector reports an instant; the parser wants the local date and time it stands for.
        func parts(_ date: Date, allDay: Bool) -> DateMatchComponents {
            let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            return DateMatchComponents(date: CivilDate(year: c.year!, month: c.month!, day: c.day!),
                                       time: allDay ? nil : CivilTime(hour: c.hour!, minute: c.minute!))
        }
        var found: [DateMatch] = []
        for await match in text.dataDetectorMatches([.calendarEvent], options: options) {
            guard let range = match.range, case let .calendarEvent(event) = match.details, let start = event.startDate else { continue }
            let utf16 = NSRange(range, in: text)
            found.append(DateMatch(range: SourceRange(offset: utf16.location, length: utf16.length),
                                   start: parts(start, allDay: event.allDay),
                                   end: event.endDate.map { parts($0, allDay: event.allDay) }))
        }
        return found
    }
}

let parser = try TimeWhisper(dateFinderFactory: { SystemDateFinder() })
```

The system detector cannot say which parts of a date were written, so the flags stay unset.

**Kotlin/JVM, a sketch over a Java date library.** Natty returns groups of dates with their position in the text, which maps directly onto a `DateMatch`. As with the spelling sketch, this shows the adapter's shape and is not shipped or tested with the library.

```kotlin
class NattyFinder : DateFinder {
    override suspend fun find(text: String, request: DateFinderRequest): List<DateMatch> {
        if (request.language != "en") return emptyList()          // Natty reads English only
        val zone = TimeZone.of(request.timeZoneId)
        val parser = com.joestelmach.natty.Parser(java.util.TimeZone.getTimeZone(request.timeZoneId))
        val reference = java.util.Date(request.referenceEpochMilliseconds)
        return parser.parse(text, reference).mapNotNull { group ->
            val found = group.dates.firstOrNull() ?: return@mapNotNull null
            val local = Instant.fromEpochMilliseconds(found.time).toLocalDateTime(zone)
            DateMatch(
                range = SourceRange(group.position - 1, group.text.length),  // Natty counts columns from 1
                start = DateMatchComponents(
                    date = CivilDate(local.year, local.month.number, local.day),
                    time = if (group.isTimeInferred) null else CivilTime(local.hour, local.minute),
                    dayStated = !group.isDateInferred,
                ),
            )
        }
    }
}

val parser = TimeWhisper(dateFinderFactory = { NattyFinder() })
```

**Chrono in a JavaScript engine.** Chrono is a JavaScript library, so on the JVM it needs an engine such as GraalJS or QuickJS: load the Chrono bundle once, call `chrono.parse(text, referenceDate)`, and translate each result's `index`, `text`, `start` and `end` into a `DateMatch`. Chrono tells which components were written (`isCertain("day")`), which maps onto the `Stated` flags. Keep every engine call on one thread, and create the engine lazily, since it is by far the heaviest part.

## Tagger

### What it is for

Someone types "could you squeeze in coffee with Ilse thursday at 4pm, thanks". The tagger says that "could you squeeze in" and "thanks" only wrap the request, that "coffee with Ilse" is the title, "thursday" a day and "at 4pm" a time, and that the line asks for an event. In "remind me to watch Saturday Night Live" it says "Saturday" is part of the title, so no date is read out of it. Where each thing is, and what it is for, are judgements about open-ended language that no word list keeps up with, least of all in an inflected language, so the lexicon does not try: it only says what the words inside a part mean.

### How the parser uses it

The tagger labels each word with what it is for, with a confidence: `TITLE`, `WRAPPER`, or one of `DAY`, `CLOCK`, `END`, `LENGTH`, `DUE`, `REPEAT`, `STARTS`, `ENDS`, `PLACE`, `LIST`, `CALENDAR`, `PRIORITY`, `REMINDER`, `KIND`. Neighbouring words with one label are one part. It also scores the whole text as a task, an event or nothing, and says how likely it states each priority. It never supplies a value. The parser:

- reads each part with the reader for its label, and puts a part no reader can read back into the title, as typed;
- takes words tagged `WRAPPER` off the title, wherever they stand;
- asks the spell checker only about words in parts about time;
- takes a stated priority in words from the tagger's urgency, when a part is labelled `PRIORITY` and the level is clear; `!`, `!!`, `!!!` are read as they are;
- offers nothing when the tagger is at least 0.9 sure the text asks for nothing and no kind, list, priority, repeat or reminder was stated; offers a task for a dateless line it reads as a task (0.5 or more); and lets a clear reading (0.3 apart) order the task and the event, beside places, calendars, lists and deadlines.

A tagger that fails makes the request fail. Words it leaves out (past the length it reads) are title.

### What to use

`training/` holds everything to build one: a generator of tagged sentences, a PyTorch training script, and an export to Core ML. `training/prompts/labels.md` is the contract for the labels. On Apple platforms the Swift package runs the exported model:

```swift
import TimeWhisper

let parser = try TimeWhisper(model: modelFolder)          // the folder training/export.py wrote
```

`CoreMLTagger` loads the model on its first request; `prepare()` loads it earlier, when the host expects text soon (a
search field opening, say). The model is compiled from its package once per process. Give it `compiledModelCache:`, a
folder of the host's own such as one in its Caches directory, and the compiled model is kept there across launches:
compiled again only when the model's contents or the system's build change, with older copies removed.

```swift
let tagger = CoreMLTagger(folder: modelFolder, compiledModelCache: cachesFolder.appendingPathComponent("TimeWhisper"))
let parser = try TimeWhisper(taggerFactory: { tagger })
```

Elsewhere, implement `Tagger` over any runtime, for example ONNX Runtime on the JVM, with the same tokenizer the model was trained with, and map the model's label names onto `TokenLabel` (`T` is `TITLE`, `W` is `WRAPPER`, the others by name).

## Lifetime

- Nothing is created when a `TimeWhisper` is constructed. A provider is created the first time a request needs it.
- `releaseResources()` closes the providers and forgets cached readings; the next request creates them again. Use it during idle periods.
- `close()` is final and may be called more than once.
- Requests on one parser run one at a time. Cancelling a request discards its result; a provider should finish its work promptly when cancelled.
- In Swift the protocols are `async`, and a `DateFinder` receives a `Date` and a `TimeZone`.
