"""Compile a clean consumer of a staged TimeWhisper package (no model or network at runtime)."""
import argparse
from pathlib import Path
import subprocess
import tempfile
import json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="time-whisper-consumer-") as directory:
        root = Path(directory)
        (root / "Sources/Smoke").mkdir(parents=True)
        path = json.dumps(str(args.package.resolve()))
        (root / "Package.swift").write_text(f'''// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "Smoke", platforms: [.macOS("15.0")],
    dependencies: [.package(name: "TimeWhisper", path: {path})],
    targets: [.executableTarget(name: "Smoke", dependencies: [.product(name: "TimeWhisper", package: "TimeWhisper")])])
''')
        (root / "Sources/Smoke/main.swift").write_text('''import Foundation
import TimeWhisper
let tagger = CoreMLTagger(folder: URL(fileURLWithPath: "/unused"))
_ = tagger
''')
        subprocess.run(["swift", "build", "--package-path", str(root)], check=True)
        subprocess.run(["swift", "run", "--skip-build", "--package-path", str(root), "Smoke"], check=True)


if __name__ == "__main__":
    main()
