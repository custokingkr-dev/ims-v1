"""Post-revision replacement verification of marker-only persisted idempotency/erasure state."""
import argparse,base64,datetime as dt,importlib.util,json,pathlib,re,time
MODULE=pathlib.Path(__file__).with_name('dev-synthetic-pubsub-drill.py')
spec=importlib.util.spec_from_file_location('drill',MODULE);d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
def main():
 p=argparse.ArgumentParser();p.add_argument('--apply',action='store_true');p.add_argument('--delivery-proof',required=True);p.add_argument('--baseline-proof',required=True);p.add_argument('--output',required=True);a=p.parse_args()
 prior=json.loads(pathlib.Path(a.delivery_proof).read_text(encoding='utf-8'));baseline=json.loads(pathlib.Path(a.baseline_proof).read_text(encoding='utf-8'))
 if prior.get('project')!=d.PROJECT or prior.get('marker')!=d.MARKER or not all(prior.get(k) for k in ('deliveryVerified','duplicateVerified','replayVerified','cleanupVerified')):raise d.SafeFailure('UNVERIFIED_PRIOR_DRILL')
 if baseline.get('project')!=d.PROJECT or baseline.get('service')!='custoking-platform-service-dev' or not re.fullmatch(r'custoking-platform-service-dev-[a-z0-9-]+',baseline.get('latestReadyRevisionName','')):raise d.SafeFailure('UNVERIFIED_BASELINE')
 if not a.apply:print(json.dumps({'project':d.PROJECT,'apply':False,'operation':'duplicate-and-late-erasure-replay-after-new-ready-revision','syntheticOnly':True}));return 0
 out=pathlib.Path(a.output)
 if out.exists():raise d.SafeFailure('REFUSE_EVIDENCE_OVERWRITE')
 drill=d.Drill(prior['run'],out)
 try:
  service=json.loads(d.cloud(['run','services','describe','custoking-platform-service-dev','--region='+d.REGION,'--format=json']))
  status=service.get('status',{});revision=status.get('latestReadyRevisionName');traffic=status.get('traffic',[])
  if revision==baseline['latestReadyRevisionName'] or revision!=status.get('latestCreatedRevisionName') or not any(c.get('type')=='Ready' and c.get('status')=='True' for c in status.get('conditions',[])):raise d.SafeFailure('FRESH_READY_REVISION_REQUIRED')
  if len(traffic)!=1 or traffic[0].get('percent')!=100 or traffic[0].get('revisionName')!=revision or traffic[0].get('tag'):raise d.SafeFailure('EXACT_NEW_REVISION_TRAFFIC_REQUIRED')
  target=json.loads(d.cloud(['deploy','targets','describe','platform-service-dev','--region='+d.REGION,'--format=json']))
  host=target.get('Target',target).get('deployParameters',{}).get('db_host','')
  if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*(?::5432)?',host):raise d.SafeFailure('UNVERIFIED_DB_HOST')
  drill.db_host=host.removesuffix(':5432')
  before=drill.verify('restart-before')
  if before.get('inboxRows')!=4 or before.get('processed')!=4 or before.get('projectionRows')!=0 or before.get('tombstones')!=2:raise d.SafeFailure('PERSISTED_BASELINE_NOT_CONFIRMED')
  drill.proof['beforeRevision']=baseline['latestReadyRevisionName'];drill.proof['afterRevision']=revision
  drill.stage('fresh-revision-persisted-baseline',**before)
  original=d.event(drill.run,0);drill.publish(d.SOURCE,original)
  late=d.event(drill.run,0);late['eventId']='security-drill:'+drill.run+':'+str(d.STUDENTS[0])+':late-upsert';late['payload']['aggregateVersion']=2
  answer=drill.api('projects/'+d.PROJECT+'/topics/'+d.SOURCE+':publish',{'messages':[{'data':base64.b64encode(json.dumps(late).encode()).decode(),'attributes':{'securityDrill':drill.run,'synthetic':'true','eventId':late['eventId']}}]})
  if len(answer.get('messageIds',[]))!=1:raise d.SafeFailure('PUBLISH_RESULT_INVALID')
  drill.stage('post-replacement-duplicate-and-late-upsert-published',messages=2)
  time.sleep(60)
  after=drill.verify('restart-after',(late['eventId'],))
  if after.get('inboxRows')!=5 or after.get('processed')!=5 or after.get('projectionRows')!=0 or after.get('tombstones')!=2:raise d.SafeFailure('RESTART_PERSISTENCE_NOT_CONFIRMED')
  drill.proof['restartVerified']=True;drill.proof['cleanupVerified']=True
  drill.proof['scope']='Actual Cloud Run revision replacement; persisted inbox dedupe and tombstone suppression across the fresh process. Not an in-flight crash or full-backlog drain test.'
  drill.stage('post-replacement-persistence-confirmed',**after);return 0
 except Exception as e:
  drill.proof['failure']=str(e) if isinstance(e,d.SafeFailure) else type(e).__name__;drill.save();print(json.dumps({'failure':drill.proof['failure']}));return 1
if __name__=='__main__':raise SystemExit(main())
