"""Render the real job and exercise release blocking/cleanup with an isolated native CLI fixture."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import shutil

POWERSHELL = shutil.which("pwsh") or shutil.which("powershell.exe") or shutil.which("powershell")
if not POWERSHELL:
    raise RuntimeError("PowerShell is required for deployment security regression tests")

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/invoke-service-migration-job.ps1"

class MigrationJobTest(unittest.TestCase):
    def arguments(self, output):
        return [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT), "-ProjectId", "custoking-dev", "-Region", "asia-south2", "-Environment", "dev", "-Service", "operations-service", "-ImageRef", "asia-south2-docker.pkg.dev/custoking-dev/custoking/operations@sha256:" + "a" * 64, "-CommitSha", "b" * 40, "-DatabaseUrl", "jdbc:postgresql://10.92.0.3/custoking_dev?sslmode=require", "-OutputDirectory", str(output)]

    def test_rendered_job_uses_exact_launcher_digest_owner_secret_and_limits(self):
        with tempfile.TemporaryDirectory(prefix="ims-migration-render-") as directory:
            result = subprocess.run(self.arguments(directory) + ["-DryRun"], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            plan = json.loads(result.stdout)
            job = json.loads(Path(plan["jobFile"]).read_text(encoding="utf-8-sig"))
            outer = job["spec"]["template"]
            task = outer["spec"]["template"]["spec"]
            container = task["containers"][0]
            self.assertEqual(300, task["timeoutSeconds"])
            self.assertEqual(0, task["maxRetries"])
            self.assertEqual("768Mi", container["resources"]["limits"]["memory"])
            self.assertEqual(["java"], container["command"])
            self.assertEqual("org.springframework.boot.loader.launch.PropertiesLauncher", container["args"][-1])
            self.assertIn("-Dloader.main=com.custoking.ims.migration.MigrationOnlyMain", container["args"])
            self.assertEqual("private-ranges-only", outer["metadata"]["annotations"]["run.googleapis.com/vpc-access-egress"])
            self.assertEqual("ims-db-migration-dev@custoking-dev.iam.gserviceaccount.com", task["serviceAccountName"])
            env = {item["name"]: item for item in container["env"]}
            self.assertEqual({"name": "db-password-dev", "key": "latest"}, env["FLYWAY_PASSWORD"]["valueFrom"]["secretKeyRef"])
            self.assertNotIn("SPRING_DATASOURCE_PASSWORD", env)

    def fixture(self, failure, config_source=None, database_host="10.92.0.3"):
        with tempfile.TemporaryDirectory(prefix="ims-migration-native-") as directory:
            folder = Path(directory)
            receipt = folder / "calls.jsonl"
            fixture = folder / "fixture.py"
            fixture.write_text("""import json, os, sys
from pathlib import Path
a=sys.argv[1:]
with open(os.environ['IMS_FIXTURE_RECEIPT'],'a') as f: f.write(json.dumps(a)+'\\n')
print('Normal CLI progress',file=sys.stderr)
if a[:3]==['run','jobs','replace']:
 job=json.loads(Path(a[3]).read_text(encoding='utf-8-sig'))
 env={e['name']:e for e in job['spec']['template']['spec']['template']['spec']['containers'][0]['env']}
 if os.environ.get('IMS_FIXTURE_HOST')=='10.92.0.3:5432':
  assert env['FLYWAY_URL']['value']=='jdbc:postgresql://10.92.0.3:5432/custoking_dev?sslmode=require'
if len(a)>2 and a[:3]==['run','jobs',os.environ.get('IMS_FIXTURE_FAILURE','')]: sys.exit(7)
if a[:3]==['run','jobs','execute']: print(json.dumps({'status':{'conditions':[{'type':'Completed','status':'True'}]}}))
elif a[:2]==['logging','read']:
 schemas=['workflow'] if os.environ.get('IMS_FIXTURE_FAILURE')=='missing-schema' else ['workflow','firefighting']
 print(json.dumps([{'textPayload':f'OWNER_MIGRATION_RESULT service=operations-service schema={s} version=7 migrationsExecuted=0 success=true'} for s in schemas]))
elif a[:3]==['deploy','targets','describe']:
 target={'deployParameters':{} if os.environ.get('IMS_FIXTURE_FAILURE')=='missing-config' else {'db_host':os.environ['IMS_FIXTURE_HOST'],'db_name':'custoking_dev'}}
 print(json.dumps(target if os.environ.get('IMS_FIXTURE_FAILURE')=='bare-target' else {'Target':'invalid-target' if os.environ.get('IMS_FIXTURE_FAILURE')=='malformed-wrapper' else target,'Active Pipeline':{'name':'controlled-fixture'}}))
elif a[:3]==['run','services','describe']:
 print(json.dumps({'spec':{'template':{'spec':{'containers':[{'env':[] if os.environ.get('IMS_FIXTURE_FAILURE')=='missing-config' else [{'name':'SPRING_DATASOURCE_URL','value':'jdbc:postgresql://10.92.0.3/custoking_dev?sslmode=require'}]}]}}}}))
""")
            if os.name == "nt":
                (folder / "gcloud.cmd").write_text(f'@echo off\n"{os.sys.executable}" "{fixture}" %*\nexit /b %errorlevel%\n')
            else:
                native=folder / "gcloud"
                native.write_text(f"#!{os.sys.executable}\nimport runpy\nrunpy.run_path({str(fixture)!r},run_name='__main__')\n")
                native.chmod(0o755)
            environment = os.environ.copy()
            environment.update(PATH=str(folder) + os.pathsep + environment["PATH"], IMS_FIXTURE_RECEIPT=str(receipt), IMS_FIXTURE_FAILURE=failure, IMS_FIXTURE_HOST=database_host)
            arguments=self.arguments(folder / "evidence")
            if config_source:
                index=arguments.index('-DatabaseUrl')
                del arguments[index:index+2]
                arguments += ['-ConfigSource',config_source]
            result = subprocess.run(arguments + ["-SchemaEvidenceTimeoutSeconds", "1"], capture_output=True, text=True, env=environment)
            calls = [json.loads(line) for line in receipt.read_text().splitlines()]
            evidence = folder / "evidence/operations-service.json"
            return result, calls, json.loads(evidence.read_text(encoding="utf-8-sig")) if evidence.exists() else None

    def test_success_requires_complete_schema_history_and_deletes_exact_job(self):
        result, calls, evidence = self.fixture("")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["replace", "execute", "read", "delete"], [call[2] if call[0] == "run" else call[1] for call in calls])
        self.assertEqual(calls[1][3], calls[-1][3])
        self.assertEqual(2, len(evidence["schemas"]))
        self.assertNotIn("Normal CLI progress", result.stdout + result.stderr)

    def test_failed_partial_creation_still_cleans_and_blocks_release(self):
        result, calls, evidence = self.fixture("replace")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["replace", "delete"], [call[2] for call in calls])
        self.assertIsNone(evidence)

    def test_failed_execution_still_cleans_and_blocks_release(self):
        result, calls, evidence = self.fixture("execute")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("delete", calls[-1][2])
        self.assertIsNone(evidence)

    def test_missing_schema_marker_blocks_even_successful_execution(self):
        result, calls, evidence = self.fixture("missing-schema")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("delete", calls[-1][2])
        self.assertIsNone(evidence)

    def test_both_explicit_cloud_configuration_sources_work(self):
        for source in ['CloudRun','CloudDeploy']:
            with self.subTest(source=source):
                result,calls,evidence=self.fixture('',source)
                self.assertEqual(0,result.returncode,result.stderr)
                self.assertTrue(evidence['success'])

    def test_missing_cloud_configuration_cannot_create_job(self):
        for source in ['CloudRun','CloudDeploy']:
            with self.subTest(source=source):
                result,calls,evidence=self.fixture('missing-config',source)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(1,len(calls))
                self.assertIsNone(evidence)

    def test_bare_rest_target_and_actual_gcloud_wrapper_are_both_supported(self):
        for shape in ['', 'bare-target']:
            with self.subTest(shape=shape):
                result,calls,evidence=self.fixture(shape,'CloudDeploy')
                self.assertEqual(0,result.returncode,result.stderr)
                self.assertTrue(evidence['success'])

    def test_actual_wrapped_clouddeploy_host_with_explicit_default_port_succeeds(self):
        result,calls,evidence=self.fixture('','CloudDeploy','10.92.0.3:5432')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertTrue(evidence['success'])
        self.assertEqual(['describe','replace','execute','read','delete'],[call[2] if call[0] != 'logging' else call[1] for call in calls])

    def test_clouddeploy_invalid_port_userinfo_and_injection_rejected_before_job_mutation(self):
        for host in ['10.92.0.3:5433','10.92.0.3:0','owner@10.92.0.3:5432','10.92.0.3:5432/database?sslmode=disable','10.92.0.3:5432;DROP TABLE users','10.92.0.3:5432\n--args=evil']:
            with self.subTest(host=host):
                result,calls,evidence=self.fixture('','CloudDeploy',host)
                self.assertNotEqual(0,result.returncode)
                self.assertIn('explicit valid database host/name',result.stderr)
                self.assertEqual(1,len(calls))
                self.assertEqual(['deploy','targets','describe'],calls[0][:3])
                self.assertIsNone(evidence)

    def test_malformed_gcloud_wrapper_cannot_create_job(self):
        result,calls,evidence=self.fixture('malformed-wrapper','CloudDeploy')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('Target wrapper is malformed',result.stderr)
        self.assertEqual(1,len(calls))
        self.assertIsNone(evidence)

if __name__ == "__main__":
    unittest.main()
