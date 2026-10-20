import copy
import datetime as dt
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('erasure', ROOT / 'scripts/security/dev-erasure-journal.py')
tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(tool)

def fixtures():
    now = dt.datetime.now(dt.timezone.utc)
    fmt = lambda value: value.isoformat().replace('+00:00', 'Z')
    inv = {'schemaVersion': 1, 'project': tool.PROJECT, 'sourceInstance': tool.SOURCE, 'database': tool.DATABASE,
           'marker': tool.MARKER, 'sourceLineageId': 'reviewed-dev-primary-epoch-1', 'ownershipProofSha256': 'a' * 64,
           'targets': [{'studentId': n, 'schoolId': 990007101 if n <= 990007310 else 990007102} for n in range(990007301, 990007321)]}
    exported = {**{key: inv[key] for key in ['project', 'sourceInstance', 'database', 'marker', 'sourceLineageId']},
                'inventorySha256': tool.digest(inv), 'capturedAt': fmt(now), 'records': [
                    {'eventId': n, 'eventKey': 'StudentDeleted:' + str(row['studentId']), **row,
                     'occurredAt': fmt(now - dt.timedelta(seconds=30))} for n, row in enumerate(inv['targets'], 1)]}
    journal = tool.seal(inv, tool.digest(inv), exported, now)
    clone = 'custoking-dev-security-restore-20261007120000-abcdef12'
    evidence = {'project': tool.PROJECT, 'sourceInstance': tool.SOURCE, 'clone': clone, 'sourceLineageId': inv['sourceLineageId'],
                'capturedAt': fmt(now), 'state': 'RUNNABLE', 'trafficAttached': False, 'deliveryDisabled': True, 'privateIp': '10.92.0.44'}
    return inv, exported, journal, clone, evidence, now

def replay(data):
    inv, _, journal, clone, evidence, now = data
    return tool.replay_sql(inv, tool.digest(inv), journal, tool.digest(journal), clone, evidence, tool.digest(evidence), 123456, now)

class ErasureControlledTests(unittest.TestCase):
    def test_stable_record_keys_create_only_and_no_restore_ready_claim(self):
        inv, exported, journal, _, _, now = fixtures()
        plan = tool.storage_plan(journal, tool.digest(journal))
        self.assertEqual('0', plan['create']['query']['ifGenerationMatch'])
        self.assertFalse(plan['automaticUpload'])
        self.assertFalse(journal['coverage']['precommitDurabilityGuaranteed'])
        changed = copy.deepcopy(exported); changed['capturedAt'] = (now + dt.timedelta(seconds=1)).isoformat().replace('+00:00', 'Z')
        second = tool.seal(inv, tool.digest(inv), changed, now + dt.timedelta(seconds=1))
        second_plan = tool.storage_plan(second, tool.digest(second))
        self.assertEqual(plan['stableRecordObjects'], second_plan['stableRecordObjects'])
        self.assertNotEqual(plan['object'], second_plan['object'])

    def test_tamper_school_duplicate_pii_partial_and_stale_rejected(self):
        inv, exported, _, _, _, now = fixtures()
        mutations = [lambda v: v['records'][0].update(schoolId=990007102),
                     lambda v: v['records'][0].update(studentId=42),
                     lambda v: v['records'][0].update(email='forbidden@example.invalid'),
                     lambda v: v['records'].pop(),
                     lambda v: v['records'][0].update(eventId=v['records'][1]['eventId']),
                     lambda v: v.update(capturedAt='2020-01-01T00:00:00Z'),
                     lambda v: v['records'][0].update(occurredAt='2099-01-01T00:00:00Z')]
        for mutate in mutations:
            changed = copy.deepcopy(exported); mutate(changed)
            with self.subTest(mutate=mutate), self.assertRaises(tool.Rejected): tool.seal(inv, tool.digest(inv), changed, now)
        with self.assertRaises(tool.Rejected): tool.inventory(inv, 'b' * 64)

    def test_live_primary_wrong_project_and_delivery_resume_rejected(self):
        for field, value in [('privateIp', '10.92.0.3'), ('project', 'custoking-prod'), ('deliveryDisabled', False), ('trafficAttached', True), ('sourceLineageId', 'other-db-lineage')]:
            data = list(fixtures()); data[4] = copy.deepcopy(data[4]); data[4][field] = value
            with self.subTest(field=field), self.assertRaises(tool.Rejected): replay(data)
        data = list(fixtures()); data[3] = tool.SOURCE
        with self.assertRaises(tool.Rejected): replay(data)

    def test_export_includes_unpublished_and_old_events_read_only(self):
        inv, *_ = fixtures(); sql = tool.export_sql(inv, tool.digest(inv))
        self.assertIn('BEGIN READ ONLY', sql)
        self.assertNotIn('published_at', sql)
        self.assertNotIn('occurred_at >', sql)
        self.assertNotIn('DELETE', sql)
        self.assertIn('payload->>\'schoolId\'=school_id::text', sql)

    def test_optimized_python_retains_scope_rejection_and_never_writes_output(self):
        inv, *_ = fixtures(); inv['project']='custoking-prod'
        with tempfile.TemporaryDirectory()as directory:
            path=pathlib.Path(directory)/'inventory.json';path.write_text(json.dumps(inv));output=pathlib.Path(directory)/'replay.sql'
            result=subprocess.run(['python','-O',str(ROOT/'scripts/security/dev-erasure-journal.py'),'export-sql','--inventory',str(path),'--inventory-sha256',tool.digest(inv),'--output',str(output)],capture_output=True,text=True,timeout=10)
            self.assertNotEqual(0,result.returncode);self.assertIn('ERASURE_PLAN_REJECTED',result.stderr);self.assertFalse(output.exists())

    def test_artifact_body_ceiling(self):
        with tempfile.TemporaryDirectory()as directory:
            path=pathlib.Path(directory)/'oversized.json';path.write_bytes(b' '*131073)
            with self.assertRaises(tool.Rejected):tool.load_bounded_json(path)

class ErasurePostgresTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'ims-erasure-local-' + uuid.uuid4().hex[:10]
        subprocess.run(['docker', 'run', '--detach', '--rm', '--name', cls.container, '-e', 'POSTGRES_PASSWORD=controlled-local-only', 'postgres:16'], capture_output=True, text=True, check=True, timeout=60)
        for _ in range(30):
            ready = subprocess.run(['docker', 'exec', cls.container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'], capture_output=True, timeout=10)
            if ready.returncode == 0: break
            time.sleep(.5)
        else: raise RuntimeError('Controlled PostgreSQL did not become ready')

    @classmethod
    def tearDownClass(cls):
        subprocess.run(['docker', 'rm', '--force', cls.container], capture_output=True, timeout=30, check=True)

    def sql(self, query, success=True):
        result = subprocess.run(['docker', 'exec', '-i', self.container, 'psql', '-X', '-A', '-t', '-h', '127.0.0.1', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1'], input=query, capture_output=True, text=True, timeout=30)
        if success: self.assertEqual(0, result.returncode, result.stderr)
        else: self.assertNotEqual(0, result.returncode)
        return result.stdout.strip()

    def setUp(self):
        self.sql('DROP SCHEMA IF EXISTS student CASCADE; DROP SCHEMA IF EXISTS reporting CASCADE; DROP SCHEMA IF EXISTS notification CASCADE; DROP SCHEMA IF EXISTS attendance CASCADE; DROP SCHEMA IF EXISTS fee CASCADE; DROP SCHEMA IF EXISTS tenant_school CASCADE; CREATE SCHEMA student; CREATE SCHEMA reporting; CREATE SCHEMA notification; CREATE SCHEMA attendance; CREATE SCHEMA fee; CREATE SCHEMA tenant_school;')
        self.sql("CREATE TABLE student.students(id bigint PRIMARY KEY,school_id bigint,admission_no text,created_by text,photo_url text); CREATE TABLE reporting.student_projection_tombstones(student_id bigint PRIMARY KEY,deleted_at timestamptz,recorded_at timestamptz); CREATE TABLE reporting.dim_student(id bigint PRIMARY KEY,school_id bigint);")
        for table in ['notification.notification_broadcast_recipients', 'notification.notification_logs']:
            self.sql('CREATE TABLE ' + table + '(student_id bigint,school_id bigint);')
        for table in ['reporting.event_student_contributions', 'reporting.fact_payment', 'reporting.fact_fee_assignment', 'student.student_guardians', 'fee.payment_records', 'fee.fee_assignments', 'attendance.attendance_student_records', 'attendance.absentee_notifications', 'student.photo_import_rows', 'student.student_review_items', 'student.student_promotion_batch_items', 'student.student_enrollments', 'student.student_consent_events']:
            self.sql('CREATE TABLE ' + table + '(student_id bigint);')
        self.sql('CREATE TABLE student.import_rows(applied_student_id bigint);')
        self.sql("INSERT INTO student.students SELECT n,CASE WHEN n<=990007310 THEN 990007101 ELSE 990007102 END,'SEC-ACPT-20261007-STUDENT-'||(n-990007300),'SEC-ACPT-20261007',NULL FROM generate_series(990007301,990007320)n; INSERT INTO reporting.dim_student SELECT id,school_id FROM student.students; INSERT INTO notification.notification_broadcast_recipients SELECT id,school_id FROM student.students; INSERT INTO student.students VALUES(42,9,'ordinary-row','ordinary',NULL);")

    def test_actual_source_export_seals_published_and_unpublished_records(self):
        self.sql("CREATE TABLE tenant_school.outbox_events(id bigint,event_key text,event_type text,aggregate_type text,aggregate_id text,school_id bigint,payload jsonb,occurred_at timestamptz,published_at timestamptz); INSERT INTO tenant_school.outbox_events SELECT id-990007300,'StudentDeleted:'||id,'student.deleted.v1','Student',id::text,school_id,jsonb_build_object('id',id,'schoolId',school_id),clock_timestamp()-interval '30seconds',CASE WHEN id<=990007310 THEN clock_timestamp() ELSE NULL END FROM student.students WHERE id<>42;")
        inv, *_=fixtures(); output=self.sql(tool.export_sql(inv,tool.digest(inv))); exported=json.loads(next(line for line in output.splitlines()if line.startswith('{')))
        journal=tool.seal(inv,tool.digest(inv),exported)
        self.assertEqual(20,len(journal['records']));self.assertFalse(journal['coverage']['precommitDurabilityGuaranteed'])

    def test_exact_restored_rows_removed_fences_seeded_replay_idempotent(self):
        self.sql(replay(fixtures())); self.sql(replay(fixtures()))
        self.assertEqual('1|20|0|0', self.sql('SELECT (SELECT count(*)FROM student.students),(SELECT count(*)FROM reporting.student_projection_tombstones),(SELECT count(*)FROM reporting.dim_student),(SELECT count(*)FROM notification.notification_broadcast_recipients);'))
        self.assertEqual('42', self.sql('SELECT id FROM student.students;'))

    def test_wrong_restored_school_rolls_back_every_fence_and_delete(self):
        self.sql('UPDATE student.students SET school_id=999 WHERE id=990007301;')
        self.sql(replay(fixtures()), success=False)
        self.assertEqual('21|0|20', self.sql('SELECT (SELECT count(*)FROM student.students),(SELECT count(*)FROM reporting.student_projection_tombstones),(SELECT count(*)FROM notification.notification_broadcast_recipients);'))

    def test_dependent_financial_record_refuses_full_transaction(self):
        self.sql('INSERT INTO fee.payment_records VALUES(990007301);')
        self.sql(replay(fixtures()), success=False)
        self.assertEqual('21|0|20', self.sql('SELECT (SELECT count(*)FROM student.students),(SELECT count(*)FROM reporting.student_projection_tombstones),(SELECT count(*)FROM notification.notification_broadcast_recipients);'))

if __name__ == '__main__': unittest.main()
