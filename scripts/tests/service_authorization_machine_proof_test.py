"""Regression proof for the exact source authorization audit; fixtures never change shared sources."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
class MachineProofAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ims-auth-proof-")
        self.root = pathlib.Path(self.temp.name)
        for path in ROOT.glob("services/*-service/src/main/java/**/MachineCallerFilter.java"):
            self.copy(path)
        names = {"OutboxRelayTriggerController.java", "AsyncWorkTriggerController.java", "PasswordResetDrainController.java", "IdentityDirectoryController.java", "TenantSchoolClient.java"}
        for path in ROOT.glob("services/*-service/src/main/java/**/*.java"):
            if path.name in names: self.copy(path)
    def tearDown(self): self.temp.cleanup()
    def copy(self, path):
        target = self.root / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    def audit(self):
        return subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "scripts/audit-service-authorization-boundaries.ps1"),
            "-GatewayTemplate", str(ROOT / "services/api-gateway/server.js"), "-ComposeFile", str(ROOT / "docker-compose.yml"),
            "-CloudRunDirectory", str(ROOT / "deploy/cloudrun"), "-AsyncSchedulerScript", str(ROOT / "scripts/configure-async-relay-scheduler.ps1")],
            cwd=self.root, capture_output=True, text=True, timeout=30)
    def alter(self, name, before, after):
        path = next(self.root.glob("services/identity-service/src/main/java/**/" + name))
        self.assertIn(before, path.read_text())
        path.write_text(path.read_text().replace(before, after))
    def test_reviewed_exact_machine_capabilities_pass(self):
        result = self.audit(); self.assertEqual(0, result.returncode, result.stdout + result.stderr)
    def test_missing_signature_verification_fails(self):
        self.alter("MachineCallerFilter.java", "GOOGLE.verify(token)", "unverified(token)")
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("proof missing", result.stdout)
    def test_missing_independent_carrier_fails(self):
        self.alter("MachineCallerFilter.java", "X-IMS-Principal-Carrier-Token", "X-Untrusted-Header")
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("carrier proof missing", result.stdout)
    def test_added_internal_method_cannot_borrow_controller_exception(self):
        self.alter("PasswordResetDrainController.java", "@PostMapping(\"/drain\")", "@PostMapping(\"/extra\") public void extra() {}\n @PostMapping(\"/drain\")")
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("mapping drifted", result.stdout)
if __name__ == "__main__": unittest.main()
