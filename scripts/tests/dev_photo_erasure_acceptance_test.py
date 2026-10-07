import copy, hashlib, importlib.util, pathlib, unittest, uuid
PATH=pathlib.Path(__file__).resolve().parents[1]/'security'/'prepare-dev-photo-erasure-acceptance.py'
spec=importlib.util.spec_from_file_location('journal_acceptance',PATH);j=importlib.util.module_from_spec(spec);spec.loader.exec_module(j)
def plan():
    nonce='a'*12
    names=['custoking-school-core-service-dev','custoking-identity-service-dev','custoking-frontend-dev','custoking-api-gateway-dev','custoking-operations-service-dev','custoking-platform-service-dev','custoking-billing-service-dev']
    return dict(project='custoking-dev',region='asia-south2',nonce=nonce,marker=j.marker(nonce),ids=copy.deepcopy(j.IDS),sourceSha='01522d74d039b9f46162572ddafd1a5c90d6e7ee',releaseRunId=37651698357,
        services={n:dict(revision=n+'-abc',image='asia-south2-docker.pkg.dev/custoking-dev/custoking/'+n.removesuffix('-dev')+'@sha256:'+'c'*64) for n in names},
        journalEnvironment=dict(STUDENT_ERASURE_JOURNAL_ENABLED='true',STUDENT_ERASURE_JOURNAL_PROJECT_ID='custoking-dev',STUDENT_ERASURE_JOURNAL_BUCKET='custoking-dev-erasure-journal',STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID='58bc724a-592f-49f1-b22f-0e5f95aa6808',STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH='d9c4f31c-3420-48a7-8fbe-7fc26d2065bf',STUDENT_ERASURE_JOURNAL_EPOCH_GENERATION='123',STUDENT_ERASURE_JOURNAL_EPOCH_SHA256='d'*64))
def fixture(p):
    env=p['journalEnvironment'];inc='06af49a3-15bf-4b20-a3c0-1ee7cadab719'
    identity=dict(schemaVersion=1,project='custoking-dev',sourceInstance='custoking-db-dev',database='custoking_dev',sourceLineageId=env['STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID'],restoreEpoch=env['STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH'],schoolId=990008401,studentId=990008601,studentIncarnation=inc)
    key=hashlib.sha256(j.canonical(identity)).hexdigest();op=str(uuid.UUID(bytes=hashlib.md5(('ims-student-erasure:'+key).encode()).digest(),version=3))
    raw=j.canonical(dict(identity,kind='student.erasure-intent.v1',intentId=key,operationId=op))
    r=dict(student_incarnation=inc,intent_id=key,operation_id=op,student_id=990008601,school_id=990008401,source_lineage_id=identity['sourceLineageId'],restore_epoch=identity['restoreEpoch'],journal_object='intents/'+identity['sourceLineageId']+'/'+identity['restoreEpoch']+'/'+key+'.json',journal_generation=7,journal_sha256=hashlib.sha256(raw).hexdigest())
    return r,raw
class JournalAcceptanceTests(unittest.TestCase):
    def test_valid_canonical_receipt(self):
        p=plan();r,raw=fixture(p);self.assertTrue(j.verify_intent(p,r,raw)['canonical'])
    def test_foreign_school_rejected(self):
        p=plan();r,raw=fixture(p);r['school_id']=990007101
        with self.assertRaises(ValueError):j.verify_intent(p,r,raw)
    def test_whitespace_tamper_rejected(self):
        p=plan();r,raw=fixture(p)
        with self.assertRaises(ValueError):j.verify_intent(p,r,raw+b' ')
    def test_wrong_hash_or_generation_rejected(self):
        for field,value in [('journal_sha256','f'*64),('journal_generation',0),('journal_object','intents/foreign.json')]:
            p=plan();r,raw=fixture(p);r[field]=value
            with self.assertRaises(ValueError):j.verify_intent(p,r,raw)
    def test_old_ids_and_prod_rejected(self):
        for field,value in [('project','custoking-prod'),('ids',dict(j.IDS,studentId=990007301)),('nonce',"x';DROP TABLE x")]:
            p=plan();p[field]=value
            with self.assertRaises(ValueError):j.validate_plan(p)
    def test_each_collision_rejected(self):
        p=plan();row=dict(marker=p['marker'],database='custoking_dev',**{k:0 for k in ['source','school','actor','receipt','photoQueue','sourceOutbox','reportingInbox','projection','parents','tombstone']});j.verify_collision(p,row)
        for k in ['source','receipt','tombstone','parents']:
            bad=dict(row);bad[k]=1
            with self.assertRaises(ValueError):j.verify_collision(p,bad)
    def test_transaction_seed_and_preserving_cleanup(self):
        p=plan();seed=j.seed_sql(p,'$2b$12$'+'a'*53);cleanup=j.cleanup_sql(p)
        self.assertIn('pg_advisory_xact_lock',seed);self.assertIn('Reserved fixture collision',seed)
        self.assertNotIn('ON CONFLICT',seed);self.assertNotIn('erasure_incarnation)',seed)
        self.assertIn("status='REVOKED'",cleanup)
        for table in ['student.students','student.erasure_journal_receipts','reporting.student_projection_tombstones','identity.rbac_audit_log']:
            self.assertNotIn('DELETE FROM '+table,cleanup)
    def test_missing_control_or_mutable_image_rejected(self):
        p=plan();p['journalEnvironment']['STUDENT_ERASURE_JOURNAL_ENABLED']='false'
        with self.assertRaises(ValueError):j.validate_plan(p)
        p=plan();next(iter(p['services'].values()))['image']='image:latest'
        with self.assertRaises(ValueError):j.validate_plan(p)
    def test_ready_context_rejects_wrong_revision_env_or_traffic(self):
        p=plan();o=dict(project='custoking-dev',releaseSourceSha=p['sourceSha'],releaseEvidenceVerified=True,journalEnvironment=copy.deepcopy(p['journalEnvironment']),services={n:dict(revision=v['revision'],image=v['image'],ready=True,trafficPercent=100,taggedOtherRevisions=[]) for n,v in p['services'].items()})
        j.verify_ready(p,o)
        for change in ['revision','traffic','env','evidence']:
            bad=copy.deepcopy(o)
            if change=='revision':next(iter(bad['services'].values()))['revision']='old'
            if change=='traffic':next(iter(bad['services'].values()))['taggedOtherRevisions']=['old']
            if change=='env':bad['journalEnvironment']['STUDENT_ERASURE_JOURNAL_ENABLED']='false'
            if change=='evidence':bad['releaseEvidenceVerified']=False
            with self.assertRaises(ValueError):j.verify_ready(p,bad)
    def test_extra_environment_rejected_without_value_disclosure(self):
        p=plan();private_value='controlled-sensitive-value-never-export'
        p['journalEnvironment']['UNRELATED_SECRET']=private_value
        with self.assertRaises(ValueError) as caught:j.validate_plan(p)
        self.assertEqual('EXACT_JOURNAL_ENVIRONMENT_KEYS_REQUIRED',str(caught.exception))
        self.assertNotIn(private_value,str(caught.exception))
    def test_foreign_or_mismapped_repository_rejected(self):
        for image in [
            'asia-south2-docker.pkg.dev/custoking-prod/custoking/custoking-school-core-service@sha256:'+'e'*64,
            'asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-identity-service@sha256:'+'e'*64,
            'asia-south2-docker.pkg.dev/custoking-dev/custoking/arbitrary@sha256:'+'e'*64]:
            p=plan();p['services']['custoking-school-core-service-dev']['image']=image
            with self.assertRaisesRegex(ValueError,'^IMMUTABLE_DEV_IMAGE_REQUIRED$'):j.validate_plan(p)
if __name__=='__main__':unittest.main()

class PhotoProfileGuards(unittest.TestCase):
    def test_only_exact_reserved_photo_target(self):
        p=plan(); uid='11111111-1111-4111-8111-111111111111'; row=dict(marker=p['marker'],schoolUid=uid,bucket='custoking-dev-student-photos',key='schools/'+uid+'/students/990008601/photos/'+'a'*64+'.jpg')
        self.assertEqual(row['key'],j.validate_photo_target(p,row)['key'])
        for field,value in [('key',row['key'].replace('990008601','990008301')),('bucket','other-bucket'),('key',row['key'].replace('/photos/','/../')),('marker','foreign')]:
            with self.assertRaises(ValueError):j.validate_photo_target(p,dict(row,**{field:value}))
    def test_source_run_and_seven_services_required(self):
        for field,value in [('sourceSha','b'*40),('releaseRunId',1),('ids',dict(j.IDS,studentId=990008301))]:
            with self.assertRaises(ValueError):j.validate_plan(dict(plan(),**{field:value}))
        p=plan();p['services'].pop('custoking-billing-service-dev')
        with self.assertRaises(ValueError):j.validate_plan(p)
    def test_photo_sql_fixed_scope_generation_and_readonly(self):
        sql=j.photo_sql(plan(),True);self.assertIn('q.object_generation',sql);self.assertIn('990008601',sql);self.assertIn('990008401',sql);self.assertIn('BEGIN READ ONLY',sql);self.assertNotIn('DELETE ',sql)
    def test_generation_absence_does_not_accept_permission_denial(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        target=dict(bucket='custoking-dev-student-photos',key='schools/11111111-1111-4111-8111-111111111111/students/990008601/photos/'+'a'*64+'.jpg',generation=17)
        with patch('subprocess.run',return_value=SimpleNamespace(returncode=1,stderr='403 permission denied')):
            with self.assertRaises(ValueError):j.require_generation_absent(target)

class DecimalPhotoGenerationTests(unittest.TestCase):
    def test_receipt_logging_is_lossless_and_fails_rounded_float(self):
        self.assertEqual(9007199254740993,j.parse_decimal_generation('9007199254740993'))
        self.assertIn('r.journal_generation::text',j.verify_sql(plan()))
        with self.assertRaises(ValueError):j.parse_decimal_generation(9007199254740992.0)
