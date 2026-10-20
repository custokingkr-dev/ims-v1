"""Regression proof for the exact source authorization audit; fixtures never change shared sources."""
import pathlib
import re
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
        self.copy(ROOT / "services/api-gateway/routing.js")
        for name in ("authentication.js", "quota.js", "transport.js"):
            self.copy(ROOT / "services/api-gateway" / name)
        for path in ROOT.glob("services/*-service/src/main/java/**/MachineCallerFilter.java"):
            self.copy(path)
        names = {"OutboxRelayTriggerController.java", "AsyncWorkTriggerController.java", "PasswordResetDrainController.java", "IdentityDirectoryController.java", "TenantSchoolClient.java", "GenericNotificationReportController.java", "NotificationReportAuthority.java", "GoogleIdentityTokenVerifier.java",
                 "NotebookTrackerMachineController.java", "CatalogTrackerMachineController.java", "OperatorDirectoryController.java"}
        for path in ROOT.glob("services/*-service/src/main/java/**/*.java"):
            if path.name in names: self.copy(path)
    def tearDown(self): self.temp.cleanup()
    def copy(self, path):
        target = self.root / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    def audit(self, script=ROOT / "scripts/audit-service-authorization-boundaries.ps1"):
        return subprocess.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script),
            "-GatewayTemplate", str(ROOT / "services/api-gateway/server.js"), "-ComposeFile", str(ROOT / "docker-compose.yml"),
            "-GatewayRoutesTemplate", str(self.root / "services/api-gateway/routing.js"),
            "-CloudRunDirectory", str(ROOT / "deploy/cloudrun"), "-AsyncSchedulerScript", str(ROOT / "scripts/configure-async-relay-scheduler.ps1")],
            cwd=self.root, capture_output=True, text=True, timeout=30)
    def alter(self, name, before, after, service="identity-service"):
        path = next(self.root.glob("services/" + service + "/src/main/java/**/" + name))
        self.assertIn(before, path.read_text())
        path.write_text(path.read_text().replace(before, after))
    def test_reviewed_exact_machine_capabilities_pass(self):
        result = self.audit(); self.assertEqual(0, result.returncode, result.stdout + result.stderr)
    def test_internal_routes_added_to_routing_module_cannot_bypass_gateway_audit(self):
        path = self.root / "services/api-gateway/routing.js"
        original = path.read_text(encoding="utf-8")
        for route, message in [
            ("/api/v1/internal/outbox/relay", "exposed through the API gateway"),
            ("/api/v1/internal/notifications/reports/reconcile", "gateway-exposed"),
            # Regex routes can never appear verbatim in a gateway file; each is guarded through its literal prefix.
            ("/api/v1/internal/catalog-orders", "exposed through the API gateway"),
            ("/api/v1/internal/notebook-trackers", "exposed through the API gateway"),
            ("/api/v1/internal/identity-directory", "exposed through the API gateway"),
        ]:
            with self.subTest(route=route):
                path.write_text(original.replace("const routes = [", "const routes = [\n  route('notification', '" + route + "'),"), encoding="utf-8")
                result = self.audit()
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(message, result.stdout)
        path.write_text(original, encoding="utf-8")
    def test_missing_signature_verification_fails(self):
        self.alter("MachineCallerFilter.java", "GOOGLE.verify(token)", "unverified(token)")
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("proof missing", result.stdout)
    def test_internal_routes_in_extracted_policy_modules_cannot_bypass_gateway_audit(self):
        for name in ("authentication.js", "quota.js", "transport.js"):
            with self.subTest(module=name):
                path = self.root / "services/api-gateway" / name
                original = path.read_text(encoding="utf-8")
                path.write_text(original + "\nconst exposed = '/api/v1/internal/outbox/relay';\n", encoding="utf-8")
                result = self.audit()
                self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("exposed through the API gateway", result.stdout)
                path.write_text(original, encoding="utf-8")
    def test_missing_independent_carrier_fails(self):
        self.alter("MachineCallerFilter.java", "X-IMS-Principal-Carrier-Token", "X-Untrusted-Header")
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("carrier proof missing", result.stdout)
    def test_added_internal_method_cannot_borrow_controller_exception(self):
        self.alter("PasswordResetDrainController.java", "@PostMapping(\"/drain\")", "@PostMapping(\"/extra\") public void extra() {}\n @PostMapping(\"/drain\")")
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("mapping drifted", result.stdout)
    def test_added_method_on_a_multi_route_machine_controller_fails(self):
        self.alter("OperatorDirectoryController.java", '@GetMapping("/resolve")', '@GetMapping("/extra") public void extra() {}\n @GetMapping("/resolve")')
        result = self.audit(); self.assertNotEqual(0, result.returncode); self.assertIn("mapping drifted", result.stdout)
    def test_each_machine_route_is_pinned_to_its_own_caller_list(self):
        # Every caller list stays present in the filter, so only an exact route -> list check can catch these.
        resolve = '"/api/v1/internal/operator-directory/resolve")) return "OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS";'
        eligible = '"/api/v1/internal/operator-directory/eligible")) return "OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS";'
        create = '"/api/v1/internal/notebook-trackers")) return "NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS";'
        for label, service, edits in [
            ("resolve and eligible swapped", "identity-service", [(resolve, resolve.replace("_RESOLVE_", "_ELIGIBILITY_")), (eligible, eligible.replace("_ELIGIBILITY_", "_RESOLVE_"))]),
            ("create on the catalog list", "operations-service", [(create, create.replace("NOTEBOOK_TRACKER_", "CATALOG_TRACKER_"))]),
        ]:
            with self.subTest(label):
                path = next(self.root.glob("services/" + service + "/src/main/java/**/MachineCallerFilter.java")); original = path.read_text()
                for before, after in edits: self.alter("MachineCallerFilter.java", before, after, service=service)
                result = self.audit(); path.write_text(original)
                self.assertNotEqual(0, result.returncode, result.stdout); self.assertIn("lacks exact signed caller filter", result.stdout)
    def test_each_machine_route_needs_its_own_registry_line(self):
        for service, line in [
            ("operations-service", 'if(path.equals("/api/v1/internal/notebook-trackers")) return "NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS";'),
            ("operations-service", 'if(path.matches("/api/v1/internal/notebook-trackers/[A-Za-z0-9_-]{1,255}/image-access")) return "NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS";'),
            ("school-core-service", 'if(path.matches("/api/v1/internal/catalog-orders/[A-Za-z0-9_-]{1,255}/draft-images")) return "CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS";'),
            ("school-core-service", 'if(path.matches("/api/v1/internal/catalog-orders/[A-Za-z0-9_-]{1,255}/tracker-status")) return "CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS";'),
            ("school-core-service", 'if(path.matches("/api/v1/internal/identity-directory/(schools|zones)/[0-9]+")) return "IDENTITY_DIRECTORY_CALLER_SERVICE_ACCOUNTS";'),
            ("identity-service", 'if(path.equals("/api/v1/internal/operator-directory/resolve")) return "OPERATOR_DIRECTORY_RESOLVE_CALLER_SERVICE_ACCOUNTS";'),
            ("identity-service", 'if(path.equals("/api/v1/internal/operator-directory/eligible")) return "OPERATOR_DIRECTORY_ELIGIBILITY_CALLER_SERVICE_ACCOUNTS";'),
        ]:
            with self.subTest(line):
                path = next(self.root.glob("services/" + service + "/src/main/java/**/MachineCallerFilter.java")); original = path.read_text()
                self.alter("MachineCallerFilter.java", line, "", service=service)
                result = self.audit(); path.write_text(original)
                self.assertNotEqual(0, result.returncode, result.stdout); self.assertIn("lacks exact signed caller filter", result.stdout)
    def test_regex_route_contract_needs_a_literal_gateway_route(self):
        source = (ROOT / "scripts/audit-service-authorization-boundaries.ps1").read_bytes()
        before = b'GatewayRoute = "/api/v1/internal/catalog-orders"; '
        self.assertIn(before, source)
        script = self.root / "audit-fixture.ps1"; script.write_bytes(source.replace(before, b"", 1))
        result = self.audit(script); self.assertNotEqual(0, result.returncode, result.stdout); self.assertIn("needs a literal GatewayRoute", result.stdout)
    CREATE = r'(@PostMapping\(""\))\s+(public ResponseEntity<CreateResponse> create\()'
    def test_create_mapping_contract_does_not_depend_on_line_layout(self):
        path = next(self.root.glob("services/operations-service/src/main/java/**/NotebookTrackerMachineController.java")); original = path.read_text()
        for layout in ("\n    ", " "):
            with self.subTest(layout=repr(layout)):
                text, count = re.subn(self.CREATE, lambda m: m.group(1) + layout + m.group(2), original); self.assertEqual(1, count)
                path.write_text(text)
                result = self.audit(); self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        path.write_text(original)
    def test_create_mapping_cannot_move_to_another_path(self):
        path = next(self.root.glob("services/operations-service/src/main/java/**/NotebookTrackerMachineController.java"))
        text, count = re.subn(self.CREATE, lambda m: '@PostMapping("/extra")\n    ' + m.group(2), path.read_text()); self.assertEqual(1, count)
        path.write_text(text)
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
