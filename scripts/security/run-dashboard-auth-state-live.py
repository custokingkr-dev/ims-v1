import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
REGION='asia-south2'
import json,pathlib,secrets,runpy,base64,subprocess,time,datetime,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud'];sa='ims-dashboard@custoking-dev.iam.gserviceaccount.com';secretName='dashboard-session-secret'
cloud(['secrets','create',secretName,'--replication-policy=automatic','--quiet','--format=json'])
result=subprocess.run(['gcloud.cmd','secrets','versions','add',secretName,'--data-file=-','--project=custoking-dev','--quiet','--format=json'],input=secrets.token_urlsafe(48),capture_output=True,text=True,timeout=60)
if result.returncode:raise RuntimeError('Managed session secret creation failed')
cloud(['secrets','add-iam-policy-binding',secretName,'--member=serviceAccount:'+sa,'--role=roles/secretmanager.secretAccessor','--quiet','--format=json'])
def uri(source):return 'data:text/javascript;base64,'+base64.b64encode(source.encode()).decode()
directory=ROOT.parent/'tools/live-dashboard';auth=(directory/'auth.mjs').read_text()
for dependency in ('security-config.mjs','security-state.mjs','query-budget.mjs'):auth=auth.replace('./'+dependency,uri((directory/dependency).read_text()))
state=uri((directory/'security-state.mjs').read_text());authUri=uri(auth)
code=f"const A=await import({json.dumps(authUri+'#replicaA')});const B=await import({json.dumps(authUri+'#replicaB')});const {{firestoreSecurityState}}=await import({json.dumps(state)});"+r'''
const token=async()=>{const r=await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token',{headers:{'Metadata-Flavor':'Google'},signal:AbortSignal.timeout(10000)});const j=await r.json();if(!r.ok)throw Error('Metadata token unavailable');return j.access_token;};
for(const auth of [A,B])auth.configureSecurityState(firestoreSecurityState({project:'custoking-dev',database:'ims-dashboard-dev',accessToken:token}));
const check=(name,passed)=>{console.log(JSON.stringify({marker:'SEC-ACPT-20261007-DASHBOARD-AUTH',check:name,passed}));if(!passed)throw Error('Dashboard auth state invariant failed');};
const flow=A.beginAuthorization('/ops');const cookie=flow.cookie.split(';')[0];check('first-instance-authorization-consumed',!!await A.consumeAuthorizationAsync(cookie,flow.state));let rejected=false;try{await B.consumeAuthorizationAsync(cookie,flow.state)}catch{rejected=true}check('second-instance-oauth-replay-rejected',rejected);
const session=A.makeSession('synthetic-dashboard@security-fixture.invalid');check('second-instance-session-valid',await B.readSessionAsync('ck_session='+session)==='synthetic-dashboard@security-fixture.invalid');await A.revokeSessionAsync('ck_session='+session);check('second-instance-logout-revocation',await B.readSessionAsync('ck_session='+session)===null);
const expiring=A.makeSession('synthetic-dashboard@security-fixture.invalid');const realNow=Date.now;Date.now=()=>realNow()+13*60*60*1000;try{check('expired-cookie-rejected-independent-of-ttl-cleanup',await B.readSessionAsync('ck_session='+expiring)===null)}finally{Date.now=realNow}
console.log(JSON.stringify({marker:'SEC-ACPT-20261007-DASHBOARD-AUTH',complete:true}));
'''
service=cloud(['run','services','describe','custoking-api-gateway-dev','--region='+REGION,'--format=json']);revision=cloud(['run','revisions','describe',service['status']['latestReadyRevisionName'],'--region='+REGION,'--format=json']);image=revision['status']['imageDigest'];job='ims-dev-dashboard-auth-state-'+secrets.token_hex(8)
env=[dict(name=k,value=v)for k,v in dict(DASHBOARD_AUTH='on',DASHBOARD_PUBLIC_URL='https://dashboard-acceptance.security-fixture.invalid',DASHBOARD_PROJECT='custoking-dev',DASHBOARD_STATE_DATABASE='ims-dashboard-dev',DASHBOARD_ALLOWED_EMAILS='synthetic-dashboard@security-fixture.invalid').items()]+[dict(name='SESSION_SECRET',valueFrom=dict(secretKeyRef=dict(name=secretName,key='1')))]
manifest=dict(apiVersion='run.googleapis.com/v1',kind='Job',metadata=dict(name=job),spec=dict(template=dict(spec=dict(taskCount=1,template=dict(spec=dict(serviceAccountName=sa,maxRetries=0,timeoutSeconds=90,containers=[dict(image=image,command=['node'],args=['--input-type=module','-e',code],env=env,resources=dict(limits=dict(cpu='1',memory='512Mi')))]))))));path=ROOT/'dashboard-auth-probe.json';path.write_text(json.dumps(manifest))
try:
 cloud(['run','jobs','replace',str(path),'--region='+REGION,'--quiet','--format=json']);result=cloud(['run','jobs','execute',job,'--region='+REGION,'--wait','--quiet','--format=json'])
 if not any(c.get('type')=='Completed'and c.get('status')=='True'for c in result.get('status',{}).get('conditions',[])):raise RuntimeError('Dashboard auth wrapper probe failed')
 for attempt in range(6):
  logs=cloud(['logging','read',f'resource.type="cloud_run_job" AND resource.labels.job_name="{job}"','--limit=30','--format=json']);rows=[x['jsonPayload']for x in logs if x.get('jsonPayload',{}).get('marker')=='SEC-ACPT-20261007-DASHBOARD-AUTH']
  if any(x.get('complete')for x in rows):break
  time.sleep(3)
 if not any(x.get('complete')for x in rows):raise RuntimeError('Dashboard auth completion evidence missing')
 proof=dict(project='custoking-dev',checks=rows,passed=all(x.get('passed',True)for x in rows),wrapperSourceSha256=hashlib.sha256((directory/'auth.mjs').read_bytes()).hexdigest(),replicas='Two independent module state instances in one CloudRun job; separate CloudRun replica persistence demonstrated by companion probe',sessionSecretManaged=True,oauthProviderTested=False,expiryMethod='Controlled clock advance13hours; real TTL deletion not observed',checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(ROOT/'dashboard-auth-state-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
finally:cloud(['run','jobs','delete',job,'--region='+REGION,'--quiet','--format=json'])
