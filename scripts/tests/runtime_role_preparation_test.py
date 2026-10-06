"""Test dev-only dry-run and the real rendered owner SQL/shell against isolated PostgreSQL."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
ROLES = ["ims_identity_rt", "ims_school_core_rt", "ims_operations_rt", "ims_platform_rt", "ims_billing_rt"]
VARIABLES = ["IDENTITY_RUNTIME_PASSWORD", "SCHOOL_CORE_RUNTIME_PASSWORD", "OPERATIONS_RUNTIME_PASSWORD", "PLATFORM_RUNTIME_PASSWORD", "BILLING_RUNTIME_PASSWORD"]

def run(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True, **kwargs)

def load_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw.decode("utf-16") if raw.startswith(b"\xff\xfe") else raw.decode("utf-8-sig"))

class RuntimeRolePreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="ims-managed-role-fixture-")
        result = run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "scripts/security/prepare-dev-runtime-roles.ps1"), "-OutputDirectory", cls.temp.name])
        cls.plan = json.loads(result.stdout)
        cls.job = load_json(cls.plan["reviewedJobFile"])
        cls.shell = cls.job["spec"]["template"]["spec"]["template"]["spec"]["containers"][0]["args"][1]
        cls.container = "ims-role-fixture-" + uuid.uuid4().hex[:10]
        run(["docker", "run", "--detach", "--rm", "--name", cls.container, "-e", "POSTGRES_USER=appuser", "-e", "POSTGRES_PASSWORD=controlled-local-only", "postgres:16"])
        for _ in range(30):
            ready = subprocess.run(["docker", "exec", cls.container, "pg_isready", "-h", "localhost", "-U", "appuser"], capture_output=True)
            if ready.returncode == 0:
                break
            import time
            time.sleep(0.2)
        cls.sql("CREATE ROLE app_rt;")
        for schema in ["identity", "tenant_school", "student", "attendance", "fee", "catalog", "workflow", "firefighting", "reporting", "notification", "audit", "billing"]:
            cls.sql(f"CREATE SCHEMA {schema}; CREATE TABLE {schema}.controlled_fixture(id BIGSERIAL PRIMARY KEY, label TEXT); GRANT USAGE ON SCHEMA {schema} TO app_rt; GRANT SELECT,INSERT,UPDATE,DELETE ON {schema}.controlled_fixture TO app_rt;")

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "container"):
            subprocess.run(["docker", "stop", cls.container], capture_output=True)
        cls.temp.cleanup()

    @classmethod
    def sql(cls, statement):
        return run(["docker", "exec", "-i", cls.container, "psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-U", "appuser", "-d", "postgres"], input=statement).stdout.strip()

    def setUp(self):
        for role in ROLES:
            if self.sql(f"SELECT count(*) FROM pg_roles WHERE rolname='{role}'") == "1":
                self.sql(f"DROP OWNED BY {role}; DROP ROLE {role};")

    def execute_owner_shell(self, overrides=None):
        environment = os.environ.copy()
        # Controlled fixture values, never cloud or application credentials.
        environment.update({name: "a" * 64 for name in VARIABLES})
        environment.update(overrides or {})
        command = ["docker", "exec", "-i", "-e", "PGHOST=localhost", "-e", "PGUSER=appuser", "-e", "PGDATABASE=postgres", "-e", "PGCONNECT_TIMEOUT=3"]
        for variable in VARIABLES:
            command += ["-e", variable]
        command += [self.container, "/bin/sh", "-c", self.shell]
        return subprocess.run(command, text=True, capture_output=True, env=environment)

    def test_dry_run_has_no_secret_values_and_correct_job_shape(self):
        self.assertFalse(self.plan["applyRequested"])
        self.assertEqual("custoking-dev", self.plan["project"])
        self.assertEqual(5, len(self.plan["roles"]))
        self.assertIn("run.googleapis.com/network-interfaces", self.job["spec"]["template"]["metadata"]["annotations"])
        container = self.job["spec"]["template"]["spec"]["template"]["spec"]["containers"][0]
        self.assertEqual("512Mi", container["resources"]["limits"]["memory"])
        self.assertIn("@sha256:", container["image"])
        for item in container["env"]:
            if "PASSWORD" in item["name"]:
                self.assertIn("secretKeyRef", item["valueFrom"])
                self.assertNotIn("value", item)

    def test_apply_refuses_unreviewed_sql_before_any_cloud_action(self):
        result = subprocess.run(["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ROOT / "scripts/security/prepare-dev-runtime-roles.ps1"), "-Apply", "-ExpectedSqlSha256", "0" * 64], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("exact SQL SHA256", result.stderr)

    def test_prepares_all_scoped_roles_atomically_and_reuses_managed_roles(self):
        prepared = self.execute_owner_shell()
        self.assertEqual(0, prepared.returncode, prepared.stderr)
        self.assertEqual("IMS_MANAGED_DEV_RUNTIME_ROLES_PREPARED", prepared.stdout.strip())
        self.assertEqual("5", self.sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('ims_identity_rt','ims_school_core_rt','ims_operations_rt','ims_platform_rt','ims_billing_rt') AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolinherit AND NOT rolreplication"))
        self.assertEqual("t|f", self.sql("SELECT has_table_privilege('ims_billing_rt','billing.controlled_fixture','SELECT'),has_table_privilege('ims_billing_rt','student.controlled_fixture','SELECT')"))
        repeated = self.execute_owner_shell()
        self.assertEqual(0, repeated.returncode, repeated.stderr)

    def test_unmanaged_existing_role_is_not_overwritten_and_no_partial_roles_commit(self):
        self.sql("CREATE ROLE ims_identity_rt LOGIN;")
        result = self.execute_owner_shell()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("1", self.sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('ims_identity_rt','ims_school_core_rt','ims_operations_rt','ims_platform_rt','ims_billing_rt')"))

    def test_invalid_secret_shape_cannot_reach_sql_or_logs(self):
        result = self.execute_owner_shell({"IDENTITY_RUNTIME_PASSWORD": "'); SELECT 'unsafe'; --"})
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("unsafe", result.stdout + result.stderr)
        self.assertEqual("0", self.sql("SELECT count(*) FROM pg_roles WHERE rolname LIKE 'ims_%_rt'"))

if __name__ == "__main__":
    unittest.main()
