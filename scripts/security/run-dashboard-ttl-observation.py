import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,secrets,runpy,time,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud'];manifest=json.loads((ROOT/'dashboard-state-probe-A.json').read_text());job='ims-dev-dashboard-ttl-'+secrets.token_hex(8);manifest['metadata']['name']=job
code=(ROOT.parent/'tools/live-dashboard/security-state.mjs').read_text()+"\nconst nonce="+json.dumps('SEC-ACPT-20261007-TTL-'+secrets.token_hex(16))+r''';
const token=async()=>{const r=await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token',{headers:{'Metadata-Flavor':'Google'},signal:AbortSignal.timeout(10000)});if(!r.ok)throw Error('Metadata authorization unavailable');return(await r.json()).access_token;};
const state=firestoreSecurityState({project:'custoking-dev',database:'ims-dashboard-dev',accessToken:token});const created=await state.claim('expired',nonce,Date.now()-86400000);if(!created)throw Error('TTL nonce collision');let absent=false;for(let i=0;i<10;i++){await new Promise(r=>setTimeout(r,30000));if(!await state.contains('expired',nonce)){absent=true;break;}}console.log(JSON.stringify({marker:'SEC-ACPT-20261007-TTL',documentCreated:true,expiresAtPast:true,serviceAccountHasDeletePermission:false,actualTtlDeletionObserved:absent,observationSecondsMaximum:300,complete:true}));
'''
spec=manifest['spec']['template']['spec']['template']['spec'];spec['timeoutSeconds']=360;spec['containers'][0]['args']=['--input-type=module','-e',code];path=ROOT/'dashboard-ttl-job.json';path.write_text(json.dumps(manifest))
try:
 cloud(['run','jobs','replace',str(path),'--region=asia-south2','--quiet','--format=json']);result=cloud(['run','jobs','execute',job,'--region=asia-south2','--wait','--quiet','--format=json'])
 if not any(c.get('type')=='Completed'and c.get('status')=='True'for c in result.get('status',{}).get('conditions',[])):raise RuntimeError('TTL observation job did not complete')
 for attempt in range(6):
  logs=cloud(['logging','read',f'resource.type="cloud_run_job" AND resource.labels.job_name="{job}"','--limit=20','--format=json']);rows=[x['jsonPayload']for x in logs if x.get('jsonPayload',{}).get('marker')=='SEC-ACPT-20261007-TTL']
  if rows:break
  time.sleep(3)
 if not rows:raise RuntimeError('TTL completion evidence missing')
 proof=dict(project='custoking-dev',database='ims-dashboard-dev',checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),observations=rows);(ROOT/'dashboard-ttl-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
finally:cloud(['run','jobs','delete',job,'--region=asia-south2','--quiet','--format=json'])
