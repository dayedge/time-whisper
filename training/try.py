#!/usr/bin/env python3
"""
Type a sentence, see what the trained tagger makes of it. Ctrl-C or Ctrl-D to stop.

    python try.py                 # uses tagger/ saved by train.py, in the current directory
    python try.py --model tagger
"""
import argparse

import torch
from transformers import AutoTokenizer

from train import EDGE, MAX_PIECES, device, first_pieces, load_tagger, title_of


def runs(words, labels, wanted):
    """Neighbouring words with the same label, joined: "on friday at 4pm" rather than four words."""
    found, current = [], []
    for word, label in zip(words, labels):
        if label == wanted:
            current.append(word)
        elif current:
            found.append(" ".join(current))
            current = []
    if current:
        found.append(" ".join(current))
    return [f.strip(EDGE) or f for f in found]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", default="tagger")
    args = p.parse_args()

    run_on = device()
    tokenizer = AutoTokenizer.from_pretrained(args.model)
    model, meta = load_tagger(args.model)
    model.to(run_on)
    labels, intents, urgencies = meta["labels"], meta["intents"], meta["urgencies"]
    print(f"model from epoch {meta['epoch']}, dev exact title {meta['dev']['title']:.3f}, role spans {meta['dev']['spans']:.3f}. "
          "Ctrl-C to stop.")

    while True:
        try:
            text = input("\n> ").strip()
        except (KeyboardInterrupt, EOFError):
            print()
            return
        if not text:
            continue
        words = text.split()
        enc = tokenizer([words], is_split_into_words=True, truncation=True, max_length=MAX_PIECES, return_tensors="pt")
        with torch.no_grad():
            token_logits, intent_logits, urgency_logits = model(enc["input_ids"].to(run_on), enc["attention_mask"].to(run_on))
        probabilities = token_logits[0].softmax(-1).cpu()
        # One label per word, read at its first piece; words cut off by truncation are left out.
        word_ids = enc.word_ids(0)
        tagged = []
        for j in first_pieces(word_ids):
            k = int(probabilities[j].argmax())
            tagged.append((words[word_ids[j]], labels[k], float(probabilities[j][k])))
        words = [w for w, _, _ in tagged]
        tags = [l for _, l, _ in tagged]
        intent = intent_logits[0].softmax(-1).cpu()
        urgency = urgency_logits[0].softmax(-1).cpu()

        print(f"  title    {title_of(words, tags) or '—'}")
        print("  intent   " + "  ".join(f"{name} {float(intent[i]):.2f}" for i, name in enumerate(intents)))
        print("  urgency  " + "  ".join(f"{name} {float(urgency[i]):.2f}" for i, name in enumerate(urgencies)))
        for label in labels:
            found = runs(words, tags, label) if label != "T" else []
            if found:
                print(f"  {label.lower():<8} " + " | ".join(found))
        print("  words    " + " ".join(f"{w}/{l}({c:.2f})" for w, l, c in tagged))


if __name__ == "__main__":
    main()
