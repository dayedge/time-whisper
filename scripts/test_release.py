"""Focused tests of archive preservation and release input rejection."""
import importlib.util
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("packaging", ROOT / "scripts/package.py")
packaging = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packaging)


class ReleaseTests(unittest.TestCase):
    def test_corpus_gate_cannot_disable_system_spelling(self):
        result = subprocess.run(["make", "--dry-run", "corpus", "SPELLING=", "CORPORA=en"], cwd=ROOT,
                                capture_output=True, text=True, check=True)
        self.assertNotIn("--no-spelling", result.stdout)
        self.assertIn("SPELLING=1", result.stdout)

    def test_spelling_off_qualification_cannot_prepare_release(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "qualification.json"
            commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
            report.write_text(json.dumps({"version": "0.7.0", "source_commit": commit, "corpora": ["en", "pl"],
                                          "make_check": "passed", "model_files": {"labels.json": "hash"}, "system_spelling": False}))
            result = subprocess.run(["python3", str(ROOT / "scripts/release.py"), "verify", "--version", "0.7.0",
                                     "--report", str(report), "--sha256", hashlib.sha256(report.read_bytes()).hexdigest()],
                                    capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("system spelling", result.stderr)

    def test_archive_preserves_framework_symlink_and_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            framework = root / "Core.framework"
            (framework / "Versions/A").mkdir(parents=True)
            binary = framework / "Versions/A/Core"
            binary.write_bytes(b"binary")
            binary.chmod(0o755)
            (framework / "Core").symlink_to("Versions/A/Core")
            archive = root / "Core.zip"
            packaging.zip_tree(framework, archive)
            with zipfile.ZipFile(archive) as zipped:
                self.assertTrue(stat.S_ISLNK(zipped.getinfo("Core.framework/Core").external_attr >> 16))
                self.assertEqual(zipped.read("Core.framework/Core"), b"Versions/A/Core")
                self.assertTrue(zipped.getinfo("Core.framework/Versions/A/Core").external_attr >> 16 & stat.S_IXUSR)

    def test_invalid_version_cannot_be_used_as_path(self):
        result = subprocess.run(["python3", str(ROOT / "scripts/package.py"), "--version", "../escape"], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"semantic version", result.stderr)

    def test_changed_qualification_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "qualification.json"
            report.write_text("{}")
            result = subprocess.run(["python3", str(ROOT / "scripts/release.py"), "verify", "--version", "0.7.0",
                                     "--report", str(report), "--sha256", "0" * 64], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(b"checksum mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
