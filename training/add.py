#!/usr/bin/env python3
"""
Adds labelled lines to a language's training set: each is checked and filed into its section of sentences.txt, or of
templates.txt when it has a {slot}. The files are then rewritten in their layout (sentences.py).

A line is rejected when it does not parse, when it repeats a line already in the set, or when it is a sentence of the
evaluation corpus (only its line number is printed). Labelled parts no reader can read are listed as warnings: fix the
label, or keep the line when the words are meant to stay in the title (labels.md, vague words).

    python3 training/add.py new.txt                 # check and add
    python3 training/add.py new.txt --dry-run       # check only
    python3 training/add.py --tidy                  # re-file every line already in the set, dropping repeats
"""
import argparse
import random
import sys
from collections import Counter
from types import SimpleNamespace

import generate
import sentences

NAMES = ("sentences", "templates")


def candidates(paths):
    """The lines of the given files, with where each came from; comments, headings and blank lines skipped."""
    for path in paths:
        for number, raw in enumerate(open(path, encoding="utf-8").read().splitlines(), 1):
            raw = raw.strip()
            if raw and not raw.startswith("#"):
                yield f"{path}:{number}", raw


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("files", nargs="*", help="files of labelled lines, in the markup of labels.md")
    p.add_argument("--lang", default="en")
    p.add_argument("--dry-run", action="store_true", help="check and report, change nothing")
    p.add_argument("--tidy", action="store_true", help="re-file every line already in the set")
    p.add_argument("--no-readers", action="store_true", help="skip the reader check (it runs Gradle)")
    args = p.parse_args()
    if not args.files and not args.tidy:
        p.error("give files to add, or --tidy")

    pools, spelling, _, errors = generate.load(args.lang)
    if errors and not args.tidy:
        sys.exit("the training set itself has lines that do not parse; run generate.py to see them")
    files = {name: sentences.read_sections(sentences.path(args.lang, name)) for name in NAMES}
    incoming = []
    if args.tidy:
        for name in NAMES:
            for key, lines in files[name].items():
                incoming += [(f"{name}.txt ({sentences.BY_KEY[key].title})", raw) for raw in lines]
                files[name][key] = []
    incoming += list(candidates(args.files))

    known = {sentences.identity(raw) for name in NAMES for lines in files[name].values() for raw in lines}
    corpus = sentences.corpus_texts(args.lang)
    accepted, rejected = [], []
    for where, raw in incoming:
        kind = "template" if sentences.is_template(raw) else "sentence"
        try:
            line = generate.Line(kind, where, raw, pools)
        except generate.Bad as e:
            rejected.append((where, f"does not parse: {e}"))
            continue
        key = sentences.identity(raw)
        if key in known:
            rejected.append((where, "repeats a line already in the set"))
            continue
        if kind == "sentence" and sentences.plain(raw) in corpus:
            rejected.append((where, "is a sentence of the evaluation corpus"))
            continue
        known.add(key)
        accepted.append((where, raw, line, "templates" if kind == "template" else "sentences", sentences.section_of(line, pools)))

    # Every labelled part of a new sentence should be readable.
    unread = []
    if not args.no_readers:
        # A sentence has no slots and gets no noise, so the rates and the random numbers make_row asks for are never used.
        settings = SimpleNamespace(trap=0, typo=0, short=0)
        rows = []
        for where, raw, line, name, _ in accepted:
            if name == "sentences":
                row = generate.make_row(line, pools, random.Random(0), "train", settings, spelling)
                row["where"] = where
                rows.append(row)
        if rows:
            for row, missed in zip(rows, generate.unread_spans(rows, args.lang)):
                unread += [(row["where"], label, text) for label, text in missed]

    filed = Counter()
    for _, raw, _, name, section in accepted:
        files[name][section.key].append(raw)
        filed[(name, section.title)] += 1
    done = "filed" if args.tidy and not args.files else "added"
    print(f"{len(accepted)} lines {'would be ' if args.dry_run else ''}{done}, {len(rejected)} rejected")
    order = [s.title for s in sentences.SECTIONS]
    for (name, title), n in sorted(filed.items(), key=lambda kv: (kv[0][0], order.index(kv[0][1]))):
        print(f"  {n:5d}  {name}.txt  {title}")
    if rejected:
        print("rejected:")
        for where, reason in rejected:
            print(f"  {where}: {reason}")
    if unread:
        print(f"labelled parts no reader can read ({len(unread)}):")
        for where, label, text in unread:
            print(f"  {where}: {label} {text!r}")
    if not args.dry_run:
        for name in NAMES:
            sentences.write_sections(sentences.path(args.lang, name), name, files[name])


if __name__ == "__main__":
    main()
