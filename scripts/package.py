"""Stage the Swift package and its symlink-preserving binary artifact; never publish."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def zip_tree(source, destination):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_symlink() or path.is_file():
                info = zipfile.ZipInfo(path.relative_to(source.parent).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = path.lstat().st_mode << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, str(path.readlink()).encode() if path.is_symlink() else path.read_bytes())


def manifest(binary):
    return '''// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TimeWhisper",
    platforms: [.macOS("15.0")],
    products: [.library(name: "TimeWhisper", targets: ["TimeWhisper"])],
    dependencies: [.package(url: "https://github.com/huggingface/swift-transformers", from: "1.1.8")],
    targets: [
        BINARY,
        .target(name: "TimeWhisper", dependencies: ["TimeWhisperCore",
            .product(name: "Tokenizers", package: "swift-transformers"),
            .product(name: "Hub", package: "swift-transformers")], path: "swift/Sources/TimeWhisper"),
        .executableTarget(name: "TimeWhisperExample", dependencies: ["TimeWhisper"], path: "examples/swift"),
        .executableTarget(name: "TimeWhisperEvaluate", dependencies: ["TimeWhisper"], path: "examples/swift-evaluate"),
        .testTarget(name: "TimeWhisperTests", dependencies: ["TimeWhisper"], path: "swift/Tests/TimeWhisperTests")
    ]
)
'''.replace("BINARY", binary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default=re.search(r'^version = "([^"]+)"', (ROOT / "build.gradle.kts").read_text(), re.M)[1])
    parser.add_argument("--release", action="store_true", help="Stage a remote-artifact manifest for a release tag")
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", args.version):
        parser.error("version must be a semantic version without a v prefix")
    framework = ROOT / "build/XCFrameworks/release/TimeWhisperCore.xcframework"
    with (framework / "Info.plist").open("rb") as stream:
        libraries = plistlib.load(stream)["AvailableLibraries"]
    architectures = {arch for item in libraries if item["SupportedPlatform"] == "macos" for arch in item["SupportedArchitectures"]}
    if not {"arm64", "x86_64"} <= architectures:
        raise SystemExit("The release requires both macOS architectures")
    out = ROOT / "build/distributions"
    out.mkdir(parents=True, exist_ok=True)
    archive = out / f"TimeWhisperCore-{args.version}.xcframework.zip"
    zip_tree(framework, archive)
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix(".zip.sha256").write_text(f"{checksum}  {archive.name}\n")
    stage = out / "package"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    for name in ("LICENSE", "THIRD_PARTY_NOTICES.txt", "README.md", "swift/Sources", "swift/Tests", "examples/swift", "examples/swift-evaluate"):
        source, target = ROOT / name, stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.is_dir():
            shutil.copytree(source, target, symlinks=True)
        else:
            shutil.copy2(source, target)
    if args.release:
        url = f"https://github.com/dayedge/time-whisper/releases/download/{args.version}/{archive.name}"
        binary = f'.binaryTarget(name: "TimeWhisperCore", url: "{url}", checksum: "{checksum}")'
    else:
        shutil.copytree(framework, stage / "TimeWhisperCore.xcframework", symlinks=True)
        binary = '.binaryTarget(name: "TimeWhisperCore", path: "TimeWhisperCore.xcframework")'
    (stage / "Package.swift").write_text(manifest(binary))
    print(json.dumps({"version": args.version, "archive": str(archive), "checksum": checksum, "package": str(stage)}))


if __name__ == "__main__":
    main()
