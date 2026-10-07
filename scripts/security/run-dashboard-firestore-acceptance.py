import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
REGION='asia-south2'
import json,pathlib,secrets,runpy,hashlib,time,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud']
service=cloud(['run','services','describe','custoking-api-gateway-dev','--region='+REGION,'--format=json']);revision=cloud(['run','revisions','describe',service['status']['latestReadyRevisionName'],'--region='+REGION,'--format=json']);image=revision['status']['imageDigest']
if not image.startswith('asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-api-gateway@sha256:'): raise RuntimeError('Unexpected probe image')
source=(ROOT.parent/'tools/live-dashboard/security-state.mjs').read_text();nonce='SEC-ACPT-20261007-'+secrets.token_hex(12);rows=[]
for phase in ('A','B'):
 code=source+"\n"+f"const phase={json.dumps(phase)},nonce={json.dumps(nonce)};"+r'''
const token=async()=>{const r=await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token',{headers:{'Metadata-Flavor':'Google'},signal:AbortSignal.timeout(10000)});if(!r.ok)throw Error('Metadata authorization unavailable');const text=await r.text();if(text.length>65536)throw Error('Metadata ceiling');return JSON.parse(text).access_token;};
const state=firestoreSecurityState({project:'custoking-dev',database:'ims-dashboard-dev',accessToken:token});
const check=(name,passed)=>{console.log(JSON.stringify({marker:'SEC-ACPT-20261007-DASHBOARD',phase,check:name,passed}));if(!passed)throw Error('Controlled state invariant failed');};
check('cross-instance-atomic-replay-claim',await state.claim('oauth',nonce,Date.now()+600000)===(phase==='A'));
if(phase==='A')check('revocation-created',await state.claim('revoked',nonce,Date.now()+600000));
else check('cross-instance-revocation-observed',await state.contains('revoked',nonce));
for(const database of ['ims-dashboard-acceptance-denied']){const url='https://firestore.googleapis.com/v1/projects/custoking-dev/databases/'+database+'/documents/dashboardSecurityState/nonexistent';const r=await fetch(url,{headers:{Authorization:'Bearer '+await token()},redirect:'error',signal:AbortSignal.timeout(10000)});console.log(JSON.stringify({marker:'SEC-ACPT-20261007-DASHBOARD',phase,check:'foreign-database-http-status',status:r.status,passed:r.status===403}));check('foreign-existing-database-denied',r.status===403);await r.body?.cancel();}
const url='https://firestore.googleapis.com/v1/projects/custoking-dev/databases/ims-dashboard-dev/documents/dashboardSecurityState';
const forbidden=await fetch(url,{headers:{Authorization:'Bearer '+await token()},redirect:'error',signal:AbortSignal.timeout(10000)});check('list-not-granted',forbidden.status===403);await forbidden.body?.cancel();
console.log(JSON.stringify({marker:'SEC-ACPT-20261007-DASHBOARD',phase,complete:true}));
'''
 job='ims-dev-dashboard-state-'+phase.lower()+'-'+secrets.token_hex(8)
 manifest=dict(apiVersion='run.googleapis.com/v1',kind='Job',metadata=dict(name=job),spec=dict(template=dict(spec=dict(taskCount=1,template=dict(spec=dict(serviceAccountName='ims-dashboard@custoking-dev.iam.gserviceaccount.com',maxRetries=0,timeoutSeconds=90,containers=[dict(image=image,command=['node'],args=['--input-type=module','-e',code],resources=dict(limits=dict(cpu='1',memory='512Mi')))]))))))
 path=ROOT/f'dashboard-state-probe-{phase}.json';path.write_text(json.dumps(manifest))
 try:
  cloud(['run','jobs','replace',str(path),'--region='+REGION,'--quiet','--format=json']);result=cloud(['run','jobs','execute',job,'--region='+REGION,'--wait','--quiet','--format=json'])
  if not any(c.get('type')=='Completed' and c.get('status')=='True' for c in result.get('status',{}).get('conditions',[])): raise RuntimeError('Dashboard state probe failed phase '+phase)
  for attempt in range(6):
   logs=cloud(['logging','read',f'resource.type="cloud_run_job" AND resource.labels.job_name="{job}"','--limit=30','--format=json']);proof=[e['jsonPayload'] for e in logs if e.get('jsonPayload',{}).get('marker')=='SEC-ACPT-20261007-DASHBOARD']
   if any(p.get('complete') for p in proof): break
   time.sleep(3)
  if not any(p.get('complete') for p in proof): raise RuntimeError('Probe completion evidence missing')
  rows.extend(proof)
 finally: cloud(['run','jobs','delete',job,'--region='+REGION,'--quiet','--format=json'])
proof=dict(project='custoking-dev',database='ims-dashboard-dev',sourceSha256=hashlib.sha256(source.encode()).hexdigest(),probeImage=image,independentCloudRunInstances=2,checks=rows,passed=all(p.get('passed',True) for p in rows),checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),webOAuthTested=False,asyncTtlCleanupObserved=False)
(ROOT/'dashboard-firestore-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
