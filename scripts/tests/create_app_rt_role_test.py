"""Docker PostgreSQL rehearsal of scripts/create-app-rt-role.sql being re-run on a database with narrowed tables.

The script grants app_rt DML on every relation of each service schema, every time it runs (environment rebuilds,
ensure-app-rt-local.ps1, the Cloud SQL runtime-role steps). Some migrations narrow that grant once, as versioned
migrations that never run again, so the script keeps a narrowing list and must never hand those privileges back.
These tests use a few synthetic tables shaped like the PR #382 ones:

* catalog V37: order_number_counters is SELECT, INSERT and UPDATE only. Deleting a counter row restarts that item's
  running number for the year (D11).
* catalog V38: product_order_workflows and product_order_workflow_steps are read-only (the PR #380 workflow is retired).
* workflow V11 and V20: the notes, follow-ups and call logs are append-only.

scripts/tests/create_app_rt_role_migrated_test.py checks the same invariant on every service's real migrations.

Without Docker these tests skip locally and fail in CI (.github/workflows/ci-pr.yml, job app-rt-role-invariants).

Run from the repository root: python -m unittest scripts.tests.create_app_rt_role_test
"""
import os
import pathlib
import secrets
import shutil
import subprocess
import time
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'create-app-rt-role.sql'

# PostgreSQL 16.15 (Debian), the multi-platform image index of postgres:16 as published on 2026-11-05. Pinned so a
# re-tag cannot change what these tests run against; bump it deliberately.
POSTGRES_IMAGE = 'postgres:16@sha256:f9a7b9ebf6f56201f949db745e47bc423c0f0f8294bafe991cc10bf74b029657'

# Tables shaped only as far as the grants care. The owner (postgres, as in a local stack) creates them after the
# script's first run, so the default privileges that run sets hand app_rt full DML on each of them.
SCHEMA = '''
CREATE TABLE catalog.catalog_orders (id BIGINT PRIMARY KEY);
CREATE TABLE catalog.order_number_counters (item_code VARCHAR(4), year SMALLINT, last_value INTEGER,
                                            PRIMARY KEY (item_code, year));
CREATE TABLE catalog.product_order_workflows (id BIGINT PRIMARY KEY);
CREATE TABLE catalog.product_order_workflow_steps (id BIGINT PRIMARY KEY);
CREATE TABLE workflow.workflow_internal_notes (id BIGINT PRIMARY KEY);
CREATE TABLE workflow.workflow_follow_ups (id BIGINT PRIMARY KEY);
CREATE TABLE workflow.workflow_call_logs (id BIGINT PRIMARY KEY);
'''

# What the versioned migrations do to app_rt, once.
MIGRATION_REVOKES = '''
GRANT SELECT, INSERT, UPDATE ON catalog.order_number_counters TO app_rt;
REVOKE DELETE, TRUNCATE ON catalog.order_number_counters FROM app_rt;
REVOKE INSERT, UPDATE, DELETE ON catalog.product_order_workflows, catalog.product_order_workflow_steps FROM app_rt;
REVOKE UPDATE, DELETE ON workflow.workflow_internal_notes, workflow.workflow_follow_ups FROM app_rt;
REVOKE UPDATE, DELETE, TRUNCATE ON workflow.workflow_call_logs FROM app_rt;
'''

PRIVILEGES = ('SELECT', 'INSERT', 'UPDATE', 'DELETE')


def in_ci():
    return any(os.environ.get(name, '').lower() == 'true' for name in ('CI', 'GITHUB_ACTIONS'))


def docker_available():
    if shutil.which('docker') is None:
        return False
    return subprocess.run(['docker', 'version', '--format', '{{.Server.Version}}'],
                          capture_output=True, timeout=30).returncode == 0


def require_docker():
    """Skips without a Docker engine locally. In CI a missing engine is an error, never a silent skip."""
    if docker_available():
        return
    if in_ci():
        raise RuntimeError('a Docker engine is required in CI for the app_rt role tests')
    raise unittest.SkipTest('needs a running Docker engine (CI runs these tests in job app-rt-role-invariants)')


class PostgresContainer:
    """A throwaway PostgreSQL. The superuser password is generated per run and never stored."""

    def __init__(self, publish=False):
        self.name = 'ims-app-rt-role-' + uuid.uuid4().hex[:12]
        self.password = secrets.token_urlsafe(24)
        self.port = None
        command = ['docker', 'run', '--detach', '--rm', '--name', self.name, '-e', 'POSTGRES_PASSWORD=' + self.password]
        if publish:
            command += ['-p', '127.0.0.1::5432']
        subprocess.run(command + [POSTGRES_IMAGE], capture_output=True, check=True, timeout=300)
        try:
            for _ in range(300):
                # The image's bootstrap server listens on a Unix socket only and restarts; wait for the final TCP one.
                probe = subprocess.run(['docker', 'exec', self.name, 'psql', '-h', '127.0.0.1', '-U', 'postgres',
                                        '-d', 'postgres', '-XAt', '-c', 'SELECT 1'], capture_output=True, timeout=10)
                if probe.returncode == 0:
                    break
                time.sleep(.2)
            else:
                raise RuntimeError('POSTGRES_NOT_READY')
            if publish:
                mapping = subprocess.run(['docker', 'port', self.name, '5432/tcp'], capture_output=True, text=True,
                                         check=True, timeout=30).stdout.split()[0]
                self.port = int(mapping.rsplit(':', 1)[1])
        except BaseException:
            self.stop()
            raise

    def stop(self):
        subprocess.run(['docker', 'stop', self.name], capture_output=True, timeout=60)

    def psql(self, database, *args, stdin=None):
        """psql inside the container as the superuser. Output is text."""
        result = subprocess.run(['docker', 'exec', '-i', self.name, 'psql', '-X', '-U', 'postgres', '-d', database,
                                 *args], input=(stdin or '').encode('utf-8'), capture_output=True, timeout=300)
        return subprocess.CompletedProcess(result.args, result.returncode, result.stdout.decode('utf-8', 'replace'),
                                           result.stderr.decode('utf-8', 'replace'))

    def sql(self, database, text):
        result = self.psql(database, '-qAt', '-v', 'ON_ERROR_STOP=1', stdin=text)
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return result.stdout.strip()


class CreateAppRtRoleRerunTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_docker()
        cls.pg = PostgresContainer()

    @classmethod
    def tearDownClass(cls):
        cls.pg.stop()

    def sql(self, database, text):
        return self.pg.sql(database, text)

    def run_script(self, database):
        return self.pg.psql(database, '-v', 'app_rt_password=' + self.pg.password, '-v', 'owner=postgres', '-f', '-',
                            stdin=SCRIPT.read_text(encoding='utf-8'))

    def new_database(self):
        database = 'rerun_' + uuid.uuid4().hex[:10]
        self.sql('postgres', 'CREATE DATABASE ' + database + ';')
        self.sql(database, 'CREATE SCHEMA catalog; CREATE SCHEMA workflow;')
        return database

    def privileges(self, database, table):
        rows = self.sql(database, ' UNION ALL '.join(
            "SELECT '%s', has_table_privilege('app_rt', '%s', '%s')" % (privilege, table, privilege)
            for privilege in PRIVILEGES))
        return {privilege: value == 't' for privilege, value in (row.split('|') for row in rows.splitlines())}

    def migrated_database(self):
        database = self.new_database()
        first = self.run_script(database)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.sql(database, SCHEMA)
        self.sql(database, MIGRATION_REVOKES)
        return database

    def test_first_run_before_the_services_migrate_is_a_no_op_for_the_narrowed_tables(self):
        database = self.new_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('create-app-rt-role: role ready', result.stdout)

    def test_rerun_keeps_order_number_counters_undeletable(self):
        database = self.migrated_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.privileges(database, 'catalog.order_number_counters'),
                         {'SELECT': True, 'INSERT': True, 'UPDATE': True, 'DELETE': False})

    def test_rerun_keeps_the_retired_product_order_workflow_tables_read_only(self):
        database = self.migrated_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        for table in ('catalog.product_order_workflows', 'catalog.product_order_workflow_steps'):
            with self.subTest(table=table):
                self.assertEqual(self.privileges(database, table),
                                 {'SELECT': True, 'INSERT': False, 'UPDATE': False, 'DELETE': False})

    def test_rerun_keeps_the_workflow_logs_append_only(self):
        database = self.migrated_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        for table in ('workflow.workflow_internal_notes', 'workflow.workflow_follow_ups', 'workflow.workflow_call_logs'):
            with self.subTest(table=table):
                self.assertEqual(self.privileges(database, table),
                                 {'SELECT': True, 'INSERT': True, 'UPDATE': False, 'DELETE': False})

    def test_rerun_still_grants_full_dml_on_ordinary_catalog_tables(self):
        database = self.migrated_database()
        self.sql(database, 'REVOKE DELETE ON catalog.catalog_orders FROM app_rt;')

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.privileges(database, 'catalog.catalog_orders'),
                         {'SELECT': True, 'INSERT': True, 'UPDATE': True, 'DELETE': True})

    def test_rerun_fails_when_app_rt_keeps_delete_on_the_counters_through_another_grantor(self):
        database = self.migrated_database()
        # A grant the owner's REVOKE cannot reach: it was made by another role holding the grant option.
        grantor = 'grantor_' + uuid.uuid4().hex[:8]
        self.sql(database, 'CREATE ROLE %s NOLOGIN;'
                           'GRANT USAGE ON SCHEMA catalog TO %s;'
                           'GRANT DELETE ON catalog.order_number_counters TO %s WITH GRANT OPTION;'
                           'SET ROLE %s;'
                           'GRANT DELETE ON catalog.order_number_counters TO app_rt;'
                           'RESET ROLE;' % (grantor, grantor, grantor, grantor))

        result = self.run_script(database)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('order_number_counters', result.stderr)


if __name__ == '__main__':
    unittest.main()
