import copy
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "validate-gcp-governance-audit-enablement.py"
CONFIG = ROOT / "deploy" / "gcp" / "governance" / "gcp-governance-audit-enablement.json"
BASELINE = ROOT / "deploy" / "gcp" / "governance" / "cloud-assets-prod.baseline.json"


def load_module():
    spec = importlib.util.spec_from_file_location("gcp_governance_enablement", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class GcpGovernanceAuditEnablementTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()
        cls.config = json.loads(CONFIG.read_text(encoding="utf-8"))
        cls.baseline = json.loads(BASELINE.read_text(encoding="utf-8"))

    def validate(self, config, baseline, repository_flag):
        return self.module.validate(
            config,
            baseline,
            "custoking-prod",
            "deploy/gcp/governance/cloud-assets-prod.baseline.json",
            repository_flag,
        )

    def test_checked_in_state_is_safely_disabled(self):
        result = self.validate(copy.deepcopy(self.config), copy.deepcopy(self.baseline), "")
        self.assertFalse(result["enabled"])
        self.assertIn("post-provision-baseline", result["reason"])

    def test_premature_repository_flag_fails_before_authentication(self):
        with self.assertRaisesRegex(RuntimeError, "Repository enable flag is true before"):
            self.validate(copy.deepcopy(self.config), copy.deepcopy(self.baseline), "true")

    def test_activation_rejects_pre_provision_baseline_counts(self):
        config = copy.deepcopy(self.config)
        config.update({
            "enabled": True,
            "status": "approved",
            "activationReviewReference": "PR-TEST",
        })
        with self.assertRaisesRegex(RuntimeError, "predates required provisioned asset count"):
            self.validate(config, copy.deepcopy(self.baseline), "false")

    def test_post_provision_count_contract_cannot_be_lowered(self):
        config = copy.deepcopy(self.config)
        config["requiredPostProvisionTrackedCounts"]["iam.googleapis.com/ServiceAccount"] = 22
        with self.assertRaisesRegex(RuntimeError, "exact post-provision tracked-count contract"):
            self.validate(config, copy.deepcopy(self.baseline), "false")

    def test_dual_approval_gate_enables_only_after_post_provision_baseline(self):
        config = copy.deepcopy(self.config)
        baseline = copy.deepcopy(self.baseline)
        replacement_digest = "a" * 64
        baseline["assetDigestSha256"] = replacement_digest
        baseline["countsByTrackedAssetType"]["iam.googleapis.com/Role"] = 2
        baseline["countsByTrackedAssetType"]["iam.googleapis.com/ServiceAccount"] = 23
        config.update({
            "enabled": True,
            "status": "approved",
            "approvedBaselineDigestSha256": replacement_digest,
            "activationReviewReference": "PR-TEST",
        })

        waiting = self.validate(copy.deepcopy(config), copy.deepcopy(baseline), "false")
        self.assertFalse(waiting["enabled"])
        self.assertEqual(waiting["reason"], "repository-variable-disabled")
        enabled = self.validate(config, baseline, "true")
        self.assertTrue(enabled["enabled"])
        self.assertEqual(enabled["activationReviewReference"], "PR-TEST")

    def test_cli_fails_on_digest_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = pathlib.Path(directory)
            config = copy.deepcopy(self.config)
            config["approvedBaselineDigestSha256"] = "b" * 64
            config_path = directory / "config.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--config", str(config_path),
                    "--baseline", str(BASELINE),
                    "--project", "custoking-prod",
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 2)
            self.assertIn("digest does not match", completed.stderr)


if __name__ == "__main__":
    unittest.main()
