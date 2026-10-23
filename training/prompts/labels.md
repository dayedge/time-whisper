# Labels

Every word of a sentence gets one label. A word is what lies between spaces; punctuation stays on its word and takes its label.
The label says what the word is **for**: the parser reads each run of words with one label by that role, and puts any run it
cannot read back into the title as typed. So a label never has to be safe, only true.

| Label | What it marks | Examples |
| --- | --- | --- |
| `T` title | What the user wants done or attended | `call the landlord`, `a quick call with Ilse`, `Twelve Angry Men`, `table for 6` |
| `W` wrapper | Words around the request that are not its content: asking, needing, reminding, politeness, hedging, chat, context; and the small word that attaches a part to the sentence | `can you remind me to`, `i really need to`, `please schedule`, `thanks`, `lol`, `because the brakes are shot`; `by`, `at`, `on`, `for`, `until`, `starting` |
| `DAY` | The day or days the entry is on | `tmrw`, `the 15th`, `next fri`, `mon-wed`, `all day saturday`, `11.12.2026`, `this weekend`, `in 3 days` |
| `CLOCK` | When on that day: a time, or a range of times | `3pm`, `9`, `3-5pm`, `10 to 11`, `2 and 4`, `in 2 hours`, `noon`, `7 sharp` |
| `END` | An end stated apart from the start | `5`, `friday`, `6pm`, `seven` |
| `LENGTH` | How long the event lasts | `45 min`, `1h30m`, `a 30-minute`, `an hour` |
| `DUE` | A deadline: the task is done by then | `friday`, `5pm`, `eow`, `the 14th`, `friday at 5pm`, `within 30 days` |
| `REPEAT` | How often, with its exceptions | `every other monday`, `daily`, `tuesdays`, `the last friday of each month`, `except fridays` |
| `STARTS` | When a series starts | `next week`, `oct 5`, `monday`, `1 nov to 1 dec` |
| `ENDS` | When a series stops, or how many times it runs | `may`, `six weeks`, `march`, `6 times`, `the next four`, `end of nov` |
| `PLACE` | Where the event happens | `Café Nero`, `@Office`, `Zoom`, `Room 3B` |
| `LIST` | A task list, by tag or by name | `#work`, `my errands list`, `the home list` |
| `CALENDAR` | A calendar, by tag or by name | `#family` (a calendar), `the team calendar`, `the family cal` |
| `PRIORITY` | Urgency stated in words or marks | `urgent`, `!!`, `asap`, `p2`, `no rush`, `an important` |
| `REMINDER` | A reminder setting | `remind me 10 min before`, `alert me an hour before`, `with a reminder the day before` |
| `KIND` | A mark that forces task or event | `todo`, `t:task`, `type:event`, `+`, `task:` |

## Rules where people disagree

- **The small word that attaches a part is `W`**, outside the part: `[W by] [DUE friday]`, `[W at] [CLOCK 3pm]`,
  `[W on] [DAY monday]`, `[W for] [LENGTH 45 min]`, `[W starting] [STARTS next week]`, `[W until] [ENDS may]`,
  `[W at] [PLACE Café Nero]`, `[W to] [LIST my errands list]`, `[W no later than] [DUE the 14th]`, `[W as of] [STARTS monday]`.
  Words that hold one date or time together stay inside it: `[DAY the 5th of october]`, `[REPEAT every week on monday]`,
  `[W by] [DUE friday at 5pm]`, `[REMINDER remind me 2 hours before]`, `[CLOCK 7 sharp]`. `in` before a count says when,
  so it stays: `[DAY in 3 days]`; elsewhere it attaches: `[W in] [DAY november]`. A range keeps its range word between its
  ends: `[W from] [CLOCK 3 to 5]`, `[CLOCK 4pm until 5pm]`. `before` opening where a repeat stops says the day is not
  included, so it stays: `[W stop] [ENDS before dec 1]`, against `[W until] [ENDS dec 1]`; before a deadline it attaches:
  `[W before] [DUE friday]`.
- **A day and a time are two runs**: `[DAY tmrw] [CLOCK 9am]`, `[W on] [DAY the 23rd] [W at] [CLOCK quarter past four]`. A deadline,
  an end and a series bound are one run each, day and time together: `[W by] [DUE friday 5pm]`, `[W until] [END saturday at 6]`.
- **A range is one run**: `[W from] [CLOCK 3 to 5]`, `[DAY mon-fri]`, `[W from] [DAY the 12th through the 14th]`. `END` is only for
  an end said apart from its start: `[W at] [CLOCK 10] [W until] [END 4]`.
- **A bare number is `CLOCK` when it is an hour and `DAY` when it is a day of the month**: `dentist [DAY friday] [CLOCK 11]`,
  `dentist [W on] [DAY the 11th]`. A number that counts things is `T`: `read chapters 3-5`, `table for 6`.
- **`END` against `DUE`**: an event ends (`[W until] [END 5]`), a task is due (`[W by] [DUE 5]`). `before` is a deadline: `[W before] [DUE noon]`.
- **`STARTS` and `ENDS` only bound a repeat.** Without a repeat, `[W from] [DAY monday]` is the day of the event and `[W until] [END friday]` its end.
- **A range of days that bounds a repeat is one `STARTS` run**, its start and its end together:
  `car race [REPEAT every two weeks] [W from] [STARTS 1 nov to 1 dec,] [CLOCK 11-12]`. Said apart, they are two runs:
  `[W starting] [STARTS next wed,] … [W until] [ENDS june 30]`.
- **A year or an edge goes with its date**, in the same run: `[W til] [ENDS nov 15 next year]`, `[DAY march 3rd this year]`,
  `[W till] [ENDS end of nov]`, `[W by] [DUE end of june next year]`.
- **A verb that only announces a series start is `W`**: `[W i'll start] spanish [STARTS next tue] [REPEAT weekly]`. When it
  is the thing done, it is `T`: `start the dishwasher`, `begin chapter 4`; mark the line `trap`.
- **`LENGTH` against `ENDS`**: how long one occurrence lasts (`for 2 hours`) against how long the series runs (`for 6 weeks`).
- **An amount made of several parts is one run**, with what joins its parts and the marker that makes it a time:
  `[DAY in 2 months, 7 days and 3 hours]`, `[DUE within 3 weeks minus a day]`, `[DAY 2 weeks and 3 days ago]`,
  `[W for] [LENGTH 2 hours 30 minutes]`, `[LENGTH an hour and a half]`, `[W for] [ENDS 3 months and 2 weeks]`,
  `[REMINDER remind me 1 day 2 hours before]`. An offset with a day or a longer unit in it is `DAY`, hours and all; one of
  only hours and minutes is `CLOCK`: `[CLOCK in 2 hours 15 min]`. A time said beside it is its own run, and an amount right
  after a date with a start time of its own is the length: `[DAY in 2 months, 7 days,] [LENGTH 3hrs] [CLOCK 9am]`.
  A list of separate amounts is still one run, which the parser does not read: `[DAY in 3 weeks, 7 weeks, 8 weeks]`.
- **`REPEAT` is only how often, with its exceptions**: `[REPEAT every monday] [W starting] [STARTS next week]`,
  `[W for] [ENDS the next six] [REPEAT saturdays]`.
- **Vague words that say when are still time**: `[W sometime] [DAY next month]`, `[CLOCK after lunch]`, `[DAY tomorrow morning]`,
  `[W before] [DUE the trip]`. The parser leaves what it cannot read in the title; a label that hides them would teach the model
  to miss the readable ones beside them.
- **Articles stay in the title**: `the gym bag`, `a quick call with Ilse`.
- **A verb that only asks to put something in the calendar is `W`**: `schedule`, `add`, `put`, `pencil in`, `set up`, `book me in for`. A verb that is the action itself is `T`: `book a table`, `call Bob`, `buy milk`.
- **`remind me to` is `W`; `remind me 10 min before` is `REMINDER`.** The first asks for a task, the second sets a reminder.
- **A reminder at a set time is one `REMINDER` run, the asking words included**, when the entry has its own date or time
  apart from it: `pay the rent [W by] [DUE friday] [REMINDER remind me thursday at 9am]`, `dentist [DAY thu] [W at]
  [CLOCK 3pm,] [REMINDER ping me at 1]`. When "remind me" only asks for the task, its time is the task's:
  `[W remind me] [W at] [CLOCK 6] [W to] call Gran`.
- **`todo`, `+`, `t:task`, `type:event` at the start are `KIND`**: they are commands, not wrappers.
- **A calendar or list by name is `CALENDAR` or `LIST`** (`on the team calendar`, `to my work list`); a generic one is `W` (`in my calendar`, `to my list`).
- **A place inside an action is `T`**: `book a table at Nonna's`, `confirm the Airbnb in Porto`. Only the place where the event itself happens is `PLACE`.
- **A word that only says the entry repeats is `W`**, as `schedule` is: `[W repeat] [REPEAT every week]`,
  `[W recurring:] standup [REPEAT daily]`, `[W set up a recurring] review [REPEAT every wed]`. Such a word is part of
  what is done when it names the thing: `order a repeat prescription`, `cancel the recurring payment`; mark the line `trap`.
- **A repeat word that is part of a thing's usual name is `T`**: `weekly report`, `daily huddle`, `annual checkup`; mark the
  line `trap`. When it says how often the entry being created repeats, it is `REPEAT`, before a noun or standing apart:
  `schedule [REPEAT weekly] progress meetings`, `standup [REPEAT daily]`.
- **A duration describing the event is `LENGTH`** even inside the title phrase: `[LENGTH a 30 min] chat with Ana` gives the title "Chat with Ana".
- **An article right before a word that leaves the title goes with it**, so no orphan is left: `[PRIORITY an important] budget review`,
  `[LENGTH a 30-minute] incident review`, `[PRIORITY a high-priority task]`. Before a word that stays it stays too: `a quick review`.
- **Date-like words that name something are `T`**: `call April`, `Tuesdays with Morrie`, `Monday Night Football`, `bug 1043`, `floor 3`. Mark the line `trap`.
- **Hedges that say nothing about when are `W`**: `maybe`, `if that works`, `unless it rains`.
- **Several actions with one date are one title**: `buy milk and call Gran tomorrow` has title "Buy milk and call Gran"; `and` is `T`. With a different date for each action, mark the line `multi`.

## Intent

| Intent | When |
| --- | --- |
| `task` | Something to do: `renew the insurance`, `call the landlord` |
| `event` | Something that happens at a time: `yoga class`, `coffee with Ilse`, `fly to Lisbon` |
| `none` | Nothing to create: a statement about the past, a question, a cancellation, availability, chat |

Intent is what the wording reads like, not a decision about durations or priorities; the parser makes that.

## Urgency

The urgency a sentence **states in words**, one per sentence, written after the intent: `L` low, `M` medium, `H` high,
or nothing when none is stated. The words that state it are `PRIORITY`.

| Urgency | Said like |
| --- | --- |
| `H` | `urgent`, `asap`, `as soon as possible`, `right away`, `critical`, `top priority`, `high priority`, `p1`, `it's urgent` |
| `M` | `medium priority`, `normal priority`, `fairly important`, `p2` |
| `L` | `low priority`, `no rush`, `no hurry`, `not urgent`, `when you get a chance`, `nice to have`, `p3` |

- `!`, `!!`, `!!!` are L, M and H by themselves; the generator reads them, so they need no letter.
- Only stated urgency counts. Pressure that is only implied ("the boss is waiting", "before it's too late", "I'm so
  behind") gets no letter: guessing a priority is a wrong guess.
- An urgency word that is part of the title is `T` and gives no urgency: "reply to the urgent email from Priya". Mark the line `trap`.
- A sentence with an urgency letter must have at least one `PRIORITY` word stating it.

## Markup

One line per sentence: `<intent> [urgency] [flags] | <markup>`. Words in `[LABEL ...]` carry that label; every other word is `T`.
Sentences live in `training/<lang>/sentences.txt`, templates (sentences with `{slots}`) in `templates.txt`; both are
added to with `make add-sentences FILE=<file>`.
Text glued after `]` joins the last word of the bracket: `[DAY friday],` puts the comma on `friday`.

```
event | [W could you squeeze in] a quick call with Ilse [W on] [DAY thursday] [W at] [CLOCK 4pm,] [W thanks]
task trap | [W remind me to] call April about the sublet [DAY tomorrow]
task multi | call the bank [DAY monday] [T and] chase the refund [DAY wednesday]
none | [W i already] paid the electricity bill [DAY yesterday]
task H | [PRIORITY asap:] send the signed lease to the landlord
task L | [W Remind me to] renew the domain [DAY next month,] [PRIORITY no rush]
event | yoga [REPEAT every tuesday] [W from] [STARTS next week] [W until] [ENDS june] [CLOCK 7am] [W at] [PLACE the studio]
task | pay the invoice [W by] [DUE friday] [LIST #work] [REMINDER remind me a day before]
```

Flags: `trap` (date-like words in the title), `multi` (a date per action), `typo` (deliberately misspelt).
