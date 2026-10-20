import importlib.util,json,pathlib,tempfile,threading,time,types,unittest,urllib.request
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from unittest.mock import patch
SPEC=importlib.util.spec_from_file_location('final_live',pathlib.Path(__file__).resolve().parents[1]/'security/final-live-fixture-acceptance.py')
M=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(M)
class FinalLiveFixtureAcceptanceTest(unittest.TestCase):
 def test_http_redirect_does_not_forward_bearer_and_body_limits_cover_error_responses(self):
  reached=[]
  class Handler(BaseHTTPRequestHandler):
   def log_message(self,*args):pass
   def do_GET(self):
    if self.path=='/redirect':self.send_response(302);self.send_header('Location','/destination');self.end_headers();return
    if self.path=='/destination':reached.append(self.headers.get('Authorization'))
    self.send_response(403 if self.path=='/error' else 200);self.end_headers()
    try:
     if self.path=='/trickle':
      for _ in range(50):self.wfile.write(b' ');self.wfile.flush();time.sleep(.03)
     else:self.wfile.write(json.dumps({'synthetic':True}).encode())
    except OSError:pass
  server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True;worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start();origin=f'http://127.0.0.1:{server.server_port}'
  def request(path):return urllib.request.Request(origin+path,headers={'Authorization':'Bearer CONTROLLED_FIXTURE_ONLY'})
  try:
   self.assertEqual((200,{'synthetic':True}),M.bounded_request(request('/ok')))
   self.assertEqual((403,{'synthetic':True}),M.bounded_request(request('/error')))
   with self.assertRaisesRegex(M.SafeFailure,'HTTP_REDIRECT_REFUSED'):M.bounded_request(request('/redirect'))
   self.assertEqual([],reached)
   with self.assertRaisesRegex(M.SafeFailure,'HTTP_RESPONSE_CEILING'):M.bounded_request(request('/error'),cap=5)
   started=time.monotonic()
   with self.assertRaises(M.SafeFailure):M.bounded_request(request('/trickle'),deadline_seconds=.15)
   self.assertLess(time.monotonic()-started,1)
  finally:server.shutdown();server.server_close();worker.join(timeout=1)
 def test_owner_job_requires_completed_and_independent_absence(self):
  with tempfile.TemporaryDirectory() as tmp:
   calls=[]
   def cloud(args,*_):
    calls.append(args)
    if args[:3]==['run','jobs','execute']:return json.dumps({'status':{'conditions':[{'type':'Completed','status':'False'}]}})
    return '{}'
   absent=types.SimpleNamespace(returncode=1,stdout='CONTROLLED_SECRET',stderr='NOT_FOUND')
   with patch.object(M,'TMP',pathlib.Path(tmp)),patch.object(M,'cloud',side_effect=cloud),patch.object(M.subprocess,'run',return_value=absent) as run:
    with self.assertRaisesRegex(M.SafeFailure,'OWNER_JOB_NOT_COMPLETED'):M.owner_sql('controlled','SELECT 1;')
    self.assertTrue(any(args[:3]==['run','jobs','delete'] for args in calls))
    self.assertIn('--project=custoking-dev',run.call_args.args[0])
    proof=json.loads((pathlib.Path(tmp)/'security-final-owned-jobs-cleanup.json').read_text());self.assertTrue(proof['jobs'][0]['independentlyAbsent'])
 def test_owner_cleanup_denies_unknown_describe_failure(self):
  with tempfile.TemporaryDirectory() as tmp:
   def cloud(args,*_):
    if args[:3]==['run','jobs','execute']:return json.dumps({'status':{'conditions':[{'type':'Completed','status':'True'}]}})
    if args[:2]==['logging','read']:return json.dumps([{'jsonPayload':{'marker':M.MARKER,'synthetic':True}}])
    return '{}'
   with patch.object(M,'TMP',pathlib.Path(tmp)),patch.object(M,'cloud',side_effect=cloud),patch.object(M.subprocess,'run',return_value=types.SimpleNamespace(returncode=1,stdout='CONTROLLED_SECRET',stderr='PERMISSION_DENIED')):
    with self.assertRaisesRegex(M.SafeFailure,'OWNED_TEMP_JOB_ABSENCE_NOT_ESTABLISHED'):M.owner_sql('controlled','SELECT 1;')
 def test_cloud_errors_withhold_partial_secret_output(self):
  with patch.object(M.subprocess,'run',return_value=types.SimpleNamespace(returncode=1,stdout='CONTROLLED_SECRET',stderr='CONTROLLED_SECRET')):
   with self.assertRaises(M.SafeFailure) as failure:M.cloud(['run','services','describe','synthetic'])
   self.assertNotIn('CONTROLLED_SECRET',str(failure.exception))
if __name__=='__main__':unittest.main()
