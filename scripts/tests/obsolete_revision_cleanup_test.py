import importlib.util,copy,json,unittest
from pathlib import Path
import tempfile,hashlib,contextlib,io
from unittest.mock import patch
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
 def resume_fixture(self):
  first,_=self.fixture();second=copy.deepcopy(first);second['revision']='custoking-identity-service-dev-old-two'
  services=[{'service':'custoking-'+s+'-dev','latestReady':'custoking-'+s+'-dev-current','latestCreated':'custoking-'+s+'-dev-current','activeRollouts':[],'revisions':[first,second] if s=='identity-service' else []} for s in m.ROLES]
  approved=[(services[0]['service'],first),(services[0]['service'],second)]
  checkpoint={'project':m.PROJECT,'region':m.REGION,'mode':'APPLY','approvedInventorySha256':'c'*64,'preflightPassed':True,'completed':False,'services':copy.deepcopy(services),'deletedRevisions':[first['revision']],'recoveredAbsentRevisions':[],'pendingRevision':None}
  current=copy.deepcopy(services);current[0]['revisions']=[second]
  return approved,checkpoint,current
 def test_interrupted_resume_requires_current_absence_and_same_seven_pins(self):
  approved,checkpoint,current=self.resume_fixture()
  self.assertEqual(m.validate_resume(checkpoint,'c'*64,approved,current),([approved[0][1]['revision']],[]))
  for kind in ['present','latest','active','digest','scope','duplicate','unknown','unclaimed-absence','changed-remaining']:
   with self.subTest(kind=kind):
    bad=copy.deepcopy(checkpoint);state=copy.deepcopy(current)
    if kind=='present':state[0]['revisions'].append(approved[0][1])
    if kind=='latest':state[-1]['latestCreated']='concurrent-release'
    if kind=='active':state[-1]['activeRollouts']=[{'state':'IN_PROGRESS'}]
    if kind=='digest':bad['approvedInventorySha256']='d'*64
    if kind=='scope':bad['project']='custoking-prod'
    if kind=='duplicate':bad['deletedRevisions']*=2
    if kind=='unknown':bad['pendingRevision']='custoking-identity-service-dev-not-reviewed'
    if kind=='unclaimed-absence':bad['deletedRevisions']=[]
    if kind=='changed-remaining':state[0]['revisions'][0]['specSha256']='d'*64
    with self.assertRaises(ValueError):m.validate_resume(bad,'c'*64,approved,state)
 def test_timed_out_attempt_is_observed_absence_not_invented_cli_success(self):
  approved,checkpoint,current=self.resume_fixture();checkpoint['deletedRevisions']=[];checkpoint['pendingRevision']=approved[0][1]['revision']
  self.assertEqual(m.validate_resume(checkpoint,'c'*64,approved,current),([],[approved[0][1]['revision']]))
  current[0]['revisions'].append(approved[0][1])
  self.assertEqual(m.validate_resume(checkpoint,'c'*64,approved,current),([],[]))
 def test_final_independent_absence_refuses_present_or_changed_pins(self):
  approved,checkpoint,current=self.resume_fixture();current[0]['revisions']=[]
  baseline={s['service']:s for s in checkpoint['services']}
  verified=m.verify_final_absence(current,baseline,approved)
  self.assertEqual(verified['independentlyAbsentCount'],2);self.assertFalse(verified['continuousReleaseFreezeCertified']);self.assertFalse(verified['physicalDrainCertified'])
  current[0]['revisions']=[approved[1][1]]
  with self.assertRaises(ValueError):m.verify_final_absence(current,baseline,approved)
  current[0]['revisions']=[];current[-1]['latestCreated']='concurrent-release'
  with self.assertRaises(ValueError):m.verify_final_absence(current,baseline,approved)
 def test_atomic_checkpoint_failure_keeps_prior_progress_and_removes_owned_temporary(self):
  with tempfile.TemporaryDirectory() as directory:
   path=Path(directory)/'progress.json';path.write_text('{"previous":true}')
   with patch.object(m.os,'replace',side_effect=OSError('simulated interruption')):
    with self.assertRaises(OSError):m.save_progress(path,{'next':True})
   self.assertEqual(json.loads(path.read_text()),{'previous':True});self.assertEqual(list(Path(directory).iterdir()),[path])
 def test_partial_apply_resumes_without_redeleting_or_bypassing_approval(self):
  approved,checkpoint,_=self.resume_fixture();state=copy.deepcopy(checkpoint['services']);calls=[]
  inventory={'project':m.PROJECT,'region':m.REGION,'mode':'READ_ONLY_DELETION_DRY_RUN','services':copy.deepcopy(state)}
  with tempfile.TemporaryDirectory() as directory:
   base=Path(directory);inv=base/'inventory.json';inv.write_text(json.dumps(inventory));digest=hashlib.sha256(inv.read_bytes()).hexdigest();first=base/'first.json';second=base/'second.json'
   def collect(item):return copy.deepcopy(next(s for s in state if s['service']=='custoking-'+item[0]+'-dev'))
   def delete(command,**kwargs):
    self.assertIn('--project=custoking-dev',command);self.assertIn('--region=asia-south2',command);calls.append(command[4])
    if len(calls)==2:raise m.subprocess.TimeoutExpired(command,120)
    state[0]['revisions']=[r for r in state[0]['revisions'] if r['revision']!=command[4]]
    return m.subprocess.CompletedProcess(command,0)
   argv=['cleanup','--inventory',str(inv),'--output',str(first),'--apply','--approved-inventory-sha256',digest]
   with patch.object(m,'collect',side_effect=collect),patch.object(m.subprocess,'run',side_effect=delete),patch('sys.argv',argv),contextlib.redirect_stdout(io.StringIO()):
    with self.assertRaises(m.subprocess.TimeoutExpired):m.main()
   progress=json.loads(first.read_text());self.assertFalse(progress['completed']);self.assertEqual(progress['deletedRevisions'],[approved[0][1]['revision']]);self.assertEqual(progress['pendingRevision'],approved[1][1]['revision'])
   resume=['cleanup','--inventory',str(inv),'--output',str(second),'--resume-checkpoint',str(first),'--apply','--approved-inventory-sha256',digest]
   with patch.object(m,'collect',side_effect=collect),patch.object(m.subprocess,'run',side_effect=delete),patch('sys.argv',resume),contextlib.redirect_stdout(io.StringIO()):m.main()
   final=json.loads(second.read_text());self.assertTrue(final['completed']);self.assertEqual(final['remainingCandidateCount'],0);self.assertEqual(final['finalAbsenceVerification']['independentlyAbsentCount'],2);self.assertEqual(calls,[approved[0][1]['revision'],approved[1][1]['revision'],approved[1][1]['revision']]);self.assertEqual(json.loads(first.read_text()),progress)
   invalid=resume[:-1]+['0'*64]
   with patch.object(m,'collect',side_effect=AssertionError('must fail before cloud reads')),patch('sys.argv',invalid):
    with self.assertRaises(ValueError):m.main()
if __name__=='__main__':unittest.main()
