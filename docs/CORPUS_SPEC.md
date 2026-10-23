# Specification: generating a correctness corpus for time-whisper

Give this whole document to the model that generates cases. It defines what to produce, the fixed world the cases live in, the conventions that decide the right answer, and the output format.

## Goal

Produce sentences as a person types them into the entry field of a calendar or to-do application, together with the output a correct parser must return. The expected output must come from the meaning of the sentence, never from guessing what some parser would do.

Work in **groups**. For each group:

1. Decide the intended result first (a task, an event, both, or nothing).
2. Write 3 to 6 different sentences that all mean exactly that result.

Every sentence in a group shares one expected output. If two sentences would need different answers, they belong in different groups.

Target: about 700 groups, about 3,000 sentences.

## The fixed world

- **Now:** Wednesday 2026-09-23, 12:00, time zone UTC.
- **Task lists:** Work (`work`), Personal (`personal`), Home (`home`).
- **Calendars:** Team (`team`), Family (`family`).
- **Language:** English. Month-first numeric dates (`10/11` is 11 October). Weeks start on Monday. The weekend is Saturday and Sunday.
- **Default event length:** 60 minutes.

Calendar for date arithmetic. Use it; do not compute weekdays from memory.

```
Mon 21 Sep   Tue 22 Sep   Wed 23 Sep (today)   Thu 24 Sep   Fri 25 Sep   Sat 26 Sep   Sun 27 Sep
Mon 28 Sep   Tue 29 Sep   Wed 30 Sep           Thu  1 Oct   Fri  2 Oct   Sat  3 Oct   Sun  4 Oct
Mon  5 Oct   Tue  6 Oct   Wed  7 Oct           Thu  8 Oct   Fri  9 Oct   Sat 10 Oct   Sun 11 Oct
Mon 12 Oct   Tue 13 Oct   Wed 14 Oct           Thu 15 Oct   Fri 16 Oct   Sat 17 Oct   Sun 18 Oct
```

September has 30 days, October 31. For dates outside this table, state the weekday in `basis` and double-check it.

## Conventions that decide the answer

These are the product's rules. Apply them exactly. If a sentence depends on something not listed here, put the group in tier `stretch`.

### Dates
- `today` is 2026-09-23, `tomorrow` 2026-09-24, `yesterday` 2026-09-22.
- A bare weekday, or `this <weekday>`, is the next such day counting today: `Friday` is 2026-09-25, `Wednesday` is 2026-09-23.
- `next <weekday>` is that weekday in the next calendar week: `next Friday` is 2026-10-02, `next Monday` is 2026-09-28.
- `in N days/weeks/months` counts from today: `in 3 days` is 2026-09-26, `in a week` is 2026-09-30.
- `next week` is Monday 2026-09-28. `next month` is 2026-10-01.
- A month and day without a year is the next occurrence: `Oct 5` is 2026-10-05, `May 4` is 2027-05-04.
- ISO dates (`2026-10-01`) mean exactly that date. An impossible date (`2026-02-30`) means the whole sentence is rejected.

### Times
- A time with am/pm or in 24-hour form is taken as written: `5pm` is 17:00, `14:30` is 14:30, `09` is 09:00.
- A bare hour from 1 to 7 means afternoon: `at 5` is 17:00. A bare hour from 8 to 12 is as written: `at 10` is 10:00.
- `noon` is 12:00, `midnight` 00:00.
- `tonight` is today at 20:00, approximately; an exact time in the same sentence wins. `morning`, `afternoon` and `evening` name no hour: right after a day (`tomorrow morning`, `Friday evening`, `this afternoon`) they belong to it and add nothing; anywhere else they are ordinary words and stay in the title (`Morning planning`).
- A time with no day is today if that time is still ahead of 12:00 now, otherwise tomorrow: `at 5pm` is today 17:00, `at 10am` is tomorrow 10:00.

### Task or event
Decide the candidates and their order with these rules, first match wins:

1. A leading `+`, or `t:task` / `type:task`, forces **task only**. `t:e` / `type:event` forces **event only**. The marker is not part of the title.
2. An end time, a duration, a range of days, or "all day" is an event signal. A priority is a task signal.
   - Both present: **task, then event**.
   - Only the event signal: **event only**.
   - Only the task signal, or no date and no time at all: **task only**.
3. Otherwise both are offered. A place or a calendar tag favours the event; a list tag, a leading `todo`/`task`, or `remind me to` favours the task. If neither or both sides are favoured, a stated time puts the event first and no time puts the task first.

### Fields
- **Title:** the sentence without the recognised parts and the small words that attach them (`at`, `on`, `by`, `for`, `from`, `to`, `until`, `due`). Keep the user's casing, capitalise the first letter. Names of people and things stay in the title.
- **Task due:** a date, or a date and time when a time is given.
- **Event time:** start and end. With no end and no duration, the end is start plus 60 minutes. With a date and no time, the event is all-day on that date.
- **List / calendar:** `#work`, `#personal`, `#home` set a task list; `#team`, `#family` set an event calendar. Matching ignores case. An unknown tag such as `#groceries` is not recognised and stays in the title.
- **Priority:** `!` low, `!!` medium, `!!!` or more high. `urgent` and `important` are high. `high priority`, `medium priority`, `low priority` as named.
- **Place:** `@Office`, `@"Main Office"`, or `at`/`in` followed by a capitalised name. The event carries it as `location`. A task keeps the place words in its title.
- **Repeat:** `daily`, `weekly`, `monthly`, `yearly`, or `every …`: `every day`, `every 2 weeks`, `every other week`, `every Monday`, `every Mon and Wed`, `every weekday` (Monday to Friday), `every month on the 15th`. With no date given, the first occurrence is the next matching day counting today. One weekday or day of the month, every month: `the last Friday of every month`, `every month on the 2nd Tuesday`, `the last day of every month`; without a word for every (`the last Friday of the month`) it is one day. Where a repeat starts and stops: `starting in November`, `until December 15`, `through March` (its last day). An ordinal weekday without the month (`every third Sunday`) is ambiguous and not read; `every second Friday` is every other week.
- **Reminder:** `remind me 10 min before`, `alert me 1 hour before`, `notify me 2 days before` give minutes before (10, 60, 2880). `remind me at due time` is 0.

## Tiers

Tag every group with one tier.

| Tier | What it is | Share |
| --- | --- | ---: |
| `core` | Covered fully by the conventions above. One right answer. | 55% |
| `stretch` | Reasonable input the conventions do not settle, or wording a parser may not support yet (`the Friday after next`, `end of day`, `in a fortnight`). Give your best single answer and explain it in `notes`. | 15% |
| `trap` | Words that look like dates, times or markers but are not. The answer keeps them in the title, or rejects the sentence. | 20% |
| `ambiguous` | Two readings a reasonable person could intend. List every acceptable answer. | 10% |

Trap ideas: names (`call May tomorrow`, `lunch with April`, `email June`), titles (`Read Friday Night Lights`, `watch Saturday Night Live`), numbers that are not times (`Chapter 3-5`, `iPhone 15`, `Room 101`, `1:1 with Sam`, `buy 2 tickets`, `Q3 report`, `5k run`, `version 2.0`), words like `second opinion`, `march to the office`, `may need a refund`, and input that is only a date with nothing to do (`tomorrow`, `next Friday at 10`), which must be rejected.

## Coverage within `core`

Spread groups across these, and combine them: about a third of groups should use three or more features together.

- Plain task, no date
- Date only; time only; date and time
- Relative dates (`in N days`, `next week`), weekdays with and without `next`, month-day, ISO and slash dates
- Time ranges (`10-11am`, `from 3 to 5pm`, `between 2 and 4`), durations (`for 30 minutes`, `1h30m`, `1.5h`)
- Day ranges (`Mon-Wed`, `Friday to Sunday`, `Oct 3-5`), all day
- Lists, calendars, priorities, forced kind
- Places
- Repeats, with and without a start date
- Reminders

## Writing the sentences

- Vary word order: the date first, last, in the middle.
- Vary wording: `tomorrow`, `tmrw`; `10am`, `10 am`, `10:00`; `Friday`, `fri`.
- Mix casing. Most people type lowercase; some capitalise. In `core`, write places capitalised.
- Titles must be realistic and varied: errands, work, health, family, travel, money. Do not reuse a title in more than three groups.
- Keep sentences between 2 and 14 words.
- No typos in `core`. Up to 30 `stretch` groups may test typos (`tomorow`, `wendsday`).
- No two sentences in the corpus may be identical.

## Output format

JSON Lines: one group per line, no surrounding array, no commentary.

```json
{"id":"g0001","tier":"core","features":["date","time","duration","repeat"],"basis":"tomorrow = Thu 2026-09-24; 10:00 + 30 min = 10:30; weekday = Mon-Fri","expect":[{"kind":"event","title":"Standup","start":"2026-09-24T10:00","end":"2026-09-24T10:30","repeat":{"every":"week","interval":1,"weekdays":["mon","tue","wed","thu","fri"]}}],"inputs":["Standup tomorrow 10am for 30 minutes every weekday","every weekday standup tmrw 10:00-10:30","standup tomorrow from 10 to 10:30 am every weekday"]}
{"id":"g0002","tier":"core","features":["date","list","priority"],"basis":"Friday = 2026-09-25; !! = medium; priority makes it task only","expect":[{"kind":"task","title":"Pay rent","due":"2026-09-25","list":"home","priority":"medium"}],"inputs":["Pay rent Friday #home !!","!! #home pay rent on fri","pay rent this friday #Home !!"]}
{"id":"g0003","tier":"trap","features":["name"],"basis":"May is a person; tomorrow = 2026-09-24; no time, so task first","expect":[{"kind":"task","title":"Call May","due":"2026-09-24"},{"kind":"event","title":"Call May","allDay":"2026-09-24"}],"inputs":["call May tomorrow","tomorrow call May"]}
{"id":"g0004","tier":"trap","features":["date-only"],"basis":"nothing to do, only a date","expect":[],"inputs":["next Friday","tomorrow at 10"]}
{"id":"g0005","tier":"ambiguous","features":["name","month-day"],"basis":"May 10 the date, or May the person and a stray 10","expect":[{"kind":"task","title":"Call","due":"2027-05-10"}],"alternatives":[[{"kind":"task","title":"Call May 10"}]],"notes":"Either reading is acceptable.","inputs":["call May 10"]}
```

Field rules:

- `expect` is the ranked list of candidates. An empty list means the sentence must be rejected.
- `alternatives` appears only in tier `ambiguous`: a list of other acceptable candidate lists.
- `basis` is required. Show the date and time arithmetic in a few words.
- `notes` is required for `stretch` and `ambiguous`.
- Times are local wall-clock values, `YYYY-MM-DDTHH:MM`. Dates are `YYYY-MM-DD`.
- Omit a field that is absent; do not write null or empty strings. `priority` is omitted when none.

Task candidate fields: `kind`, `title`, `due` (date or date-time), `list`, `priority` (`low`/`medium`/`high`), `repeat`, `reminder` (minutes before).

Event candidate fields: `kind`, `title`, then either `start` and `end`, or `allDay` (one date) or `allDay` and `allDayEnd` (first and last day, both inclusive), `calendar`, `location`, `repeat`, `reminder` (minutes before).

`repeat`: `every` is `day`, `week`, `month` or `year`; `interval` is a whole number, 1 when not stated; `weekdays` is a list of `mon` to `sun` when named; `monthDays` is a list of day numbers when named; a weekday with its place in the month is written with it in front, `-1fri` for the last Friday and `2tue` for the second Tuesday; `until` is the last date (inclusive) when the text names one.

## Before you output a group, check

1. Would every sentence in `inputs` lead a careful human to exactly `expect`? If one would not, drop it or move it.
2. Is each date consistent with the calendar table and with `basis`?
3. Does the candidate order follow the task-or-event rules?
4. Is the title what remains of the sentence, with the user's words intact?
5. Is the tier right? Anything that needed a judgment call is not `core`.

Generate in batches of about 50 groups, one coverage area at a time, and number ids continuously.
