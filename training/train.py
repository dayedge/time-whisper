#!/usr/bin/env python3
"""
Trains the tagger on the rows written by generate.py: one label per word, and per sentence its intent and the
urgency it states in words (none, L, M or H).

    python train.py                                   # reads train.jsonl and dev.jsonl from the current directory
    python train.py --train data/train.jsonl --dev data/dev.jsonl --epochs 8

Needs torch and transformers. The best epoch on dev (exact title and exact role spans, averaged) is saved to tagger/,
with every epoch's scores in tagger/metrics.jsonl and the dev mistakes of the best epoch in tagger/dev-errors.tsv
(words) and tagger/dev-spans.tsv (role spans). The log ends with the words behind the main confusions, the sources with
the most wrong words, and the words the model still gets wrong in its own training rows (tagger/train-errors.tsv),
which usually point at data that disagrees with itself.

The model is a small multilingual BERT (the encoder) with three linear heads on top: a label for every word piece,
and from the sentence's first piece its intent and its urgency.
"""
import argparse
import json
from collections import Counter
from pathlib import Path

import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from transformers import AutoModel, AutoTokenizer

# The labels in the order the model numbers them; labels.json carries it to the app. A copy of sentences.LABELS, as this
# file runs on a machine without the generator: a label change goes into both.
LABELS = ["T", "W", "DAY", "CLOCK", "END", "LENGTH", "DUE", "REPEAT", "STARTS", "ENDS", "PLACE", "LIST", "CALENDAR",
          "PRIORITY", "REMINDER", "KIND"]
# The roles the spell checker is asked about.
TIME = {"DAY", "CLOCK", "END", "LENGTH", "DUE", "REPEAT", "STARTS", "ENDS"}
ROLES = [l for l in LABELS if l not in ("T", "W")]
INTENTS = ["task", "event", "none"]
URGENCIES = ["none", "L", "M", "H"]
# Punctuation around a word that is not part of it.
EDGE = ",.;:!?…-–—\"“”"
# The most word pieces a sentence is cut to in training and in try.py. Rows are at most 40 words (generate.py), well
# under this, so it only guards against a stray long row. The export allows 128, which is what the app reads.
MAX_PIECES = 96
# PyTorch's "no label here" for cross-entropy: such positions add nothing to the loss.
IGNORED = -100


def load(path):
    return [json.loads(line) for line in open(path, encoding="utf-8")]


def device():
    """The fastest place to run: an NVIDIA GPU, Apple's GPU, or the CPU."""
    return "cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu"


def spans(labels):
    """The runs of one role, as (label, first word, last word + 1). Title and wrapper words are not spans."""
    out, start = [], 0
    for i in range(1, len(labels) + 1):
        if i == len(labels) or labels[i] != labels[start]:
            if labels[start] in ROLES:
                out.append((labels[start], start, i))
            start = i
    return out


def title_of(words, labels):
    """The title words, without punctuation at the ends, with a capital first letter: what the parser makes the title."""
    text = " ".join(w for w, l in zip(words, labels) if l == "T").strip().strip(EDGE).strip()
    return text[:1].upper() + text[1:]


def first_pieces(word_ids):
    """
    For each word, the position of its first piece. The tokenizer cuts a word into pieces ("tomorrow" → "▁tomor", "row");
    the first one speaks for the word, in training and in the app. word_ids gives each piece's word, None for the special
    pieces that open and close the text.
    """
    found, previous = [], None
    for j, word in enumerate(word_ids):
        if word is not None and word != previous:
            found.append(j)
        previous = word
    return found


class Tagger(nn.Module):
    """The encoder and the three heads. Its outputs are logits; the export adds the softmax."""

    def __init__(self, base):
        super().__init__()
        self.encoder = AutoModel.from_pretrained(base)
        hidden = self.encoder.config.hidden_size
        self.dropout = nn.Dropout(0.1)  # BERT's own rate
        self.token_head = nn.Linear(hidden, len(LABELS))
        self.intent_head = nn.Linear(hidden, len(INTENTS))
        self.urgency_head = nn.Linear(hidden, len(URGENCIES))

    def forward(self, input_ids, attention_mask):
        states = self.dropout(self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state)
        # The first piece is the tokenizer's sentence-opening token; its state stands for the whole sentence, as in BERT
        # sentence classification.
        return self.token_head(states), self.intent_head(states[:, 0]), self.urgency_head(states[:, 0])


def load_tagger(folder):
    """A tagger train.py saved, and what labels.json says about it, ready to run."""
    folder = Path(folder)
    meta = json.loads((folder / "labels.json").read_text())
    model = Tagger(meta["base"])
    model.load_state_dict(torch.load(folder / "model.pt", map_location="cpu"))
    return model.eval(), meta


def collate_with(tokenizer):
    def collate(rows):
        """A batch: the pieces of each row padded to the longest, and the label of every piece that starts a word."""
        words = [r["text"].split(" ") for r in rows]
        enc = tokenizer(words, is_split_into_words=True, padding=True, truncation=True, max_length=MAX_PIECES, return_tensors="pt")
        # Only the first piece of a word carries its label. The other pieces, the opening and closing ones and the padding
        # are ignored by the loss. evaluate() relies on this: the labelled positions are exactly the words, in order.
        tags = torch.full(enc["input_ids"].shape, IGNORED)
        for i, row in enumerate(rows):
            word_ids = enc.word_ids(i)
            for j in first_pieces(word_ids):
                tags[i, j] = LABELS.index(row["labels"][word_ids[j]])
        intents = torch.tensor([INTENTS.index(r["intent"]) for r in rows])
        urgencies = torch.tensor([URGENCIES.index(r.get("urgency") or "none") for r in rows])
        return enc["input_ids"], enc["attention_mask"], tags, intents, urgencies, rows
    return collate


def role_scores(hit, truth, guess):
    """Precision, recall and F1 of the exact spans of each role."""
    roles = {}
    for label in ROLES:
        precision = hit[label] / guess[label] if guess[label] else 0.0
        recall = hit[label] / truth[label] if truth[label] else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        roles[label] = {"precision": precision, "recall": recall, "f1": f1, "support": truth[label]}
    return roles


@torch.no_grad()
def evaluate(model, loader, device):
    """The scores of the model on the rows, with the confusions and every mistake, for the reports."""
    model.eval()
    misspelt = misspelt_right = 0
    titles, titles_right = Counter(), Counter()
    # Rows are the truth, columns what the model said.
    confusion = torch.zeros(len(LABELS), len(LABELS), dtype=torch.long)
    intent_confusion = torch.zeros(len(INTENTS), len(INTENTS), dtype=torch.long)
    urgency_confusion = torch.zeros(len(URGENCIES), len(URGENCIES), dtype=torch.long)
    # Role spans: found exactly (same role, same words), in the truth, in the guess.
    span_hit, span_truth, span_guess = Counter(), Counter(), Counter()
    mistakes, span_mistakes = [], []
    for input_ids, mask, tags, intents, urgencies, rows in loader:
        token_logits, intent_logits, urgency_logits = model(input_ids.to(device), mask.to(device))
        predicted = token_logits.argmax(-1).cpu()
        predicted_intent = intent_logits.argmax(-1).cpu()
        predicted_urgency = urgency_logits.argmax(-1).cpu()
        for i, row in enumerate(rows):
            # One guess per word that survived truncation, read at the word's first piece.
            first = tags[i] != IGNORED
            guess = [LABELS[k] for k in predicted[i][first].tolist()]
            truth = row["labels"][: len(guess)]
            words_of = row["text"].split(" ")
            for at, (g, t) in enumerate(zip(guess, truth)):
                confusion[LABELS.index(t), LABELS.index(g)] += 1
                if g != t:
                    marked = " ".join(f"[{w}]" if j == at else w for j, w in enumerate(words_of))
                    mistakes.append((f"{t}>{g}", words_of[at], marked, row.get("where", "")))
            true_spans, guessed_spans = set(spans(truth)), set(spans(guess))
            for label, a, b in true_spans:
                span_truth[label] += 1
                if (label, a, b) in guessed_spans:
                    span_hit[label] += 1
                else:
                    span_mistakes.append((label, " ".join(words_of[a:b]), " ".join(f"{w}/{g}" for w, g in zip(words_of[a:b], guess[a:b])),
                                          row["text"], row.get("where", "")))
            for label, _, _ in guessed_spans:
                span_guess[label] += 1
            # Misspelt date and repeat words: the spell checker is only asked about words tagged with a time role.
            for at in row.get("misspelt", []):
                if at < len(guess) and truth[at] in TIME:
                    misspelt += 1
                    misspelt_right += guess[at] in TIME
            intent_confusion[INTENTS.index(row["intent"]), int(predicted_intent[i])] += 1
            urgency_confusion[int(urgencies[i]), int(predicted_urgency[i])] += 1
            if row["intent"] != "none":
                right = title_of(words_of[: len(guess)], guess) == row["title"]
                for key in ("all", row.get("source", "?")):
                    titles[key] += 1
                    titles_right[key] += right
    hit, truth_n, guess_n = sum(span_hit.values()), sum(span_truth.values()), sum(span_guess.values())
    # Urgency: how often a stated one is read right (row 0 is "none stated"), and how often one is read where none was.
    u = urgency_confusion
    stated, stated_right = int(u[1:].sum()), int(u.diagonal()[1:].sum())
    invented, unstated = int(u[0, 1:].sum()), int(u[0].sum())
    return {"words": int(confusion.trace()) / int(confusion.sum()), "title": titles_right["all"] / max(titles["all"], 1),
            "title by source": {k: titles_right[k] / titles[k] for k in titles if k != "all"},
            "spans": 2 * hit / (truth_n + guess_n) if truth_n + guess_n else 0.0,
            "roles": role_scores(span_hit, span_truth, span_guess),
            "intent": int(intent_confusion.trace()) / int(intent_confusion.sum()),
            "urgency": stated_right / max(stated, 1), "invented urgency": invented / max(unstated, 1),
            "misspelt dates": misspelt_right / max(misspelt, 1), "confusion": confusion, "intent confusion": intent_confusion,
            "urgency confusion": urgency_confusion, "mistakes": mistakes, "span mistakes": span_mistakes}


# What is printed and saved for each epoch; the rest (confusions, mistakes) only for the best one.
SCALARS = ("words", "title", "spans", "intent", "urgency", "invented urgency", "misspelt dates")


def make_losses(rows, label_weights, device):
    """
    One cross-entropy per head. A rare urgency level, and with --label-weights sqrt a rare word label, weighs by the square
    root of how rare it is, so the model does not learn to always say the common one, without the rare ones drowning out
    everything else.
    """
    def rarity(counts):
        # total / (classes × count) is the usual balanced class weight: a class seen half as often weighs twice as much.
        # The square root softens it; clamp keeps a class with no rows from dividing by zero.
        return (counts.sum() / (len(counts) * counts.clamp(min=1))).sqrt()
    words = Counter(label for r in rows for label in r["labels"])
    word_counts = torch.tensor([words[l] for l in LABELS], dtype=torch.float)
    token_weights = rarity(word_counts).to(device) if label_weights == "sqrt" else None
    stated = Counter(r.get("urgency") or "none" for r in rows)
    counts = torch.tensor([stated[u] for u in URGENCIES], dtype=torch.float)
    print("urgency rows in training: " + ", ".join(f"{u} {int(n)}" for u, n in zip(URGENCIES, counts)))
    return (nn.CrossEntropyLoss(weight=token_weights, ignore_index=IGNORED), nn.CrossEntropyLoss(),
            nn.CrossEntropyLoss(weight=rarity(counts).to(device)))


def train_epoch(model, loader, losses, optimizer, schedule, device):
    """One pass over the training rows. Returns the mean loss."""
    token_loss, intent_loss, urgency_loss = losses
    model.train()
    total = 0.0
    for input_ids, mask, tags, intents, urgencies, _ in loader:
        token_logits, intent_logits, urgency_logits = model(input_ids.to(device), mask.to(device))
        # Cross-entropy wants (examples, classes): every piece of every row is one example.
        loss = (token_loss(token_logits.flatten(0, 1), tags.flatten().to(device)) + intent_loss(intent_logits, intents.to(device))
                + urgency_loss(urgency_logits, urgencies.to(device)))
        optimizer.zero_grad()
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)  # the usual BERT fine-tuning value
        optimizer.step()
        schedule.step()
        total += loss.item()
    return total / len(loader)


def write_tsv(path, header, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write(header + "\n")
        for row in sorted(rows):
            f.write("\t".join(row) + "\n")


def save_best(out, model, base, epoch, scores):
    """The model, labels.json (what the app reads, with the dev scores), and the dev mistakes of this epoch."""
    torch.save(model.state_dict(), out / "model.pt")
    dev_scores = {k: scores[k] for k in SCALARS} | {"roles": scores["roles"], "title by source": scores["title by source"]}
    (out / "labels.json").write_text(json.dumps({"base": base, "labels": LABELS, "intents": INTENTS, "urgencies": URGENCIES,
                                                 "epoch": epoch, "dev": dev_scores}, indent=2))
    # Every word the model got wrong on dev, grouped by truth>predicted: what to fix in the data next.
    write_tsv(out / "dev-errors.tsv", "truth>predicted\tword\tsentence\tsource", scores["mistakes"])
    # Every role span not found exactly, with what the model said for each of its words.
    write_tsv(out / "dev-spans.tsv", "role\tspan\tpredicted\tsentence\tsource", scores["span mistakes"])


def print_roles(roles):
    """Exact role spans on dev: F1, and how many there were."""
    print("  role spans (F1/support): " + "  ".join(f"{l} {r['f1']:.2f}/{r['support']}" for l, r in roles.items() if r["support"]))


def print_words(mistakes, top=10, per=6):
    """The words behind the most frequent confusions: "END>CLOCK: until 41, till 7, to 5". One line per confusion."""
    by = {}
    for pair, word, _, _ in mistakes:
        by.setdefault(pair, Counter())[word.lower().strip(EDGE)] += 1
    print(f"words behind the top {top} confusions (truth > predicted):")
    for pair, words in sorted(by.items(), key=lambda kv: -sum(kv[1].values()))[:top]:
        print(f"  {pair:<18} " + ", ".join(f"{w} {n}" for w, n in words.most_common(per)))


def print_sources(mistakes, rows, top=10):
    """Wrong words per file the rows came from, against the words it has: a badly labelled file stands out."""
    words, wrong = Counter(), Counter()
    for row in rows:
        words[row.get("where", "?").rsplit(":", 1)[0]] += len(row["labels"])
    for _, _, _, where in mistakes:
        wrong[where.rsplit(":", 1)[0]] += 1
    print(f"wrong words by source (the {top} worst):")
    for source, n in sorted(wrong.items(), key=lambda kv: -kv[1] / max(words[kv[0]], 1))[:top]:
        print(f"  {source:<40} {n:5d} of {words[source]:6d}  {n / max(words[source], 1):.1%}")


def print_confusions(confusion, top=10):
    """The most frequent word mistakes, instead of a matrix too wide to read."""
    pairs = [(int(confusion[i, j]), LABELS[i], LABELS[j]) for i in range(len(LABELS)) for j in range(len(LABELS)) if i != j and confusion[i, j]]
    print(f"top {top} word confusions (truth > predicted):")
    for n, t, g in sorted(pairs, reverse=True)[:top]:
        print(f"  {n:6d}  {t} > {g}")


def print_matrix(name, matrix, names):
    print(f"{name} (rows: truth, columns: predicted)")
    print("        " + " ".join(f"{n:>7}" for n in names))
    for i, n in enumerate(names):
        print(f"{n:>8}" + " ".join(f"{int(v):>7}" for v in matrix[i]))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--train", default="train.jsonl")
    p.add_argument("--dev", default="dev.jsonl")
    p.add_argument("--base", default="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    p.add_argument("--epochs", type=int, default=6)
    p.add_argument("--batch", type=int, default=32)
    p.add_argument("--lr", type=float, default=5e-5)
    p.add_argument("--out", default="tagger")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--label-weights", choices=("none", "sqrt"), default="none",
                   help="sqrt: each word label weighs by the square root of how rare it is, for roles with few examples")
    args = p.parse_args()

    torch.manual_seed(args.seed)
    run_on = device()
    tokenizer = AutoTokenizer.from_pretrained(args.base)
    collate = collate_with(tokenizer)
    train = DataLoader(load(args.train), batch_size=args.batch, shuffle=True, collate_fn=collate)
    dev = DataLoader(load(args.dev), batch_size=128, collate_fn=collate)
    print(f"device {run_on}, train {len(train.dataset)} rows, dev {len(dev.dataset)} rows")
    # How many spans of each role there are to learn from, and to be measured on.
    support = {name: Counter(label for r in loader.dataset for label, _, _ in spans(r["labels"])) for name, loader in (("train", train), ("dev", dev))}
    print("role spans (train/dev): " + "  ".join(f"{l} {support['train'][l]}/{support['dev'][l]}" for l in ROLES))

    model = Tagger(args.base).to(run_on)
    # The usual BERT fine-tuning: AdamW with a little weight decay.
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    steps = args.epochs * len(train)

    def warmup_then_decay(step):
        """The learning rate's factor: rising over the first 10% of the steps, then falling to 0 at the last step."""
        return min(1.0, step / (0.1 * steps)) * max(0.0, (steps - step) / (0.9 * steps))
    schedule = torch.optim.lr_scheduler.LambdaLR(optimizer, warmup_then_decay)
    losses = make_losses(train.dataset, args.label_weights, run_on)

    out = Path(args.out)
    out.mkdir(exist_ok=True)
    # The tokenizer does not change in training; the app reads it from the export, try.py from here.
    tokenizer.save_pretrained(out)
    metrics = open(out / "metrics.jsonl", "w", encoding="utf-8")
    best, best_scores = -1.0, None
    for epoch in range(1, args.epochs + 1):
        loss = train_epoch(model, train, losses, optimizer, schedule, run_on)
        scores = evaluate(model, dev, run_on)
        # The title is what the user sees; the role spans are what the readers get. Both count.
        score = (scores["title"] + scores["spans"]) / 2
        print(f"epoch {epoch}: loss {loss:.4f}  " + "  ".join(f"{k} {scores[k]:.3f}" for k in SCALARS) + f"  → {score:.3f}")
        print_roles(scores["roles"])
        print("  exact title by source: " + "  ".join(f"{k} {v:.3f}" for k, v in sorted(scores["title by source"].items())))
        metrics.write(json.dumps({"epoch": epoch, "loss": loss, "score": score, "title by source": scores["title by source"],
                                  "roles": scores["roles"]} | {k: scores[k] for k in SCALARS}) + "\n")
        metrics.flush()
        if score > best:
            best, best_scores = score, scores
            save_best(out, model, args.base, epoch, scores)
    metrics.close()

    print(f"best epoch on dev: {best:.3f} (exact title {best_scores['title']:.3f}, role spans {best_scores['spans']:.3f}), saved to {args.out}/")
    print_confusions(best_scores["confusion"])
    print_words(best_scores["mistakes"])
    print_sources(best_scores["mistakes"], dev.dataset)
    # Words the model still gets wrong in the rows it learned from: the data most likely disagrees with itself there.
    # This is the model of the last epoch, which has seen the training rows the most.
    fit = evaluate(model, DataLoader(train.dataset, batch_size=128, collate_fn=collate), run_on)
    write_tsv(out / "train-errors.tsv", "truth>predicted\tword\tsentence\tsource", fit["mistakes"])
    print(f"on its own training rows (last epoch): words {fit['words']:.3f}, exact title {fit['title']:.3f}")
    print_words(fit["mistakes"], top=5)
    print_matrix("intent", best_scores["intent confusion"], INTENTS)
    print_matrix("urgency", best_scores["urgency confusion"], URGENCIES)
    print(f"dev mistakes of the best epoch: {args.out}/dev-errors.tsv (words), {args.out}/dev-spans.tsv (role spans); "
          f"on training rows: {args.out}/train-errors.tsv")


if __name__ == "__main__":
    main()
