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
* Migrate again (nothing pending, the callbacks still run): nothing may change, dedicated roles included.
* Every relation on the script's narrowing list must exist, so a dropped or renamed table cannot leave a stale row.
* The order the script and the migrations run in must not matter: a build whose first run came before any schema
  existed, and a re-run that lands while student is still before V39, end with the same ACLs.
* The script repairs a database the old blanket grant widened, and fails naming relation, privilege and grantor when
  another role granted a listed privilege. The audit passes after the script ran and names what a widening added.

It also checks, without Docker, that the narrowing list in the script and in scripts/audit-app-rt-privileges.sql are
the same text, and that the twelve afterMigrate copies differ only in their role and schema.

Needs Docker, a JDK and the Maven wrapper (it resolves Flyway from a service's own dependency tree). Without Docker it
skips locally and fails in CI (.github/workflows/ci-pr.yml, job app-rt-role-invariants).

Run from the repository root: python -m unittest scripts.tests.create_app_rt_role_migrated_test
"""
import collections
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest
import uuid

from scripts.tests.create_app_rt_role_test import PostgresContainer, require_docker

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'create-app-rt-role.sql'
AUDIT = ROOT / 'scripts' / 'audit-app-rt-privileges.sql'
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


def narrowing_rows(path):
    """(relation, privilege) pairs and (relation, column, privilege) triples the list declares."""
    block = narrowing_block(path)
    tables = [(relation, privilege)
              for relation, privileges in re.findall(r"\('([a-z_]+\.[a-z_]+)',\s*'([A-Z,]+)',\s*'[^']*'\)", block)
              for privilege in privileges.split(',')]
    columns = re.findall(r"\('([a-z_]+\.[a-z_]+)',\s*'([a-z_]+)',\s*'([A-Z]+)',\s*'[^']*'\)", block)
    return tables, columns


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
        # The Cloud Run job ships each file inline as base64 (invoke-create-app-rt-role-cloudsql.ps1), so they cannot
        # share an \ir include. The two copies must not drift.
        self.assertEqual(narrowing_block(SCRIPT), narrowing_block(AUDIT))

    def test_the_list_names_only_service_schemas_and_table_privileges(self):
        tables, columns = narrowing_rows(SCRIPT)
        self.assertTrue(tables)
        self.assertEqual(len(tables), len(set(tables)), 'a (relation, privilege) row is declared twice')
        for relation, privilege in tables:
            with self.subTest(relation=relation, privilege=privilege):
                self.assertIn(relation.split('.')[0], SCHEMAS)
                self.assertIn(privilege, PRIVILEGES)
        self.assertEqual(columns, [('student.students', 'erasure_incarnation', 'INSERT')])

    def test_the_list_covers_every_flyway_history_table(self):
        tables, _ = narrowing_rows(SCRIPT)
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


class CreateAppRtRoleMigratedInvariantTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        require_docker()
        cls.units = migration_units()
        cls.workdir = pathlib.Path(tempfile.mkdtemp(prefix='ims-app-rt-'))
        try:
            cls.argfile = cls.resolve_flyway_classpath()
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
    def resolve_flyway_classpath(cls):
        """A java @argfile with the runtime classpath of a service, as its Maven build resolves it."""
        wrapper = ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
        output = cls.workdir / 'classpath.txt'
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
        if os.name == 'nt':
            classpath = classpath.replace('\\', '/')
        argfile = cls.workdir / 'flyway.args'
        # UTC as in a Cloud Run migration job; pgjdbc sends the JVM zone, and this image rejects legacy names such as
        # Asia/Calcutta that a developer machine may report.
        argfile.write_text('-Duser.timezone=UTC\n-cp\n"%s"\n' % classpath, encoding='utf-8')
        return argfile

    @classmethod
    def migrate(cls, database, targets=None):
        """Migrates every unit, each to its latest version or to targets[schema]."""
        java = shutil.which('java') or (os.path.join(os.environ['JAVA_HOME'], 'bin', 'java')
                                        if os.environ.get('JAVA_HOME') else None)
        if java is None:
            raise RuntimeError('needs a JDK (java on PATH or JAVA_HOME) to run the migrations')
        url = 'jdbc:postgresql://127.0.0.1:%d/%s' % (cls.pg.port, database)
        units = ['%s|%s|%s|%s' % (schema, folder, table, (targets or {}).get(schema, ''))
                 for _, schema, folder, table in cls.units]
        result = subprocess.run([java, '@' + str(cls.argfile), str(HARNESS), url, 'postgres', *units],
                                env=dict(os.environ, IMS_TEST_PG_PASSWORD=cls.pg.password),
                                capture_output=True, text=True, timeout=900)
        migrated = re.findall(r'^MIGRATED (\S+)', result.stdout, re.M)
        if result.returncode != 0 or migrated != [schema for _, schema, _, _ in cls.units]:
            output = '\n'.join(line for line in (result.stdout + result.stderr).splitlines()
                               if not re.match(r'\s+at ', line))
            raise AssertionError('migration of %s failed:\n%s' % (database, output[-6000:]))
        return result.stdout

    @classmethod
    def run_script(cls, database, path=SCRIPT):
        return cls.pg.psql(database, '-v', 'app_rt_password=' + cls.pg.password, '-v', 'owner=postgres',
                           '-f', '-', stdin=read(path))

    @classmethod
    def run_audit(cls, database):
        return cls.pg.psql(database, '-v', 'ON_ERROR_STOP=1', '-v', 'run_live_probes=1', '-f', '-', stdin=read(AUDIT))

    @classmethod
    def snapshot(cls, database):
        return cls.pg.sql(database, SNAPSHOT).splitlines()

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

    def test_every_listed_relation_and_column_exists_after_migrating(self):
        # A stale row (a dropped or renamed relation) would silently stop protecting anything.
        missing = self.pg.sql('ims_template', narrowing_block(SCRIPT) + '''
            SELECT relation FROM app_rt_narrowing WHERE to_regclass(relation) IS NULL
            UNION ALL
            SELECT relation || '.' || column_name FROM app_rt_column_narrowing c
            WHERE NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = to_regclass(c.relation)
                                AND a.attname = c.column_name AND NOT a.attisdropped);''')
        self.assertEqual(missing, '')

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
        held = [line for line in self.after_migrate
                if line.startswith('ims_') and HISTORY.match(line.split('|')[2])]
        self.assertEqual(held, [])

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
        # The owner's part committed before the check: only the other grantor's grant remains.
        added, removed = diff(narrowed, self.snapshot(database))
        self.assertEqual(removed, [])
        self.assertEqual(added, ['app_rt|table|tenant_school.photo_cleanup_outbox|DELETE|%s|f' % grantor])

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

    def test_audit_on_a_fresh_migration_reports_only_the_flyway_history_tables(self):
        # Until the script has run once after Flyway created them, app_rt holds DML on the history tables through the
        # default privileges. Everything else the migrations narrowed is already as the list says.
        database = self.clone()

        result = self.run_audit(database)

        self.assertNotEqual(result.returncode, 0)
        reported = re.findall(r'([a-z_]+\.[a-z_]+) (SELECT|INSERT|UPDATE|DELETE|TRUNCATE) \(', result.stderr)
        self.assertEqual(len(reported), 4 * len(self.units), result.stderr)
        self.assertEqual([relation for relation, _ in reported if not HISTORY.match(relation)], [])


if __name__ == '__main__':
    unittest.main()
