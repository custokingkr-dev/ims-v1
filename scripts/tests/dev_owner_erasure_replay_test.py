import importlib.util,pathlib,unittest,json,subprocess,uuid,time,copy
from unittest.mock import patch
root=pathlib.Path(__file__).parents[1]
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
v=load('replay',root/'security/prepare-dev-owner-erasure-replay.py')
f=load('coverage_fixture',root/'tests/dev_erasure_coverage_test.py')
class AdapterTests(unittest.TestCase):
 def test_exact_old_epoch_external_only_refs_not_rounded(self):
  b=f.fixture();p=v.prepare(b,f.seal(b));self.assertEqual(2,len(p['tasks']))
  self.assertEqual({'9007199254740993'},{t['generation'] for t in p['tasks']});self.assertEqual(2,len({t['restoreEpoch'] for t in p['tasks']}))
  self.assertFalse(p['restorationReady']);self.assertFalse(p['deliveryResume'])
 def test_wrong_bundle_hash_rejected_before_sql(self):
  with self.assertRaises(ValueError):v.snapshot_sql(f.fixture(),'0'*64)
 def test_partial_duplicate_unknown_foreign_results_rejected(self):
  b=f.fixture();p=v.prepare(b,f.seal(b));rows=[dict(intentId=t['intentId'],state='OWNER_REPLAY_REQUIRED') for t in p['tasks']]
  for bad in (rows[:1],[rows[0],rows[0]],[dict(rows[0],state='DONE'),rows[1]],[dict(rows[0],intentId='0'*64),rows[1]]):
   with self.assertRaises(ValueError):v.reconcile(b,f.seal(b),bad)
 def test_conflict_never_certifies_restore_or_downstream(self):
  b=f.fixture();p=v.prepare(b,f.seal(b));r=v.reconcile(b,f.seal(b),[dict(intentId=t['intentId'],state='CONFLICT')for t in p['tasks']]);self.assertTrue(r['quarantineRequired']);self.assertTrue(r['downstreamReconciliationRequired']);self.assertFalse(r['restorationReady'])
 def test_empty_snapshot_also_pins_catalog_search_path(self):
  b=f.fixture();b['epochs'][0]['objects']=[];b['epochs'][1]['objects']=[]
  for e in b['epochs']:e['inventorySha256']=f.seal([])
  b['sqlReceipts']=[]
  self.assertIn('SET LOCAL search_path=pg_catalog',v.snapshot_sql(b,f.seal(b)))
 def test_readiness_exception_cleans_only_created_fixture(self):
  with patch('subprocess.run',side_effect=[subprocess.CompletedProcess([],0),RuntimeError('controlled'),subprocess.CompletedProcess([],0)]) as run:
   with self.assertRaises(RuntimeError):PostgresTests.setUpClass()
   self.assertEqual(['docker','rm','--force',PostgresTests.container],run.call_args_list[-1].args[0])
 def test_monotonic_readiness_deadline_cleans_created_fixture(self):
  with patch('subprocess.run',return_value=subprocess.CompletedProcess([],0)) as run,patch('time.monotonic',side_effect=[0,31]):
   with self.assertRaises(RuntimeError):PostgresTests.setUpClass()
   self.assertEqual(2,run.call_count);self.assertEqual(['docker','rm','--force',PostgresTests.container],run.call_args_list[-1].args[0])
class PostgresTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.container='ims-owner-replay-local-'+uuid.uuid4().hex[:10]
  created=False
  try:
   subprocess.run(['docker','run','--detach','--rm','--name',cls.container,'-e','POSTGRES_PASSWORD=controlled-local-only','postgres:16'],capture_output=True,check=True,timeout=60)
   created=True;deadline=time.monotonic()+30
   while time.monotonic()<deadline:
    remaining=deadline-time.monotonic()
    r=subprocess.run(['docker','exec',cls.container,'psql','-h','127.0.0.1','-U','postgres','-Atc','SELECT 1'],capture_output=True,timeout=max(.01,min(3,remaining)))
    if r.returncode==0:break
    time.sleep(min(.25,max(0,deadline-time.monotonic())))
   else:raise RuntimeError('Local PostgreSQL readiness failed')
  except BaseException:
   if created:
    try:subprocess.run(['docker','rm','--force',cls.container],capture_output=True,timeout=15)
    except Exception:pass
   raise
 @classmethod
 def tearDownClass(cls):subprocess.run(['docker','rm','--force',cls.container],capture_output=True,check=True,timeout=30)
 def sql(self,q,ok=True):
  r=subprocess.run(['docker','exec','-i',self.container,'psql','-X','-A','-t','-h','127.0.0.1','-U','postgres','-v','ON_ERROR_STOP=1'],input=q,text=True,capture_output=True,timeout=20)
  if ok:self.assertEqual(0,r.returncode,r.stderr)
  else:self.assertNotEqual(0,r.returncode)
  return r.stdout
 def setUp(self):
  self.b=f.fixture();self.p=v.prepare(self.b,f.seal(self.b));self.t=self.p['tasks'][0]
  self.sql('DROP SCHEMA IF EXISTS student CASCADE; CREATE SCHEMA student; CREATE TABLE student.students(id bigint PRIMARY KEY,school_id bigint NOT NULL,erasure_incarnation uuid NOT NULL); CREATE TABLE student.erasure_journal_receipts(intent_id text PRIMARY KEY,operation_id uuid NOT NULL,school_id bigint NOT NULL,student_id bigint NOT NULL,student_incarnation uuid NOT NULL,source_lineage_id text NOT NULL,restore_epoch uuid NOT NULL,journal_object text NOT NULL,journal_generation bigint NOT NULL,journal_sha256 text NOT NULL);')
 def observations(self):
  out=self.sql(v.snapshot_sql(self.b,f.seal(self.b)));return json.loads(next(x for x in out.splitlines()if x.startswith('[')))
 def insert_source(self,inc=None,school=None):
  t=self.t;self.sql(f"INSERT INTO student.students VALUES({t['studentId']},{school or t['schoolId']},'{inc or t['studentIncarnation']}');")
 def insert_receipt(self,**overrides):
  t=self.t|overrides;vals=[t[k] for k in ('intentId','operationId','schoolId','studentId','studentIncarnation')]+[self.b['lineageId'],t['restoreEpoch'],t['object'],t['generation'],t['sha256']]
  self.sql('INSERT INTO student.erasure_journal_receipts VALUES('+','.join(v.literal(x)for x in vals)+');')
 def state(self):return next(r['state']for r in self.observations()if r['intentId']==self.t['intentId'])
 def test_matching_incarnation_and_reused_id_conflict_without_mutation(self):
  self.insert_source();self.assertEqual('OWNER_REPLAY_REQUIRED',self.state());self.assertIn('CONFLICT',{r['state']for r in self.observations()});self.assertIn('1',self.sql('SELECT count(*)FROM student.students;'))
 def test_absent_receipt_exact_generation_and_wrong_generation(self):
  self.assertEqual('EXTERNAL_ONLY_SOURCE_ABSENT',self.state());self.insert_receipt();self.assertEqual('SOURCE_ERASURE_RECEIPT_MATCHED',self.state());self.sql("UPDATE student.erasure_journal_receipts SET journal_generation=9007199254740992;");self.assertEqual('CONFLICT',self.state())
 def test_other_school_and_incarnation_refused(self):
  self.insert_source(school=77);self.assertEqual('CONFLICT',self.state());self.sql('DELETE FROM student.students;');self.insert_source(inc=str(uuid.uuid4()));self.assertEqual('CONFLICT',self.state())
 def test_receipt_binding_conflict_is_not_completion(self):
  self.insert_receipt(operationId=str(uuid.uuid4()));self.assertEqual('CONFLICT',self.state())
 def test_restored_hostile_search_path_cannot_shadow_builtin_json(self):
  self.insert_source()
  self.sql("CREATE SCHEMA hostile; CREATE FUNCTION hostile.to_jsonb(anyelement) RETURNS jsonb LANGUAGE sql AS $$SELECT '{\"intentId\":\"forged\",\"state\":\"SOURCE_ERASURE_RECEIPT_MATCHED\"}'::jsonb$$;")
  # Control proves an explicit restored path selects the shadow for the exact record type.
  control=self.sql("SET search_path=hostile,pg_catalog; SELECT to_jsonb(s) FROM student.students s;")
  self.assertIn('forged',control)
  output=self.sql('SET search_path=hostile,pg_catalog; '+v.snapshot_sql(self.b,f.seal(self.b)))
  rows=json.loads(next(x for x in output.splitlines()if x.startswith('[')))
  self.assertNotIn('forged',output);self.assertEqual('OWNER_REPLAY_REQUIRED',next(r['state']for r in rows if r['intentId']==self.t['intentId']))
 def test_tenant_rls_hidden_rows_cannot_be_reported_absent(self):
  self.insert_source();self.sql("DO $$BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='replay_reader') THEN CREATE ROLE replay_reader; END IF; END$$; GRANT USAGE ON SCHEMA student TO replay_reader; GRANT SELECT ON ALL TABLES IN SCHEMA student TO replay_reader; ALTER TABLE student.students ENABLE ROW LEVEL SECURITY; ALTER TABLE student.students FORCE ROW LEVEL SECURITY; CREATE POLICY hidden ON student.students USING(false);")
  self.sql('SET ROLE replay_reader; '+v.snapshot_sql(self.b,f.seal(self.b)),ok=False)
 def test_readonly_sql_cannot_be_extended_to_write(self):
  q=v.snapshot_sql(self.b,f.seal(self.b)).replace('ROLLBACK;',"INSERT INTO student.students VALUES(1,1,gen_random_uuid()); ROLLBACK;");self.sql(q,ok=False);self.assertIn('0',self.sql('SELECT count(*)FROM student.students;'))
if __name__=='__main__':unittest.main()
