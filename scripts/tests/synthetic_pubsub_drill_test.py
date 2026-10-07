import base64, importlib.util, json, pathlib, tempfile, unittest
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('drill',ROOT/'scripts/security/dev-synthetic-pubsub-drill.py'); d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
RUN='20261007095500-aabbccdd'
class SyntheticDrillTest(unittest.TestCase):
 def test_only_fixed_marker_student_and_school_pairs(self):
  for i in (0,1):self.assertEqual(d.validate_event(d.event(RUN,i),RUN)['schoolId'],d.SCHOOLS[i])
  bad=d.event(RUN);bad['schoolId']=123
  with self.assertRaises(d.SafeFailure):d.validate_event(bad,RUN)
  bad=d.event(RUN);bad['payload']['phone']='real-contact'
  with self.assertRaises(d.SafeFailure):d.validate_event(bad,RUN)
  with self.assertRaises(d.SafeFailure):d.event('unsafe;id')
 def test_deadletter_owned_bytes_and_wrapper_only(self):
  value=d.event(RUN,1);encoded=base64.b64encode(json.dumps(value).encode()).decode()
  self.assertEqual(d.decode_owned({'data':encoded},RUN),value)
  wrapped=base64.b64encode(json.dumps({'data':encoded}).encode()).decode()
  self.assertEqual(d.decode_owned({'data':wrapped},RUN),value)
  value['eventId']='real-event'
  with self.assertRaises(d.SafeFailure):d.decode_owned({'data':base64.b64encode(json.dumps(value).encode()).decode()},RUN)
 def test_shared_inspection_queue_cannot_be_pulled_or_acknowledged(self):
  with tempfile.TemporaryDirectory() as tmp:
   drill=d.Drill(RUN,pathlib.Path(tmp)/'proof.json')
   with patch.object(d,'cloud',side_effect=AssertionError('unexpected auth')):
    for action in ('pull','acknowledge'):
     with self.assertRaises(d.SafeFailure):drill.api('projects/custoking-dev/subscriptions/reporting-dead-letter-inspection-dev:'+action,{})
 def test_publish_refuses_other_topic_before_network(self):
  with tempfile.TemporaryDirectory() as tmp:
   drill=d.Drill(RUN,pathlib.Path(tmp)/'proof.json')
   with patch.object(drill,'api',side_effect=AssertionError('unexpected network')):
    with self.assertRaises(d.SafeFailure):drill.publish('ims-notifications-events-v1-dev',d.event(RUN))
 def test_cleanup_deletes_only_exact_nonce_owned_resources(self):
  with tempfile.TemporaryDirectory() as tmp:
   drill=d.Drill(RUN,pathlib.Path(tmp)/'proof.json');drill.owned=[('topics',drill.prefix+'-source'),('subscriptions',drill.prefix+'-push')]
   calls=[]
   with patch.object(d,'cloud',side_effect=lambda args:calls.append(args) or ''):drill.cleanup()
   self.assertEqual(len(calls),2);self.assertTrue(all(drill.prefix in args[3] for args in calls))
   drill.owned=[('topics',d.SOURCE)]
   with patch.object(d,'cloud',side_effect=AssertionError('unexpected delete')):
    with self.assertRaises(d.SafeFailure):drill.cleanup()
 def test_api_redirect_rejected(self):
  with self.assertRaises(d.SafeFailure):d.NoRedirect().redirect_request(None,None,302,'',{},'https://attacker.invalid')
 def test_real_gcloud_always_explicit_dev_and_secret_errors_are_sanitized(self):
  with patch.object(d.subprocess,'run') as run:
   run.return_value.returncode=1;run.return_value.stderr='password=do-not-print';run.return_value.stdout=''
   with self.assertRaisesRegex(d.SafeFailure,'^CLOUD_COMMAND_FAILED:pubsub:topics:describe$'):d.cloud(['pubsub','topics','describe','name'])
   self.assertIn('--project=custoking-dev',run.call_args.args[0]);self.assertEqual(run.call_args.kwargs['timeout'],60)
class RestartDrillTest(unittest.TestCase):
 def fixture(self,tmp):
  path=pathlib.Path(tmp);prior={'project':d.PROJECT,'marker':d.MARKER,'run':RUN,**{k:True for k in ('deliveryVerified','duplicateVerified','replayVerified','cleanupVerified')}}
  baseline={'project':d.PROJECT,'service':'custoking-platform-service-dev','latestReadyRevisionName':'custoking-platform-service-dev-old'}
  (path/'prior.json').write_text(json.dumps(prior));(path/'baseline.json').write_text(json.dumps(baseline))
  spec=importlib.util.spec_from_file_location('restart',ROOT/'scripts/security/verify-dev-pubsub-restart.py');restart=importlib.util.module_from_spec(spec);spec.loader.exec_module(restart)
  argv=['script','--apply','--delivery-proof',str(path/'prior.json'),'--baseline-proof',str(path/'baseline.json'),'--output',str(path/'result.json')]
  return restart,argv,path
 def test_old_revision_cannot_publish(self):
  with tempfile.TemporaryDirectory() as tmp:
   restart,argv,path=self.fixture(tmp)
   service={'status':{'latestReadyRevisionName':'custoking-platform-service-dev-old','latestCreatedRevisionName':'custoking-platform-service-dev-old'}}
   with patch('sys.argv',argv),patch.object(restart.d,'cloud',return_value=json.dumps(service)),patch.object(restart.d.Drill,'publish',side_effect=AssertionError('unexpected publish')):
    self.assertEqual(restart.main(),1)
   self.assertEqual(json.loads((path/'result.json').read_text())['failure'],'FRESH_READY_REVISION_REQUIRED')
 def test_extra_db_identifier_rejected_before_owner_job(self):
  with tempfile.TemporaryDirectory() as tmp:
   drill=d.Drill(RUN,pathlib.Path(tmp)/'proof.json')
   with patch.object(d,'cloud',side_effect=AssertionError('unexpected job')):
    with self.assertRaises(d.SafeFailure):drill.verify('restart',('real-business-event',))
 def test_new_revision_duplicate_and_late_tombstone_proof(self):
  with tempfile.TemporaryDirectory() as tmp:
   restart,argv,path=self.fixture(tmp)
   service={'status':{'latestReadyRevisionName':'custoking-platform-service-dev-new','latestCreatedRevisionName':'custoking-platform-service-dev-new','conditions':[{'type':'Ready','status':'True'}],'traffic':[{'revisionName':'custoking-platform-service-dev-new','percent':100}]}}
   target={'Target':{'deployParameters':{'db_host':'10.92.0.3:5432'}}}
   before={'marker':d.MARKER,'inboxRows':4,'processed':4,'projectionRows':0,'tombstones':2};after=dict(before,inboxRows=5,processed=5)
   with patch('sys.argv',argv),patch.object(restart.d,'cloud',side_effect=[json.dumps(service),json.dumps(target)]),patch.object(restart.d.Drill,'verify',side_effect=[before,after]),patch.object(restart.d.Drill,'publish') as publish,patch.object(restart.d.Drill,'api',return_value={'messageIds':['synthetic-message']}) as api,patch.object(restart.time,'sleep'):
    self.assertEqual(restart.main(),0)
   self.assertEqual(publish.call_count,1);self.assertEqual(api.call_count,1)
   proof=json.loads((path/'result.json').read_text());self.assertTrue(proof['restartVerified']);self.assertIn('Not an in-flight crash',proof['scope'])
if __name__=='__main__':unittest.main()
