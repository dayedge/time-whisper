SHELL := /bin/sh
ifneq ($(wildcard /Applications/Xcode.app/Contents/Developer),)
export DEVELOPER_DIR := /Applications/Xcode.app/Contents/Developer
endif
GRADLE = sh ./gradlew --console=plain
HOST_ARCH := $(shell uname -m)
NATIVE_TEST := $(if $(filter arm64,$(HOST_ARCH)),macosArm64Test,macosX64Test)
.PHONY: build test check package swift-test model evaluate evaluate-tools score corpus accept-corpus try swiftrepl training-data add-sentences clean
build:
	$(GRADLE) jvmJar linkReleaseFrameworkMacosArm64 linkReleaseFrameworkMacosX64

test:
	$(GRADLE) jvmTest $(NATIVE_TEST)

package:
	$(GRADLE) assembleTimeWhisperCoreReleaseXCFramework
	python3 scripts/package.py

swift-test:
	$(GRADLE) assembleTimeWhisperCoreReleaseXCFramework
	swift test

# Accepted corpus results and release qualification always include the system spell checker.
check corpus accept-corpus: override SPELLING = 1
check: test swift-test corpus

# The tagger model: the folder training/export.py wrote. It is not in git.
MODEL ?= build/tagger-coreml
CORPUS ?= fixtures/en-evaluation-corpus.jsonl
# The languages with a corpus in fixtures/: each one is a gate, against its own list of known failures.
CORPORA ?= en pl
# The corpus measures the whole pipeline, the system spell checker included. SPELLING= turns it off.
SPELLING ?= 1
NAME = $(basename $(notdir $(CORPUS)))$(if $(SPELLING),,-no-spelling)
OBSERVED = build/reports/observed-$(NAME).jsonl
# The corpus's list of known failures, beside it: fixtures/en-evaluation-known-failures.txt.
KNOWN = $(patsubst %-corpus.jsonl,%-known-failures.txt,$(CORPUS))

model:
	@test -f $(MODEL)/labels.json || { echo "No tagger model in $(MODEL): copy the folder training/export.py wrote there, or set MODEL=<folder>."; exit 1; }

# Runs CORPUS through the Swift API with the model and writes build/reports/corpus-<corpus>.md. GATE=check compares the
# result with the corpus's list of known failures, GATE=accept rewrites that list.
# With SPELLING= it runs without the system spell checker, which is the same on every Mac but reads no misspelt date.
evaluate: evaluate-tools score

# What a corpus run needs built: the Kotlin framework and the Swift evaluator.
evaluate-tools: model
	$(GRADLE) assembleTimeWhisperCoreReleaseXCFramework
	swift build -c release --product TimeWhisperEvaluate

score:
	mkdir -p build/reports
	.build/release/TimeWhisperEvaluate $(CORPUS) $(OBSERVED) --model $(MODEL) $(if $(SPELLING),,--no-spelling)
	$(GRADLE) -q evaluateCorpus --args="$(CORPUS) --observed $(OBSERVED) $(if $(SPELLING),,--label no-spelling) $(if $(GATE),--$(GATE) $(KNOWN))"

# The gate: every corpus fails the way its accepted list says, no better and no worse. All are run; any change fails it.
corpus: evaluate-tools
	@failed=; for lang in $(CORPORA); do \
		$(MAKE) score CORPUS=fixtures/$$lang-evaluation-corpus.jsonl GATE=check SPELLING=1 || failed="$$failed $$lang"; \
	done; if [ -n "$$failed" ]; then echo "The corpus result changed for:$$failed"; exit 1; fi

# Accept the current results as the new lists of known failures, after reviewing what changed. CORPORA=pl for one language.
accept-corpus: evaluate-tools
	@for lang in $(CORPORA); do $(MAKE) score CORPUS=fixtures/$$lang-evaluation-corpus.jsonl GATE=accept SPELLING=1 || exit 1; done

# Sentences through the whole pipeline, each shown as a spec: make try TEXT="dentist tomorrow at 3pm" for one
# (LANGUAGE=pl for Polish); make swiftrepl to type them one per line, /lang pl to switch language.
try: model
	$(GRADLE) -q assembleTimeWhisperCoreReleaseXCFramework
	swift run -q TimeWhisperExample --model $(MODEL) --lang $(or $(LANGUAGE),en) $(if $(TEXT),"$(TEXT)")

swiftrepl: try

# The tagger's training data, and the labelled parts of it no reader can read.
training-data:
	python3 training/generate.py --lang en,pl --n 12000 --out build/training/en --readers

# Adds labelled lines to the training set: make add-sentences FILE=new.txt (DRY=1 to check only).
add-sentences:
	python3 training/add.py $(FILE) $(if $(DRY),--dry-run)

clean:
	$(GRADLE) clean
	swift package clean
