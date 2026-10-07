import copy, hashlib, importlib.util, pathlib, unittest, uuid
PATH=pathlib.Path(__file__).resolve().parents[1]/'security'/'prepare-dev-precommit-journal-acceptance.py'
spec=importlib.util.spec_from_file_location('journal_acceptance',PATH);j=importlib.util.module_from_spec(spec);spec.loader.exec_module(j)
def plan():
    nonce='a'*12
    names=['custoking-school-core-service-dev','custoking-identity-service-dev','custoking-frontend-dev','custoking-api-gateway-dev']
    return dict(project='custoking-dev',region='asia-south2',nonce=nonce,marker=j.marker(nonce),ids=copy.deepcopy(j.IDS),sourceSha='b'*40,
        services={n:dict(revision=n+'-abc',image='asia-south2-docker.pkg.dev/custoking-dev/custoking/'+n.removesuffix('-dev')+'@sha256:'+'c'*64) for n in names},
        journalEnvironment=dict(STUDENT_ERASURE_JOURNAL_ENABLED='true',STUDENT_ERASURE_JOURNAL_PROJECT_ID='custoking-dev',STUDENT_ERASURE_JOURNAL_BUCKET='custoking-dev-erasure-journal',STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID='58bc724a-592f-49f1-b22f-0e5f95aa6808',STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH='d9c4f31c-3420-48a7-8fbe-7fc26d2065bf',STUDENT_ERASURE_JOURNAL_EPOCH_GENERATION='123',STUDENT_ERASURE_JOURNAL_EPOCH_SHA256='d'*64))
def fixture(p):
    env=p['journalEnvironment'];inc='06af49a3-15bf-4b20-a3c0-1ee7cadab719'
    identity=dict(schemaVersion=1,project='custoking-dev',sourceInstance='custoking-db-dev',database='custoking_dev',sourceLineageId=env['STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID'],restoreEpoch=env['STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH'],schoolId=990008101,studentId=990008301,studentIncarnation=inc)
    key=hashlib.sha256(j.canonical(identity)).hexdigest();op=str(uuid.UUID(bytes=hashlib.md5(('ims-student-erasure:'+key).encode()).digest(),version=3))
    raw=j.canonical(dict(identity,kind='student.erasure-intent.v1',intentId=key,operationId=op))
    r=dict(student_incarnation=inc,intent_id=key,operation_id=op,student_id=990008301,school_id=990008101,source_lineage_id=identity['sourceLineageId'],restore_epoch=identity['restoreEpoch'],journal_object='intents/'+identity['sourceLineageId']+'/'+identity['restoreEpoch']+'/'+key+'.json',journal_generation=7,journal_sha256=hashlib.sha256(raw).hexdigest())
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
