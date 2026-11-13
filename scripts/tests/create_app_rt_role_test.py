"""Docker PostgreSQL rehearsal of scripts/create-app-rt-role.sql being re-run on a migrated database.

The script grants app_rt full DML on every table of each service schema, every time it runs (environment rebuilds,
ensure-app-rt-local.ps1, the Cloud SQL runtime-role steps). Some migrations narrow that grant once, as versioned
migrations that never run again, so the script has to take the same privileges back itself:

* catalog V37: order_number_counters is SELECT, INSERT and UPDATE only. Deleting a counter row restarts that item's
  running number for the year (D11).
* catalog V38: product_order_workflows and product_order_workflow_steps are read-only (the PR #380 workflow is retired).
* workflow V11 and V20: the notes, follow-ups and call logs are append-only.

Run from the repository root: python -m unittest scripts.tests.create_app_rt_role_test
"""
import pathlib
import secrets
import shutil
import subprocess
import time
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'create-app-rt-role.sql'

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


def docker_available():
    if shutil.which('docker') is None:
        return False
    return subprocess.run(['docker', 'version', '--format', '{{.Server.Version}}'],
                          capture_output=True, timeout=30).returncode == 0


@unittest.skipUnless(docker_available(), 'needs a running Docker engine')
class CreateAppRtRoleRerunTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.name = 'ims-app-rt-role-' + uuid.uuid4().hex[:12]
        # A throwaway container that only listens inside itself; the value is generated per run and never stored.
        cls.password = secrets.token_urlsafe(24)
        subprocess.run(['docker', 'run', '--detach', '--rm', '--name', cls.name,
                        '-e', 'POSTGRES_PASSWORD=' + secrets.token_urlsafe(24), 'postgres:16'],
                       capture_output=True, check=True, timeout=120)
        try:
            for _ in range(150):
                # The image's bootstrap server listens on a Unix socket only and restarts; wait for the final TCP one.
                probe = subprocess.run(['docker', 'exec', cls.name, 'psql', '-h', '127.0.0.1', '-U', 'postgres',
                                        '-d', 'postgres', '-XAt', '-c', 'SELECT 1'], capture_output=True, timeout=10)
                if probe.returncode == 0:
                    break
                time.sleep(.2)
            else:
                raise RuntimeError('POSTGRES_NOT_READY')
        except BaseException:
            subprocess.run(['docker', 'stop', cls.name], capture_output=True, timeout=60)
            raise

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'stop', cls.name], capture_output=True, timeout=60)

    def psql(self, database, *args, stdin=None):
        return subprocess.run(['docker', 'exec', '-i', self.name, 'psql', '-X', '-U', 'postgres', '-d', database,
                               *args], input=stdin, capture_output=True, timeout=60)

    def sql(self, database, text):
        result = self.psql(database, '-At', '-v', 'ON_ERROR_STOP=1', stdin=text.encode('utf-8'))
        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        return result.stdout.decode('utf-8').strip()

    def run_script(self, database):
        return self.psql(database, '-v', 'app_rt_password=' + self.password, '-v', 'owner=postgres', '-f', '-',
                         stdin=SCRIPT.read_bytes())

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
        self.assertEqual(first.returncode, 0, first.stderr.decode('utf-8', 'replace'))
        self.sql(database, SCHEMA)
        self.sql(database, MIGRATION_REVOKES)
        return database

    def test_first_run_before_the_services_migrate_is_a_no_op_for_the_narrowed_tables(self):
        database = self.new_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        self.assertIn('create-app-rt-role: role ready', result.stdout.decode('utf-8'))

    def test_rerun_keeps_order_number_counters_undeletable(self):
        database = self.migrated_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        self.assertEqual(self.privileges(database, 'catalog.order_number_counters'),
                         {'SELECT': True, 'INSERT': True, 'UPDATE': True, 'DELETE': False})

    def test_rerun_keeps_the_retired_product_order_workflow_tables_read_only(self):
        database = self.migrated_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        for table in ('catalog.product_order_workflows', 'catalog.product_order_workflow_steps'):
            with self.subTest(table=table):
                self.assertEqual(self.privileges(database, table),
                                 {'SELECT': True, 'INSERT': False, 'UPDATE': False, 'DELETE': False})

    def test_rerun_keeps_the_workflow_logs_append_only(self):
        database = self.migrated_database()

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
        for table in ('workflow.workflow_internal_notes', 'workflow.workflow_follow_ups', 'workflow.workflow_call_logs'):
            with self.subTest(table=table):
                self.assertEqual(self.privileges(database, table),
                                 {'SELECT': True, 'INSERT': True, 'UPDATE': False, 'DELETE': False})

    def test_rerun_still_grants_full_dml_on_ordinary_catalog_tables(self):
        database = self.migrated_database()
        self.sql(database, 'REVOKE DELETE ON catalog.catalog_orders FROM app_rt;')

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr.decode('utf-8', 'replace'))
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
        self.assertIn('order_number_counters', result.stderr.decode('utf-8', 'replace'))


if __name__ == '__main__':
    unittest.main()
