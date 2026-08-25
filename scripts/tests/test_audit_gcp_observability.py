import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ObservabilityAuditTest(unittest.TestCase):
    def test_fixture_validates_filters_and_live_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "audit.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "audit-gcp-observability.py"),
                    "--project", "custoking-prod",
                    "--fixture", str(ROOT / "scripts" / "tests" / "fixtures" / "gcp-observability-audit.json"),
                    "--output-json", str(output),
                    "--output-markdown", str(pathlib.Path(directory) / "audit.md"),
                    "--fail-on-no-data",
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["uniqueFilterCount"], 2)
            self.assertEqual(report["summary"]["filtersWithData"], 2)
            self.assertEqual(report["summary"]["missingResourceReferences"], 0)

    def test_missing_exact_resource_reference_fails(self):
        fixture_path = ROOT / "scripts" / "tests" / "fixtures" / "gcp-observability-audit.json"
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        dashboard_filter = fixture["inventory"]["dashboards"][0]["widgets"][0]["scorecard"]["timeSeriesQuery"]["timeSeriesFilter"]
        dashboard_filter["filter"] = dashboard_filter["filter"].replace(
            "custoking-prod-student-photos", "custoking-student-photos-prod"
        )
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            changed_fixture = directory / "stale.json"
            changed_fixture.write_text(json.dumps(fixture), encoding="utf-8")
            output = directory / "audit.json"
            completed = subprocess.run(
                [
                    sys.executable,
                    str(ROOT / "scripts" / "audit-gcp-observability.py"),
                    "--project", "custoking-prod",
                    "--fixture", str(changed_fixture),
                    "--output-json", str(output),
                    "--output-markdown", str(directory / "audit.md"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 1)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["missingResourceReferences"], 1)
            self.assertEqual(
                report["missingResourceReferences"][0]["resourceId"],
                "custoking-student-photos-prod",
            )

    def test_monitoring_query_error_fails_closed(self):
        fixture_path = ROOT / "scripts" / "tests" / "fixtures" / "gcp-observability-audit.json"
        fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
        first_filter = next(iter(fixture["timeSeriesByFilter"]))
        fixture["timeSeriesByFilter"][first_filter] = {
            "seriesPresent": False,
            "error": "HTTP 403: monitoring.timeSeries.list denied",
        }
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            changed_fixture = directory / "query-error.json"
            changed_fixture.write_text(json.dumps(fixture), encoding="utf-8")
            output = directory / "audit.json"
            completed = subprocess.run(
                [
                    sys.executable, str(ROOT / "scripts" / "audit-gcp-observability.py"),
                    "--project", "custoking-prod", "--fixture", str(changed_fixture),
                    "--output-json", str(output), "--output-markdown", str(directory / "audit.md"),
                ], capture_output=True, text=True, check=False,
            )
            self.assertEqual(completed.returncode, 1)
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["summary"]["filterQueryErrors"], 1)


if __name__ == "__main__":
    unittest.main()
