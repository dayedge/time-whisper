#!/usr/bin/env python3
"""
Converts the trained tagger to Core ML. Needs torch, transformers and coremltools; no GPU.

    python export.py                      # reads tagger/, writes coreml/: English and Polish pieces only, 8-bit weights
    python export.py --model tagger --out coreml --languages en,pl
    python export.py --all-pieces --no-quantize                           # the full model, as trained

Two things make the model smaller without retraining:
- the vocabulary keeps only the pieces made of characters the languages are written in (plus every single character,
  so a foreign letter is still a known piece), and the embedding table only their rows. Text in those languages is
  tokenized exactly as before: a piece can only be used where all its characters occur, and none of those is removed.
  This is checked on the samples and on train.jsonl and dev.jsonl when they are in the current directory;
- the weights are stored as 8-bit integers (linear, symmetric) instead of 16-bit floats.

coreml/ then holds everything an Apple app needs: TimeWhisperTagger.mlpackage, tokenizer.json and labels.json,
plus reference.json, the PyTorch output for a few sentences, so the converted model can be checked on a Mac.
The model takes input_ids and attention_mask (int32, 1 × up to 128 pieces) and returns probabilities:
labels (1 × pieces × labels), intent (1 × intents) and urgency (1 × urgencies: none, L, M, H).
"""
import argparse
import json
import math
import shutil
from pathlib import Path

import coremltools as ct
import numpy as np
import torch
import torch.nn.functional as F
from tokenizers import Tokenizer
from transformers import AutoTokenizer, PreTrainedTokenizerFast

from train import load, load_tagger

SAMPLES = [
    "could you squeeze in coffee with Ilse thursday at 4pm, thanks",
    "don't let me forget I still have to renew the car insurance by friday !!",
    "standup every weekday at 9:30 @Office",
    "remind me to call April about the lease tomorrow",
    "already paid the electricity bill yesterday",
    "pick up the cake from Gail's",
    "send the signed lease to the landlord asap",
    "renew the domain next month, no rush",
]


# Characters every language here writes with: ASCII, common typography, and the accented Latin-1 letters that names and
# places bring into any of them ("Café Nero", "Zoë"). Then the letters each language adds. "▁" is not a character of the
# text: it is SentencePiece's mark for "a word starts here", which most pieces begin with.
COMMON = ("".join(chr(c) for c in range(0x20, 0x7F)) + "▁’‘“”„–—…€£°×·•"
          + "".join(chr(c) for c in range(0xC0, 0x100) if chr(c).isalpha()))
LETTERS = {"en": "", "pl": "ąćęłńóśźżĄĆĘŁŃÓŚŹŻ"}


def allowed_characters(languages):
    return set(COMMON) | set("".join(LETTERS[language] for language in languages))


def trim(tokenizer, embeddings, languages):
    """
    A tokenizer and an embedding table with only the pieces made of the languages' characters, every single character,
    and the special pieces. Returns the new tokenizer, the new table, and `new`: the new id of each kept old id.

    The base model's vocabulary covers about 50 languages, and its embedding table (one vector per piece) is most of the
    model's size. Trimming works on the tokenizer's JSON: the Unigram model is a list of [piece, score], so removing
    entries and renumbering the rest keeps every remaining piece's score, and so the way text is cut into pieces.
    """
    data = json.loads(tokenizer.backend_tokenizer.to_str())
    model = data["model"]
    if model["type"] != "Unigram":
        raise SystemExit(f"trimming is written for a Unigram vocabulary, not {model['type']}")
    allowed = allowed_characters(languages)
    specials = {token["id"] for token in data["added_tokens"]} | {model["unk_id"]}
    # A piece of one character (with or without "▁") is kept whatever the character, so a letter of another script is
    # still a piece and never becomes <unk>.
    keep = [i for i, (piece, _) in enumerate(model["vocab"])
            if i in specials or len(piece.replace("▁", "")) <= 1 or all(c in allowed for c in piece)]
    # Pieces keep their order, so new ids are their position among the kept ones. Every place the JSON names an id is
    # renumbered: the unknown piece, the special pieces, and the pieces the post-processor adds around a text.
    new = {old: i for i, old in enumerate(keep)}
    model["vocab"] = [model["vocab"][i] for i in keep]
    model["unk_id"] = new[model["unk_id"]]
    for token in data["added_tokens"]:
        token["id"] = new[token["id"]]
    post = data.get("post_processor") or {}
    for special in post.get("special_tokens", {}).values():     # TemplateProcessing
        special["ids"] = [new[i] for i in special["ids"]]
    for key in ("cls", "sep"):                                  # RobertaProcessing, BertProcessing: [token, id]
        if key in post:
            post[key][1] = new[post[key][1]]
    small = PreTrainedTokenizerFast(tokenizer_object=Tokenizer.from_str(json.dumps(data)), **tokenizer.special_tokens_map)
    # Row i of the new table is row keep[i] of the old one, so each kept piece keeps its trained vector under its new id.
    table = torch.nn.Embedding(len(keep), embeddings.embedding_dim)
    table.weight.data = embeddings.weight.data[keep].clone()
    return small, table, new


def same_pieces(before, after, new, texts, allowed):
    """Checks that every text written only in the allowed characters is split into the same pieces as before."""
    checked = 0
    for text in texts:
        if not all(c in allowed or c.isspace() for c in text):
            continue
        words = text.split()
        old = before([words], is_split_into_words=True)["input_ids"][0]
        # A piece that was removed maps to -1, which matches nothing, so the check fails on it.
        if [new.get(i, -1) for i in old] != after([words], is_split_into_words=True)["input_ids"][0]:
            raise SystemExit(f"the trimmed vocabulary splits {text!r} differently")
        checked += 1
    return checked


class PlainEncoder(torch.nn.Module):
    """
    The BERT encoder written out with its own weights, in operations Core ML converts. Newer transformers build the
    attention mask with operations coremltools cannot convert, and their internals change between versions; this does
    not depend on either. main() checks that it gives the same output as the transformers model before converting.

    It is BertModel in eval mode, step by step: embeddings, then for each layer self-attention and a feed-forward block,
    each added to its input and normalised (post-LayerNorm). Dropout is left out: it does nothing in eval. The batch is
    always one sentence.
    """

    def __init__(self, bert):
        super().__init__()
        config = bert.config
        if config.model_type != "bert" or config.hidden_act != "gelu":
            raise SystemExit(f"PlainEncoder is written for a BERT encoder with gelu, not {config.model_type}/{config.hidden_act}")
        self.bert, self.heads = bert, config.num_attention_heads
        self.head_size = config.hidden_size // config.num_attention_heads

    def split(self, x):
        """(1, pieces, hidden) → (1, heads, pieces, head size): each attention head looks at its own slice of the vector."""
        return x.reshape(1, -1, self.heads, self.head_size).transpose(1, 2)

    def forward(self, input_ids, attention_mask):
        e = self.bert.embeddings
        # A piece's vector is the sum of three: what the piece is, where it stands (0, 1, 2…), and which segment it is in.
        # There is only ever one segment (0): training never passed token_type_ids, and BERT then uses zeros.
        positions = torch.arange(input_ids.shape[1]).unsqueeze(0)
        x = e.word_embeddings(input_ids) + e.position_embeddings(positions) + e.token_type_embeddings(torch.zeros_like(input_ids))
        x = e.LayerNorm(x)
        # Added to the attention scores: 0 where a piece may be attended to, -10000 where it is padding. BERT's own value:
        # it must stay finite, as the model runs in float16 (whose largest negative is about -65504, where -inf would turn
        # into NaN), and after softmax exp(-10000) is exactly 0, so padding gets no attention.
        bias = (1.0 - attention_mask[:, None, None, :].float()) * -10000.0
        for layer in self.bert.encoder.layer:
            attention = layer.attention
            q, k, v = (self.split(f(x)) for f in (attention.self.query, attention.self.key, attention.self.value))
            # Scaled dot-product attention: how much each piece looks at each other piece, as weights that sum to 1.
            weights = (q @ k.transpose(-1, -2) / math.sqrt(self.head_size) + bias).softmax(-1)
            # The heads' results side by side again: (1, heads, pieces, head size) → (1, pieces, hidden).
            context = (weights @ v).transpose(1, 2).reshape(1, -1, self.heads * self.head_size)
            x = attention.output.LayerNorm(attention.output.dense(context) + x)
            # The feed-forward block: wider, through GELU (exact, as the hidden_act check ensures), and back.
            x = layer.output.LayerNorm(layer.output.dense(F.gelu(layer.intermediate.dense(x))) + x)
        return x


class Probabilities(torch.nn.Module):
    """
    The tagger's heads on the plain encoder, with softmax applied, so the app reads probabilities directly. Training
    leaves softmax out because the loss wants logits; the largest is the same either way, and the app also gets how
    sure the model is. The heads are called directly, so the tagger's dropout is skipped (it does nothing in eval).
    """

    def __init__(self, tagger):
        super().__init__()
        self.encoder, self.tagger = PlainEncoder(tagger.encoder), tagger

    def forward(self, input_ids, attention_mask):
        states = self.encoder(input_ids, attention_mask)
        first = states[:, 0]
        return (self.tagger.token_head(states).softmax(-1), self.tagger.intent_head(first).softmax(-1),
                self.tagger.urgency_head(first).softmax(-1))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", default="tagger")
    p.add_argument("--out", default="coreml")
    # The most pieces the Core ML model accepts. The app (Loaded.maxPieces in CoreMLTagger.swift) must use the same.
    p.add_argument("--max-pieces", type=int, default=128)
    p.add_argument("--languages", default="en,pl", help=f"languages whose pieces are kept; known: {', '.join(LETTERS)}")
    p.add_argument("--all-pieces", action="store_true", help="keep the whole vocabulary")
    p.add_argument("--no-quantize", action="store_true", help="keep 16-bit weights")
    args = p.parse_args()

    folder, out = Path(args.model), Path(args.out)
    tagger, meta = load_tagger(folder)
    # From the base model rather than tagger/: the export may run on an older transformers than training did.
    tokenizer = AutoTokenizer.from_pretrained(meta["base"])

    if not args.all_pieces:
        languages = args.languages.split(",")
        full = tokenizer
        word_embeddings = tagger.encoder.embeddings.word_embeddings
        # The tagger's embedding table is swapped for the trimmed one; the rest of the model is untouched.
        tokenizer, tagger.encoder.embeddings.word_embeddings, new = trim(full, word_embeddings, languages)
        texts = list(SAMPLES)
        for name in ("train.jsonl", "dev.jsonl"):
            if Path(name).exists():
                texts += [row["text"] for row in load(name)]
        checked = same_pieces(full, tokenizer, new, texts, allowed_characters(languages))
        print(f"vocabulary: {len(new)} of {word_embeddings.num_embeddings} pieces kept for {args.languages}; "
              f"{checked} texts split exactly as before")
    model = Probabilities(tagger).eval()

    def encode(text):
        """The pieces of a text as the app makes them: int32, one sentence, no padding, so every piece is attended to."""
        enc = tokenizer([text.split()], is_split_into_words=True, truncation=True, max_length=args.max_pieces, return_tensors="pt")
        return enc["input_ids"].int(), enc["attention_mask"].int(), enc.word_ids(0)

    # The plain encoder must say what the transformers model says, or the export would be of a different model.
    for text in SAMPLES:
        ids, mask, _ = encode(text)
        with torch.no_grad():
            # The transformers model wants int64 ids; the plain one is traced with the int32 the app passes.
            trained = [o.softmax(-1) for o in tagger(ids.long(), mask.long())]
            plain = model(ids, mask)
        # Both run in float32 and differ only in the order of operations, so they agree to about 1e-6; 1e-4 leaves room.
        difference = max((a - b).abs().max().item() for a, b in zip(trained, plain))
        if difference > 1e-4:
            raise SystemExit(f"the plain encoder differs from the trained model by {difference:.6f} on {text!r}")
    print("plain encoder matches the trained model")

    # Tracing runs the model once and records its operations; coremltools converts that record.
    ids, mask, _ = encode(SAMPLES[0])
    with torch.no_grad():
        traced = torch.jit.trace(model, (ids, mask))

    # The number of pieces varies from 1 to --max-pieces; `default` is only the shape Core ML plans for.
    pieces = ct.RangeDim(lower_bound=1, upper_bound=args.max_pieces, default=ids.shape[1])
    mlmodel = ct.convert(
        traced,
        inputs=[
            ct.TensorType(name="input_ids", shape=(1, pieces), dtype=np.int32),
            ct.TensorType(name="attention_mask", shape=(1, pieces), dtype=np.int32),
        ],
        outputs=[ct.TensorType(name="labels"), ct.TensorType(name="intent"), ct.TensorType(name="urgency")],
        convert_to="mlprogram",
        minimum_deployment_target=ct.target.macOS14,
        # Core ML computes in float16, the Neural Engine's own type. Probabilities differ slightly from PyTorch's float32;
        # coreml_check.py checks on a Mac that the labels still match.
        compute_precision=ct.precision.FLOAT16,
    )
    # Only the stored weights become 8-bit integers, each output channel with its own scale (symmetric, so no offset).
    # The file is about half the size; Core ML turns the weights back into float16 when it runs.
    if not args.no_quantize:
        import coremltools.optimize.coreml as cto
        config = cto.OptimizationConfig(global_config=cto.OpLinearQuantizerConfig(mode="linear_symmetric"))
        mlmodel = cto.linear_quantize_weights(mlmodel, config=config)
        print("weights quantized to 8 bits")
    mlmodel.short_description = "time-whisper tagger: a label per word piece, the intent of the sentence and the urgency it states"
    # For anyone opening the model alone; the app reads labels.json.
    for key in ("labels", "intents", "urgencies"):
        mlmodel.user_defined_metadata[key] = json.dumps(meta[key])
    mlmodel.user_defined_metadata["base"] = meta["base"]

    out.mkdir(parents=True, exist_ok=True)
    target = out / "TimeWhisperTagger.mlpackage"
    if target.exists():
        shutil.rmtree(target)
    mlmodel.save(str(target))
    tokenizer.save_pretrained(out)
    shutil.copy(folder / "labels.json", out / "labels.json")

    # What PyTorch says for the samples, to compare the Core ML model against on a Mac.
    reference = []
    for text in SAMPLES:
        ids, mask, word_ids = encode(text)
        with torch.no_grad():
            labels, intent, urgency = model(ids, mask)
        reference.append({
            "text": text, "input_ids": ids[0].tolist(), "word_ids": word_ids,
            "labels": labels[0].tolist(), "intent": intent[0].tolist(), "urgency": urgency[0].tolist(),
        })
    (out / "reference.json").write_text(json.dumps(reference))
    (out / "export.json").write_text(json.dumps({
        "languages": args.languages.split(","), "max_pieces": args.max_pieces,
        "all_pieces": args.all_pieces, "quantized": not args.no_quantize, "base": meta["base"],
    }, indent=2) + "\n")
    print(f"wrote {target}, tokenizer.json, labels.json and reference.json to {out}/")


if __name__ == "__main__":
    main()
