"""Local release qualification and CI verification. These commands never publish or push."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def version(value):
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", value):
        raise argparse.ArgumentTypeError("use a semantic version without a v prefix")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    qualify = commands.add_parser("qualify", help="Run make check on a clean commit and record its model hashes")
    qualify.add_argument("--version", required=True, type=version)
    qualify.add_argument("--model", required=True, type=Path)
    verify = commands.add_parser("verify", help="Check an uploaded qualification before preparing a release")
    verify.add_argument("--version", required=True, type=version)
    verify.add_argument("--report", required=True, type=Path)
    verify.add_argument("--sha256", required=True)
    args = parser.parse_args()
    if args.command == "qualify":
        if git("status", "--porcelain", "--untracked-files=all"):
            raise SystemExit("Qualification requires a clean committed source tree")
        model = args.model.resolve()
        required = ["labels.json", "tokenizer.json", "tokenizer_config.json", "reference.json", "TimeWhisperTagger.mlpackage/Manifest.json"]
        for name in required:
            if not (model / name).is_file():
                raise SystemExit(f"Missing qualification input: {name}")
        files = {str(p.relative_to(model)): sha256(p) for p in sorted(model.rglob("*")) if p.is_file()}
        commit = git("rev-parse", "HEAD")
        subprocess.run(["make", "check", f"MODEL={model}", "CORPORA=en pl", "SPELLING=1"], cwd=ROOT, check=True,
                       env={**os.environ, "TIME_WHISPER_TAGGER": str(model)})
        if git("rev-parse", "HEAD") != commit or git("status", "--porcelain", "--untracked-files=all"):
            raise SystemExit("Source changed during qualification")
        if files != {str(p.relative_to(model)): sha256(p) for p in sorted(model.rglob("*")) if p.is_file()}:
            raise SystemExit("Model changed during qualification")
        report = {"version": args.version, "source_commit": commit, "corpora": ["en", "pl"], "system_spelling": True,
                  "make_check": "passed", "model_files": files}
        destination = ROOT / "build/release/qualification.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2) + "\n")
        print(f"{destination}\nSHA256: {sha256(destination)}")
    else:
        if sha256(args.report) != args.sha256:
            raise SystemExit("Qualification checksum mismatch")
        report = json.loads(args.report.read_text())
        if (report.get("version") != args.version or report.get("source_commit") != git("rev-parse", "HEAD")
                or report.get("corpora") != ["en", "pl"] or report.get("system_spelling") is not True
                or report.get("make_check") != "passed" or not report.get("model_files")):
            raise SystemExit("Qualification must cover this version, commit and both language corpora with system spelling")
        actual = re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1]
        if actual != args.version:
            raise SystemExit(f"Gradle version {actual} differs from release {args.version}")
        print("Local qualification matches the selected release source")


if __name__ == "__main__":
    main()
