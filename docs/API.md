# API behavior

The public surface is small: the `TimeWhisper` class, the values it takes and returns, and two provider interfaces. Everything is the same in Kotlin and in the Swift wrapper, which adds only native types and `async`.

## What goes in

| Value | What it carries |
| --- | --- |
| `Configuration` | `language` ("en" by default, "pl" for the first-draft Polish). Optionally `dateOrder` for numeric dates such as "5/10"; left unset, the language's own habit applies (month first in English, day first in Polish). `weekStartsOn` (Monday), `weekendDays` (Saturday, Sunday), `defaultEventMinutes` (60), `reminderTime` (09:00: the time of a reminder that names only a day, "remind me on the 23rd"). |
| `ParseContext` | The reference time, the name of the time zone, and the lists and calendars a `#tag` may name, each with an id, a name and a kind. |

Nothing else influences a result. The device's clock, locale and default time zone are never read, so the same text, configuration and context always give the same answer. The one exception is a spell checker, if one is used; pass `NoSpellChecker` for fully reproducible results.

## Tasks and events: `parse`

`parse(text, context)` returns an ordered list of candidates. An empty list means there is nothing to offer, which is a normal outcome. A failing provider throws; it is never reported as "nothing found".

| Type | Fields |
| --- | --- |
| `Candidate.Task` | `spec: TaskSpec`: `title`, `due` (a date, or a moment when a time was given), `listId`, `priority`, `recurrence`, `reminder` (minutes before the due time, or a moment: "remind me thursday at 9am") |
| `Candidate.Event` | `spec: EventSpec`: `title`, `time` (all-day dates, or a timed start and exclusive end), `calendarId`, `location`, `recurrence`, `reminders` |
| both | `spans`: the parts of the text that were recognised, as UTF-16 ranges, for highlighting |

In Swift a candidate's `content` is `.task(TaskSpec)` or `.event(EventSpec)`.

**What is read.** The tagger says where each thing is: a day, a time, an end, a length, a deadline, a repeat and where it starts and stops, a place, a list or calendar, a priority, a reminder, a forced kind. The parser reads each such part: clocks and ranges of clocks, spoken times ("half past 3", "eight thirty"), durations, relative days and counted offsets ("in 3 days", "in 2 hours"), weekdays, month-and-day dates, ISO and numeric dates, ranges of days, repeats, reminders, priorities, `#tags` and named lists and calendars, places and time zones. A part it cannot read stays in the title as typed, and so does every word the tagger calls title; words it says only wrap the request ("can you remind me to", "thanks") leave the title. A host's date finder may read a day or a time part the readers cannot.

**Task or event.** The order of the list is the library's preference, decided by fixed rules; the tagger's scores are one of their inputs. There is no confidence value in the result.

- `+` or `todo` at the start, `t:task` or `type:event` forces one kind.
- A tagger sure the text asks for nothing ("already paid the bill yesterday") means nothing is offered, unless a kind, list, priority, repeat or reminder was stated. A line with no date and nothing stated is offered as a task when the tagger reads it as one ("buy groceries"), and not otherwise ("groceries").
- Something that takes up time (an end, a duration, a range of days, "all day") makes it an event; a priority makes it a task; both together offer the task first, then the event.
- Otherwise both are offered. A place, a calendar, or a tagger reading the text clearly as an event puts the event first; a list, a deadline ("by friday"), or a tagger reading it clearly as a task puts the task first; failing those, a stated time puts the event first.

**Lists and calendars.** `#work` counts only when it matches a destination the caller supplied; an unknown tag stays in the title. The library never discovers calendars or lists.

**Places.** `@Office`, a quoted name after "at" or "in", or a capitalised name after them. An event carries the place as `location`; a task keeps the words in its title.

**Repeats** cover days, weeks, months and years with an interval, named weekdays, days of the month, one weekday of the month (`WeekdayRule.ordinal`: the second Tuesday is 2, the last Friday -1), and a last day (`until`) when the text names one, says how long ("for 6 weeks") or how many times ("6 times"). Where a repeat starts ("starting in November") sets its first occurrence. An ordinal weekday without the month ("every third Sunday") is ambiguous and left unread; "every first Sunday" and "every last Friday" are of each month. This is not a full implementation of calendar recurrence rules.

## Dates on their own: `parseTemporal`

`parseTemporal(text, context)` answers "what date is this?" for text that is nothing but a date expression: "next week", "tomorrow at 9", "friday to sunday", "2026-10-01". It returns a `TemporalSpec` with a start, an exclusive end and a granularity, or nothing when any word is not part of the date.

- A period keeps its length: "next week" is seven days.
- A point in time has equal start and end, and `hasExplicitTime` set.
- `A..B` gives the range from the start of A to the end of B.

## Dates inside other text: `findTemporal`

`findTemporal(text, context)` is made for search and filtering. It returns the first date in the text and the text without it: "standup tomorrow" gives tomorrow and "standup".

- The small words that attach the date leave with it, exactly as they would leave a title: "report by friday" leaves "report". A time right after the day leaves too, though the range returned is the day.
- A month name alone is a date here, in the current year ("March", asked in September, is last March), unless a word before it says otherwise ("next March"). In `parse` a bare month is not a date, because it is so often a name.
- A month name inside another word ("Mayday") is not a date.

Query languages are the application's business: one with operators such as `before:` or `between:A..B` splits its own query and passes each value to `parseTemporal`.

## How dates and times are resolved

- **Weekdays.** A bare "Friday" is the coming Friday, counting today. "next Friday" is the Friday of the next calendar week, by the configured first day of the week.
- **Dates without a year** are the next time they come round. A year named beside the date replaces that: "May 4 this year".
- **Bare hours.** After a time word ("at 5") an hour from 1 to 7 is read as afternoon, in both languages. A time written with minutes follows the language: "7:30" is taken as written in Polish, where the 24-hour clock is normal.
- **Time zones.** A zone written right after a clock makes it a time there: an abbreviation ("3pm EST", "11-12 PT", "15:00 CET") or an offset from UTC ("10:00 UTC+2", "10am GMT+0900", "10:00 -05:30"). An abbreviation names a zone, so the offset is the one that zone has on that date. "IST" is not read, because it names three zones. The same letters anywhere else are ordinary words.
- **Results are in the caller's zone.** A `Moment` has epoch milliseconds, the request's zone name and the offset at that instant. A `CivilDate` is a calendar date with no time or zone attached.
- **Clock changes.** Calendar days advance as local dates, so a range of days can span 23 or 25 hours. "in 2 hours" counts elapsed time. A local time that does not exist moves forward; one that occurs twice is the earlier.
- **Dates that do not exist** ("February 30", "2026-02-30") never roll over into another month; nothing is offered.
- All range ends are exclusive.

## Providers

A spell checker and a date finder can be supplied by the host; both are optional. See [Providers](PROVIDERS.md) for what they are for, how their results are used, examples for macOS and the JVM, and their lifetime.

## Lifetime

Constructing a `TimeWhisper` creates nothing heavy. `releaseResources()` drops what the providers and the word cache hold and allows them to be created again on the next request. `close()` is final and may be repeated. Requests on one instance run one at a time; cancelling a request discards its result.
