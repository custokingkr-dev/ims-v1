"""Actual resolver over isolated Git commits; no registry/cloud access."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PS = shutil.which("pwsh") or shutil.which("powershell.exe")

@unittest.skipUnless(PS and shutil.which("git"), "PowerShell and Git required")
class ImageSourcePatchEpochTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ims-image-epoch-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / "scripts").mkdir()
        shutil.copyfile(ROOT / "scripts/resolve-image-source-id.ps1", self.repo / "scripts/resolve-image-source-id.ps1")
        (self.repo / "deploy").mkdir()
        for name in ("identity", "school", "operations", "platform", "billing", "gateway", "frontend"):
            (self.repo / name).mkdir()
            (self.repo / name / "app.txt").write_text("stable app\n", encoding="ascii")
        (self.repo / "docs.txt").write_text("docs\n", encoding="ascii")
        self.git("init", "-q")
        self.git("config", "user.name", "Isolated Test")
        self.git("config", "user.email", "isolated-test@example.invalid")
        self.git("config", "core.autocrlf", "false")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.repo), *args], capture_output=True, text=True, timeout=15, check=True).stdout.strip()

    def commit(self, epoch=b"2026-10-08\n"):
        p = self.repo / "deploy/runtime-patch-epoch.txt"
        if epoch is None:
            if p.exists(): p.unlink()
        else:
            p.write_bytes(epoch)
        self.git("add", "-A")
        self.git("commit", "-qm", "isolated fixture", "--allow-empty")
        return self.git("rev-parse", "HEAD")

    def resolve(self, sha, context="identity", paths="identity", args="", ok=True):
        result = subprocess.run([PS, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(self.repo / "scripts/resolve-image-source-id.ps1"), "-CommitSha", sha, "-Context", context, "-SourcePaths", paths, "-BuildArgs", args], capture_output=True, text=True, timeout=20)
        if not ok:
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn('"sourceId"', result.stdout)
            return result
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def test_epoch_bump_changes_every_image_key_with_unchanged_apps(self):
        first = self.commit()
        second = self.commit(b"2026-10-09\n")
        for service in ("identity", "school", "operations", "platform", "billing", "gateway", "frontend"):
            with self.subTest(service=service):
                a = self.resolve(first, service, service)
                b = self.resolve(second, service, service)
                self.assertNotEqual(a["sourceId"], b["sourceId"])
                self.assertEqual("2026-10-09", b["securityPatchEpoch"])
                self.assertIn("deploy/runtime-patch-epoch.txt", b["sourcePaths"])
                self.assertEqual(1, b["sourcePaths"].count("deploy/runtime-patch-epoch.txt"))

    def test_same_day_refresh_cycle_changes_key(self):
        first = self.commit()
        second = self.commit(b"2026-10-08-r2\n")
        self.assertNotEqual(self.resolve(first)["sourceId"], self.resolve(second)["sourceId"])
        self.assertEqual("2026-10-08-r2", self.resolve(second)["securityPatchEpoch"])

    def test_unrelated_docs_commit_preserves_key(self):
        first = self.commit()
        (self.repo / "docs.txt").write_text("unrelated revised docs\n", encoding="ascii")
        second = self.commit()
        self.assertEqual(self.resolve(first)["sourceId"], self.resolve(second)["sourceId"])

    def test_selected_commit_ignores_dirty_checkout_and_current_head(self):
        first = self.commit()
        expected = self.resolve(first)
        second = self.commit(b"2026-10-09\n")
        (self.repo / "deploy/runtime-patch-epoch.txt").write_bytes(b"malformed dirty checkout")
        (self.repo / "identity/app.txt").write_text("dirty app", encoding="ascii")
        self.assertEqual(expected, self.resolve(first))
        self.assertEqual("2026-10-09", self.resolve(second)["securityPatchEpoch"])

    def test_missing_epoch_fails_closed(self):
        self.resolve(self.commit(None), ok=False)

    def test_invalid_epoch_blobs_fail_closed(self):
        for epoch in (b"2026-02-30\n", b"2026-1-08\n", b"2026-10-08\n\n", b"2026-10-08 extra", b" 2026-10-08", b"\xef\xbb\xbf2026-10-08\n", b"2026-10-08\xff", b"x" * 65, b"", b"2026-10-08-r0", b"2026-10-08-r01", b"2026-10-08-r1000000", b"2026-10-08-r", b"2026-10-08-r-1", b"2026-10-08-r1x", b"2026-10-08-R2", b"2026-02-30-r2"):
            with self.subTest(epoch=repr(epoch)):
                self.resolve(self.commit(epoch), ok=False)

    def test_supported_single_line_endings_and_real_future_date(self):
        for epoch in (b"2026-10-08", b"2026-10-08\r\n", b"2096-02-29\n", b"2026-10-08-r1\n", b"2026-10-08-r999999\n"):
            with self.subTest(epoch=epoch):
                result = self.resolve(self.commit(epoch))
                self.assertEqual(epoch.decode("ascii").strip(), result["securityPatchEpoch"])

    def test_dev_prod_same_selected_inputs_and_retry_preserve_key(self):
        sha = self.commit()
        dev = self.resolve(sha, args="VITE_API_BASE_URL=/api")
        prod = self.resolve(sha, args="VITE_API_BASE_URL=/api")
        self.assertEqual(dev, prod)
        self.assertEqual(dev, self.resolve(sha, args="VITE_API_BASE_URL=/api"))

    def test_existing_build_args_and_application_hashing_preserved(self):
        first = self.commit()
        a = self.resolve(first, args="  MODE=one  ")
        self.assertEqual("MODE=one", a["buildArgs"])
        self.assertNotEqual(a["sourceId"], self.resolve(first, args="MODE=two")["sourceId"])
        (self.repo / "identity/app.txt").write_text("new app\n", encoding="ascii")
        second = self.commit()
        self.assertNotEqual(a["sourceId"], self.resolve(second, args="MODE=one")["sourceId"])
        self.assertEqual(self.resolve(first, "gateway", "gateway")["sourceId"], self.resolve(second, "gateway", "gateway")["sourceId"])

    def test_explicit_epoch_path_is_deduplicated(self):
        r = self.resolve(self.commit(), paths="identity|deploy/runtime-patch-epoch.txt|identity")
        self.assertEqual(["deploy/runtime-patch-epoch.txt", "identity"], r["sourcePaths"])

    def test_nonexact_commit_reference_fails_closed(self):
        self.commit()
        self.resolve("HEAD", ok=False)

if __name__ == "__main__":
    unittest.main()
