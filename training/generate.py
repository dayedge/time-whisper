#!/usr/bin/env python3
"""
Builds tagged training sentences for the time-whisper tagger.

A sentence is written in a small markup: words in brackets carry a label, every other word is title.

    [W could you squeeze in] a quick call with Ilse [DAY on thursday] [CLOCK at 4pm,] [W thanks]

Labels: T title, W wrapper, and the roles DAY CLOCK END LENGTH DUE REPEAT STARTS ENDS PLACE LIST CALENDAR PRIORITY
REMINDER KIND. prompts/labels.md is the contract.

Three files feed the output, all under <lang>/:
- templates.txt: markup with {slots}, filled from pools.json by a seeded sampler, up to --n rows;
- sentences.txt: whole sentences in markup, every one always included, on top of --n;
- pools.json: titles, dates, repeats, places and metadata, each labelled by construction.

Each line of templates.txt and sentences.txt is `<intent> [urgency] [flags] | <markup>`. The intent is task, event,
none, or auto (taken from the title slots). The urgency, when the sentence states one in words, is L, M or H; a filler
of the priority pool carries its own, and `!`, `!!`, `!!!` give L, M, H without being written. The flags are trap,
multi and typo. Both files are kept in sections by sentences.py; add lines with add.py (`make add-sentences FILE=…`).

No row may be a sentence of the evaluation corpus: the run fails if one is.

Templates with the same structure (label runs, wrapper words kept) form one family. A family is capped, so a structure
written many times does not dominate, and whole families go to dev: dev measures new structures, not new fillers.
About 20% of the titles are kept for dev as well.

    python3 training/generate.py --n 12000 --out build/training/en            # write train.jsonl, dev.jsonl
    python3 training/generate.py --n 12000 --out build/training/en --readers  # also list the parts the parser cannot read
    python3 training/generate.py --signatures                                # the structures covered, for a prompt
"""
import argparse
import hashlib
import json
import math
import random
import re
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from pathlib import Path

import sentences
from sentences import LABELS

HERE = Path(__file__).resolve().parent
# Words that say when, and how often: the ones the spell checker is asked about, and that the noise shortens or misspells.
TIME = {"DAY", "CLOCK", "END", "LENGTH", "DUE", "STARTS", "ENDS", "REPEAT"}
# Marks whose punctuation is part of them ("task:"), so the noise never strips it.
META = {"LIST", "CALENDAR", "PRIORITY", "REMINDER", "KIND"}
INTENTS = {"task", "event", "none", "auto"}
FLAGS = {"trap", "multi", "typo"}
URGENCY = ("L", "M", "H")
# Punctuation around a word that is not part of it.
EDGE = ",.;:!?…-–—\"“”"
# One item of markup, in three forms: `[LABEL words]glue`; a slot `{pool}`, `{pool.N}` (part N of a split item) or
# `{pool#N}` (another draw from the same pool), then glue; or a bare word. Glue is punctuation stuck to the end: `[CLOCK 4pm],`.
ITEM = re.compile(r"\[([A-Z]+) ([^\]]*)\](\S*)|\{([a-z_]+)(?:\.(\d))?(?:#(\d))?\}(\S*)|(\S+)")


class Bad(Exception):
    """A line or a filled row that breaks the markup or the rules of labels.md."""


def stable(text, salt):
    """A number from text that is the same on every run, unlike hash()."""
    return int(hashlib.sha1((salt + text).encode()).hexdigest()[:8], 16)


class Slot:
    """A {slot} of a template: the pool it draws from, which part of a split item, which draw, and the glue after it."""

    def __init__(self, name, part, draw, glue):
        self.name, self.part, self.draw, self.glue = name, int(part) if part else None, draw or "", glue


def parse_markup(markup, slots, default="T"):
    """Tokens as [word, label] and, where allowed, Slots. Text glued after a bracket or slot joins its last word."""
    pieces = []
    for m in ITEM.finditer(markup):
        label, words, glue, slot, part, draw, slot_glue, bare = m.groups()
        if label:
            if label not in LABELS:
                raise Bad(f"unknown label {label}")
            if any(c in glue for c in "[]{}"):
                raise Bad(f"no space after a bracket: {m.group(0)!r}")
            tokens = [[w, label] for w in words.split()]
            if not tokens:
                raise Bad("empty bracket")
            tokens[-1][0] += glue
            pieces += tokens
        elif slot:
            if not slots:
                raise Bad(f"slot {{{slot}}} outside a template")
            if any(c in slot_glue for c in "[]{}"):
                raise Bad(f"no space after a slot: {m.group(0)!r}")
            pieces.append(Slot(slot, part, draw, slot_glue))
        else:
            if any(c in bare for c in "[]{}"):
                raise Bad(f"cannot read {bare!r}")
            pieces.append([bare, default])
    if not pieces:
        raise Bad("empty")
    return pieces


class Pool:
    """The fillers of one slot from pools.json: titles, dates, places... each labelled by construction, plus traps."""

    def __init__(self, name, spec):
        self.name, self.label, self.intent = name, spec.get("label", "T"), spec.get("intent")
        self.items = [(text, self.read(text)) for text in spec["items"]]
        # The urgency a filler states: "urgent" is H, "low priority" is L.
        self.urgency = spec.get("urgency", {})
        if set(self.urgency.values()) - set(URGENCY) or set(self.urgency) - set(spec["items"]):
            raise Bad(f"pool {name}: urgency must name items of the pool and be one of {URGENCY}")
        self.traps = [(text, self.read(text)) for text in spec.get("traps", [])]
        # About 20% of the titles, fixed by a hash of their text, appear only in dev, so dev measures titles the model has
        # not seen. Other pools are shared. A pool with nothing for one side uses all its items there.
        self.by_split = {split: (self.choices(self.items, split), self.choices(self.traps, split)) for split in ("train", "dev")}

    def read(self, text):
        """An item's parts: one, or several where the title is split around a date ("call the landlord | about the leak")."""
        if "[" in text:
            return [parse_markup(part.strip(), slots=False, default=self.label) for part in text.split(" | ")]
        return [[[w, self.label] for w in part.split()] for part in text.split(" | ")]

    def held(self, text):
        return self.label == "T" and stable(text, "title") % 100 < 20

    def choices(self, items, split):
        if self.label != "T":
            return items
        chosen = [item for item in items if self.held(item[0]) == (split == "dev")]
        return chosen or items

    def draw(self, rng, split, trap):
        """An item's parts, whether it is a trap, and the urgency it states."""
        items, traps = self.by_split[split]
        if self.traps and rng.random() < trap:
            return rng.choice(traps)[1], True, None
        text, parts = rng.choice(items)
        return parts, False, self.urgency.get(text)


class Line:
    """One line of templates.txt or sentences.txt, parsed: its intent, urgency, flags, pieces, and structure (signature)."""

    def __init__(self, kind, where, raw, pools):
        self.kind, self.where = kind, where
        head, sep, markup = raw.partition("|")
        if not sep:
            raise Bad("expected '<intent> [flags] | <markup>'")
        words = head.split()
        if not words or words[0] not in INTENTS:
            raise Bad(f"the intent must be one of {sorted(INTENTS)}")
        self.intent = words[0]
        stated = [w for w in words[1:] if w in URGENCY]
        if len(stated) > 1:
            raise Bad("one urgency at most")
        self.urgency = stated[0] if stated else None
        self.flags = set(words[1:]) - set(URGENCY)
        if self.flags - FLAGS:
            raise Bad(f"unknown flags {sorted(self.flags - FLAGS)}")
        self.pieces = parse_markup(markup.strip(), slots=kind == "template")
        slots = [p for p in self.pieces if isinstance(p, Slot)]
        for s in slots:
            if s.name not in pools:
                raise Bad(f"unknown slot {{{s.name}}}")
            if s.part and any(len(parts) < s.part for _, parts in pools[s.name].items + pools[s.name].traps):
                raise Bad(f"{{{s.name}.{s.part}}}, but not every item of {s.name} has {s.part} parts")
        if self.intent == "auto":
            found = {pools[s.name].intent for s in slots} - {None}
            if len(found) != 1:
                raise Bad("auto needs title slots of one intent; write the intent")
            self.intent = found.pop()
        shape = [(None, pools[p.name].label) if isinstance(p, Slot) else tuple(p) for p in self.pieces]
        self.signature = signature(self.intent, shape)
        self.category = "none" if self.intent == "none" else "wrapped" if any(l == "W" for _, l in shape) else "plain"


def signature(intent, tokens):
    """The structure of a sentence: its runs of labels, with the wrapper words written out."""
    runs = []
    for word, label in tokens:
        if label == "W":
            # A slot filled with wrapper words has no word yet.
            core = (word or "").lower().strip(EDGE + "'\"") or word or "…"
            if runs and runs[-1][0] == "W":
                runs[-1][1].append(core)
            else:
                runs.append(["W", [core]])
        elif not runs or runs[-1][0] != label:
            runs.append([label, []])
    return intent + ": " + " ".join(f"W({' '.join(ws)})" if l == "W" else l for l, ws in runs)


def family_of(sig):
    return "f" + hashlib.sha1(sig.encode()).hexdigest()[:6]


def title_of(tokens):
    """The title words, without punctuation at the ends, with a capital first letter: what the parser makes the title."""
    text = " ".join(w for w, l in tokens if l == "T").strip().strip(EDGE).strip()
    return text[:1].upper() + text[1:]


def core(word):
    return word.strip(EDGE)


# Each letter's neighbours on its row of a QWERTY keyboard, for slips of the finger.
KEYS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]
NEAR = {c: "".join(row[j] for j in (i - 1, i + 1) if 0 <= j < len(row)) for row in KEYS for i, c in enumerate(row)}


def slip(word, rng):
    """
    A misspelling people make: letters swapped, dropped or doubled, a neighbouring key, or the vowels left out
    ("tmrrow", "wdnesday"). The first letter stays, as it does in most real slips.
    """
    k = rng.randrange(1, len(word) - 1)
    kind = rng.random()
    if kind < 0.2:
        vowels = [j for j, c in enumerate(word) if j > 0 and c.lower() in "aeiou"]
        drop = set(rng.sample(vowels, rng.randint(1, len(vowels)))) if vowels else set()
        return "".join(c for j, c in enumerate(word) if j not in drop)
    if kind < 0.4:
        return word[:k] + word[k + 1] + word[k] + word[k + 2:]
    if kind < 0.6:
        return word[:k] + word[k + 1:]
    if kind < 0.8:
        return word[:k] + word[k] + word[k:]
    near = NEAR.get(word[k].lower())
    return word[:k] + rng.choice(near) + word[k + 1:] if near else word


def typo(tokens, rng, typos):
    """
    One misspelt word, more often a date or repeat word than a title or wrapper word, so that a misspelling says nothing
    about the label: the parser asks the spell checker only about words the tagger calls dates. A date word gets a known
    misspelling or a slip.
    """
    def words(labels, shortest):
        return [i for i, (w, l) in enumerate(tokens) if l in labels and core(w).isalpha() and len(core(w)) >= shortest]

    # Long enough for slip() to change a letter inside the word and leave it readable: 4 letters for dates, 5 for others.
    dates, others = words(TIME, 4), words({"T", "W"}, 5)
    if not dates and not others:
        return None
    # About two in three typos hit a date word when both kinds are there; a third of those use a known misspelling.
    i = rng.choice(dates if dates and (not others or rng.random() < 0.65) else others)
    word = core(tokens[i][0])
    if tokens[i][1] in TIME and word.lower() in typos and rng.random() < 0.3:
        wrong = rng.choice(typos[word.lower()])
        wrong = wrong[:1].upper() + wrong[1:] if word[:1].isupper() else wrong
    else:
        wrong = slip(word, rng)
    if wrong == word or len(wrong) < 4:
        return None
    tokens[i][0] = tokens[i][0].replace(word, wrong, 1)
    return i


def shorten(tokens, rng, short):
    """Date and repeat words in the short forms people type: "nxt wk", "tues", "sept", "2moro", "every mon & wed"."""
    for t in tokens:
        word = core(t[0])
        forms = short.get(word.lower())
        if t[1] in TIME and forms and rng.random() < 0.7:
            form = rng.choice(forms)
            t[0] = t[0].replace(word, form[:1].upper() + form[1:] if word[:1].isupper() else form, 1)


NUMERIC_DATE = re.compile(r"^(\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2})$")
DATE_FORMATS = ["{m}/{d}", "{mm}/{dd}", "{m}/{d}/{y}", "{mm}/{dd}/{y}", "{d}/{m}/{y}", "{dd}.{mm}.{y}", "{d}.{m}.{y}",
                "{dd}-{mm}-{y}", "{mm}-{dd}-{y}", "{y}-{mm}-{dd}", "{y}/{mm}/{dd}", "{d}.{m}.", "{dd}.{mm}."]


def numeric_date(rng):
    """A date in digits in one of the ways people write it, with a real day: "11-11-2026", "3.4.", "2027/01/15", "10/20"."""
    # Days up to 28 exist in every month; 2026 comes twice as often as 2027.
    m, d, y = rng.randint(1, 12), rng.randint(1, 28), rng.choice([2026, 2026, 2027])
    return rng.choice(DATE_FORMATS).format(m=m, d=d, mm=f"{m:02}", dd=f"{d:02}", y=y)


def dates_in_digits(tokens, rng):
    """Every date the pools write in digits becomes a random one in a random format, so the model learns the shape and not a few strings."""
    for t in tokens:
        word = core(t[0])
        if t[1] in TIME and NUMERIC_DATE.match(word):
            t[0] = t[0].replace(word, numeric_date(rng), 1)


def noise(tokens, flags, rng, args, spelling):
    """
    How people type: often all lowercase, sometimes capitalised, sometimes without punctuation, in short forms, now and
    then misspelt. Returns the positions of misspelt words.
    """
    # 12% all lower case, 18% the first word capitalised, the rest as written.
    r = rng.random()
    if r < 0.12:
        for t in tokens:
            t[0] = t[0].lower()
    elif r < 0.30:
        tokens[0][0] = tokens[0][0][:1].upper() + tokens[0][0][1:]
    # 15% without trailing punctuation, except on marks ("task:") and words that are only punctuation.
    if rng.random() < 0.15:
        for t in tokens:
            bare = t[0].rstrip(",.;:?")
            if t[1] not in META and bare:
                t[0] = bare
    dates_in_digits(tokens, rng)
    if rng.random() < args.short:
        shorten(tokens, rng, spelling["short"])
    at = typo(tokens, rng, spelling["typos"]) if rng.random() < args.typo else None
    if at is None:
        return []
    flags.add("typo")
    return [at]


def make_row(line, pools, rng, split, args, spelling):
    """One row from a line: its slots filled, noise added to a template, and the result checked (finish)."""
    # Slots with the same pool and draw number share one item: {title.1} … {title.2} are the two halves of one title,
    # while {title#2} draws another.
    tokens, flags, drawn, urgencies = [], set(line.flags), {}, []
    for piece in line.pieces:
        if not isinstance(piece, Slot):
            tokens.append(list(piece))
            continue
        key = (piece.name, piece.draw)
        if key not in drawn:
            drawn[key] = pools[piece.name].draw(rng, split, args.trap)
        parts, trap, urgency = drawn[key]
        if trap:
            flags.add("trap")
        if urgency:
            urgencies.append(urgency)
        chosen = parts[piece.part - 1] if piece.part else [t for part in parts for t in part]
        new = [list(t) for t in chosen]
        new[-1][0] += piece.glue
        tokens += new
    misspelt = noise(tokens, flags, rng, args, spelling) if line.kind == "template" else []
    return finish(tokens, line, flags, urgencies, misspelt)


def urgency_of(tokens, line, urgencies):
    """
    The urgency the sentence states: written on the line, else from a priority filler, else from exclamation marks
    (`!` L, `!!` M, `!!!` H). When several fillers or marks state one, the highest counts.
    """
    if line.urgency:
        found = line.urgency
    else:
        bangs = [len(w) for w, l in tokens if l == "PRIORITY" and w and set(w) == {"!"}]
        found = [URGENCY[min(n, 3) - 1] for n in bangs] + list(urgencies)
        found = max(found, key=URGENCY.index) if found else None
    if found and not any(l == "PRIORITY" for _, l in tokens):
        raise Bad("urgency without a word that states it: tag the words PRIORITY, or leave the urgency out")
    return found


RANGE_WORDS = {"until", "till", "til", "to", "through", "thru", "-", "–"}
# A word that says only a time of day, in digits or spoken: "5", "5pm", "17:30", "noon", "half past 5"; never a day such as
# "nov", "3rd", "sunday" or "12/13".
TIME_WORD = re.compile(r"^(\d{1,2}([:.]\d{2})?(am|pm|a|p|ish)?|am|pm|a\.m\.|p\.m\.|noon|midnight|half|quarter|past|to|o'clock)$")


def one_range(tokens):
    """
    An end right after its start time is one range, labelled CLOCK: "4pm until 5pm" as "3-5pm" is (labels.md, "a range
    is one run"). END is only for an end said apart from the start: "at 4, done by 6". Patterns that put {clock} and {end}
    side by side are made to follow the rule here, so the model never sees the same words labelled both ways.

    Only an end that opens with a range word and is nothing but a time joins it: "4pm until 5pm", "3 to 5". "3pm until
    friday" spans days, "until nov 3 at 16:30" names its day, "until the 14th" names a day, and "4pm, ending at 6" says it
    ends in words of its own: those stay END.
    """
    def a_time(run):
        words = [w.lower().strip(",") for w in run]
        opens = words[0] in RANGE_WORDS or words[0].startswith(("-", "–"))
        rest = [w.lstrip("-–") for w in (words[1:] if words[0] in RANGE_WORDS or words[0] in ("-", "–") else words)]
        # Only time words, and a digit or a named hour among them, as before: "until half past 5", not "until six".
        return opens and bool(rest) and all(TIME_WORD.match(w) for w in rest if w) and \
            re.search(r"\d|\bnoon\b|\bmidnight\b", " ".join(rest)) is not None
    i = 1
    while i < len(tokens):
        # The range word may be written as the wrapper of the end: "[CLOCK 4pm] [W until] [END 5pm]".
        wrapped = tokens[i][1] == "W" and tokens[i][0].lower() in RANGE_WORDS and i + 1 < len(tokens) and tokens[i + 1][1] == "END"
        if (tokens[i][1] == "END" or wrapped) and tokens[i - 1][1] == "CLOCK":
            j = i + 1 if wrapped else i
            while j < len(tokens) and tokens[j][1] == "END":
                j += 1
            if a_time([w for w, _ in tokens[i:j]]):
                for t in tokens[i:j]:
                    t[1] = "CLOCK"
            i = j
        else:
            i += 1


def finish(tokens, line, flags, urgencies, misspelt):
    """A filled row checked against the rules and written as the generator outputs it."""
    one_range(tokens)
    for word, label in tokens:
        if not word or any(c.isspace() for c in word) or label not in LABELS:
            raise Bad(f"bad token {word!r}/{label}")
    title = title_of(tokens)
    if line.intent != "none" and not any(c.isalpha() for c in title):
        raise Bad("no title")
    if len(tokens) > 40:
        raise Bad("longer than 40 words")
    return {
        "text": " ".join(w for w, _ in tokens), "labels": [l for _, l in tokens], "intent": line.intent,
        "title": title if line.intent != "none" else None, "urgency": urgency_of(tokens, line, urgencies), "flags": sorted(flags),
        "source": line.kind, "family": family_of(line.signature), "where": line.where, "misspelt": list(misspelt),
    }


def load(lang):
    """The pools, the spelling noise, every line of the two files, and the lines that do not parse."""
    base = HERE / lang
    spec = json.loads((base / "pools.json").read_text(encoding="utf-8"))
    # Misspellings and short forms of date words; each short form is one the English lexicon reads.
    spelling = {"typos": spec.pop("_typos", {}), "short": spec.pop("_short", {})}
    pools = {name: Pool(name, s) for name, s in spec.items()}
    lines, errors = [], []
    for kind, name in (("template", "templates"), ("sentence", "sentences")):
        path = sentences.path(lang, name)
        if not path.exists():
            continue
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            raw = raw.strip()
            if not raw or raw.startswith("#"):
                continue
            where = f"{path.name}:{number}"
            try:
                lines.append(Line(kind, where, raw, pools))
            except Bad as e:
                errors.append(f"{where}: {e}\n    {raw}")
    return pools, spelling, lines, errors


def unread_spans(rows, lang):
    """
    The labelled parts no reader of the parser can read, for each row, from the readSpans Gradle task. The row's labels
    stand in for the tagger; the parser puts a part it cannot read back into the title.
    """
    context = HERE / lang / f"{lang}-training-context.json"
    with tempfile.TemporaryDirectory() as tmp:
        source, output = Path(tmp) / "rows.jsonl", Path(tmp) / "unread.jsonl"
        source.write_text("".join(json.dumps({k: r[k] for k in ("text", "labels", "urgency")}, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
        command = ["sh", "./gradlew", "-q", "--console=plain", "readSpans", f"--args={context} {source} {output}"]
        subprocess.run(command, cwd=HERE.parent, check=True)
        return [json.loads(line)["unread"] for line in output.read_text(encoding="utf-8").splitlines()]


def report_unread(rows, lang):
    """
    How many labelled parts the readers cannot read, by role, and the most common of them. A pool item or a hand-labelled
    line among them is either labelled wrongly or written in a way the readers do not know: fix the label, or accept that
    the parser leaves it in the title (vague times are meant to stay unread).
    """
    found = unread_spans(rows, lang)
    parts, unread, forms, where = Counter(), Counter(), Counter(), {}
    for row, missed in zip(rows, found):
        for label, _ in runs(row):
            parts[label] += label not in ("T", "W")
        for label, text in missed:
            unread[label] += 1
            key = (label, text.lower().strip(EDGE))
            forms[key] += 1
            where.setdefault(key, row["where"])
    print(f"readers: {sum(unread.values())} of {sum(parts.values())} labelled parts are not read "
          + "(" + ", ".join(f"{l} {unread[l]}/{parts[l]}" for l in LABELS if unread[l]) + ")")
    for (label, text), n in forms.most_common(30):
        print(f"  {n:4d}  {label:<9} {text!r}  e.g. {where[(label, text)]}")


def share(part, whole):
    return f"{100 * part / whole:.0f}%" if whole else "-"


def runs(row):
    """The runs of one label in a row, as (label, lowercased words), title and wrapping words included."""
    out = []
    for word, label in zip(row["text"].split(" "), row["labels"]):
        if out and out[-1][0] == label:
            out[-1][1].append(word.lower().strip(EDGE))
        else:
            out.append((label, [word.lower().strip(EDGE)]))
    return [(label, " ".join(words)) for label, words in out]


# Below these, a role is too rare in train for the model to learn it from: write more templates, pool items or sentences.
FEW_SPANS, FEW_FORMS = 300, 30


def report_conflicts(rows, least=5, share_min=0.2):
    """
    Words labelled differently in the same surroundings: the word with the word before and after it, all the same, and
    yet "until" is END in some rows and CLOCK in others. Words that change role with context ("friday" a day in one
    sentence, a deadline in another) are not listed: their context differs. What is left is the data disagreeing with
    itself, usually a pool, a template or a sentence labelled against labels.md, which no amount of training fixes.
    """
    labels, where = defaultdict(Counter), defaultdict(Counter)
    for row in rows:
        words = [w.lower().strip(EDGE) for w in row["text"].split(" ")]
        for i, label in enumerate(row["labels"]):
            key = (words[i - 1] if i else "^", words[i], words[i + 1] if i + 1 < len(words) else "$")
            labels[key][label] += 1
            where[(key, label)][row["where"].rsplit(":", 1)[0]] += 1
    found = []
    for key, counts in labels.items():
        total = sum(counts.values())
        if len(counts) < 2 or total < least:
            continue
        (top, _), (other, minority), *_ = counts.most_common()
        if minority / total >= share_min:
            found.append((minority, key, counts, other))
    print(f"the same words labelled differently in the same surroundings ({len(found)}):")
    for minority, (before, word, after), counts, other in sorted(found, reverse=True)[:40]:
        labelled = ", ".join(f"{l} {n}" for l, n in counts.most_common())
        source = ", ".join(f"{w} {n}" for w, n in where[((before, word, after), other)].most_common(2))
        print(f"  {before} [{word}] {after:<12} {labelled:<28} {other} from: {source}")


def report_roles(rows):
    """For every role: spans in train and dev, and how many different ways train writes it."""
    count, forms = {s: Counter() for s in ("train", "dev")}, defaultdict(set)
    for row in rows:
        for label, text in runs(row):
            count[row["split"]][label] += 1
            if row["split"] == "train":
                forms[label].add(text)
    print(f"{'role':<10}{'train':>7}{'dev':>6}{'forms':>7}")
    for label in LABELS:
        if label in ("T", "W"):
            continue
        few = count["train"][label] < FEW_SPANS or len(forms[label]) < FEW_FORMS
        print(f"{label:<10}{count['train'][label]:>7}{count['dev'][label]:>6}{len(forms[label]):>7}" + ("   ← few" if few else ""))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--n", type=int, help="rows to generate from each language's templates, train and dev together; sentences are added on top")
    p.add_argument("--out", type=Path, help="directory for train.jsonl and dev.jsonl")
    p.add_argument("--lang", default="en", help="the language, or several separated by commas: en,pl")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--dev", type=float, default=0.15, help="share of structures kept for dev")
    p.add_argument("--plain-share", type=float, default=0.35, help="share of template rows with no wrapper")
    p.add_argument("--none-share", type=float, default=0.08, help="share of template rows that create nothing")
    p.add_argument("--max-per-family", type=int, default=30, help="rows one structure may contribute")
    p.add_argument("--trap", type=float, default=0.12, help="chance that a title slot takes a title with date-like words")
    p.add_argument("--typo", type=float, default=0.2, help="chance of one misspelt word in a template row")
    p.add_argument("--short", type=float, default=0.3, help="chance of short forms for the date words of a template row")
    p.add_argument("--readers", action="store_true", help="report the labelled parts the parser's readers cannot read (runs Gradle)")
    p.add_argument("--conflicts", action="store_true", help="report words the data labels in more than one way")
    p.add_argument("--signatures", action="store_true", help="print the structures already covered and stop")
    args = p.parse_args()

    languages = args.lang.split(",")
    if args.signatures:
        for lang in languages:
            if len(languages) > 1:
                print(f"# {lang}")
            for (_, sig), members in sorted(families_of(load(lang)[2]).items(), key=lambda kv: kv[0][1]):
                print(f"{sig}" + (f"   ×{len(members)}" if len(members) > 1 else ""))
        return
    if not args.n or not args.out:
        p.error("--n and --out are required unless --signatures is given")

    # Families are counted per language: the same structure in two languages is two families.
    rows, notes, rejected, families = [], [], Counter(), []
    for lang in languages:
        made, short, refused, structures = language_rows(lang, args)
        rows += made
        notes += [f"{lang} {note}" for note in short] if len(languages) > 1 else short
        rejected += refused
        families += list(structures)
    write_rows(rows, args)
    print_report(rows, families, notes, rejected, args)


def families_of(lines):
    """Lines grouped by kind and structure: each group is one family."""
    families = defaultdict(list)
    for line in lines:
        families[(line.kind, line.signature)].append(line)
    return families


def language_rows(lang, args):
    """
    The rows of one language: its templates filled, then its sentences, none of them a sentence of its evaluation corpus.
    Each language draws from its own random numbers, so adding a language leaves the rows of the others as they were.
    """
    pools, spelling, lines, errors = load(lang)
    for e in errors:
        print(f"skipped {e}", file=sys.stderr)
    families = families_of(lines)

    rng = random.Random(args.seed)
    # The split is chosen by a hash of the structure, so a family lands on the same side in every run, whatever the seed.
    split_of = lambda sig: "dev" if stable(sig, "split") % 100 < args.dev * 100 else "train"
    seen, rejected = set(), Counter()

    def add(line, split):
        """A row from the line, or None when it breaks a rule or repeats a row already made."""
        try:
            row = make_row(line, pools, rng, split, args, spelling)
        except Bad as e:
            rejected[f"{line.where}: {e}"] += 1
            return None
        if row["text"].lower() in seen:
            return None
        seen.add(row["text"].lower())
        row["split"], row["lang"] = split, lang
        return row

    rows, notes = fill_templates(families, args, add, split_of, rng)
    # Sentences, every one, beside what was generated.
    for line in lines:
        if line.kind == "sentence":
            row = add(line, split_of(line.signature))
            if row:
                rows.append(row)

    # The evaluation corpus measures sentences the model has not seen.
    corpus = sentences.corpus_texts(lang)
    leaked = [r["where"] for r in rows if " ".join(r["text"].lower().split()) in corpus]
    if leaked:
        sys.exit(f"{len(leaked)} {lang} rows are sentences of the evaluation corpus; remove them: " + ", ".join(sorted(set(leaked))[:20]))
    return rows, notes, rejected, families


def fill_templates(families, args, add, split_of, rng):
    """
    Rows from the templates, by category (none, plain, wrapped) in the shares asked for. Each family gives at most
    --max-per-family rows, so a structure written many times does not dominate. Returns the rows and notes on shortfalls.
    """
    shares = {"none": args.none_share, "plain": args.plain_share, "wrapped": 1 - args.none_share - args.plain_share}
    by_category = defaultdict(list)
    for (kind, sig), members in families.items():
        if kind == "template":
            by_category[members[0].category].append((sig, members))
    rows, notes = [], []
    for category, fams in sorted(by_category.items()):
        want = math.ceil(args.n * shares[category])
        per = min(args.max_per_family, math.ceil(want / len(fams)))
        produced = []
        for sig, members in sorted(fams):
            split, made, attempts = split_of(sig), 0, 0
            # Give a family up after six tries per row wanted: repeats and fills that break a rule use up tries.
            while made < per and attempts < per * 6:
                attempts += 1
                row = add(rng.choice(members), split)
                if row:
                    produced.append(row)
                    made += 1
        # Rounding up per family overshoots: sample back to the category's share.
        if len(produced) > want:
            produced = rng.sample(produced, want)
        if len(produced) < want:
            notes.append(f"{category}: wanted {want}, the templates give {len(produced)} "
                         f"({len(fams)} structures × at most {args.max_per_family}); write more templates or raise --max-per-family")
        rows += produced
    return rows, notes


def write_rows(rows, args):
    """train.jsonl and dev.jsonl. Each row gets an id from its family and its number in it."""
    counters = defaultdict(int)
    args.out.mkdir(parents=True, exist_ok=True)
    files = {split: open(args.out / f"{split}.jsonl", "w", encoding="utf-8") for split in ("train", "dev")}
    for row in rows:
        counters[(row["lang"], row["family"])] += 1
        row["id"] = f"{row['lang']}-{row['family']}-{counters[(row['lang'], row['family'])]:03d}"
        out = {"id": row["id"], "lang": row["lang"]} | {k: row[k] for k in ("text", "labels", "intent", "title", "urgency", "flags", "source", "family", "where", "misspelt")}
        files[row["split"]].write(json.dumps(out, ensure_ascii=False) + "\n")
    for f in files.values():
        f.close()


def print_report(rows, families, notes, rejected, args):
    """What was written: the splits, the mix of intents, urgencies, flags and labels, each role's spans, and what fell short."""
    total = len(rows)
    print(f"{total} rows: train {sum(r['split'] == 'train' for r in rows)}, dev {sum(r['split'] == 'dev' for r in rows)}  →  {args.out}")
    print(f"structures: {len({r['family'] for r in rows})} "
          f"(template families {sum(k == 'template' for k, _ in families)}, sentences {sum(k == 'sentence' for k, _ in families)})")
    for name, key in (("source", "source"), ("intent", "intent"), ("urgency", "urgency")):
        c = Counter(r[key] for r in rows)
        print(f"{name}: " + ", ".join(f"{k} {v} ({share(v, total)})" for k, v in c.most_common()))
    c = Counter(f for r in rows for f in r["flags"])
    print("flags: " + (", ".join(f"{k} {v} ({share(v, total)})" for k, v in c.most_common()) or "none"))
    print(f"no wrapper: {share(sum('W' not in r['labels'] for r in rows), total)}")
    c = Counter(l for r in rows for l in r["labels"])
    print("labels: " + ", ".join(f"{k} {share(v, sum(c.values()))}" for k, v in c.most_common()))
    report_roles(rows)
    sizes = sorted(Counter(r["family"] for r in rows).values())
    if sizes:
        print(f"rows per structure: median {sizes[len(sizes) // 2]}, max {sizes[-1]}")
    for note in notes:
        print(f"short: {note}")
    if rejected:
        print(f"rejected while filling: {sum(rejected.values())}")
        for reason, n in rejected.most_common(10):
            print(f"  {n:4d}  {reason}")
    if args.conflicts:
        report_conflicts(rows)
    if args.readers:
        for lang in args.lang.split(","):
            report_unread([r for r in rows if r["lang"] == lang], lang)
    # --n is asked of each language that has templates.
    for lang in args.lang.split(","):
        generated = sum(r["source"] == "template" and r["lang"] == lang for r in rows)
        if 0 < generated < args.n:
            print(f"generated {generated} of the {args.n} {lang} rows asked for")

if __name__ == "__main__":
    main()
