"""Dev-only irreversible obsolete revision cleanup; default is read-only. Explicit user approval is required before --apply."""
import argparse,re,time,os,tempfile
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

def validate_resume(checkpoint,digest,approved,services):
 """Resume only the exact reviewed inventory during the same release freeze.

 A successful CLI result is historical evidence. Every previous deletion must
 independently be absent now; a timed-out attempted deletion is recorded as
 observed absence rather than invented CLI success.
 """
 if checkpoint.get('project')!=PROJECT or checkpoint.get('region')!=REGION or checkpoint.get('mode')!='APPLY' or checkpoint.get('approvedInventorySha256')!=digest or checkpoint.get('preflightPassed') is not True or checkpoint.get('completed') is not False:
  raise ValueError('Resume requires an incomplete exact dev apply checkpoint')
 old_services=checkpoint.get('services',[])
 if not isinstance(old_services,list) or len(old_services)!=7 or {s.get('service') for s in old_services}!={s['service'] for s in services}:
  raise ValueError('Resume requires all seven original service pins')
 old={s['service']:s for s in old_services}; current={s['service']:s for s in services}
 for name,state in current.items():
  if state['activeRollouts'] or state['latestReady']!=old[name].get('latestReady') or state['latestCreated']!=old[name].get('latestCreated'):
   raise ValueError('Resume release freeze or latest revision pins changed')
 names={row['revision']:name for name,row in approved}
 deleted=checkpoint.get('deletedRevisions',[]); recovered=checkpoint.get('recoveredAbsentRevisions',[])
 if not isinstance(deleted,list) or not isinstance(recovered,list) or any(not isinstance(x,str) for x in deleted+recovered) or len(deleted+recovered)>len(names) or len(set(deleted+recovered))!=len(deleted+recovered) or not set(deleted+recovered)<=set(names):
  raise ValueError('Resume progress is not a unique exact inventory subset')
 pending=checkpoint.get('pendingRevision')
 if pending is not None and (not isinstance(pending,str) or pending not in names or pending in deleted+recovered):
  raise ValueError('Resume pending revision is outside exact remaining inventory')
 absent=set(deleted+recovered)
 for revision in absent:
  if any(r['revision']==revision for r in current[names[revision]]['revisions']):
   raise ValueError('Previously completed revision is present; resume refused')
 if pending is not None and not any(r['revision']==pending for r in current[names[pending]]['revisions']):
  recovered=[*recovered,pending];absent.add(pending)
 for name,row in approved:
  if row['revision'] not in absent: assert_unchanged(current[name],row)
 return list(deleted),list(recovered)

def save_progress(path,proof):
 """Keep either the previous or complete new checkpoint across interruption."""
 temporary=None
 try:
  with tempfile.NamedTemporaryFile(mode='wb',dir=path.parent,prefix='.'+path.name+'-',delete=False) as stream:
   temporary=Path(stream.name);stream.write((json.dumps(proof,indent=2)+'\n').encode('utf-8'));stream.flush();os.fsync(stream.fileno())
  os.replace(temporary,path)
 finally:
  if temporary is not None: temporary.unlink(missing_ok=True)

def verify_final_absence(services,baseline,approved):
 """Independent final snapshot, not a continuous lock or physical drain proof."""
 states={s['service']:s for s in services}
 if set(states)!=set(baseline) or len(services)!=7: raise ValueError('Final verification requires exact seven services')
 for name,state in states.items():
  if state['activeRollouts'] or state['latestReady']!=baseline[name]['latestReady'] or state['latestCreated']!=baseline[name]['latestCreated']:
   raise ValueError('Release pins or rollout state changed before final verification')
 for name,row in approved:
  if any(r['revision']==row['revision'] for r in states[name]['revisions']): raise ValueError('Deleted revision still present during final verification')
 return {'capturedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'independentlyAbsentCount':len(approved),'latestPinsUnchanged':True,'noActiveRollouts':True,'continuousReleaseFreezeCertified':False,'physicalDrainCertified':False}

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--inventory',default='docs/security-remediation/dev-obsolete-revision-dry-run.json')
 parser.add_argument('--output',default='tmp/dev-obsolete-revision-cleanup-preflight.json')
 parser.add_argument('--apply',action='store_true',help='Irreversible: use only after explicit user approval of the inventory')
 parser.add_argument('--approved-inventory-sha256',default='')
 parser.add_argument('--resume-checkpoint',default='',help='Revalidate an incomplete exact apply checkpoint; does not grant deletion approval')
 args=parser.parse_args(); raw=Path(args.inventory).read_bytes(); inventory=json.loads(raw.decode('utf-8-sig')); approved=candidates(inventory); digest=hashlib.sha256(raw).hexdigest()
 if args.apply and args.approved_inventory_sha256!=digest: raise ValueError('Apply requires exact explicitly approved inventory SHA256')
 # Every candidate must still pass a fresh all-seven preflight before the first mutation.
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: services=list(pool.map(collect,ROLES.items()))
 if any(s['activeRollouts'] for s in services): raise ValueError('Active Cloud Deploy rollout in dev release freeze')
 by_name={s['service']:s for s in services}
 deleted=[];recovered=[]
 if args.resume_checkpoint:
  resume_path=Path(args.resume_checkpoint)
  if resume_path.resolve()==Path(args.output).resolve(): raise ValueError('Preserve the prior checkpoint in a separate file')
  if resume_path.stat().st_size>16*1024*1024: raise ValueError('Resume checkpoint exceeds bounded size')
  checkpoint=json.loads(resume_path.read_text(encoding='utf-8-sig'))
  deleted,recovered=validate_resume(checkpoint,digest,approved,services)
 else:
  for name,row in approved: assert_unchanged(by_name[name],row)
 remaining=[(name,row) for name,row in approved if row['revision'] not in set(deleted+recovered)]
 proof={'project':PROJECT,'region':REGION,'mode':'APPLY' if args.apply else 'READ_ONLY','capturedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'approvedInventorySha256':digest,'approvedCandidateCount':len(approved),'remainingCandidateCount':len(remaining),'preflightPassed':True,'deletedRevisions':deleted,'recoveredAbsentRevisions':recovered,'pendingRevision':None,'completed':False,'physicalDrainCertified':False,'services':services}
 output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
 save_progress(output,proof)
 if args.apply:
  apply_deadline=time.monotonic()+1800
  try:
   for name,row in remaining:
    if time.monotonic()>=apply_deadline: raise RuntimeError('Bounded 30-minute cleanup deadline reached')
    short=name[len('custoking-'):-len('-dev')]
    # Re-read exact revision metadata, desired/current traffic, tags, both latest pointers and all rollout states immediately before each deletion.
    refreshed=collect((short,ROLES[short]))
    baseline=by_name[name]
    if refreshed['latestReady']!=baseline['latestReady'] or refreshed['latestCreated']!=baseline['latestCreated']: raise ValueError('Concurrent release changed latest revision pointers')
    assert_unchanged(refreshed,row)
    proof['pendingRevision']=row['revision'];save_progress(output,proof)
    proc=subprocess.run(['gcloud.cmd','run','revisions','delete',row['revision'],'--project='+PROJECT,'--region='+REGION,'--quiet'],capture_output=True,text=True,timeout=120)
    if proc.returncode: raise RuntimeError('Exact revision deletion failed: '+row['revision'])
    proof['deletedRevisions'].append(row['revision']);proof['pendingRevision']=None;proof['remainingCandidateCount']=len(approved)-len(proof['deletedRevisions'])-len(recovered);save_progress(output,proof)
   with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: final_services=list(pool.map(collect,ROLES.items()))
   proof['finalAbsenceVerification']=verify_final_absence(final_services,by_name,approved)
  except Exception:
   proof['completed']=False;save_progress(output,proof);raise
 proof['completed']=True;save_progress(output,proof)
 print(json.dumps({'mode':proof['mode'],'preflightPassed':True,'candidateCount':len(approved),'remainingCandidateCount':proof['remainingCandidateCount'],'deletedCount':len(proof['deletedRevisions']),'recoveredAbsentCount':len(proof['recoveredAbsentRevisions']),'inventorySha256':digest}))
if __name__=='__main__': main()
