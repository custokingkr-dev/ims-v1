import json
import re
import shutil
import subprocess
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "ci-pr.yml"
RESOLVER = REPOSITORY_ROOT / "scripts" / "resolve-affected-ci-targets.ps1"


def job_body(workflow: str, job_name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\s*\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError(f"CI workflow is missing job {job_name!r}")
    return match.group("body")


class OpenApiCiGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        cls.static_job = job_body(cls.workflow, "static-architecture-audits")

    def test_generation_check_is_in_always_run_static_job(self) -> None:
        self.assertNotRegex(self.static_job, r"(?m)^    needs:")
        self.assertNotRegex(self.static_job, r"(?m)^    if:")
        self.assertIn(
            "actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 # v7",
            self.static_job,
        )
        self.assertIn('node-version: "24"', self.static_job)
        self.assertEqual(
            self.static_job.count(
                "run: node scripts/generate-openapi-typescript-client.js --check"
            ),
            1,
        )

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell is required for routing checks")
    def test_contract_only_change_cannot_bypass_static_gate(self) -> None:
        selection = self.resolve("contracts/openapi/identity-auth.v1.openapi.json")
        self.assertFalse(selection["has_service_changes"])
        self.assertEqual(selection["service_matrix"]["include"], [])
        self.assertIn(
            "run: node scripts/generate-openapi-typescript-client.js --check",
            self.static_job,
        )

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell is required for routing checks")
    def test_identity_auth_service_change_selects_identity_and_static_gate(self) -> None:
        selection = self.resolve(
            "services/identity-service/src/main/java/com/custoking/ims/identityservice/"
            "application/IdentityAuthService.java"
        )
        selected_names = [entry["name"] for entry in selection["service_matrix"]["include"]]
        self.assertTrue(selection["has_service_changes"])
        self.assertEqual(selected_names, ["identity-service"])
        self.assertIn(
            "run: node scripts/generate-openapi-typescript-client.js --check",
            self.static_job,
        )

    @staticmethod
    def resolve(changed_file: str) -> dict:
        completed = subprocess.run(
            [
                "pwsh",
                "-NoProfile",
                "-File",
                str(RESOLVER),
                "-Environment",
                "prod",
                "-ChangedFilesOverride",
                changed_file,
            ],
            cwd=REPOSITORY_ROOT,
            check=True,
            capture_output=True,
            text=True,
        )
        return json.loads(completed.stdout)


if __name__ == "__main__":
    unittest.main()
