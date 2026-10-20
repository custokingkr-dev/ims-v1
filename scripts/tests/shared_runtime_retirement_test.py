"""Controlled retirement admission plus execution of the rendered SQL on real PostgreSQL.

Requires Docker and PowerShell; deliberately separate from credential-free static CI.
No cloud commands or application credentials are used.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which("pwsh") or shutil.which("powershell.exe") or shutil.which("powershell")
if not POWERSHELL:
    raise RuntimeError("PowerShell is required for retirement regression tests")
SCRIPT = ROOT / "scripts/security/retire-dev-shared-runtime-role.ps1"
ROLES = {
    "identity-service": "ims_identity_rt",
    "school-core-service": "ims_school_core_rt",
    "operations-service": "ims_operations_rt",
    "platform-service": "ims_platform_rt",
    "billing-service": "ims_billing_rt",
}


def checked(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, check=True, **kwargs)


def load_json(path):
    raw = Path(path).read_bytes()
    return json.loads(raw.decode("utf-16") if raw.startswith(b"\xff\xfe") else raw.decode("utf-8-sig"))


class SharedRuntimeRetirementTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="ims-retirement-fixture-")
        cls.folder = Path(cls.temp.name)
        cli = cls.folder / "fixture_cli.py"
        cli.write_text("""import json, os, sys
from pathlib import Path
args=sys.argv[1:]
with Path(os.environ['FIXTURE_CALLS']).open('a') as log:
    log.write(json.dumps(args)+'\\n')
if args[:3] not in (['run','services','describe'], ['run','revisions','describe']):
    print('Unapproved fixture command',file=sys.stderr)
    sys.exit(19)
services=json.loads(Path(os.environ['FIXTURE_SERVICES']).read_text())
if args[1]=='revisions':
    service=next(value for value in services.values() if value['status']['latestReadyRevisionName']==args[3])
    print(json.dumps(service['fixtureRevision']))
else:
    print(json.dumps(services[args[3]]))
""")
        native = cls.folder / "gcloud.cmd"
        if os.name == "nt":
            native.write_text(f'@echo off\n"{sys.executable}" "{cli}" %*\nexit /b %errorlevel%\n')
        else:
            native.write_text(f'#!{sys.executable}\nimport runpy,sys\nsys.path.insert(0,{str(cls.folder)!r})\nrunpy.run_path({str(cli)!r},run_name="__main__")\n')
            native.chmod(0o755)
        cls.container = "ims-retirement-fixture-" + uuid.uuid4().hex[:10]
        cls.processes = []
        try:
            checked(["docker", "run", "--detach", "--rm", "--name", cls.container,
                     "-e", "POSTGRES_USER=appuser", "-e", "POSTGRES_PASSWORD=controlled-local-only", "postgres:16"])
            for _ in range(60):
                if subprocess.run(["docker", "exec", cls.container, "pg_isready", "-h", "localhost", "-U", "appuser"], capture_output=True).returncode == 0:
                    break
                time.sleep(0.2)
            else:
                raise RuntimeError("Controlled PostgreSQL fixture did not become ready")
            cls.sql("CREATE ROLE app_rt LOGIN NOINHERIT; CREATE TABLE public.baseline(id integer); GRANT SELECT ON public.baseline TO app_rt;")
            for role in ROLES.values():
                cls.sql(f"CREATE ROLE {role} LOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOREPLICATION;")
            cls.sql("CREATE ROLE dangerous BYPASSRLS; CREATE SCHEMA controlled_owner;")
        except BaseException:
            subprocess.run(["docker", "stop", cls.container], capture_output=True)
            cls.temp.cleanup()
            raise

    @classmethod
    def tearDownClass(cls):
        for process in cls.processes:
            if process.poll() is None:
                process.terminate()
            process.communicate(timeout=10)
        subprocess.run(["docker", "stop", cls.container], capture_output=True)
        cls.temp.cleanup()

    @classmethod
    def sql(cls, statement, check=True):
        result = subprocess.run(["docker", "exec", "-i", cls.container, "psql", "-X", "-A", "-t",
                                 "-v", "ON_ERROR_STOP=1", "-h", "localhost", "-U", "appuser", "-d", "postgres"],
                                input=statement, text=True, capture_output=True)
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result

    def setUp(self):
        self.sql("ALTER ROLE app_rt LOGIN;")
        self.calls = self.folder / (uuid.uuid4().hex + "-calls.jsonl")
        self.services_file = self.folder / (uuid.uuid4().hex + "-services.json")
        self.output = self.folder / (uuid.uuid4().hex + "-output")

    def services(self):
        result = {}
        for service, role in ROLES.items():
            revision = "custoking-" + service + "-dev-00042-fixture"
            result["custoking-" + service + "-dev"] = {
                "spec": {"template": {"spec": {"containers": [{"env": [
                    {"name": "RUNTIME_DB_ROLE", "value": role},
                    {"name": "SPRING_DATASOURCE_USERNAME", "value": role},
                    {"name": "APP_MIGRATIONS_ENABLED", "value": "false"},
                    {"name": "SPRING_DATASOURCE_PASSWORD", "valueFrom": {"secretKeyRef": {"name": service.replace("-service", "") + "-runtime-db-password-dev", "key": "latest"}}},
                ]}]}}},
                "status": {"latestReadyRevisionName": revision, "latestCreatedRevisionName": revision,
                           "traffic": [{"revisionName": revision, "percent": 100}]},
            }
        for service, value in result.items():
            stem = service.removeprefix("custoking-").removesuffix("-dev").replace("-service", "")
            value["fixtureRevision"] = {
                "metadata": {"name": value["status"]["latestReadyRevisionName"]},
                "spec": {"serviceAccountName": f"ims-{stem}-dev@custoking-dev.iam.gserviceaccount.com",
                         "containers": value["spec"]["template"]["spec"]["containers"]},
                "status": {"conditions": [{"type": "Ready", "status": "True"}]},
            }
        return result

    def render(self, services=None, apply=False):
        self.services_file.write_text(json.dumps(services or self.services()))
        env = os.environ.copy()
        env.update(PATH=str(self.folder) + os.pathsep + env.get("PATH", ""),
                   FIXTURE_CALLS=str(self.calls), FIXTURE_SERVICES=str(self.services_file), NO_COLOR="1")
        command = [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(SCRIPT),
                   "-OutputDirectory", str(self.output)]
        if apply:
            command.append("-Apply")
        return subprocess.run(command, env=env, text=True, capture_output=True)

    def rendered_sql(self):
        result = self.render()
        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(result.stdout)
        self.assertFalse(plan["apply"])
        job = load_json(plan["jobFile"])
        container = job["spec"]["template"]["spec"]["template"]["spec"]["containers"][0]
        self.assertIn("@sha256:", container["image"])
        self.assertEqual(["psql"], container["command"])
        self.assertIn("ON_ERROR_STOP=1", container["args"])
        password = next(entry for entry in container["env"] if entry["name"] == "PGPASSWORD")
        self.assertNotIn("value", password)
        self.assertIn("secretKeyRef", password["valueFrom"])
        self.assertNotIn("controlled-local-only", result.stdout + result.stderr + json.dumps(job))
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
        self.assertEqual(10, len(calls))
        self.assertTrue(all(call[:3] in (["run", "services", "describe"], ["run", "revisions", "describe"]) for call in calls))
        return container["args"][container["args"].index("-c") + 1]

    def active_connection(self, role):
        process = subprocess.Popen(["docker", "exec", self.container, "psql", "-X", "-U", role,
                                    "-d", "postgres", "-c", "SELECT pg_sleep(60)"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.processes.append(process)
        for _ in range(60):
            if self.sql(f"SELECT count(*) FROM pg_stat_activity WHERE usename='{role}' AND query='SELECT pg_sleep(60)' AND state='active'").stdout.strip() == "1":
                return process
            if process.poll() is not None:
                self.fail(process.communicate()[1])
            time.sleep(0.1)
        self.fail("Controlled connection never became active")

    def test_real_sql_disables_only_shared_login_and_terminates_only_shared_sessions_preserving_acl(self):
        sql = self.rendered_sql()
        shared = self.active_connection("app_rt")
        dedicated = self.active_connection("ims_identity_rt")
        owner = self.active_connection("appuser")
        result = self.sql(sql)
        self.assertEqual(0, result.returncode, result.stderr)
        shared.communicate(timeout=10)
        self.assertNotEqual(0, shared.returncode)
        self.assertIsNone(dedicated.poll())
        self.assertIsNone(owner.poll())
        self.assertEqual("f|t", self.sql("SELECT rolcanlogin,has_table_privilege('app_rt','public.baseline','SELECT') FROM pg_roles WHERE rolname='app_rt'").stdout.strip())
        refused = subprocess.run(["docker", "exec", self.container, "psql", "-X", "-U", "app_rt", "-d", "postgres", "-c", "SELECT 1"], text=True, capture_output=True)
        self.assertNotEqual(0, refused.returncode)
        self.assertIn("not permitted to log in", refused.stderr)
        self.assertEqual("5", self.sql("SELECT count(*) FROM pg_roles WHERE rolname IN ('ims_identity_rt','ims_school_core_rt','ims_operations_rt','ims_platform_rt','ims_billing_rt') AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit").stdout.strip())
        self.assertEqual("t|t", self.sql("SELECT rolcanlogin,rolsuper FROM pg_roles WHERE rolname='appuser'").stdout.strip())
        proof = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertFalse(proof["canLogin"])
        self.assertEqual(0, proof["remainingSessions"])
        # Close fixture peer/owner sleepers without affecting the assertions above.
        self.sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query='SELECT pg_sleep(60)' AND pid<>pg_backend_pid();")

    def test_every_missing_dedicated_role_blocks_before_alter(self):
        sql = self.rendered_sql()
        for role in ROLES.values():
            with self.subTest(role=role):
                self.sql(f"ALTER ROLE {role} NOLOGIN;")
                try:
                    result = self.sql(sql, check=False)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("t", self.sql("SELECT rolcanlogin FROM pg_roles WHERE rolname='app_rt'").stdout.strip())
                finally:
                    self.sql(f"ALTER ROLE {role} LOGIN;")

    def test_unsafe_role_privileges_membership_and_ownership_block_before_alter(self):
        sql = self.rendered_sql()
        role = "ims_identity_rt"
        cases = [
            (f"ALTER ROLE {role} SUPERUSER", f"ALTER ROLE {role} NOSUPERUSER"),
            (f"ALTER ROLE {role} CREATEROLE", f"ALTER ROLE {role} NOCREATEROLE"),
            (f"ALTER ROLE {role} CREATEDB", f"ALTER ROLE {role} NOCREATEDB"),
            (f"CREATE TABLE public.owned_fixture(id integer); ALTER TABLE public.owned_fixture OWNER TO {role}", "DROP TABLE public.owned_fixture"),
            (f"CREATE DATABASE owned_fixture OWNER {role}", "DROP DATABASE owned_fixture"),
            (f"CREATE FUNCTION public.owned_fixture() RETURNS integer LANGUAGE sql AS 'SELECT 1'; ALTER FUNCTION public.owned_fixture() OWNER TO {role}", "DROP FUNCTION public.owned_fixture()"),
            (f"ALTER ROLE {role} BYPASSRLS", f"ALTER ROLE {role} NOBYPASSRLS"),
            (f"ALTER ROLE {role} INHERIT", f"ALTER ROLE {role} NOINHERIT"),
            (f"ALTER ROLE {role} REPLICATION", f"ALTER ROLE {role} NOREPLICATION"),
            (f"GRANT dangerous TO {role}", f"REVOKE dangerous FROM {role}"),
            (f"ALTER SCHEMA controlled_owner OWNER TO {role}", "ALTER SCHEMA controlled_owner OWNER TO appuser"),
        ]
        for unsafe, cleanup in cases:
            with self.subTest(unsafe=unsafe):
                self.sql(unsafe)
                try:
                    result = self.sql(sql, check=False)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("t", self.sql("SELECT rolcanlogin FROM pg_roles WHERE rolname='app_rt'").stdout.strip())
                finally:
                    self.sql(cleanup)

    def test_failed_cloud_admission_creates_no_job_even_with_apply_requested(self):
        for failure in ["not-ready", "split-traffic", "shared-role", "wrong-username", "missing-role", "duplicate-role", "old-tag", "wrong-account", "shared-secret", "desired-only-isolation", "unready-serving", "owner-secret", "migrations-enabled"]:
            with self.subTest(failure=failure):
                services = self.services()
                selected = next(iter(services.values()))
                env = selected["spec"]["template"]["spec"]["containers"][0]["env"]
                if failure == "not-ready":
                    selected["status"]["latestCreatedRevisionName"] = "unready-revision"
                elif failure == "split-traffic":
                    selected["status"]["traffic"][0]["percent"] = 50
                elif failure == "shared-role":
                    env[0]["value"] = "app_rt"
                elif failure == "wrong-username":
                    env[1]["value"] = "appuser"
                elif failure == "missing-role":
                    del env[0]
                elif failure == "duplicate-role":
                    env.append(dict(env[0]))
                elif failure == "old-tag":
                    selected["status"]["traffic"].append({"revisionName": "old-revision", "percent": 0, "tag": "old"})
                elif failure == "wrong-account":
                    selected["fixtureRevision"]["spec"]["serviceAccountName"] = "shared@custoking-dev.iam.gserviceaccount.com"
                elif failure == "shared-secret":
                    env[3]["valueFrom"]["secretKeyRef"]["name"] = "app-rt-password-dev"
                elif failure == "desired-only-isolation":
                    # Safe desired template must not mask the actual serving revision.
                    selected["fixtureRevision"]["spec"]["containers"] = json.loads(json.dumps(selected["fixtureRevision"]["spec"]["containers"]))
                    selected["fixtureRevision"]["spec"]["containers"][0]["env"][0]["value"] = "app_rt"
                elif failure == "unready-serving":
                    selected["fixtureRevision"]["status"]["conditions"][0]["status"] = "False"
                elif failure == "owner-secret":
                    env.append({"name": "FLYWAY_PASSWORD", "valueFrom": {"secretKeyRef": {"name": "controlled-owner-reference", "key": "latest"}}})
                else:
                    env[2]["value"] = "true"
                result = self.render(services, apply=True)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.output / "retirement-job.json").exists())
                calls = [json.loads(line) for line in self.calls.read_text().splitlines()]
                self.assertTrue(all(call[:3] in (["run", "services", "describe"], ["run", "revisions", "describe"]) for call in calls))


if __name__ == "__main__":
    unittest.main()
