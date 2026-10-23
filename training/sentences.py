"""
The two hand-maintained files of a language's training set, and the sections they are kept in.

- `<lang>/sentences.txt`: whole sentences in markup, every one always used.
- `<lang>/templates.txt`: sentences with {slots}, filled from `<lang>/pools.json` by the generator.

Both are grouped by what a line teaches, under `## ` headings. A line belongs to the first section in MATCH_ORDER whose test
it passes, so the most specific section wins: a repeat with a deadline is filed under its repeat. `add.py` files new lines
and rewrites the files in this layout; a line can also be edited where it stands.
"""
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
# Every label, in the order the model numbers them. train.py keeps a copy (it runs on a machine without these files);
# a label change goes into both, and into the Kotlin TokenLabel.
LABELS = ["T", "W", "DAY", "CLOCK", "END", "LENGTH", "DUE", "REPEAT", "STARTS", "ENDS", "PLACE", "LIST", "CALENDAR",
          "PRIORITY", "REMINDER", "KIND"]
# The labels of the parts a sentence states: everything but title (T) and wrapping (W) words.
PARTS = set(LABELS) - {"T", "W"}


class Section:
    """One `## ` section of the files: its key, heading, one-line note, and the test a line must pass to belong to it."""

    def __init__(self, key, title, about, test):
        self.key, self.title, self.about, self.test = key, title, about, test


def _uses(*labels):
    return lambda line: bool(line.labels & set(labels))


def _wrapped(line):
    """Wrapping words that are more than the one word attaching a part: chat, politeness, a wrapper in the middle or at the end."""
    runs = line.runs
    for i, (label, size) in enumerate(runs):
        if label == "W" and (size > 1 or i + 1 == len(runs) or runs[i + 1][0] not in PARTS):
            return True
    return False


# In the order the files show them.
SECTIONS = [
    Section("days", "Days and times", "A day, a time, or both, with nothing else to read.", lambda line: True),
    Section("ends", "Ends and lengths", "An end said apart from the start (END), and how long an event lasts (LENGTH).", _uses("END", "LENGTH")),
    Section("deadlines", "Deadlines", "When a task is due (DUE), against an event's end.", _uses("DUE")),
    Section("repeats", "Repeats", "How often (REPEAT), and words that only say an entry repeats (W).", _uses("REPEAT")),
    Section("bounds", "Repeat bounds", "Where a repeat starts and stops (STARTS, ENDS), a range of days among them.", _uses("STARTS", "ENDS")),
    Section("places", "Places, lists and calendars", "Where an event happens (PLACE), and the list or calendar it goes to.", _uses("PLACE", "LIST", "CALENDAR")),
    Section("marks", "Priority, reminders and kind marks", "Stated urgency (PRIORITY), reminders (REMINDER), and marks that force a kind (KIND).",
            _uses("PRIORITY", "REMINDER", "KIND")),
    Section("wrappers", "Wrappers", "Words around the request: asking, chat, politeness, and wrappers in the middle or at the end.", _wrapped),
    Section("traps", "Traps", "Date-like words that belong to the title: names, films, rooms, versions.", lambda line: "trap" in line.flags),
    Section("none", "Nothing to create", "Statements about the past, questions, cancellations, chat.", lambda line: line.intent == "none"),
    Section("multi", "Several actions", "A date for each of several actions.", lambda line: "multi" in line.flags),
]
BY_KEY = {s.key: s for s in SECTIONS}
# The most specific first.
MATCH_ORDER = [BY_KEY[k] for k in ("multi", "none", "traps", "bounds", "repeats", "deadlines", "ends", "places", "marks", "wrappers", "days")]

HEADERS = {
    "sentences": """\
# Training sentences, each used as written. Grouped by what a line teaches; a line belongs to the first matching section
# in training/sentences.py. Format and labels: training/prompts/labels.md.
# Add lines with `make add-sentences FILE=<file>`: it checks them, files each into its section and rewrites this file.
""",
    "templates": """\
# Training templates: sentences with {slots}, filled from pools.json. Grouped like sentences.txt.
# Format and labels: training/prompts/labels.md. Add lines with `make add-sentences FILE=<file>`; a line with a slot is a template.
""",
}


class Shape:
    """What a section test needs to know of a parsed line: its intent, flags, labels and runs of labels."""

    def __init__(self, line, pools):
        self.intent, self.flags = line.intent, line.flags
        self.runs, self.labels = [], set()
        for piece in line.pieces:
            # A slot (generate.Slot; told apart by its name, as generate imports this module): it counts with every label
            # its pool's items carry, and as one word of the pool's label in the runs.
            if hasattr(piece, "name"):
                pool = pools[piece.name]
                self.labels |= {label for _, parts in pool.items + pool.traps for part in parts for _, label in part}
                label = pool.label
            else:
                label = piece[1]
                self.labels.add(label)
            if self.runs and self.runs[-1][0] == label:
                self.runs[-1] = (label, self.runs[-1][1] + 1)
            else:
                self.runs.append((label, 1))


def section_of(line, pools):
    """The section a parsed line is filed under: the first in MATCH_ORDER whose test it passes."""
    shape = Shape(line, pools)
    return next(s for s in MATCH_ORDER if s.test(shape))


def path(lang, name):
    """`<lang>/sentences.txt` or `<lang>/templates.txt`."""
    return HERE / lang / f"{name}.txt"


def read_sections(file):
    """The lines of a file by section key, as written. Comments are not kept: the headers and section notes are written anew."""
    sections = {s.key: [] for s in SECTIONS}
    titles = {s.title: s.key for s in SECTIONS}
    current = None
    if not file.exists():
        return sections
    for raw in file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        heading = re.match(r"## \d+ (.+)", line)
        if heading:
            current = titles.get(heading.group(1))
            if current is None:
                raise ValueError(f"{file}: unknown section {heading.group(1)!r}")
            continue
        if not line or line.startswith("#") or current is None:
            continue
        sections[current].append(line)
    return sections


def write_sections(file, name, sections):
    """Writes a file in its layout: the header, then each section that has lines, with its heading and note."""
    out = [HEADERS[name]]
    # A section keeps its number in both files, empty or not.
    for number, s in enumerate(SECTIONS, 1):
        if not sections[s.key]:
            continue
        out.append(f"\n## {number} {s.title}\n# {s.about}\n")
        out.extend(line + "\n" for line in sections[s.key])
    file.write_text("".join(out), encoding="utf-8")


def plain(markup):
    """A line's words without markup, lower case: what a sentence is compared with the corpus and other sentences by."""
    text = markup.split("|", 1)[1] if "|" in markup else markup
    text = re.sub(r"\[[A-Z]+ |\]", " ", text)
    return " ".join(text.lower().split())


def is_template(raw):
    """A line with a {slot} after its head is a template; any other is a sentence."""
    return "{" in raw.partition("|")[2]


def identity(raw):
    """What makes two lines the same line: a sentence by its words, a template by all it says (labels, intent, flags)."""
    return " ".join(raw.split()) if is_template(raw) else plain(raw)


def corpus_texts(lang):
    """The inputs of the evaluation corpus, compared like plain(). Only for checking: never print them."""
    corpus = HERE.parent / "fixtures" / f"{lang}-evaluation-corpus.jsonl"
    texts = set()
    if corpus.exists():
        for raw in corpus.read_text(encoding="utf-8").splitlines():
            if raw.strip():
                for text in json.loads(raw).get("inputs", []):
                    texts.add(" ".join(str(text).lower().split()))
    return texts
