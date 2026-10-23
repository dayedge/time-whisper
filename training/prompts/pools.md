# Prompt: more fillers

Give a model this file and the current `training/en/pools.json`. Merge its answer into the pools by hand.

---

Add fillers to the pools in the attached `pools.json`. Answer with JSON of the same shape, only the new items:
`{"vp": {"items": [...], "traps": [...]}, "np_event": {...}, ...}`.

- `vp`, `vp_ing`, `vp_event`, `vp_past`, `np_event`, `np_task`: 60 new items each, across work, home, money, health,
  school, family, travel and hobbies. Every item must fit every slot of its kind: a `vp` reads correctly after
  "remind me to", "i need to" and on its own; an `np_event` after "let's do", "pencil in" and before "is at 4".
- `vps`, `evs`: 30 each, split with " | " where a date can sit naturally between the two parts.
- `traps`: 20 per title pool. Names that are months or weekdays (April, June, Will), titles of things with day words
  (films, books, shows), numbers that look like times or ranges (versions, rooms, ratios, pages, quantities).
- No item may repeat an existing one or differ from it by a single word.
- Invent names of people, companies and places. No famous people.
