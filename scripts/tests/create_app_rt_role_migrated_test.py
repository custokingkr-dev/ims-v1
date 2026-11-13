"""scripts/create-app-rt-role.sql against every service's real migrations, in a Docker PostgreSQL 16.

The script grants app_rt DML on the relations of the twelve service schemas. About twenty versioned migrations narrow
that once (append-only logs, column-only grants, tables with no runtime access at all) and never run again, so a re-run
of the script must leave those narrowings alone. scripts/tests/create_app_rt_role_test.py checks the idea on a few
synthetic tables; this module checks the invariant itself on the real schema:

* Migrate all 12 units with each service's own configuration (MigrationOnlyMain: one Flyway per schema, its own history
  table and folder, afterMigrate__dedicated_runtime_acl.sql included), after a first run of the script, as in every
  environment. Snapshot every ACL entry for app_rt, the ims_*_rt roles and PUBLIC (tables, views, columns, sequences,
  functions, schemas, default privileges; a NULL ACL is expanded to its default).
* Re-run the script: the snapshot must not change. The one deliberate exception is the Flyway history tables, which the
  script's own default privileges hand to app_rt when Flyway creates them, and which the script now takes back.
* Migrate again (nothing pending, the callbacks still run): nothing may change, dedicated roles included. Each dedicated
  role holds in its own schemas exactly what app_rt holds, and nothing on a history table.
* Every relation on the script's narrowing list must exist, so a dropped or renamed table cannot leave a stale row.
* The order the script and the migrations run in must not matter: a build whose first run came before any schema
  existed, a re-run that lands while student is still before V39, and (ProdShapeTests) migrations that ran before
  app_rt existed at all end with the same privileges.
* The script repairs a database the old blanket grant widened, and fails naming relation, privilege and grantor when
  another role granted a listed privilege. The audit passes after the script ran and names what a widening added.
* ProdShapeTests runs the shell of the Cloud Run job invoke-create-app-rt-role-cloudsql.ps1 renders, as a non-superuser
  owner through cloudsqlsuperuser, over TLS. CloudRunJobTests checks the rendered job without Docker.

It also checks, without Docker, that the narrowing list in the script and in scripts/audit-app-rt-privileges.sql are
the same text, that every row of it can be read, and that the twelve afterMigrate copies differ only in their role and
schema.

Needs Docker, a JDK and the Maven wrapper (it resolves Flyway from a service's own dependency tree), and PowerShell (pwsh
or Windows PowerShell) for the job tests. Without them it skips locally and fails in CI (.github/workflows/ci-pr.yml,
job app-rt-role-invariants).

Run from the repository root: python -m unittest scripts.tests.create_app_rt_role_migrated_test
"""
import base64
import collections
import gzip
import hashlib
import json
import os
import pathlib
import re
import secrets
import shutil
import subprocess
import tempfile
import unittest
import uuid

from scripts.tests.create_app_rt_role_test import PostgresContainer, in_ci, require_docker

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'create-app-rt-role.sql'
AUDIT = ROOT / 'scripts' / 'audit-app-rt-privileges.sql'
INVOKE = ROOT / 'scripts' / 'invoke-create-app-rt-role-cloudsql.ps1'
CUTOVER = ROOT / 'docs' / 'security-remediation' / 'runtime-role-cutover.sql'
HARNESS = ROOT / 'scripts' / 'tests' / 'AppRtFlywayHarness.java'
MIGRATION_ONLY_MAIN = ('services/school-core-service/src/main/java/com/custoking/ims/migration/MigrationOnlyMain.java')

SCHEMAS = ('identity', 'tenant_school', 'student', 'attendance', 'fee', 'catalog', 'workflow', 'firefighting',
           'reporting', 'notification', 'audit', 'billing')
# The order the units migrate in. MigrationOnlyMain fixes the order of schemas within a service; services deploy in
# any order, and each service's callback only touches its own schemas.
SERVICES = ('identity-service', 'school-core-service', 'operations-service', 'platform-service', 'billing-service')
DEDICATED_ROLES = {'identity-service': 'ims_identity_rt', 'school-core-service': 'ims_school_core_rt',
                   'operations-service': 'ims_operations_rt', 'platform-service': 'ims_platform_rt',
                   'billing-service': 'ims_billing_rt'}
# No service overrides the parent-managed Flyway version (checked below), so one service's runtime classpath is the
# Flyway every migration job runs.
CLASSPATH_MODULE = 'services/school-core-service'

LIST_BEGIN = '-- app_rt narrowing list: begin'
LIST_END = '-- app_rt narrowing list: end'
HISTORY = re.compile(r'^[a-z_]+\.flyway_schema_history[a-z_]*$')
PRIVILEGES = {'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE'}
RELATION = re.compile(r'^([a-z0-9_]+)\.[a-z0-9_]+$')
COLUMN = re.compile(r'^[a-z0-9_]+$')

# Cloud Run's limit on one environment variable (https://cloud.google.com/run/quotas: "Maximum variable length, in Kb",
# 32), and cmd.exe's limit on a command line, which every gcloud.cmd call goes through on Windows.
CLOUD_RUN_VARIABLE_LIMIT = 32 * 1024
CMD_EXE_COMMAND_LINE_LIMIT = 8191

POWERSHELL = shutil.which('pwsh') or shutil.which('powershell.exe') or shutil.which('powershell')

# One line per ACL entry: grantee|kind|object|privilege|grantor|grantable.
SNAPSHOT = r'''
WITH schemas(name) AS (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),('catalog'),
                              ('workflow'),('firefighting'),('reporting'),('notification'),('audit'),('billing')),
roles AS (SELECT oid, rolname FROM pg_roles WHERE rolname = 'app_rt' OR rolname LIKE 'ims\_%\_rt'
          UNION ALL SELECT 0, 'PUBLIC'),
rel AS (
  SELECT r.rolname AS grantee,
         CASE c.relkind WHEN 'S' THEN 'sequence' WHEN 'v' THEN 'view' WHEN 'm' THEN 'matview' ELSE 'table' END AS kind,
         n.nspname || '.' || c.relname AS object, a.privilege_type AS privilege,
         pg_get_userbyid(a.grantor) AS grantor, a.is_grantable
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN schemas s ON s.name = n.nspname
  CROSS JOIN LATERAL aclexplode(coalesce(c.relacl, acldefault(CASE WHEN c.relkind = 'S' THEN 's' ELSE 'r' END::"char", c.relowner))) a
  JOIN roles r ON r.oid = a.grantee
  WHERE c.relkind IN ('r','p','v','m','S','f')),
col AS (
  SELECT r.rolname, 'column', n.nspname || '.' || c.relname || '(' || att.attname || ')', a.privilege_type,
         pg_get_userbyid(a.grantor), a.is_grantable
  FROM pg_attribute att JOIN pg_class c ON c.oid = att.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN schemas s ON s.name = n.nspname
  CROSS JOIN LATERAL aclexplode(att.attacl) a JOIN roles r ON r.oid = a.grantee
  WHERE att.attnum > 0 AND NOT att.attisdropped AND att.attacl IS NOT NULL),
fn AS (
  SELECT r.rolname, 'function', p.oid::regprocedure::text, a.privilege_type, pg_get_userbyid(a.grantor), a.is_grantable
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN schemas s ON s.name = n.nspname
  CROSS JOIN LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a JOIN roles r ON r.oid = a.grantee),
sch AS (
  SELECT r.rolname, 'schema', n.nspname::text, a.privilege_type, pg_get_userbyid(a.grantor), a.is_grantable
  FROM pg_namespace n JOIN schemas s ON s.name = n.nspname
  CROSS JOIN LATERAL aclexplode(coalesce(n.nspacl, acldefault('n', n.nspowner))) a JOIN roles r ON r.oid = a.grantee),
dflt AS (
  SELECT r.rolname, 'default:' || d.defaclobjtype::text, coalesce(n.nspname, '<global>') || ' for ' || pg_get_userbyid(d.defaclrole),
         a.privilege_type, pg_get_userbyid(a.grantor), a.is_grantable
  FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace
  CROSS JOIN LATERAL aclexplode(d.defaclacl) a JOIN roles r ON r.oid = a.grantee)
SELECT concat_ws('|', grantee, kind, object, privilege, grantor, is_grantable) FROM (
  SELECT * FROM rel UNION ALL SELECT * FROM col UNION ALL SELECT * FROM fn UNION ALL SELECT * FROM sch UNION ALL SELECT * FROM dflt
) x
ORDER BY 1;
'''

# What a role can actually do in the service schemas, whoever granted it: one line per privilege. A column privilege is
# listed only where the role lacks it at table level. Two databases whose grants differ only in redundant entries (a
# column grant the table-level grant already covers) give the same lines.
EFFECTIVE = r'''
WITH schemas(name) AS (VALUES ('identity'),('tenant_school'),('student'),('attendance'),('fee'),('catalog'),
                              ('workflow'),('firefighting'),('reporting'),('notification'),('audit'),('billing')),
rels AS (SELECT c.oid, c.relkind, n.nspname || '.' || c.relname AS object
         FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN schemas s ON s.name = n.nspname),
tbl AS (SELECT 'table|' || r.object || '|' || p AS line
        FROM rels r CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) p
        WHERE r.relkind IN ('r','p','v','m','f') AND has_table_privilege('{role}', r.oid, p)),
col AS (SELECT 'column|' || r.object || '(' || a.attname || ')|' || p
        FROM rels r JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped
        CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','REFERENCES']) p
        WHERE r.relkind IN ('r','p','v','m','f') AND has_column_privilege('{role}', r.oid, a.attnum, p)
          AND NOT has_table_privilege('{role}', r.oid, p)),
seq AS (SELECT 'sequence|' || r.object || '|' || p
        FROM rels r CROSS JOIN unnest(ARRAY['USAGE','SELECT','UPDATE']) p
        WHERE r.relkind = 'S' AND has_sequence_privilege('{role}', r.oid, p)),
fn AS (SELECT 'function|' || p.oid::regprocedure::text || '|EXECUTE'
       FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN schemas s ON s.name = n.nspname
       WHERE has_function_privilege('{role}', p.oid, 'EXECUTE')),
sch AS (SELECT 'schema|' || n.nspname || '|' || p
        FROM pg_namespace n JOIN schemas s ON s.name = n.nspname CROSS JOIN unnest(ARRAY['USAGE','CREATE']) p
        WHERE has_schema_privilege('{role}', n.oid, p))
SELECT line FROM (SELECT * FROM tbl UNION ALL SELECT * FROM col UNION ALL SELECT * FROM seq
                  UNION ALL SELECT * FROM fn UNION ALL SELECT * FROM sch) x
ORDER BY 1;
'''

# Where a dedicated role and app_rt differ in the dedicated role's own schemas (the history tables aside), and how many
# table privileges each dedicated role holds there. afterMigrate__dedicated_runtime_acl.sql copies what app_rt can do.
DEDICATED_PARITY = r'''
WITH units(role_name, schema_name) AS (VALUES {units}),
rels AS (SELECT u.role_name::name AS role_name, c.oid, c.relkind, n.nspname || '.' || c.relname AS object
         FROM units u JOIN pg_namespace n ON n.nspname = u.schema_name JOIN pg_class c ON c.relnamespace = n.oid
         WHERE NOT starts_with(c.relname, 'flyway_schema_history')),
checks AS (
  SELECT r.role_name, 'table ' || r.object AS object, p AS privilege,
         has_table_privilege(r.role_name, r.oid, p) AS dedicated, has_table_privilege('app_rt', r.oid, p) AS shared
  FROM rels r CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) p WHERE r.relkind IN ('r','p','v','m')
  UNION ALL
  SELECT r.role_name, 'column ' || r.object || '(' || a.attname || ')', p,
         has_column_privilege(r.role_name, r.oid, a.attnum, p), has_column_privilege('app_rt', r.oid, a.attnum, p)
  FROM rels r JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped
  CROSS JOIN unnest(ARRAY['SELECT','INSERT','UPDATE']) p WHERE r.relkind IN ('r','p','v','m')
  UNION ALL
  SELECT r.role_name, 'sequence ' || r.object, p,
         has_sequence_privilege(r.role_name, r.oid, p), has_sequence_privilege('app_rt', r.oid, p)
  FROM rels r CROSS JOIN unnest(ARRAY['USAGE','SELECT','UPDATE']) p WHERE r.relkind = 'S'
  UNION ALL
  SELECT u.role_name::name, 'function ' || f.oid::regprocedure::text, 'EXECUTE',
         has_function_privilege(u.role_name::name, f.oid, 'EXECUTE'), has_function_privilege('app_rt', f.oid, 'EXECUTE')
  FROM units u JOIN pg_namespace n ON n.nspname = u.schema_name JOIN pg_proc f ON f.pronamespace = n.oid)
SELECT 'mismatch|' || role_name || '|' || object || '|' || privilege || '|dedicated=' || dedicated || '|app_rt=' || shared
FROM checks WHERE dedicated <> shared
UNION ALL
SELECT 'held|' || role_name || '|' || count(*) FROM checks WHERE dedicated AND object LIKE 'table %' GROUP BY role_name
ORDER BY 1;
'''

# What a re-run of the script before this change did: the blanket table grant, which hands back every narrowing.
OLD_BLANKET_GRANT = '''
DO $$ DECLARE s text; BEGIN
  FOREACH s IN ARRAY ARRAY['identity','tenant_school','student','attendance','fee','catalog','workflow','firefighting',
                           'reporting','notification','audit','billing'] LOOP
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO app_rt', s);
  END LOOP;
END $$;
'''


def read(path):
    return pathlib.Path(path).read_text(encoding='utf-8').replace('\r\n', '\n')


def narrowing_block(path):
    """The narrowing list, from its begin marker to its end marker inclusive."""
    text = read(path)
    begin, end = text.count(LIST_BEGIN), text.count(LIST_END)
    if begin != 1 or end != 1:
        raise AssertionError('%s must hold exactly one narrowing list (found %d begin and %d end markers)'
                             % (pathlib.Path(path).name, begin, end))
    start = text.index(LIST_BEGIN)
    stop = text.index('\n', text.index(LIST_END)) + 1
    return text[start:stop]


def values_rows(block, insert, end, arity):
    """The rows of the VALUES list that follows insert, up to end: tuples of string literals. Anything between rows
    that is not a comma or white space fails, so a row this reader cannot parse is never skipped silently."""
    start = block.index('VALUES', block.index(insert)) + len('VALUES')
    body = block[start:block.index(end, start)].strip()
    literal = r"'((?:[^']|'')*)'"
    row = re.compile(r'\(\s*' + r'\s*,\s*'.join([literal] * arity) + r'\s*\)')
    rows, position = [], 0
    for match in row.finditer(body):
        if body[position:match.start()].strip(' \n,'):
            raise AssertionError('cannot read the narrowing list near: %r' % body[position:match.start()].strip()[:160])
        rows.append(tuple(group.replace("''", "'") for group in match.groups()))
        position = match.end()
    if body[position:].strip(' \n,'):
        raise AssertionError('cannot read the narrowing list near: %r' % body[position:].strip()[:160])
    if not rows:
        raise AssertionError('the VALUES list after %r is empty' % insert)
    return rows


def parse_narrowing(block):
    """The list's rows, validated: (relation, privilege) pairs, (relation, privilege, column) column grants and
    (relation, column, privilege) column rules. Fails on a row with an unknown schema, privilege or column name."""
    def relation(name):
        match = RELATION.match(name)
        if not match or match.group(1) not in SCHEMAS:
            raise AssertionError('not a relation in a service schema: %r' % name)
        return name

    def privileges(text, allowed):
        tokens = text.split(',')
        for token in tokens:
            if token not in allowed:
                raise AssertionError('not a privilege the list can name: %r' % token)
        return tokens

    def columns(text):
        tokens = text.split(',')
        for token in tokens:
            if not COLUMN.match(token):
                raise AssertionError('not a column name: %r' % token)
        return tokens

    tables = [(relation(name), privilege)
              for name, listed, _ in values_rows(block, 'INSERT INTO app_rt_narrowing ',
                                                 ') AS r(relation, privileges, reason)', 3)
              for privilege in privileges(listed, PRIVILEGES)]
    grants = [(relation(name), privilege, column)
              for name, privilege, _, listed in values_rows(block, 'INSERT INTO app_rt_column_grants ',
                                                            ') AS r(relation, privilege, reason, columns)', 4)
              for privilege in privileges(privilege, {'SELECT', 'INSERT', 'UPDATE'})
              for column in columns(listed)]
    rules = [(relation(name), column, privilege)
             for name, column, privilege, _ in values_rows(block, 'INSERT INTO app_rt_column_narrowing ', ';\n', 4)
             for column in columns(column)
             for privilege in privileges(privilege, {'SELECT', 'INSERT', 'UPDATE'})]
    return tables, grants, rules


def migration_units():
    """(service, schema, migration folder, history table) for every unit, read from MigrationOnlyMain.

    Every service image ships an identical copy (scripts/check-duplicate-class-drift.py), so one copy is the plan of all.
    """
    source = read(ROOT / MIGRATION_ONLY_MAIN)
    for expected in ('String location = plan.schemas().size() == 1 ? "classpath:db/migration" : "classpath:db/migration/" + schema;',
                     '.table(schema.equals("tenant_school") ? "flyway_schema_history_tenant_school" : "flyway_schema_history")',
                     'Flyway.configure().dataSource(owner).schemas(schema).defaultSchema(schema)'):
        if expected not in source:
            raise AssertionError('MigrationOnlyMain no longer configures Flyway as this test assumes; update '
                                 'migration_units() to match. Missing: ' + expected)
    plans = {service: re.findall(r'"([a-z_]+)"', schemas) for service, schemas in
             re.findall(r'"([a-z-]+-service)",\s*new Plan\("[^"]+",\s*List\.of\(([^)]*)\)\)', source)}
    if set(plans) != set(SERVICES):
        raise AssertionError('MigrationOnlyMain plans %s, this test migrates %s' % (sorted(plans), sorted(SERVICES)))
    units = []
    for service in SERVICES:
        for schema in plans[service]:
            folder = ROOT / 'services' / service / 'src' / 'main' / 'resources' / 'db' / 'migration'
            if len(plans[service]) > 1:
                folder = folder / schema
            table = 'flyway_schema_history_tenant_school' if schema == 'tenant_school' else 'flyway_schema_history'
            units.append((service, schema, folder, table))
    return units


_CLASSPATH = []


def flyway_argfile(workdir):
    """A java @argfile with the runtime classpath of a service, as its Maven build resolves it (resolved once)."""
    if not _CLASSPATH:
        wrapper = ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
        output = pathlib.Path(workdir) / 'classpath.txt'
        result = subprocess.run([str(wrapper), '-q', '-B', '--no-transfer-progress', '-pl', CLASSPATH_MODULE,
                                 'dependency:build-classpath', '-Dmdep.includeScope=runtime',
                                 '-Dmdep.outputFile=' + str(output)],
                                cwd=ROOT, capture_output=True, text=True, timeout=1200)
        if result.returncode != 0 or not output.exists():
            raise RuntimeError('could not resolve the Flyway classpath with the Maven wrapper:\n'
                               + result.stdout[-3000:] + result.stderr[-3000:])
        classpath = output.read_text(encoding='utf-8').strip()
        for jar in ('flyway-core-', 'flyway-database-postgresql-', 'postgresql-'):
            if jar not in classpath:
                raise RuntimeError('the resolved classpath has no ' + jar + ' jar')
        _CLASSPATH.append(classpath.replace('\\', '/') if os.name == 'nt' else classpath)
    argfile = pathlib.Path(workdir) / 'flyway.args'
    # UTC as in a Cloud Run migration job; pgjdbc sends the JVM zone, and this image rejects legacy names such as
    # Asia/Calcutta that a developer machine may report.
    argfile.write_text('-Duser.timezone=UTC\n-cp\n"%s"\n' % _CLASSPATH[0], encoding='utf-8')
    return argfile


def run_migrations(pg, argfile, units, database, user, password, targets=None):
    """Migrates every unit as user, each to its latest version or to targets[schema]."""
    java = shutil.which('java') or (os.path.join(os.environ['JAVA_HOME'], 'bin', 'java')
                                    if os.environ.get('JAVA_HOME') else None)
    if java is None:
        raise RuntimeError('needs a JDK (java on PATH or JAVA_HOME) to run the migrations')
    url = 'jdbc:postgresql://127.0.0.1:%d/%s' % (pg.port, database)
    arguments = ['%s|%s|%s|%s' % (schema, folder, table, (targets or {}).get(schema, ''))
                 for _, schema, folder, table in units]
    result = subprocess.run([java, '@' + str(argfile), str(HARNESS), url, user, *arguments],
                            env=dict(os.environ, IMS_TEST_PG_PASSWORD=password),
                            capture_output=True, text=True, timeout=900)
    migrated = re.findall(r'^MIGRATED (\S+)', result.stdout, re.M)
    if result.returncode != 0 or migrated != [schema for _, schema, _, _ in units]:
        output = '\n'.join(line for line in (result.stdout + result.stderr).splitlines()
                           if not re.match(r'\s+at ', line))
        raise AssertionError('migration of %s failed:\n%s' % (database, output[-6000:]))
    return result.stdout


def effective(pg, database, role):
    return pg.sql(database, EFFECTIVE.replace('{role}', role)).splitlines()


def snapshot(pg, database):
    return pg.sql(database, SNAPSHOT).splitlines()


def require_powershell():
    """Skips without PowerShell locally. In CI a missing PowerShell is an error, never a silent skip."""
    if POWERSHELL:
        return POWERSHELL
    if in_ci():
        raise RuntimeError('PowerShell is required in CI to render the app_rt Cloud Run job')
    raise unittest.SkipTest('needs PowerShell (pwsh or powershell.exe) to render the app_rt Cloud Run job')


def render_job(workdir, audit_only=False, **parameters):
    """(plan, job definition) from invoke-create-app-rt-role-cloudsql.ps1 -RenderOnly. Calls no gcloud."""
    powershell = require_powershell()
    job_file = pathlib.Path(workdir) / ('job-%s.json' % uuid.uuid4().hex[:8])
    command = [powershell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(INVOKE),
               '-Project', 'ims-test-project', '-RenderOnly', '-JobFile', str(job_file)]
    for name, value in parameters.items():
        command += ['-' + name, str(value)]
    if audit_only:
        command.append('-AuditOnly')
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    if result.returncode != 0:
        raise AssertionError('rendering the job failed:\n' + result.stdout + result.stderr)
    return json.loads(result.stdout), json.loads(job_file.read_text(encoding='utf-8'))


def container_of(job):
    containers = job['spec']['template']['spec']['template']['spec']['containers']
    if len(containers) != 1:
        raise AssertionError('the job must run one container, not %d' % len(containers))
    return containers[0]


def environment_of(job):
    return {entry['name']: entry for entry in container_of(job)['env']}


def gunzip_base64(value):
    return gzip.decompress(base64.b64decode(value, validate=True))


def diff(before, after):
    added = sorted((collections.Counter(after) - collections.Counter(before)).elements())
    removed = sorted((collections.Counter(before) - collections.Counter(after)).elements())
    return added, removed


def describe(added, removed, limit=80):
    lines = ['+ ' + line for line in added[:limit]] + ['- ' + line for line in removed[:limit]]
    return '%d added, %d removed\n%s' % (len(added), len(removed), '\n'.join(lines))


class NarrowingListTests(unittest.TestCase):
    """Text checks; no Docker needed."""

    def test_create_and_audit_scripts_declare_the_same_narrowing_list(self):
        # The Cloud Run job ships each file on its own (invoke-create-app-rt-role-cloudsql.ps1), so they cannot share
        # an \ir include. The two copies must not drift.
        self.assertEqual(narrowing_block(SCRIPT), narrowing_block(AUDIT))

    def test_both_scripts_run_the_check_the_block_defines_and_no_copy_of_it(self):
        # The check is in the block, so the drift test above covers it; neither file may keep its own copy.
        for path in (SCRIPT, AUDIT):
            text, block = read(path), narrowing_block(path)
            outside = text.replace(block, '')
            with self.subTest(file=path.name):
                self.assertIn('CREATE FUNCTION pg_temp.app_rt_check_narrowing()', block)
                self.assertEqual(outside.count('PERFORM pg_temp.app_rt_check_narrowing();'), 1)
                for message in ('app_rt holds table privileges the narrowing list bars',
                                'app_rt holds column privileges the narrowing list bars',
                                'app_rt lacks column privileges a migration grants it'):
                    self.assertIn(message, block)
                    self.assertNotIn(message, outside)

    def test_the_list_names_only_service_schemas_and_table_privileges(self):
        tables, grants, rules = parse_narrowing(narrowing_block(SCRIPT))
        self.assertGreater(len(tables), 50)
        self.assertEqual(len(tables), len(set(tables)), 'a (relation, privilege) row is declared twice')
        self.assertEqual(len(grants), len(set(grants)), 'a column grant is declared twice')
        self.assertEqual(rules, [('student.students', 'erasure_incarnation', 'INSERT')])

    def test_every_column_grant_and_column_rule_names_a_listed_table_privilege(self):
        # A column grant for a privilege app_rt holds at table level anyway would mean nothing (the SQL enforces this
        # with a foreign key too).
        tables, grants, rules = parse_narrowing(narrowing_block(SCRIPT))
        for relation, privilege, column in grants:
            self.assertIn((relation, privilege), tables, '%s.%s' % (relation, column))
        for relation, column, privilege in rules:
            self.assertIn((relation, privilege), tables, '%s.%s' % (relation, column))

    def test_the_list_reader_fails_on_a_row_it_cannot_read_instead_of_skipping_it(self):
        block = narrowing_block(SCRIPT)
        anchor = "  ('catalog.legacy_catalog_migration_map',"
        self.assertEqual(block.count(anchor), 1)
        # A digit in a relation name is fine; a misspelt privilege, an unknown schema or a malformed row is not.
        tables, _, _ = parse_narrowing(block.replace(anchor, "  ('notification.msg91_reports', 'UPDATE', 'it''s fine'),\n" + anchor))
        self.assertIn(('notification.msg91_reports', 'UPDATE'), tables)
        for bad in ("  ('notification.msg91_reports', 'UPDTE', 'typo row'),\n",
                    "  ('payments.ledger', 'UPDATE', 'unknown schema'),\n",
                    "  ('notification.msg91_reports', UPDATE, 'unquoted'),\n"):
            with self.subTest(row=bad.strip()):
                with self.assertRaises(AssertionError):
                    parse_narrowing(block.replace(anchor, bad + anchor))

    def test_the_list_covers_every_flyway_history_table(self):
        tables, _, _ = parse_narrowing(narrowing_block(SCRIPT))
        for _, schema, _, table in migration_units():
            for privilege in ('SELECT', 'INSERT', 'UPDATE', 'DELETE'):
                self.assertIn((schema + '.' + table, privilege), tables)

    def test_after_migrate_copies_differ_only_in_role_and_schema(self):
        copies = {}
        for service, schema, folder, _ in migration_units():
            path = folder / 'afterMigrate__dedicated_runtime_acl.sql'
            text = read(path)
            declaration = "role_name text := '%s'; schema_name text := '%s';" % (DEDICATED_ROLES[service], schema)
            with self.subTest(copy=str(path.relative_to(ROOT))):
                self.assertEqual(text.count(declaration), 1, 'expected the declaration: ' + declaration)
            copies[str(path.relative_to(ROOT))] = text.replace(declaration, '<role and schema>')
        self.assertEqual(len(copies), 12)
        variants = collections.defaultdict(list)
        for path, text in copies.items():
            variants[text].append(path)
        self.assertEqual(len(variants), 1, 'afterMigrate copies drifted: %s' % sorted(variants.values()))

    def test_no_service_overrides_the_parent_managed_flyway_version(self):
        for service in SERVICES:
            pom = read(ROOT / 'services' / service / 'pom.xml')
            with self.subTest(service=service):
                self.assertNotIn('flyway.version', pom)
                for dependency in re.findall(r'<dependency>(.*?)</dependency>', pom, re.S):
                    if '<groupId>org.flywaydb</groupId>' in dependency:
                        self.assertNotIn('<version>', dependency)


class CloudRunJobTests(unittest.TestCase):
    """The job invoke-create-app-rt-role-cloudsql.ps1 renders (-RenderOnly). Needs PowerShell, not Docker or gcloud."""

    @classmethod
    def setUpClass(cls):
        cls.powershell = require_powershell()
        cls.workdir = pathlib.Path(tempfile.mkdtemp(prefix='ims-app-rt-job-'))
        try:
            cls.full = render_job(cls.workdir)
            cls.audit_only = render_job(cls.workdir, audit_only=True)
        except BaseException:
            shutil.rmtree(cls.workdir, ignore_errors=True)
            raise

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.workdir, ignore_errors=True)

    def test_every_gcloud_command_line_fits_cmd_exe(self):
        # Before: the SQL went inline as base64 in --args, about 45K for create and audit, so gcloud.cmd failed with
        # "The command line is too long" (and Windows refuses any command line over 32767 characters).
        for name, (plan, _) in (('full', self.full), ('audit only', self.audit_only)):
            self.assertEqual([command[:3] for command in plan['commands']],
                             [['run', 'jobs', 'replace'], ['run', 'jobs', 'execute'], ['run', 'jobs', 'delete']])
            for command in plan['commands']:
                with self.subTest(job=name, command=' '.join(command[:3])):
                    self.assertLess(len(subprocess.list2cmdline([plan['gcloud'], *command])),
                                    CMD_EXE_COMMAND_LINE_LIMIT)

    def test_no_sql_travels_in_the_container_arguments(self):
        for name, (_, job) in (('full', self.full), ('audit only', self.audit_only)):
            container = container_of(job)
            with self.subTest(job=name):
                self.assertEqual(container['command'], ['sh'])
                self.assertEqual(container['args'][0], '-c')
                self.assertEqual(len(container['args']), 2)
                self.assertLess(len(container['args'][1]), 4096)
                for fragment in ('CREATE TEMP TABLE', 'GRANT ', 'app_rt_narrowing'):
                    self.assertNotIn(fragment, container['args'][1])

    def test_each_sql_file_travels_whole_in_one_variable_under_cloud_runs_limit(self):
        environment = environment_of(self.full[1])
        for variable, path in (('APP_RT_CREATE_SQL_GZ_B64', SCRIPT), ('APP_RT_AUDIT_SQL_GZ_B64', AUDIT)):
            with self.subTest(variable=variable):
                value = environment[variable]['value']
                self.assertLess(len(value), CLOUD_RUN_VARIABLE_LIMIT)
                self.assertEqual(gunzip_base64(value), path.read_bytes())
                # The shell checks the decoded file against this hash and prints it, so the job log names the version.
                self.assertIn(hashlib.sha256(path.read_bytes()).hexdigest(), container_of(self.full[1])['args'][1])

    def test_passwords_come_from_secret_manager_and_tls_is_required(self):
        environment = environment_of(self.full[1])
        self.assertEqual(environment['PGPASSWORD']['valueFrom'],
                         {'secretKeyRef': {'name': 'db-password', 'key': 'latest'}})
        self.assertEqual(environment['APP_RT_PASSWORD']['valueFrom'],
                         {'secretKeyRef': {'name': 'app-rt-password', 'key': 'latest'}})
        self.assertEqual(environment['PGSSLMODE']['value'], 'require')
        shell = container_of(self.full[1])['args'][1]
        self.assertIn('export PGSSLMODE=require', shell)
        self.assertIn('-v set_superuser=1', shell)
        annotations = self.full[1]['spec']['template']['metadata']['annotations']
        self.assertEqual(annotations['run.googleapis.com/vpc-access-egress'], 'private-ranges-only')
        self.assertEqual(json.loads(annotations['run.googleapis.com/network-interfaces']),
                         [{'network': 'default', 'subnetwork': 'default'}])
        self.assertEqual(self.full[1]['spec']['template']['spec']['template']['spec']['maxRetries'], 0)

    def test_the_audit_only_job_carries_neither_the_create_script_nor_the_app_rt_password(self):
        environment = environment_of(self.audit_only[1])
        self.assertEqual(sorted(environment), ['APP_RT_AUDIT_SQL_GZ_B64', 'PGPASSWORD', 'PGSSLMODE'])
        self.assertNotIn('create.sql', container_of(self.audit_only[1])['args'][1])

    def test_a_failing_gcloud_call_fails_the_run_and_removes_the_job_file(self):
        # Before, exit codes were not checked: a failed execution still printed "job completed" in green.
        if os.name == 'nt':
            fake = self.workdir / 'gcloud-fails.cmd'
            fake.write_text('@exit /b 7\r\n', encoding='ascii')
        else:
            fake = self.workdir / 'gcloud-fails'
            fake.write_text('#!/bin/sh\nexit 7\n', encoding='ascii')
            fake.chmod(0o755)
        job_file = self.workdir / 'job-failing.json'

        result = subprocess.run([self.powershell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                                 '-File', str(INVOKE), '-Project', 'ims-test-project', '-Gcloud', str(fake),
                                 '-JobFile', str(job_file)], capture_output=True, text=True, timeout=180)

        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn('Could not create the app_rt provisioning job', result.stdout + result.stderr)
        self.assertNotIn('job completed', result.stdout)
        self.assertFalse(job_file.exists())


class CreateAppRtRoleMigratedInvariantTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_docker()
        cls.units = migration_units()
        cls.workdir = pathlib.Path(tempfile.mkdtemp(prefix='ims-app-rt-'))
        try:
            cls.argfile = flyway_argfile(cls.workdir)
            cls.pg = PostgresContainer(publish=True)
        except BaseException:
            shutil.rmtree(cls.workdir, ignore_errors=True)
            raise
        try:
            cls.pg.sql('postgres', 'CREATE DATABASE ims_template;' + ''.join(
                'CREATE ROLE %s NOLOGIN NOINHERIT;' % role for role in sorted(set(DEDICATED_ROLES.values()))))
            # Schemas exist before the first run, so its per-schema default privileges are in place when Flyway
            # creates the tables: the state every environment reaches (deploy/local/initdb sets the same globally).
            cls.pg.sql('ims_template', ''.join('CREATE SCHEMA %s;' % schema for schema in SCHEMAS))
            first = cls.run_script('ims_template')
            if first.returncode != 0:
                raise AssertionError('first run failed: ' + first.stderr)
            cls.migrate('ims_template')
            cls.after_migrate = cls.snapshot('ims_template')
        except BaseException:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.pg.stop()
        shutil.rmtree(cls.workdir, ignore_errors=True)

    @classmethod
    def migrate(cls, database, targets=None):
        """Migrates every unit as the superuser, each to its latest version or to targets[schema]."""
        return run_migrations(cls.pg, cls.argfile, cls.units, database, 'postgres', cls.pg.password, targets)

    @classmethod
    def run_script(cls, database, path=SCRIPT):
        return cls.pg.psql(database, '-v', 'app_rt_password=' + cls.pg.password, '-v', 'owner=postgres',
                           '-f', '-', stdin=read(path))

    @classmethod
    def run_audit(cls, database):
        return cls.pg.psql(database, '-v', 'ON_ERROR_STOP=1', '-v', 'run_live_probes=1', '-f', '-', stdin=read(AUDIT))

    @classmethod
    def snapshot(cls, database):
        return snapshot(cls.pg, database)

    def clone(self):
        database = 'ims_' + uuid.uuid4().hex[:10]
        self.pg.sql('postgres', 'CREATE DATABASE %s TEMPLATE ims_template;' % database)
        return database

    def rerun(self, database):
        result = self.run_script(database)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('create-app-rt-role: role ready', result.stdout)
        return self.snapshot(database)

    def canonical(self):
        """The post-migration snapshot without app_rt's grants on the Flyway history tables."""
        history = [line for line in self.after_migrate
                   if line.startswith('app_rt|table|') and HISTORY.match(line.split('|')[2])]
        return [line for line in self.after_migrate if line not in history], history

    def history_grants_of_dedicated_roles(self, lines):
        return [line for line in lines if line.startswith('ims_') and HISTORY.match(line.split('|')[2])]

    def test_every_listed_relation_and_column_exists_after_migrating(self):
        # A stale row (a dropped or renamed relation or column) would silently stop protecting anything.
        missing = self.pg.sql('ims_template', narrowing_block(SCRIPT) + '''
            SELECT relation FROM app_rt_narrowing WHERE to_regclass(relation) IS NULL
            UNION ALL
            SELECT relation || '.' || column_name FROM app_rt_column_narrowing c
            WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = to_regclass(c.relation)
                                AND a.attname = c.column_name AND NOT a.attisdropped)
            UNION ALL
            SELECT relation || '.' || column_name FROM app_rt_column_grants g
            WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = to_regclass(g.relation)
                                AND a.attname = g.column_name AND NOT a.attisdropped);''')
        self.assertEqual(missing, '')

    def test_the_column_grants_are_exactly_the_ones_the_migrations_make(self):
        # Where the list bars a table-level privilege, the column grants app_rt holds after the migrations must be the
        # ones the list names: a migration that grants another column needs a row, and a row must not grant a column
        # no migration does.
        declared = self.pg.sql('ims_template', narrowing_block(SCRIPT) + '''
            SELECT relation || '(' || column_name || ')|' || privilege FROM app_rt_column_grants ORDER BY 1;''')
        migrated = self.pg.sql('ims_template', narrowing_block(SCRIPT) + '''
            SELECT DISTINCT a.relation || '(' || att.attname || ')|' || a.privilege
            FROM app_rt_narrowing a JOIN pg_attribute att ON att.attrelid = to_regclass(a.relation)
                 AND att.attnum > 0 AND NOT att.attisdropped
            CROSS JOIN LATERAL aclexplode(att.attacl) x
            WHERE x.grantee = 'app_rt'::regrole AND x.privilege_type = a.privilege
              AND NOT EXISTS (SELECT 1 FROM app_rt_column_narrowing c WHERE c.relation = a.relation
                                AND c.privilege = a.privilege)
            ORDER BY 1;''')
        # Sorted here: attname is a name (collation C), so the two queries order differently.
        self.assertEqual(sorted(declared.splitlines()), sorted(migrated.splitlines()))
        self.assertEqual(len(declared.splitlines()), 46)

    def test_rerun_leaves_every_migration_grant_and_only_takes_back_the_flyway_history_tables(self):
        database = self.clone()

        after_rerun = self.rerun(database)

        expected, history = self.canonical()
        # The script's default privileges gave app_rt S, I, U and D on each history table when Flyway created it.
        self.assertEqual(sorted({line.split('|')[2] for line in history}),
                         sorted(schema + '.' + table for _, schema, _, table in self.units))
        self.assertEqual(len(history), 4 * len(self.units))
        added, removed = diff(expected, after_rerun)
        self.assertEqual((added, removed), ([], []), 'the re-run changed ACLs a migration set:\n'
                         + describe(added, removed))

    def test_migrating_after_a_rerun_and_rerunning_again_change_nothing(self):
        database = self.clone()
        after_rerun = self.rerun(database)

        self.migrate(database)
        after_migrate = self.snapshot(database)
        after_second_rerun = self.rerun(database)

        added, removed = diff(after_rerun, after_migrate)
        self.assertEqual((added, removed), ([], []), 'the next migration changed ACLs (afterMigrate copies app_rt '
                         'onto the dedicated roles):\n' + describe(added, removed))
        added, removed = diff(after_migrate, after_second_rerun)
        self.assertEqual((added, removed), ([], []), 'a second re-run changed ACLs:\n' + describe(added, removed))

    def test_a_build_that_ran_the_script_before_any_schema_existed_ends_in_the_same_acls(self):
        # docs/GREENFIELD-DEPLOYMENT-PLAN.md 1.3 runs the script before the first deploy. With no schemas yet it sets no
        # default privileges, so the migrations run while app_rt holds nothing; student V39 then grants no INSERT
        # columns (it converts a table-level INSERT only if app_rt holds one). The re-run after the deploy must still
        # end where every other build ends.
        expected = self.rerun(self.clone())
        database = 'ims_' + uuid.uuid4().hex[:10]
        self.pg.sql('postgres', 'CREATE DATABASE %s;' % database)
        first = self.run_script(database)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.migrate(database)

        self.rerun(database)
        self.migrate(database)

        added, removed = diff(expected, self.snapshot(database))
        self.assertEqual((added, removed), ([], []), describe(added, removed))

    def test_a_rerun_while_student_is_still_before_v39_ends_in_the_same_acls(self):
        # Locally ensure-app-rt-local.ps1 re-runs the script while the services migrate. Before V39 commits, app_rt
        # must keep its table-level INSERT on student.students, or V39 grants it no INSERT columns at all.
        expected = self.rerun(self.clone())
        database = 'ims_' + uuid.uuid4().hex[:10]
        self.pg.sql('postgres', 'CREATE DATABASE %s;' % database)
        self.pg.sql(database, ''.join('CREATE SCHEMA %s;' % schema for schema in SCHEMAS))
        first = self.run_script(database)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.migrate(database, targets={'student': '38'})
        self.rerun(database)
        self.assertEqual(self.pg.sql(database, "SELECT has_table_privilege('app_rt', 'student.students', 'INSERT')"),
                         't')

        self.migrate(database)
        self.rerun(database)

        added, removed = diff(expected, self.snapshot(database))
        self.assertEqual((added, removed), ([], []), describe(added, removed))

    def test_dedicated_roles_hold_nothing_on_the_flyway_history_tables(self):
        self.assertEqual(self.history_grants_of_dedicated_roles(self.after_migrate), [])

    def test_each_dedicated_role_holds_what_app_rt_holds_in_its_own_schemas(self):
        # The positive side of the callback: without it, every check on the dedicated roles above would pass if the
        # callback copied nothing at all (no roles, an early return, a callback Flyway never found).
        units = ', '.join("('%s', '%s')" % (DEDICATED_ROLES[service], schema) for service, schema, _, _ in self.units)
        lines = self.pg.sql('ims_template', DEDICATED_PARITY.replace('{units}', units)).splitlines()

        mismatches = [line for line in lines if line.startswith('mismatch|')]
        held = dict(line.split('|')[1:] for line in lines if line.startswith('held|'))
        self.assertEqual(mismatches, [], '\n'.join(mismatches[:40]))
        for role in sorted(set(DEDICATED_ROLES.values())):
            with self.subTest(role=role):
                self.assertGreater(int(held.get(role, '0')), 0, role + ' holds no table privilege at all')

    def test_a_migration_takes_back_a_dedicated_role_grant_on_a_flyway_history_table(self):
        # Dev's state before this change: the callback excluded only the exact name flyway_schema_history, so
        # ims_school_core_rt held DML on tenant_school.flyway_schema_history_tenant_school. The next migration's
        # callback must take that back, not merely stop adding to it.
        database = self.clone()
        self.pg.sql(database, 'GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_school.flyway_schema_history_tenant_school '
                              'TO ims_school_core_rt;')
        self.assertEqual(len(self.history_grants_of_dedicated_roles(self.snapshot(database))), 4)

        self.migrate(database)

        self.assertEqual(self.history_grants_of_dedicated_roles(self.snapshot(database)), [])

    def test_the_runtime_role_cutover_gives_dedicated_roles_nothing_on_the_flyway_history_tables(self):
        # docs/security-remediation/runtime-role-cutover.sql (prepare-dev-runtime-roles.ps1, and the template of the
        # pending prod cutover) copies app_rt onto the dedicated roles the same way. Here app_rt still holds DML on
        # every history table, as it does until the script has run once after Flyway created them.
        database = self.clone()

        result = self.pg.psql(database, '-v', 'ON_ERROR_STOP=1', '-f', '-', stdin=read(CUTOVER))

        self.assertEqual(result.returncode, 0, result.stderr)
        after = self.snapshot(database)
        self.assertEqual(self.history_grants_of_dedicated_roles(after), [])
        self.assertTrue(any(line.startswith('ims_school_core_rt|table|tenant_school.') for line in after))

    def test_rerun_repairs_a_database_the_old_script_widened(self):
        database = self.clone()
        narrowed = self.rerun(database)
        self.pg.sql(database, OLD_BLANKET_GRANT)
        widened = self.snapshot(database)
        self.assertNotEqual(widened, narrowed)

        repaired = self.rerun(database)

        added, removed = diff(narrowed, repaired)
        self.assertEqual((added, removed), ([], []), describe(added, removed))

    def test_rerun_takes_back_an_owner_column_insert_on_erasure_incarnation(self):
        database = self.clone()
        narrowed = self.rerun(database)
        self.pg.sql(database, 'GRANT INSERT (erasure_incarnation) ON student.students TO app_rt;')

        repaired = self.rerun(database)

        added, removed = diff(narrowed, repaired)
        self.assertEqual((added, removed), ([], []), describe(added, removed))

    def test_rerun_gives_back_a_column_grant_a_migration_made(self):
        database = self.clone()
        narrowed = self.rerun(database)
        self.pg.sql(database, 'REVOKE UPDATE (status) ON firefighting.quotation_documents FROM app_rt;')

        result = self.run_script(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('gave app_rt UPDATE on firefighting.quotation_documents(status)', result.stderr)
        added, removed = diff(narrowed, self.snapshot(database))
        self.assertEqual((added, removed), ([], []), describe(added, removed))

    def test_rerun_fails_naming_relation_privilege_and_grantor_when_another_role_granted_it(self):
        database = self.clone()
        narrowed = self.rerun(database)
        grantor = 'grantor_' + uuid.uuid4().hex[:8]
        self.pg.sql(database, 'CREATE ROLE %s NOLOGIN;'
                              'GRANT USAGE ON SCHEMA tenant_school TO %s;'
                              'GRANT DELETE ON tenant_school.photo_cleanup_outbox TO %s WITH GRANT OPTION;'
                              'SET ROLE %s;'
                              'GRANT DELETE ON tenant_school.photo_cleanup_outbox TO app_rt;'
                              'RESET ROLE;' % (grantor, grantor, grantor, grantor))

        result = self.run_script(database)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('tenant_school.photo_cleanup_outbox DELETE', result.stderr)
        self.assertIn(grantor, result.stderr)
        # The owner's REVOKE cannot reach that grant, so the run must not claim it took it back.
        self.assertNotIn('took table-level DELETE on tenant_school.photo_cleanup_outbox back', result.stderr)
        # The owner's part committed before the check: only the other grantor's grant remains.
        added, removed = diff(narrowed, self.snapshot(database))
        self.assertEqual(removed, [])
        self.assertEqual(added, ['app_rt|table|tenant_school.photo_cleanup_outbox|DELETE|%s|f' % grantor])

    def test_a_misspelt_list_row_stops_the_script_before_any_grant(self):
        database = self.clone()
        before = self.snapshot(database)
        anchor = "  ('catalog.legacy_catalog_migration_map',"
        text = read(SCRIPT).replace(anchor, "  ('catalog.annual_plan_confirmations', 'UPDTE', 'typo'),\n" + anchor, 1)

        result = self.pg.psql(database, '-v', 'app_rt_password=' + self.pg.password, '-v', 'owner=postgres',
                              '-f', '-', stdin=text)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('app_rt_narrowing_privilege_check', result.stderr)
        self.assertEqual(self.snapshot(database), before)

    def test_audit_passes_after_the_script_ran(self):
        database = self.clone()
        self.rerun(database)

        result = self.run_audit(database)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('app_rt audit: all checks passed', result.stdout)

    def test_audit_fails_on_a_database_the_old_script_widened(self):
        database = self.clone()
        self.rerun(database)
        self.pg.sql(database, OLD_BLANKET_GRANT)

        result = self.run_audit(database)

        self.assertNotEqual(result.returncode, 0)
        for violation in ('catalog.legacy_catalog_migration_map SELECT', 'tenant_school.photo_cleanup_outbox DELETE',
                          'notification.generic_submissions UPDATE', 'student.students INSERT'):
            self.assertIn(violation, result.stderr)

    def test_audit_fails_naming_a_column_grant_app_rt_lacks(self):
        database = self.clone()
        self.rerun(database)
        self.pg.sql(database, 'REVOKE UPDATE (status, uploaded_at) ON firefighting.quotation_documents FROM app_rt;')

        result = self.run_audit(database)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('app_rt lacks column privileges a migration grants it: '
                      'firefighting.quotation_documents.status UPDATE; '
                      'firefighting.quotation_documents.uploaded_at UPDATE', result.stderr)

    def test_audit_on_a_fresh_migration_reports_only_the_flyway_history_tables(self):
        # Until the script has run once after Flyway created them, app_rt holds DML on the history tables through the
        # default privileges. Everything else the migrations narrowed is already as the list says.
        database = self.clone()

        result = self.run_audit(database)

        self.assertNotEqual(result.returncode, 0)
        reported = re.findall(r'([a-z_]+\.[a-z_]+) (SELECT|INSERT|UPDATE|DELETE|TRUNCATE) \(', result.stderr)
        self.assertEqual(len(reported), 4 * len(self.units), result.stderr)
        self.assertEqual([relation for relation, _ in reported if not HISTORY.match(relation)], [])


class ProdShapeTests(unittest.TestCase):
    """The shape the Cloud Run job runs in, and the order of a restore or of a cutover onto a migrated database.

    * appuser is no superuser. It is a member of cloudsqlsuperuser, which owns the database; appuser owns the schemas,
      runs Flyway and runs the script with set_superuser, as invoke-create-app-rt-role-cloudsql.ps1 runs it.
    * PUBLIC has no TEMP on these databases, so the temp tables both scripts need come from the database owner.
    * The script and the audit run as the shell of the rendered job, with the SQL decoded from the job's variables and
      TLS required (sslmode=require against the image's snakeoil certificate).
    * In ims_late the migrations run before app_rt exists (no dedicated role exists either, as in prod), so they make
      none of their IF EXISTS (app_rt) grants. It must end with the privileges of ims_first, where app_rt came first.

    Vanilla PostgreSQL 16 lets only a BYPASSRLS role name that attribute, and the role step says NOBYPASSRLS, so the
    stand-in cloudsqlsuperuser has BYPASSRLS. Cloud SQL's own rules for that step are not reproduced here; the step is
    unchanged by the narrowing work. Attributes are not inherited, so appuser itself has no BYPASSRLS.
    """

    @classmethod
    def setUpClass(cls):
        require_docker()
        require_powershell()
        cls.units = migration_units()
        cls.workdir = pathlib.Path(tempfile.mkdtemp(prefix='ims-app-rt-prod-'))
        try:
            cls.argfile = flyway_argfile(cls.workdir)
            cls.pg = PostgresContainer(publish=True, ssl=True)
        except BaseException:
            shutil.rmtree(cls.workdir, ignore_errors=True)
            raise
        try:
            cls.owner_password = secrets.token_urlsafe(24)
            cls.app_rt_password = secrets.token_urlsafe(24)
            cls.pg.sql('postgres', "CREATE ROLE cloudsqlsuperuser NOLOGIN CREATEROLE CREATEDB BYPASSRLS;"
                                   "CREATE ROLE appuser LOGIN PASSWORD '%s' IN ROLE cloudsqlsuperuser;"
                                   % cls.owner_password)
            for database in ('ims_late', 'ims_first'):
                cls.pg.sql('postgres', 'CREATE DATABASE %s OWNER cloudsqlsuperuser;' % database)
                cls.pg.sql('postgres', 'REVOKE TEMPORARY ON DATABASE %s FROM PUBLIC;' % database)
            # Migrate first: neither app_rt nor any dedicated role exists in this cluster yet.
            run_migrations(cls.pg, cls.argfile, cls.units, 'ims_late', 'appuser', cls.owner_password)
            cls.late = cls.run_job('ims_late')
            # The order everywhere else: schemas, script, migrations, script.
            cls.pg.sql('ims_first', ''.join('CREATE SCHEMA %s;' % schema for schema in SCHEMAS), user='appuser')
            cls.run_script_as_owner('ims_first', check=True)
            run_migrations(cls.pg, cls.argfile, cls.units, 'ims_first', 'appuser', cls.owner_password)
            cls.run_script_as_owner('ims_first', check=True)
        except BaseException:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        cls.pg.stop()
        shutil.rmtree(cls.workdir, ignore_errors=True)

    @classmethod
    def run_script_as_owner(cls, database, check=False):
        result = cls.pg.psql(database, '-v', 'app_rt_password=' + cls.app_rt_password, '-v', 'owner=appuser',
                             '-v', 'set_superuser=1', '-f', '-', stdin=read(SCRIPT), user='appuser')
        if check and result.returncode != 0:
            raise AssertionError('script run on %s failed: %s' % (database, result.stderr))
        return result

    @classmethod
    def run_job(cls, database, audit_only=False):
        """Runs the rendered job's container command in the database container, with the job's environment."""
        _, job = render_job(cls.workdir, audit_only=audit_only, HostAddress='127.0.0.1', Port=5432,
                            Database=database, ConnectUser='appuser', Owner='appuser')
        container = container_of(job)
        secret_values = {'db-password': cls.owner_password, 'app-rt-password': cls.app_rt_password}
        environment, names = dict(os.environ), []
        for entry in container['env']:
            environment[entry['name']] = (entry['value'] if 'value' in entry
                                          else secret_values[entry['valueFrom']['secretKeyRef']['name']])
            names.append(entry['name'])
        shell = container['args'][1]
        subprocess.run(['docker', 'exec', '-i', cls.pg.name, 'sh', '-c', 'cat > /tmp/job.sh'],
                       input=shell.encode('utf-8'), capture_output=True, check=True, timeout=60)
        # The job runs `sh -c <shell>`; so does this, with the variables passed by name from this process.
        command = ['docker', 'exec']
        for name in names:
            command += ['-e', name]
        command += [cls.pg.name, *container['command'], '-c', 'exec sh -c "$(cat /tmp/job.sh)"']
        result = subprocess.run(command, env=environment, capture_output=True, timeout=600)
        return subprocess.CompletedProcess(result.args, result.returncode, result.stdout.decode('utf-8', 'replace'),
                                           result.stderr.decode('utf-8', 'replace'))

    def test_the_job_provisions_app_rt_on_a_database_migrated_before_app_rt_existed(self):
        self.assertEqual(self.late.returncode, 0, self.late.stdout + self.late.stderr)
        self.assertIn('create-app-rt-role.sql sha256 ' + hashlib.sha256(SCRIPT.read_bytes()).hexdigest(),
                      self.late.stdout)
        self.assertIn('create-app-rt-role: role ready', self.late.stdout)
        self.assertIn('app_rt audit: all checks passed', self.late.stdout)
        # The migrations made none of their column grants; the script made them.
        for privilege, relation in (('UPDATE', 'firefighting.quotation_documents'),
                                    ('UPDATE', 'notification.generic_submissions'),
                                    ('INSERT', 'notification.generic_unknown_report_assertions')):
            self.assertIn('gave app_rt %s on %s(' % (privilege, relation), self.late.stderr)

    def test_creating_app_rt_after_the_migrations_ends_where_creating_it_first_does(self):
        first, late = effective(self.pg, 'ims_first', 'app_rt'), effective(self.pg, 'ims_late', 'app_rt')
        self.assertGreater(len(first), 500)

        added, removed = diff(first, late)
        self.assertEqual((added, removed), ([], []), describe(added, removed))
        defaults = [[line for line in snapshot(self.pg, database) if line.startswith('app_rt|default:')]
                    for database in ('ims_first', 'ims_late')]
        self.assertEqual(defaults[0], defaults[1])
        self.assertEqual(len(defaults[0]), 6 * len(SCHEMAS))

    def test_the_audit_only_job_passes(self):
        result = self.run_job('ims_late', audit_only=True)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('app_rt audit: all checks passed', result.stdout)
        self.assertNotIn('create-app-rt-role', result.stdout)

    def test_without_temp_on_the_database_the_script_stops_before_granting_anything(self):
        # Owned by postgres, so appuser gets no TEMP from ownership; PUBLIC's TEMP is revoked too.
        database = 'ims_notemp_' + uuid.uuid4().hex[:8]
        self.pg.sql('postgres', 'CREATE DATABASE %s;' % database)
        self.pg.sql('postgres', 'REVOKE TEMPORARY ON DATABASE %s FROM PUBLIC;' % database)
        self.pg.sql(database, 'CREATE SCHEMA catalog AUTHORIZATION appuser;')
        self.pg.sql(database, 'CREATE TABLE catalog.catalog_orders (id bigint);', user='appuser')

        result = self.run_script_as_owner(database)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn('permission denied to create temporary tables', result.stderr)
        self.assertEqual(self.pg.sql(database, "SELECT has_schema_privilege('app_rt', 'catalog', 'USAGE'), "
                                               "has_table_privilege('app_rt', 'catalog.catalog_orders', 'SELECT')"),
                         'f|f')


if __name__ == '__main__':
    unittest.main()
