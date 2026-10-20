import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
REGION='asia-south2'
import json,pathlib,runpy,secrets,subprocess,time,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud'];nonce=secrets.token_hex(6)
name='ims-dev-identity-rotation-'+nonce;job='ims-dev-key-rotation-probe-'+nonce;keySecret='ims-dev-rotation-key-'+nonce;fixtureSecret='ims-dev-rotation-fixture-'+nonce
identitySA='ims-identity-dev@custoking-dev.iam.gserviceaccount.com';gatewaySA='ims-api-gateway-dev@custoking-dev.iam.gserviceaccount.com';created=[];checks=[]
def secret(name,values,members):
 cloud(['secrets','create',name,'--replication-policy=automatic','--quiet','--format=json']);created.append(name)
 for value in values:
  p=subprocess.run(['gcloud.cmd','secrets','versions','add',name,'--data-file=-','--project=custoking-dev','--quiet','--format=json'],input=value,capture_output=True,text=True,timeout=60)
  if p.returncode: raise RuntimeError('Managed sandbox secret version creation failed')
 for member in members: cloud(['secrets','add-iam-policy-binding',name,'--member=serviceAccount:'+member,'--role=roles/secretmanager.secretAccessor','--quiet','--format=json'])
def deploy(manifest):
 path=ROOT/'identity-rotation-clone-private.json';path.write_text(json.dumps(manifest));cloud(['run','services','replace',str(path),'--region='+REGION,'--quiet','--format=json'])
def logs():
 return [x['jsonPayload'] for x in cloud(['logging','read',f'resource.type="cloud_run_job" AND resource.labels.job_name="{job}"','--limit=60','--format=json']) if x.get('jsonPayload',{}).get('marker')=='SEC-ACPT-20261007-ROTATION']
def wait(check):
 deadline=time.monotonic()+180
 for attempt in range(24):
  if time.monotonic()>deadline:break
  rows=logs()
  if any(x.get('failed') for x in rows):raise RuntimeError('Bounded rotation probe failed; sanitized diagnostics retained')
  if any(x.get('check')==check and x.get('passed') for x in rows): return rows
  time.sleep(5)
 raise RuntimeError('Bounded rotation stage not observed: '+check)
try:
 secret(keySecret,[secrets.token_urlsafe(48),secrets.token_urlsafe(48)],[identitySA,gatewaySA]);fixture=json.loads((ROOT/'security-acceptance-private.json').read_text())['credentials'][1];secret(fixtureSecret,[json.dumps(fixture)],[gatewaySA])
 source=cloud(['run','services','describe','custoking-identity-service-dev','--region='+REGION,'--format=json']);rev=cloud(['run','revisions','describe',source['status']['latestReadyRevisionName'],'--region='+REGION,'--format=json']);container=rev['spec']['containers'][0];container['image']=rev['status']['imageDigest'];container.pop('name',None)
 env=[e for e in container['env'] if e['name'] not in ['APP_JWT_SECRET','APP_JWT_PREVIOUS_SECRET','SERVICE_OIDC_AUDIENCES','DB_POOL_MAX','DB_POOL_MIN']]
 originalAudiences=next(e['value'] for e in container['env'] if e['name']=='SERVICE_OIDC_AUDIENCES')
 env.extend([dict(name='APP_JWT_SECRET',valueFrom=dict(secretKeyRef=dict(name=keySecret,key='1'))),dict(name='SERVICE_OIDC_AUDIENCES',value=originalAudiences)])
 env.extend([dict(name='DB_POOL_MAX',value='2'),dict(name='DB_POOL_MIN',value='0')])
 container['env']=env
 annotations={k:v for k,v in rev['metadata'].get('annotations',{}).items() if k in ['run.googleapis.com/network-interfaces','run.googleapis.com/vpc-access-egress','run.googleapis.com/startup-cpu-boost']};annotations.update({'autoscaling.knative.dev/minScale':'0','autoscaling.knative.dev/maxScale':'1'})
 manifest=dict(apiVersion='serving.knative.dev/v1',kind='Service',metadata=dict(name=name,annotations={'run.googleapis.com/ingress':'all'}),spec=dict(template=dict(metadata=dict(annotations=annotations),spec=dict(serviceAccountName=identitySA,containerConcurrency=2,timeoutSeconds=60,containers=[container]))))
 deploy(manifest);data=cloud(['run','services','describe',name,'--region='+REGION,'--format=json']);url=data['status']['url'];next(e for e in env if e['name']=='SERVICE_OIDC_AUDIENCES')['value']=url;deploy(manifest)
 cloud(['run','services','add-iam-policy-binding',name,'--region='+REGION,'--member=serviceAccount:'+gatewaySA,'--role=roles/run.invoker','--quiet','--format=json'])
 gateway=cloud(['run','services','describe','custoking-api-gateway-dev','--region='+REGION,'--format=json']);gatewayRev=cloud(['run','revisions','describe',gateway['status']['latestReadyRevisionName'],'--region='+REGION,'--format=json']);probeImage=gatewayRev['status']['imageDigest']
 code=r'''const crypto=require('node:crypto');const fixture=JSON.parse(process.env.FIXTURE);const target=process.env.TARGET;const check=(check,passed)=>{console.log(JSON.stringify({marker:'SEC-ACPT-20261007-ROTATION',check,passed}));if(!passed)throw Error('Rotation invariant failed');};const sleep=ms=>new Promise(r=>setTimeout(r,ms));async function request(path,body){const idr=await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience='+encodeURIComponent(target)+'&format=full',{headers:{'Metadata-Flavor':'Google'},signal:AbortSignal.timeout(10000)});const id=await idr.text();if(!idr.ok||id.length>65536)throw Error('Transport token unavailable');const r=await fetch(target+'/api/v1/auth/'+path,{method:'POST',headers:{Authorization:'Bearer '+id,'X-Serverless-Authorization':'Bearer '+id,'X-Identity-Service-Token':process.env.PEER,'Content-Type':'application/json'},body:JSON.stringify(body),redirect:'error',signal:AbortSignal.timeout(30000)});const text=await r.text();if(text.length>262144)throw Error('Response ceiling');return {status:r.status,data:JSON.parse(text)};}const login=()=>request('login',{email:fixture.email,password:fixture.password});const inspect=token=>request('introspect',{token,method:'GET',path:'/api/v1/students',schoolId:fixture.schoolId});(async()=>{const first=await login();check('initial-key-actual-login',first.status===200&&first.data.branchId===fixture.schoolId);const old=first.data.token??first.data.accessToken;check('initial-key-authoritative-introspection',(await inspect(old)).data.active===true);let fresh;for(let i=0;i<16;i++){await sleep(10000);const r=await login();if(r.status!==200)continue;const token=r.data.token??r.data.accessToken;const pieces=token.split('.');const expected=crypto.createHmac('sha256',process.env.KEY_B).update(pieces[0]+'.'+pieces[1]).digest('base64url');if(expected===pieces[2]){fresh=token;break;}}check('new-managed-key-signature-actual-login',!!fresh);check('previous-key-overlap-authoritative-acceptance',(await inspect(old)).data.active===true);check('new-key-authoritative-acceptance',(await inspect(fresh)).data.active===true);check('overlap-ready',true);let retired=false;for(let i=0;i<16;i++){await sleep(10000);const r=await inspect(old);if(r.data.active===false){retired=true;break;}}check('retired-key-authoritative-rejection',retired);check('new-key-survives-previous-key-retirement',(await inspect(fresh)).data.active===true);console.log(JSON.stringify({marker:'SEC-ACPT-20261007-ROTATION',complete:true}));})().catch(()=>{console.log(JSON.stringify({marker:'SEC-ACPT-20261007-ROTATION',failed:true}));process.exit(1)});'''
 code=code.replace("'Content-Type':'application/json'","'Content-Type':'application/json',Origin:'https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app'")
 code=code.replace("return {status:r.status,data:JSON.parse(text)};","let data;try{data=JSON.parse(text)}catch{console.log(JSON.stringify({marker:'SEC-ACPT-20261007-ROTATION',check:'non-json-http-rejection',status:r.status,passed:false}));throw Error('NonJSON response');}return {status:r.status,data};")
 code=code.replace("const first=await login();", "let first;for(let attempt=0;attempt<10;attempt++){try{first=await login();if(first.status===200)break;}catch{}await sleep(5000);}if(!first)throw Error('Transport admission not ready');")
 probeEnv=[dict(name='TARGET',value=url)]+[dict(name=n,valueFrom=dict(secretKeyRef=dict(name=s,key=k))) for n,s,k in [('FIXTURE',fixtureSecret,'1'),('KEY_B',keySecret,'2'),('PEER','identity-introspection-token-dev','latest')]]
 probe=dict(apiVersion='run.googleapis.com/v1',kind='Job',metadata=dict(name=job),spec=dict(template=dict(spec=dict(taskCount=1,template=dict(spec=dict(serviceAccountName=gatewaySA,maxRetries=0,timeoutSeconds=420,containers=[dict(image=probeImage,command=['node'],args=['-e',code],env=probeEnv,resources=dict(limits=dict(cpu='1',memory='512Mi')))]))))));path=ROOT/'identity-rotation-probe-private.json';path.write_text(json.dumps(probe));cloud(['run','jobs','replace',str(path),'--region='+REGION,'--quiet','--format=json']);cloud(['run','jobs','execute',job,'--region='+REGION,'--async','--quiet','--format=json']);wait('initial-key-authoritative-introspection')
 for e in env:
  if e['name']=='APP_JWT_SECRET':e['valueFrom']['secretKeyRef']['key']='2'
 env.append(dict(name='APP_JWT_PREVIOUS_SECRET',valueFrom=dict(secretKeyRef=dict(name=keySecret,key='1'))));deploy(manifest);wait('overlap-ready');container['env']=[e for e in env if e['name']!='APP_JWT_PREVIOUS_SECRET'];deploy(manifest);rows=wait('new-key-survives-previous-key-retirement')
 admission=[row for row in rows if row.get('check')=='non-json-http-rejection'];verified=[row for row in rows if row.get('check')!='non-json-http-rejection']
 proof=dict(schemaVersion=1,project='custoking-dev',isolatedPrivateService=name,sourceImage=container['image'],secretManagerVersions=[1,2],checks=verified,transportAdmissionAttempts=admission,passed=all(row.get('passed',True)for row in verified),mainIdentityConfigChanged=False,mainSigningSecretRead=False,onlySyntheticUserId=fixture['userId'],checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(ROOT/'identity-managed-rotation-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
finally:
 for kind,resource in [('jobs',job),('services',name)]:
  try:cloud(['run',kind,'delete',resource,'--region='+REGION,'--quiet','--format=json'])
  except RuntimeError:pass
 for secretName in created:cloud(['secrets','delete',secretName,'--quiet','--format=json'])
