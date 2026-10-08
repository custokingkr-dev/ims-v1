"""Regression proof for the exact source authorization audit; fixtures never change shared sources."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

POWERSHELL = shutil.which("pwsh") or shutil.which("powershell.exe") or shutil.which("powershell")
if not POWERSHELL:
    raise RuntimeError("PowerShell is required for deployment security regression tests")

ROOT = pathlib.Path(__file__).resolve().parents[2]
class MachineProofAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ims-auth-proof-")
        self.root = pathlib.Path(self.temp.name)
        for path in ROOT.glob("services/*-service/src/main/java/**/MachineCallerFilter.java"):
            self.copy(path)
        names = {"OutboxRelayTriggerController.java", "AsyncWorkTriggerController.java", "PasswordResetDrainController.java", "IdentityDirectoryController.java", "TenantSchoolClient.java", "GenericNotificationReportController.java", "NotificationReportAuthority.java", "GoogleIdentityTokenVerifier.java"}
        for path in ROOT.glob("services/*-service/src/main/java/**/*.java"):
            if path.name in names: self.copy(path)
    def tearDown(self): self.temp.cleanup()
    def copy(self, path):
        target = self.root / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    def audit(self):
        return subprocess.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "scripts/audit-service-authorization-boundaries.ps1"),
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
    def alter_platform(self, name, before, after):
        path = next(self.root.glob("services/platform-service/src/main/java/**/" + name))
        self.assertIn(before, path.read_text())
        path.write_text(path.read_text().replace(before, after))
    def test_factored_report_authority_safety_mutations_fail(self):
        for before, after in [("if(!enabled)", "if(false)"), ("length<32", "length<1"),
            ("equal(token,sharedToken)", "false"), ("equal(token,providerToken)", "false"),
            ("!Collections.disjoint(callers,forbidden)", "false"), ("callers.isEmpty()", "false"),
            ("!equal(token,supplied)", "false"), ("identities.verifiedEmail(authorization.substring(7))", "java.util.Optional.of(authorization)"),
            ("!callers.contains(principal.toLowerCase(Locale.ROOT))", "false"), ("MessageDigest.isEqual", "java.util.Arrays.equals")]:
            with self.subTest(before=before):
                path=next(self.root.glob("services/platform-service/src/main/java/**/NotificationReportAuthority.java")); original=path.read_text()
                self.alter_platform("NotificationReportAuthority.java",before,after)
                result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("Factored report authority",result.stdout)
                path.write_text(original)
    def test_commented_old_helper_cannot_mask_disabled_authority(self):
        path=next(self.root.glob("services/platform-service/src/main/java/**/NotificationReportAuthority.java"));original=path.read_text()
        path.write_text(original.replace("if(!enabled)","if(false)")+"\n/*\n"+original.replace("*/","* /")+"\n*/")
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("Factored report authority",result.stdout)
    def test_factored_report_guard_removed_or_after_body_fails(self):
        self.alter_platform("GenericNotificationReportController.java", "var reporter=authority.verify(authorization,token);", "var reporter=null;")
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("before body processing",result.stdout)
    def test_factored_report_body_before_guard_fails(self):
        self.alter_platform("GenericNotificationReportController.java", "var reporter=authority.verify(authorization,token);", "BODY.receive(request,response,body->{}); var reporter=authority.verify(authorization,token);")
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("before body processing",result.stdout)
    def test_factored_report_additional_endpoint_fails(self):
        self.alter_platform("GenericNotificationReportController.java", '@PostMapping(value="/reconcile",consumes="application/json")', '@PostMapping("/extra") public void extra() {}\n @PostMapping(value="/reconcile",consumes="application/json")')
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("sole route",result.stdout)
    def test_factored_report_requestmapping_cannot_add_an_unguarded_method(self):
        self.alter_platform("GenericNotificationReportController.java", '@PostMapping(value="/reconcile",consumes="application/json")', '@RequestMapping("/extra") public void extra() {}\n @PostMapping(value="/reconcile",consumes="application/json")')
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("sole route",result.stdout)
    def test_bearer_literal_delimiter_is_preserved_by_source_normalization(self):
        self.alter_platform("NotificationReportAuthority.java", 'startsWith("Bearer ")','startsWith("Bearer")')
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("Factored report authority",result.stdout)
    def test_factored_report_oidc_signature_tamper_fails(self):
        self.alter_platform("GoogleIdentityTokenVerifier.java", "tokenVerifier.verify(idToken).getPayload()", "unverified(idToken)")
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("OIDC verification",result.stdout)
    def test_factored_report_oidc_audience_and_email_verification_tamper_fail(self):
        for before,after in [('if (audiences.isEmpty())','if (false)'),('if (!CallerIdentity.audienceAllowed(payload.getAudience(), audiences))','if (false)'),('if (!Boolean.TRUE.equals(payload.get("email_verified")))','if (false)'),('setIssuer(GOOGLE_ISSUER)','setIssuer("untrusted")')]:
            with self.subTest(before=before):
                path=next(self.root.glob("services/platform-service/src/main/java/**/GoogleIdentityTokenVerifier.java"));original=path.read_text();self.alter_platform("GoogleIdentityTokenVerifier.java",before,after)
                result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("OIDC verification",result.stdout);path.write_text(original)
    def test_factored_report_wrong_machine_purpose_fails(self):
        self.alter_platform("MachineCallerFilter.java", 'if(path.equals("/api/v1/internal/notifications/reports/reconcile")) return "NOTIFICATION_REPORT_CALLER_SERVICE_ACCOUNTS";', 'if(path.equals("/api/v1/internal/notifications/reports/reconcile")) return "NOTIFICATION_DELIVERY_CALLER_SERVICE_ACCOUNTS";')
        result=self.audit();self.assertNotEqual(0,result.returncode);self.assertIn("dedicated signed purpose",result.stdout)
if __name__ == "__main__": unittest.main()
