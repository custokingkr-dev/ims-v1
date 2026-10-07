"""Dev-only irreversible obsolete revision cleanup; default is read-only. Explicit user approval is required before --apply."""
import argparse,re,time
import concurrent.futures,hashlib,json,subprocess,datetime
from pathlib import Path
PROJECT='custoking-dev'; REGION='asia-south2'
ROLES={'identity-service':'ims_identity_rt','school-core-service':'ims_school_core_rt','operations-service':'ims_operations_rt','platform-service':'ims_platform_rt','billing-service':'ims_billing_rt','api-gateway':None,'frontend':None}
def read(args):
 p=subprocess.run(['gcloud.cmd',*args,'--project='+PROJECT,'--format=json'],capture_output=True,text=True,timeout=90)
 if p.returncode: raise RuntimeError('Metadata command failed: '+args[0]+' '+args[1])
 return json.loads(p.stdout)
def collect(item):
 short,role=item; name='custoking-'+short+'-dev'
 service=read(['run','services','describe',name,'--region='+REGION]); status=service['status']; desired=service.get('spec',{}).get('traffic',[]); traffic=status.get('traffic',[])
 revisions=read(['run','revisions','list','--service='+name,'--region='+REGION,'--limit=1000'])
 rollouts=read(['deploy','rollouts','list','--delivery-pipeline='+name,'--release=-','--region='+REGION,'--limit=1000'])
 if len(revisions)>=1000 or len(rollouts)>=1000: raise RuntimeError('Metadata bounded inventory truncated')
 active=[{'name':x['name'],'state':x.get('state')} for x in rollouts if x.get('state') not in ('SUCCEEDED','FAILED','CANCELLED')]
 rows=[]
 for r in revisions:
  rev=r['metadata']['name']; spec=r.get('spec',{}); containers=spec.get('containers',[]); env={e['name']:e for c in containers for e in c.get('env',[])}
  reasons=[]; owner_names=[k for k in env if k.startswith(('FLYWAY_','SPRING_FLYWAY_'))]
  if role:
   if owner_names: reasons.append('migration-owner-environment-attached')
   if env.get('SPRING_DATASOURCE_USERNAME',{}).get('value') in ('app_rt','appuser','postgres'): reasons.append('legacy-shared-or-owner-database-role')
   if env.get('APP_MIGRATIONS_ENABLED',{}).get('value')!='false': reasons.append('runtime-migrations-not-explicitly-disabled')
   if not env.get('SPRING_PROFILES_ACTIVE',{}).get('value'): reasons.append('deployed-security-profile-missing')
  if short=='api-gateway':
   if env.get('GATEWAY_AUTH_MODE',{}).get('value')!='enforce': reasons.append('gateway-authentication-not-enforced')
   if any('JWT' in k and 'SECRET' in k for k in env): reasons.append('gateway-shared-jwt-secret-attached')
  if short=='frontend':
   # Only clear positive evidence; absence of a CSP env setting does not prove compiled nginx insecure.
   if env.get('FRONTEND_CSP_REPORT_ONLY',{}).get('value')=='true': reasons.append('frontend-csp-explicitly-report-only')
  secret_refs=[e.get('valueFrom',{}).get('secretKeyRef',{}).get('name') for e in env.values()]
  if any(x in ('db-password-dev','app-rt-password-dev') for x in secret_refs): reasons.append('retired-owner-or-shared-db-secret-reference')
  tr=[x for x in traffic if x.get('revisionName')==rev]; dt=[x for x in desired if x.get('revisionName')==rev]
  protected=rev in (status.get('latestReadyRevisionName'),status.get('latestCreatedRevisionName')) or any(x.get('tag') or x.get('percent',0)>0 for x in tr+dt)
  conditions=[{'type':x.get('type'),'status':x.get('status'),'reason':x.get('reason')} for x in r.get('status',{}).get('conditions',[])]
  rows.append({'revision':rev,'createdAt':r['metadata'].get('creationTimestamp'),'imageDigest':r.get('status',{}).get('imageDigest'),'specSha256':hashlib.sha256(json.dumps(spec,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'unsafeReasons':reasons,'protectedByLatestTrafficOrTag':protected,'trafficPercent':sum(x.get('percent',0) for x in tr),'tags':[x['tag'] for x in tr+dt if x.get('tag')],'desiredTrafficPercent':sum(x.get('percent',0) for x in dt),'conditions':conditions,'candidate':bool(reasons) and not protected and not active,'ownerEnvNames':owner_names})
 return {'service':name,'latestReady':status.get('latestReadyRevisionName'),'latestCreated':status.get('latestCreatedRevisionName'),'traffic':traffic,'activeRollouts':active,'rolloutCount':len(rollouts),'revisionCount':len(rows),'revisions':rows}

def candidates(inventory):
 if inventory.get('project')!=PROJECT or inventory.get('region')!=REGION or inventory.get('mode')!='READ_ONLY_DELETION_DRY_RUN': raise ValueError('Wrong approved inventory scope')
 services=inventory.get('services',[])
 if {x.get('service') for x in services}!={'custoking-'+x+'-dev' for x in ROLES} or len(services)!=7: raise ValueError('Require exact seven services')
 result=[]
 for service in services:
  if service.get('activeRollouts'): raise ValueError('Approved inventory had active rollouts')
  for row in service['revisions']:
   if not row.get('candidate'): continue
   name=row['revision']; prefix=service['service']+'-'
   if not name.startswith(prefix) or not re.fullmatch(r'[a-z0-9-]+',name): raise ValueError('Revision outside exact service')
   if row.get('protectedByLatestTrafficOrTag') or row.get('tags') or row.get('trafficPercent') or row.get('desiredTrafficPercent') or not row.get('unsafeReasons'): raise ValueError('Unsafe approved candidate')
   if not row.get('imageDigest') or not re.fullmatch(r'[a-f0-9]{64}',row.get('specSha256','')): raise ValueError('Missing immutable preservation metadata')
   result.append((service['service'],row))
 if not result or len(result)>250 or len({r['revision'] for _,r in result})!=len(result): raise ValueError('Invalid bounded candidate set')
 return result

def assert_unchanged(service,approved):
 if service['activeRollouts']: raise ValueError('Active Cloud Deploy rollout')
 matches=[r for r in service['revisions'] if r['revision']==approved['revision']]
 if len(matches)!=1: raise ValueError('Revision absent or ambiguous')
 row=matches[0]
 if not row['candidate'] or row['protectedByLatestTrafficOrTag'] or row['tags'] or row['trafficPercent'] or row['desiredTrafficPercent']: raise ValueError('Revision no longer safe to delete')
 if row['specSha256']!=approved['specSha256'] or row['imageDigest']!=approved['imageDigest'] or set(row['unsafeReasons'])!=set(approved['unsafeReasons']): raise ValueError('Revision safety predicate/spec changed')
 return row

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--inventory',default='docs/security-remediation/dev-obsolete-revision-dry-run.json')
 parser.add_argument('--output',default='tmp/dev-obsolete-revision-cleanup-preflight.json')
 parser.add_argument('--apply',action='store_true',help='Irreversible: use only after explicit user approval of the inventory')
 parser.add_argument('--approved-inventory-sha256',default='')
 args=parser.parse_args(); raw=Path(args.inventory).read_bytes(); inventory=json.loads(raw.decode('utf-8-sig')); approved=candidates(inventory); digest=hashlib.sha256(raw).hexdigest()
 if args.apply and args.approved_inventory_sha256!=digest: raise ValueError('Apply requires exact explicitly approved inventory SHA256')
 # Every candidate must still pass a fresh all-seven preflight before the first mutation.
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: services=list(pool.map(collect,ROLES.items()))
 by_name={s['service']:s for s in services}
 for name,row in approved: assert_unchanged(by_name[name],row)
 proof={'project':PROJECT,'region':REGION,'mode':'APPLY' if args.apply else 'READ_ONLY','capturedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'approvedInventorySha256':digest,'approvedCandidateCount':len(approved),'preflightPassed':True,'deletedRevisions':[],'physicalDrainCertified':False,'services':services}
 output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
 output.write_text(json.dumps(proof,indent=2))
 if args.apply:
  apply_deadline=time.monotonic()+1800
  try:
   for name,row in approved:
    if time.monotonic()>=apply_deadline: raise RuntimeError('Bounded 30-minute cleanup deadline reached')
    short=name[len('custoking-'):-len('-dev')]
    # Re-read exact revision metadata, desired/current traffic, tags, both latest pointers and all rollout states immediately before each deletion.
    refreshed=collect((short,ROLES[short]))
    baseline=by_name[name]
    if refreshed['latestReady']!=baseline['latestReady'] or refreshed['latestCreated']!=baseline['latestCreated']: raise ValueError('Concurrent release changed latest revision pointers')
    assert_unchanged(refreshed,row)
    proc=subprocess.run(['gcloud.cmd','run','revisions','delete',row['revision'],'--project='+PROJECT,'--region='+REGION,'--quiet'],capture_output=True,text=True,timeout=120)
    if proc.returncode: raise RuntimeError('Exact revision deletion failed: '+row['revision'])
    proof['deletedRevisions'].append(row['revision']);output.write_text(json.dumps(proof,indent=2))
  except Exception:
   proof['completed']=False;output.write_text(json.dumps(proof,indent=2));raise
 proof['completed']=True;output.write_text(json.dumps(proof,indent=2))
 print(json.dumps({'mode':proof['mode'],'preflightPassed':True,'candidateCount':len(approved),'deletedCount':len(proof['deletedRevisions']),'inventorySha256':digest}))
if __name__=='__main__': main()
