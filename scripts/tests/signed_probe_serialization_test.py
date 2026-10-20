"""Real PS5 dry-run renders plain JavaScript, bounded and isolated from cloud mutation."""
import json,os,shutil,subprocess,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
class SignedProbeSerializationTest(unittest.TestCase):
 def test_real_powershell5_six_serving_revisions_render_small_plain_string_job(self):
  ps=shutil.which('powershell.exe')
  if not ps:self.skipTest('Actual Windows PowerShell5 regression requires powershell.exe')
  with tempfile.TemporaryDirectory(prefix='ims-signed-serialization-') as directory:
   folder=Path(directory);receipt=folder/'calls.jsonl';fixture=folder/'gcloud.py'
   fixture.write_text('''import json,os,sys
from pathlib import Path
a=sys.argv[1:]
with open(os.environ['PROBE_RECEIPT'],'a') as f:f.write(json.dumps(a)+'\\n')
if '--project=custoking-dev' not in a or a[:2]!=['run','services'] and a[:2]!=['run','revisions'] or a[2]!='describe':sys.exit(99)
name=a[3];service=name.removeprefix('custoking-').split('-dev-')[0].removesuffix('-dev');revision='custoking-'+service+'-dev-mock1';stem=service.removesuffix('-service')
if a[:2]==['run','services']:print(json.dumps({'status':{'url':'https://custoking-'+service+'-dev-controlled-em.a.run.app','latestReadyRevisionName':revision,'latestCreatedRevisionName':revision,'traffic':[{'revisionName':revision,'percent':100}]}}))
else:
 role='ims_'+stem.replace('-','_')+'_rt'
 env=[{'name':'RUNTIME_DB_ROLE','value':role},{'name':'SPRING_DATASOURCE_USERNAME','value':role},{'name':'APP_MIGRATIONS_ENABLED','value':'false'},{'name':'SPRING_DATASOURCE_PASSWORD','valueFrom':{'secretKeyRef':{'name':stem+'-runtime-db-password-dev','key':'latest'}}}]
 print(json.dumps({'metadata':{'name':revision},'status':{'conditions':[{'type':'Ready','status':'True'}]},'spec':{'serviceAccountName':'ims-'+stem+'-dev@custoking-dev.iam.gserviceaccount.com','containers':[{'image':'asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-api-gateway@sha256:'+'a'*64,'env':env}]}}))
''')
   (folder/'gcloud.cmd').write_text(f'@echo off\n"{os.sys.executable}" "{fixture}" %*\nexit /b %errorlevel%\n')
   environment=os.environ.copy();environment.update(PATH=str(folder)+os.pathsep+environment['PATH'],PROBE_RECEIPT=str(receipt))
   result=subprocess.run([ps,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/security/dev-signed-transport-probe.ps1'),'-OutputDirectory',str(folder/'output')],env=environment,capture_output=True,text=True,timeout=30)
   self.assertEqual(0,result.returncode,result.stderr)
   plan=json.loads(result.stdout);self.assertFalse(plan['execute'])
   calls=[json.loads(line) for line in receipt.read_text().splitlines()];self.assertEqual(12,len(calls))
   self.assertTrue(all(call[2]=='describe' for call in calls))
   job=Path(plan['jobFile']);self.assertLess(job.stat().st_size,65536)
   payload=json.loads(job.read_text(encoding='utf-8-sig'));container=payload['spec']['template']['spec']['template']['spec']['containers'][0]
   self.assertEqual(['node'],container['command']);self.assertEqual('-e',container['args'][0])
   self.assertIsInstance(container['args'][1],str)
   self.assertEqual((ROOT/'scripts/security/dev-signed-transport-probe.js').read_text(),container['args'][1].replace('\r\n','\n'))
   self.assertNotIn('PSPath',job.read_text(encoding='utf-8-sig'))
   targets=json.loads(container['env'][0]['value']);self.assertEqual(5,len(targets))
if __name__=='__main__':unittest.main()
