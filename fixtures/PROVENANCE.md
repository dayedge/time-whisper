# Fixtures

`en-date-expressions.txt`: 116 bare date expressions ("next week", "eom", "3 days ago") with the exact result of
`parseTemporal`: `D` and a date for a day, `M` for a month or year, `—` for no match. Every line must pass.

`en-evaluation-corpus.jsonl`: a hand-authored correctness corpus for `parse`, written against `docs/CORPUS_SPEC.md`:
145 groups, 533 sentences in four tiers, each with the full expected output. It is not expected to pass in full.
The last ten groups (g0132–g0141) check failures reported from use: a date and a time at each end of a range, a
reminder at a set time, a request not to create anything, calendar words inside a quoted title, and a time alone in
another zone; g0142–g0144 spans of days and times: "from today till friday", a numeric date and time at
each end, "today for 3 days"; g0145 a repeat bounded by "end of 2027". A set-time reminder is written as a date-time; a time in another zone is shown in UTC, as
every time is.

`pl-evaluation-corpus.jsonl`: the Polish corpus, written by hand against `docs/CORPUS_SPEC_PL.md`: 188 groups,
582 sentences. The first 123 groups mirror the English ones, with the same ids, dates and candidate order and with
Polish titles; the rest are Polish sentences with no English counterpart, g0175–g0188 mirroring
English g0132–g0145.

`en-evaluation-context.json`, `pl-evaluation-context.json`: the fixed world each corpus is written for: the language,
the reference time and zone, and the lists and calendars a tag may name. The evaluators read these, so a corpus for
another language needs no change to any tool.

`en-evaluation-known-failures.txt`: the English corpus sentences that do not match today with the tagger model, one per
line, each with how it fails (`FAIL`, or `RANKING` when only the candidates offered or their order are wrong).
`make check` compares the current result with this list and fails on any difference in either direction. After
reviewing a change, or a new model, `make accept-corpus` rewrites it. Edit the corpus, then run that command; never
edit this list by hand. `pl-evaluation-known-failures.txt` is the same for Polish.

Reference for all of them: 2026-09-23 12:00 UTC, Monday week start. All sentences were written for this purpose; none
come from users.
