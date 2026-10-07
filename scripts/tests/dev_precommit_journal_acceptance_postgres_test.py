"""Docker PostgreSQL syntax/transaction rehearsal, not an application or GCS proof."""
import json, pathlib, subprocess, time, unittest, uuid
from scripts.tests.dev_precommit_journal_acceptance_test import j,plan
class JournalSqlPostgresTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.name='ims-journal-prepare-'+uuid.uuid4().hex[:12]
        subprocess.run(['docker','run','--detach','--rm','--name',cls.name,'-e','POSTGRES_PASSWORD=controlled-local-only','postgres:16'],capture_output=True,check=True,timeout=60)
        try:
            for _ in range(60):
                if subprocess.run(['docker','exec',cls.name,'pg_isready','-U','postgres'],capture_output=True,timeout=5).returncode==0:break
                time.sleep(.2)
            else:raise RuntimeError('POSTGRES_NOT_READY')
            cls.sql('CREATE DATABASE custoking_dev;')
            cls.sql('CREATE SCHEMA student;CREATE SCHEMA tenant_school;CREATE SCHEMA identity;CREATE SCHEMA reporting; CREATE ROLE ims_school_core_rt; '+
              'CREATE TABLE tenant_school.schools(id bigint PRIMARY KEY,name text,short_code text,active boolean,created_at timestamptz);'+
              'CREATE TABLE tenant_school.school_classes(id text PRIMARY KEY,name text,sort_order integer);'+
              'CREATE TABLE tenant_school.academic_years(id text PRIMARY KEY,label text,active boolean);'+
              'CREATE TABLE tenant_school.school_sections(id text PRIMARY KEY,name text,active boolean,school_class_id text,school_id bigint);'+
              'CREATE TABLE tenant_school.school_module_entitlements(school_id bigint,module_code text,enabled boolean,notes text);'+
              'CREATE TABLE tenant_school.photo_cleanup_outbox(student_id bigint);CREATE TABLE tenant_school.outbox_events(aggregate_id text);CREATE TABLE reporting.reporting_event_inbox(aggregate_id text);'+
              'CREATE TABLE student.students(id bigint PRIMARY KEY,admission_no text,full_name text,created_at timestamptz,updated_at timestamptz,school_id bigint,class_id text,section_id text,academic_year_id text,created_by text,erasure_incarnation uuid DEFAULT gen_random_uuid());'+
              'CREATE TABLE student.erasure_journal_receipts(student_id bigint,school_id bigint);'+
              'CREATE TABLE reporting.student_projection_tombstones(student_id bigint);'+
              'CREATE TABLE identity.app_users(id bigint PRIMARY KEY,full_name text,email text,password_hash text,role text,branch_id bigint,branch_name text,created_at timestamptz,deleted_at timestamptz,deleted_by text,credential_version integer DEFAULT 0);'+
              "CREATE TABLE identity.roles(id bigint,name text);INSERT INTO identity.roles VALUES(1,'SCHOOL_ADMIN');"+
              'CREATE TABLE identity.user_role_assignments(user_id bigint,role_id bigint,school_id bigint,active boolean,revoked_at timestamptz);'+
              'CREATE TABLE identity.auth_sessions(user_id bigint,status text);CREATE TABLE identity.rbac_audit_log(event_type text,target_user_id bigint,new_value text,correlation_id text);')
            # Use the owner's actual projection migration, rather than duplicating its name.
            projection = pathlib.Path(j.__file__).resolve().parents[2] / 'services/platform-service/src/main/resources/db/migration/reporting/V15__student_dimension.sql'
            cls.sql('SET search_path TO reporting; ' + projection.read_text(encoding='utf-8'))
        except BaseException:
            subprocess.run(['docker','stop',cls.name],capture_output=True,timeout=30);raise
    @classmethod
    def tearDownClass(cls):subprocess.run(['docker','stop',cls.name],capture_output=True,check=True,timeout=30)
    @classmethod
    def sql(cls,sql):
        database='postgres' if sql.startswith('CREATE DATABASE') else 'custoking_dev'
        result=subprocess.run(['docker','exec','-i',cls.name,'psql','-U','postgres','-d',database,'-XAt','-v','ON_ERROR_STOP=1'],input=sql,capture_output=True,text=True,timeout=25)
        if result.returncode:raise RuntimeError('CONTROLLED_SQL_FAILED:'+result.stderr)
        rows=[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
        return rows[-1] if rows else None
    def test_seed_collision_cleanup_preserves_receipt(self):
        p=plan();j.verify_collision(p,self.sql(j.collision_sql(p)))
        seeded=self.sql(j.seed_sql(p,'$2b$12$'+'a'*53));self.assertEqual(1,seeded['seeded']);self.assertTrue(seeded['studentIncarnation'])
        with self.assertRaises(RuntimeError):self.sql(j.seed_sql(p,'$2b$12$'+'a'*53))
        # Owner direct fixture erasure simulates prerequisites ONLY; not application acceptance.
        self.sql("DELETE FROM student.students WHERE id=990008301;INSERT INTO student.erasure_journal_receipts VALUES(990008301,990008101);INSERT INTO identity.auth_sessions VALUES(990008201,'ACTIVE');")
        done=self.sql(j.cleanup_sql(p));self.assertEqual(1,done['receipt']);self.assertEqual(1,done['disabled']);self.assertEqual(0,done['school']);self.assertEqual(0,done['activeSessions'])
if __name__=='__main__':unittest.main()
