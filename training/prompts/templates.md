# Prompt: templates

Give a model this file, `labels.md`, and the output of `python3 training/generate.py --signatures`. Save its answer to a
file and add it with `make add-sentences FILE=<file>`: a line with a slot is filed into `templates.txt`, and malformed or
repeated lines are rejected. Then delete the file.

---

You write sentence templates for training a tagger that reads what people type into a calendar or to-do app.
A template is a sentence in the markup of `labels.md` where the variable content is a slot:

| Slot | Fills with | Example |
| --- | --- | --- |
| `{vp}` | a task as a verb phrase | `renew the car insurance` |
| `{vp_ing}` | the same as an -ing form | `renewing the car insurance` |
| `{vp_event}` | an event as a verb phrase | `meet Ilse for coffee` |
| `{vp_past}` | a finished task, past tense | `paid the electricity bill` |
| `{np_event}` | an event as a noun phrase | `yoga class`, `a quick sync with legal` |
| `{np_task}` | a thing to remember | `the gym bag` |
| `{vps.1} … {vps.2}` | a task split around something | `call the landlord` … `about the leaking tap` |
| `{evs.1} … {evs.2}` | an event split the same way | `dinner` … `with the in-laws` |
| `{day}` `{clock}` `{datetime}` `{range}` `{dayrange}` `{past}` | when | `next tue`, `at 4pm`, `fri 3pm`, `2-3pm`, `mon-wed`, `yesterday` |
| `{end}` `{duration}` `{length_before}` `{deadline}` | an end, a length, a deadline | `until 5pm`, `for an hour`, `a 30-minute`, `by eod` |
| `{repeat}` `{repeat_start}` `{repeat_end}` | repeats, with where they start or stop | `every monday`, `weekly starting next week`, `daily for two weeks` |
| `{place}` | an event's place | `at Café Nero` |
| `{tag}` `{priority}` `{reminder}` `{calendar}` `{list}` `{kind_task}` `{kind_event}` | metadata | `#work`, `!!`, `remind me 10 min before`, `on the team calendar`, `to my work list`, `todo`, `t:event` |

`{x#2}` is a second, independent filler of the same kind. Write the intent as `auto` unless the template mixes task and
event slots or creates nothing. A template that states urgency in its own words (not through `{priority}`) writes the
level after the intent: `auto H | [PRIORITY asap:] {vp}`.

**Your objective:** <one of: everyday conversational phrasing / unusual word order, indirect requests, slang /
complex grammar, subordinate clauses, conjunctions, negation / adversarial: fragments, ambiguity, things that should
create nothing>

Write 80 templates. Each must be a structure that is **not** in the attached list of signatures: a different wrapper,
a different position of the date or the wrapper, a different way of splitting the title. Changing only which slot
of the same kind is used (`{day}` for `{deadline}`) is the same structure. Write wrappers people really type, in
the register of your objective; no stiff or formal assistant language. Keep each sentence under 16 words once filled.

Output only the lines, one template per line.
