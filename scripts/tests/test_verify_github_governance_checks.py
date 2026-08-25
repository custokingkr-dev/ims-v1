import copy
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify-github-governance-checks.py"
FIXTURE = ROOT / "scripts" / "tests" / "fixtures" / "github-governance-checks.json"
COMMIT = "1" * 40


def load_module():
    spec = importlib.util.spec_from_file_location("github_governance_checks", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class GitHubGovernanceChecksTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_classic_and_active_ruleset_exact_contexts_are_ready(self):
        result = self.module.verify(
            copy.deepcopy(self.fixture),
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertTrue(result["ready"], result["blockers"])
        self.assertEqual(result["blockerCount"], 0)
        self.assertEqual([branch["exact"] for branch in result["branches"]], [True, True])

    def test_missing_failed_and_mistyped_contexts_fail_closed(self):
        evidence = copy.deepcopy(self.fixture)
        evidence["checkRuns"]["check_runs"][0]["conclusion"] = "failure"
        evidence["branchProtection"]["main"]["required_status_checks"]["contexts"][0] = "Summary"
        result = self.module.verify(
            evidence,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(result["ready"])
        self.assertIn("required check 'summary' is not completed successfully", result["blockers"])
        self.assertIn("branch 'main' is missing required contexts: summary", result["blockers"])
        self.assertIn("branch 'main' has unexpected required contexts: Summary", result["blockers"])

    def test_excluded_or_evaluate_rulesets_do_not_count_as_enforcement(self):
        for enforcement in ("evaluate", "active"):
            evidence = copy.deepcopy(self.fixture)
            evidence["branchProtection"]["main"] = None
            evidence["rulesets"][0]["enforcement"] = enforcement
            if enforcement == "active":
                evidence["rulesets"][0]["conditions"]["ref_name"]["exclude"] = ["~DEFAULT_BRANCH"]
            result = self.module.verify(
                evidence,
                "custokingkr-dev/ims-v1",
                COMMIT,
                ["main"],
                list(self.module.DEFAULT_REQUIRED_CHECKS),
            )
            self.assertFalse(result["ready"])
            self.assertIn("branch 'main' has no active required-status-check protection", result["blockers"])

    def test_unsupported_ruleset_pattern_fails_closed(self):
        evidence = copy.deepcopy(self.fixture)
        evidence["rulesets"][0]["conditions"]["ref_name"]["include"] = ["refs/heads/[dm]*"]
        result = self.module.verify(
            evidence,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(result["ready"])
        self.assertIn("unsupported GitHub ref pattern", "\n".join(result["blockers"]))

    def test_cli_requires_immutable_sha_and_fixture_path_never_calls_github(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "report.json"
            valid = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--commit", COMMIT,
                    "--fixture", str(FIXTURE),
                    "--gh", "must-not-run",
                    "--output-json", str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(valid.returncode, 0, valid.stderr)
            self.assertTrue(json.loads(output.read_text(encoding="utf-8"))["ready"])

            mutable = subprocess.run(
                [sys.executable, str(SCRIPT), "--commit", "main", "--fixture", str(FIXTURE)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(mutable.returncode, 2)
            self.assertIn("immutable full 40-character", mutable.stderr)

            failing_evidence = copy.deepcopy(self.fixture)
            failing_evidence["branchProtection"]["main"] = None
            failing_fixture = pathlib.Path(directory) / "failing-fixture.json"
            failing_fixture.write_text(json.dumps(failing_evidence), encoding="utf-8")
            failed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--commit", COMMIT,
                    "--fixture", str(failing_fixture),
                    "--output-json", str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(failed.returncode, 1, failed.stderr)
            self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["ready"])


if __name__ == "__main__":
    unittest.main()
