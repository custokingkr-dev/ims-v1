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


if __name__ == "__main__":
    unittest.main()
