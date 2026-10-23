# Prompt: sentences

Give a model this file and `labels.md`. Save its answer to a file, review it, and add it with
`make add-sentences FILE=<file>`: lines that do not parse, repeat a line or are evaluation sentences are rejected, and
labelled parts the readers cannot read are listed, so a wrong label can be fixed. Then delete the file.

---

Write 150 sentences a real person might type, in one line, into a calendar or to-do app, each in the markup of
`labels.md` with its intent and flags.

- Every sentence must be a construction of its own, not a variation of another. Do not reuse a wrapper.
- Write the way people actually type: lowercase, abbreviations, missing punctuation, the odd emoji, run-on thoughts,
  half sentences. Some should be tidy.
- Cover work, home, money, health, school, family, travel, hobbies. Invent names and places; do not use famous people.
- About 15% should create nothing (`none`): statements about the past, questions, cancellations, availability, chat.
- About 15% should be traps: names, titles or numbers that look like dates or times.
- Include: titles interrupted by a date; requests with negation or double negation; several actions joined by
  "and" or commas; dates said vaguely ("after work", "before Gran visits"); the request at the end of the sentence.
- About 10% should contain one realistic typo; flag those lines `typo`.
- About 10% should state an urgency in words, spread over L, M and H, in varied phrasing and position; write the letter
  after the intent. About 5% more should only imply pressure without stating it, and get no letter.

Label every word. When unsure between two labels, follow `labels.md`; if it does not settle the case, leave the
sentence out.

Output only the lines.
