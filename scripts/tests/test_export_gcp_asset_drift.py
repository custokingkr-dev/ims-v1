import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class AssetDriftTest(unittest.TestCase):
    def test_fixture_generates_deterministic_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "assets.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "export-gcp-asset-drift.py"),
                    "--project", "custoking-prod",
                    "--fixture", str(ROOT / "scripts" / "tests" / "fixtures" / "gcp-assets.json"),
                    "--output-json", str(output),
                    "--output-markdown", str(pathlib.Path(directory) / "assets.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["assetCount"], 2)
            self.assertEqual(report["countsByAssetType"]["run.googleapis.com/Service"], 1)
            self.assertFalse(report["drift"]["baselineProvided"])

    def test_reviewed_manifest_redacts_names_and_fails_on_unexplained_drift(self):
        fixture = ROOT / "scripts" / "tests" / "fixtures" / "gcp-assets.json"
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            candidate = directory / "candidate.json"
            create = subprocess.run(
                [
                    sys.executable, str(ROOT / "scripts" / "export-gcp-asset-drift.py"),
                    "--project", "custoking-prod", "--fixture", str(fixture),
                    "--ignore-asset-type", "run.googleapis.com/Service",
                    "--write-baseline-candidate", str(candidate),
                    "--review-reference", "TEST-APPROVAL",
                    "--output-json", str(directory / "candidate-report.json"),
                    "--output-markdown", str(directory / "candidate-report.md"),
                ], capture_output=True, text=True, check=False,
            )
            self.assertEqual(create.returncode, 0, create.stderr)
            baseline = json.loads(candidate.read_text(encoding="utf-8"))
            self.assertEqual(baseline["status"], "candidate")
            baseline["status"] = "approved"
            candidate.write_text(json.dumps(baseline), encoding="utf-8")

            output = directory / "safe.json"
            compare = subprocess.run(
                [
                    sys.executable, str(ROOT / "scripts" / "export-gcp-asset-drift.py"),
                    "--project", "custoking-prod", "--fixture", str(fixture),
                    "--baseline", str(candidate), "--require-reviewed-baseline",
                    "--redact-assets", "--fail-on-drift",
                    "--output-json", str(output), "--output-markdown", str(directory / "safe.md"),
                ], capture_output=True, text=True, check=False,
            )
            self.assertEqual(compare.returncode, 0, compare.stderr)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertNotIn("assets", report)
            self.assertFalse(report["drift"]["driftDetected"])

            replacement = directory / "replacement-candidate.json"
            update = subprocess.run(
                [
                    sys.executable, str(ROOT / "scripts" / "export-gcp-asset-drift.py"),
                    "--project", "custoking-prod", "--fixture", str(fixture),
                    "--baseline", str(candidate), "--require-reviewed-baseline",
                    "--write-baseline-candidate", str(replacement),
                    "--review-reference", "TEST-CHANGE-2",
                    "--redact-assets", "--output-json", str(output),
                    "--output-markdown", str(directory / "safe.md"),
                ], capture_output=True, text=True, check=False,
            )
            self.assertEqual(update.returncode, 0, update.stderr)
            replacement_manifest = json.loads(replacement.read_text(encoding="utf-8"))
            self.assertEqual(replacement_manifest["status"], "candidate")
            self.assertEqual(replacement_manifest["ignoredAssetTypes"], baseline["ignoredAssetTypes"])

            changed_assets = json.loads(fixture.read_text(encoding="utf-8"))
            changed_assets[1]["state"] = "DELETING"
            changed_fixture = directory / "changed.json"
            changed_fixture.write_text(json.dumps(changed_assets), encoding="utf-8")
            drifted = subprocess.run(
                [
                    sys.executable, str(ROOT / "scripts" / "export-gcp-asset-drift.py"),
                    "--project", "custoking-prod", "--fixture", str(changed_fixture),
                    "--baseline", str(candidate), "--require-reviewed-baseline",
                    "--redact-assets", "--fail-on-drift",
                    "--output-json", str(output), "--output-markdown", str(directory / "safe.md"),
                ], capture_output=True, text=True, check=False,
            )
            self.assertEqual(drifted.returncode, 1)
            self.assertTrue(json.loads(output.read_text(encoding="utf-8"))["drift"]["driftDetected"])

    def test_candidate_baseline_is_rejected_by_scheduled_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            baseline = directory / "candidate.json"
            baseline.write_text(json.dumps({
                "baselineVersion": 1, "status": "candidate", "projectId": "custoking-prod",
                "reviewReference": "TEST", "assetDigestSha256": "0" * 64,
                "trackedAssetCount": 0, "ignoredAssetTypes": [], "countsByTrackedAssetType": {},
            }), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable, str(ROOT / "scripts" / "export-gcp-asset-drift.py"),
                    "--project", "custoking-prod",
                    "--fixture", str(ROOT / "scripts" / "tests" / "fixtures" / "gcp-assets.json"),
                    "--baseline", str(baseline), "--require-reviewed-baseline",
                ], capture_output=True, text=True, check=False,
            )
            self.assertEqual(completed.returncode, 2)
            self.assertIn("approved baselineVersion 1", completed.stderr)


if __name__ == "__main__":
    unittest.main()
