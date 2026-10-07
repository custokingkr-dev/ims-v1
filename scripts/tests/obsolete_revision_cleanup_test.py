import importlib.util,copy,json,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('cleanup',Path(__file__).parents[1]/'security'/'cleanup-dev-obsolete-revisions.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class RevisionCleanupGuardTest(unittest.TestCase):
 def fixture(self):
  row={'revision':'custoking-identity-service-dev-old','candidate':True,'unsafeReasons':['legacy-shared-or-owner-database-role'],'protectedByLatestTrafficOrTag':False,'trafficPercent':0,'desiredTrafficPercent':0,'tags':[],'specSha256':'a'*64,'imageDigest':'registry/image@sha256:'+'b'*64}
  service={'service':'custoking-identity-service-dev','activeRollouts':[],'revisions':[row]}
  return row,service
 def test_valid_exact_candidate_and_fresh_state(self):
  row,service=self.fixture();self.assertEqual(m.assert_unchanged(service,row),row)
  inventory={'project':m.PROJECT,'region':m.REGION,'mode':'READ_ONLY_DELETION_DRY_RUN','services':[{'service':'custoking-'+s+'-dev','activeRollouts':[],'revisions':[row] if s=='identity-service' else []} for s in m.ROLES]}
  self.assertEqual(len(m.candidates(inventory)),1)
  inventory['project']='custoking-prod'
  with self.assertRaises(ValueError):m.candidates(inventory)
 def test_latest_tag_traffic_secure_changed_and_active_rollout_refused(self):
  original,service=self.fixture()
  for field,value in [('candidate',False),('protectedByLatestTrafficOrTag',True),('trafficPercent',1),('desiredTrafficPercent',1),('tags',['rollback']),('specSha256','c'*64),('imageDigest','changed'),('unsafeReasons',[])]:
   with self.subTest(field=field):
    state=copy.deepcopy(service);state['revisions'][0][field]=value
    with self.assertRaises(ValueError):m.assert_unchanged(state,original)
  service['activeRollouts']=[{'state':'IN_PROGRESS'}]
  with self.assertRaises(ValueError):m.assert_unchanged(service,original)
 def test_metadata_predicates_keep_secure_and_unknown_revisions(self):
  # Real-shaped native metadata fixture: a secure latest and a positively unsafe old revision, with a tag making the unsafe revision ineligible.
  old={'metadata':{'name':'custoking-identity-service-dev-old'},'spec':{'containers':[{'env':[{'name':'SPRING_DATASOURCE_USERNAME','value':'app_rt'},{'name':'APP_MIGRATIONS_ENABLED','value':'true'},{'name':'SPRING_PROFILES_ACTIVE','value':'dev'}]}]},'status':{'imageDigest':'image@sha256:'+'d'*64}}
  secure=copy.deepcopy(old);secure['metadata']['name']='custoking-identity-service-dev-new';secure['spec']['containers'][0]['env'][0]['value']='ims_identity_rt';secure['spec']['containers'][0]['env'][1]['value']='false'
  service={'status':{'latestReadyRevisionName':secure['metadata']['name'],'latestCreatedRevisionName':secure['metadata']['name'],'traffic':[{'revisionName':secure['metadata']['name'],'percent':100},{'revisionName':old['metadata']['name'],'percent':0,'tag':'rollback'}]},'spec':{'traffic':[]}}
  original=m.read
  try:
   m.read=lambda args:service if args[1:3]==['services','describe'] else ([old,secure] if args[1:3]==['revisions','list'] else [])
   result=m.collect(('identity-service','ims_identity_rt'))
   self.assertFalse(any(r['candidate'] for r in result['revisions']))
   self.assertEqual(result['revisions'][1]['unsafeReasons'],[])
   self.assertNotIn('app_rt',json.dumps(result)) # no raw env values preserved
  finally:m.read=original
if __name__=='__main__':unittest.main()
