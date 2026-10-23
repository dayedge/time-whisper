#!/usr/bin/env python3
"""
Checks the Core ML tagger on a Mac against the PyTorch reference, then lets you type sentences. No PyTorch needed:
only coremltools, tokenizers and numpy.

    python3 training/coreml_check.py build/tagger-coreml           # check, time, then a prompt
    python3 training/coreml_check.py build/tagger-coreml --bench   # check and time only

Ctrl-C or Ctrl-D stops the prompt.
"""
import argparse
import json
import statistics
import time
from pathlib import Path

import coremltools as ct
import numpy as np
from tokenizers import Tokenizer

# Copies of train.EDGE and train.first_pieces: this file runs on a Mac without PyTorch, so it cannot import train.py.
EDGE = ",.;:!?…-–—\"“”"


def first_pieces(word_ids):
    """For each word, the index of its first piece: that piece speaks for the word."""
    found, previous = [], None
    for j, word in enumerate(word_ids):
        if word is not None and word != previous:
            found.append(j)
        previous = word
    return found


def run(model, tokenizer, words):
    """The model's output for one sentence, given as words: no padding, so every piece is attended to."""
    enc = tokenizer.encode(words, is_pretokenized=True)
    ids = np.array([enc.ids], dtype=np.int32)
    out = model.predict({"input_ids": ids, "attention_mask": np.ones_like(ids)})
    return enc, np.array(out["labels"])[0], np.array(out["intent"])[0], np.array(out["urgency"])[0]


def bench(folder, tokenizer, sentences, rounds=200):
    """
    Time per sentence on each kind of compute unit: loading (compiling) the model, then tokenizing and predicting,
    after a warm-up. The times include Python's overhead around predict, so an app calling Core ML directly is a little faster.
    """
    pieces = [tokenizer.encode(s.split(), is_pretokenized=True).ids for s in sentences]
    started = time.perf_counter()
    for s in sentences * 20:
        tokenizer.encode(s.split(), is_pretokenized=True)
    tokenize = (time.perf_counter() - started) / (len(sentences) * 20) * 1000
    print(f"tokenizing: {tokenize:.3f} ms per sentence ({min(map(len, pieces))}-{max(map(len, pieces))} pieces)")
    units = {"all": ct.ComputeUnit.ALL, "cpu+neural engine": ct.ComputeUnit.CPU_AND_NE,
             "cpu+gpu": ct.ComputeUnit.CPU_AND_GPU, "cpu only": ct.ComputeUnit.CPU_ONLY}
    for name, unit in units.items():
        started = time.perf_counter()
        model = ct.models.MLModel(str(folder / "TimeWhisperTagger.mlpackage"), compute_units=unit)
        loading = (time.perf_counter() - started) * 1000
        inputs = [{"input_ids": np.array([ids], dtype=np.int32), "attention_mask": np.ones((1, len(ids)), dtype=np.int32)} for ids in pieces]
        for x in inputs * 3:
            model.predict(x)
        times = []
        for i in range(rounds):
            x = inputs[i % len(inputs)]
            started = time.perf_counter()
            model.predict(x)
            times.append((time.perf_counter() - started) * 1000)
        times.sort()
        print(f"{name:>18}: median {statistics.median(times):6.2f} ms, 95th percentile {times[int(len(times) * 0.95)]:6.2f} ms, "
              f"first load {loading:7.0f} ms")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("folder", nargs="?", default="build/tagger-coreml", help="the folder export.py wrote")
    p.add_argument("--bench", action="store_true", help="check and time only, no prompt")
    args = p.parse_args()
    folder = Path(args.folder)
    meta = json.loads((folder / "labels.json").read_text())
    labels, intents, urgencies = meta["labels"], meta["intents"], meta["urgencies"]
    model = ct.models.MLModel(str(folder / "TimeWhisperTagger.mlpackage"))
    tokenizer = Tokenizer.from_file(str(folder / "tokenizer.json"))
    cases = json.loads((folder / "reference.json").read_text())

    # 1. The same answers as PyTorch: the same pieces, the same label for every word, close probabilities. Core ML runs in
    # float16, so the probabilities differ a little; what counts is that every label, intent and urgency is the same.
    worst, failures = 0.0, 0
    for case in cases:
        enc, got, intent, urgency = run(model, tokenizer, case["text"].split())
        if enc.ids != case["input_ids"]:
            print(f"FAIL tokenizer differs: {case['text']!r}")
            failures += 1
            continue
        expected = np.array(case["labels"])
        firsts = first_pieces(case["word_ids"])
        same = (all(got[j].argmax() == expected[j].argmax() for j in firsts) and intent.argmax() == np.argmax(case["intent"])
                and urgency.argmax() == np.argmax(case["urgency"]))
        difference = float(max(np.abs(got - expected).max(), np.abs(intent - np.array(case["intent"])).max(),
                               np.abs(urgency - np.array(case["urgency"])).max()))
        worst = max(worst, difference)
        if not same:
            failures += 1
            print(f"FAIL labels differ: {case['text']!r}")
    print(f"reference: {'all match' if not failures else f'{failures} failed'}, largest probability difference {worst:.4f}")

    # 2. How long it takes.
    bench(folder, tokenizer, [case["text"] for case in cases])
    if args.bench:
        return

    # 3. Your own sentences.
    while True:
        try:
            text = input("\n> ").strip()
        except (KeyboardInterrupt, EOFError):
            print()
            return
        if not text:
            continue
        words = text.split()
        started = time.perf_counter()
        enc, got, intent, urgency = run(model, tokenizer, words)
        took = (time.perf_counter() - started) * 1000
        tagged = [(words[enc.word_ids[j]], labels[int(got[j].argmax())], float(got[j].max())) for j in first_pieces(enc.word_ids)]
        title = " ".join(w for w, l, _ in tagged if l == "T").strip().strip(EDGE).strip()
        print(f"  title    {title[:1].upper() + title[1:] if title else '—'}")
        print("  intent   " + "  ".join(f"{name} {float(intent[i]):.2f}" for i, name in enumerate(intents)))
        print("  urgency  " + "  ".join(f"{name} {float(urgency[i]):.2f}" for i, name in enumerate(urgencies)))
        print("  words    " + " ".join(f"{w}/{l}({c:.2f})" for w, l, c in tagged))
        print(f"  took     {took:.1f} ms ({len(enc.ids)} pieces)")


if __name__ == "__main__":
    main()
