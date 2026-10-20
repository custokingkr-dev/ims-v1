"""Bounded dev-only synthetic reporting Pub/Sub acceptance drill. Never reads shared queues."""
from __future__ import annotations
import argparse, base64, datetime as dt, json, pathlib, re, subprocess, time, urllib.request, uuid
PROJECT='custoking-dev'; REGION='asia-south2'; MARKER='SEC-ACPT-20261007'
SCHOOLS=(990007101,990007102); STUDENTS=(990007111,990007112)
SOURCE='ims-reporting-events-v1-dev'
PUSH_SA='ims-reporting-push-dev@custoking-dev.iam.gserviceaccount.com'
AGENT='service-1087017280590@gcp-sa-pubsub.iam.gserviceaccount.com'
PG_IMAGE='docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b'
class SafeFailure(Exception): pass
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args): raise SafeFailure('API_REDIRECT_REFUSED')
def cloud(args, timeout=60):
 result=subprocess.run(['gcloud.cmd',*args,'--project='+PROJECT],capture_output=True,text=True,timeout=timeout)
 if result.returncode: raise SafeFailure('CLOUD_COMMAND_FAILED:'+':'.join(args[:3]))
 return result.stdout

def event(run, index=0, deleted=False):
 if not re.fullmatch(r'[0-9]{14}-[a-f0-9]{8}',run) or index not in (0,1): raise SafeFailure('INVALID_SYNTHETIC_IDENTIFIER')
 sid=STUDENTS[index]; school=SCHOOLS[index]
 return dict(schemaVersion='ims.event-envelope.v1',eventId=f'security-drill:{run}:{sid}:'+('deleted' if deleted else 'upsert'),eventType='student.deleted.v1' if deleted else 'student.upserted.v1',eventVersion='1',eventKey=str(sid),aggregateType='STUDENT',aggregateId=str(sid),schoolId=school,occurredAt=dt.datetime.now(dt.timezone.utc).isoformat(),payload=dict(id=sid,schoolId=school,fullName=MARKER+'-REPORTING-ONLY',admissionNo=MARKER+'-REPORTING-'+str(sid),active=True,aggregateVersion=1))
def validate_event(value,run):
 if value.get('eventId') not in {event(run,i,d)['eventId'] for i in (0,1) for d in (False,True)}: raise SafeFailure('UNOWNED_EVENT')
 i=STUDENTS.index(int(value.get('aggregateId',0))) if int(value.get('aggregateId',0)) in STUDENTS else -1
 if i<0 or value.get('schoolId')!=SCHOOLS[i] or value.get('payload',{}).get('id')!=STUDENTS[i] or value.get('payload',{}).get('schoolId')!=SCHOOLS[i]: raise SafeFailure('UNOWNED_FIXTURE')
 if value.get('eventType') not in ('student.upserted.v1','student.deleted.v1'): raise SafeFailure('UNSAFE_EVENT_TYPE')
 if set(value.get('payload',{})) != {'id','schoolId','fullName','admissionNo','active','aggregateVersion'}: raise SafeFailure('UNSAFE_PAYLOAD_FIELDS')
 if value.get('payload',{}).get('fullName')!=MARKER+'-REPORTING-ONLY': raise SafeFailure('MISSING_SYNTHETIC_MARKER')
 return value

def decode_owned(message,run):
 # Broker forwarding preserves the original data; accept an explicit PubsubMessage wrapper too.
 data=base64.b64decode(message['data'],validate=True)
 if len(data)>16384: raise SafeFailure('DRILL_MESSAGE_TOO_LARGE')
 value=json.loads(data)
 if 'eventId' not in value and isinstance(value.get('data'),str): value=json.loads(base64.b64decode(value['data'],validate=True))
 return validate_event(value,run)

class Drill:
 def __init__(self,run,out):
  self.run=run; self.out=pathlib.Path(out); self.owned=[]; self.token=None
  self.prefix='ims-security-drill-'+run
  self.proof=dict(project=PROJECT,region=REGION,marker=MARKER,run=run,syntheticOnly=True,noProviderDelivery=True,stages=[],deliveryVerified=False,duplicateVerified=False,deadLetterVerified=False,replayVerified=False,restartVerified=False,cleanupVerified=False)
 def save(self): self.out.write_text(json.dumps(self.proof,indent=2)+'\n',encoding='utf-8')
 def stage(self,name,**counts):
  self.proof['stages'].append(dict(stage=name,atUtc=dt.datetime.now(dt.timezone.utc).isoformat(),**counts)); self.save(); print(json.dumps(dict(stage=name,**counts)),flush=True)
 def api(self,path,body):
  allowed={'projects/'+PROJECT+'/topics/'+name+':publish' for name in (SOURCE,self.prefix+'-source')}
  allowed|={'projects/'+PROJECT+'/subscriptions/'+self.prefix+'-inspection:'+action for action in ('pull','acknowledge')}
  if path not in allowed: raise SafeFailure('UNSAFE_API_PATH')
  if not self.token: self.token=cloud(['auth','print-access-token'],15).strip()
  req=urllib.request.Request('https://pubsub.googleapis.com/v1/'+path,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+self.token,'Content-Type':'application/json'},method='POST')
  with urllib.request.build_opener(NoRedirect()).open(req,timeout=20) as answer:
   data=answer.read(65537)
   if len(data)>65536: raise SafeFailure('API_RESPONSE_TOO_LARGE')
   return json.loads(data)
 def publish(self,topic,value):
  validate_event(value,self.run)
  if topic not in (SOURCE,self.prefix+'-source'): raise SafeFailure('UNSAFE_PUBLISH_TOPIC')
  result=self.api('projects/'+PROJECT+'/topics/'+topic+':publish',dict(messages=[dict(data=base64.b64encode(json.dumps(value,separators=(',',':')).encode()).decode(),attributes={'securityDrill':self.run,'synthetic':'true','eventId':value['eventId']})]))
  if len(result.get('messageIds',[]))!=1: raise SafeFailure('PUBLISH_RESULT_INVALID')
 def verify(self,phase,extra_event_ids=()):
  if any(x != 'security-drill:'+self.run+':'+str(STUDENTS[0])+':late-upsert' for x in extra_event_ids): raise SafeFailure('UNOWNED_VERIFICATION_IDENTIFIER')
  # Catalog/runtime secret values are never read: the owner-secret reference stays in the job spec.
  job=self.prefix+'-verify-'+str(len(self.proof['stages']))
  host=self.db_host
  if phase=='preflight':
   sql="""SELECT jsonb_build_object('marker','SEC-ACPT-20261007','schools',(SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%'),'occupied',(SELECT count(*) FROM student.students WHERE id IN(990007111,990007112))+(SELECT count(*) FROM reporting.dim_student WHERE id IN(990007111,990007112))+(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id IN(990007111,990007112)));"""
  else:
   ids=','.join("'"+x+"'" for x in [event(self.run,i,d)['eventId'] for i in (0,1) for d in (False,True)]+list(extra_event_ids))
   sql=f"""SELECT jsonb_build_object('marker','SEC-ACPT-20261007','inboxRows',(SELECT count(*) FROM reporting.reporting_event_inbox WHERE event_id IN({ids})),'processed',(SELECT count(*) FROM reporting.reporting_event_inbox WHERE event_id IN({ids}) AND status='PROCESSED'),'projectionRows',(SELECT count(*) FROM reporting.dim_student WHERE id IN(990007111,990007112) AND full_name='SEC-ACPT-20261007-REPORTING-ONLY'),'tombstones',(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id IN(990007111,990007112)));"""
  sql="BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SET LOCAL app.bypass_rls='on'; "+sql+' COMMIT;'
  env=[dict(name=k,value=v) for k,v in dict(PGHOST=host,PGDATABASE='custoking_dev',PGUSER='appuser',PGSSLMODE='require',PGCONNECT_TIMEOUT='10').items()]+[dict(name='PGPASSWORD',valueFrom=dict(secretKeyRef=dict(name='db-password-dev',key='latest')))]
  spec=dict(apiVersion='run.googleapis.com/v1',kind='Job',metadata=dict(name=job),spec=dict(template=dict(metadata=dict(annotations={'run.googleapis.com/network-interfaces':'[{"network":"default","subnetwork":"default"}]','run.googleapis.com/vpc-access-egress':'private-ranges-only'}),spec=dict(taskCount=1,template=dict(spec=dict(serviceAccountName='ims-db-migration-dev@custoking-dev.iam.gserviceaccount.com',maxRetries=0,timeoutSeconds=60,containers=[dict(image=PG_IMAGE,command=['psql'],args=['-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql],resources=dict(limits={'cpu':'1','memory':'512Mi'}),env=env)]))))))
  path=self.out.parent/(job+'.json'); path.write_text(json.dumps(spec),encoding='utf-8')
  try:
   cloud(['run','jobs','replace',str(path),'--region='+REGION,'--quiet'])
   cloud(['run','jobs','execute',job,'--region='+REGION,'--wait','--quiet'],120)
   logs=json.loads(cloud(['logging','read','resource.type="cloud_run_job" AND resource.labels.job_name="'+job+'"','--limit=50','--format=json']))
   for entry in logs:
    structured=entry.get('jsonPayload',{})
    if structured.get('marker')==MARKER: return structured
    text=entry.get('textPayload','')
    if text.startswith('{'):
     value=json.loads(text)
     if value.get('marker')==MARKER: return value
   raise SafeFailure('VERIFICATION_MARKER_MISSING')
  finally:
   cloud(['run','jobs','delete',job,'--region='+REGION,'--quiet'])
   path.unlink(missing_ok=True)
 def setup(self):
  target=json.loads(cloud(['deploy','targets','describe','platform-service-dev','--region='+REGION,'--format=json']))
  params=target.get('Target',target).get('deployParameters',{})
  host=params.get('db_host','')
  if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*(?::5432)?',host): raise SafeFailure('UNVERIFIED_DB_HOST')
  self.db_host=host.removesuffix(':5432')
  service=json.loads(cloud(['run','services','describe','custoking-platform-service-dev','--region='+REGION,'--format=json']))
  if not any(c.get('type')=='Ready' and c.get('status')=='True' for c in service.get('status',{}).get('conditions',[])): raise SafeFailure('PLATFORM_NOT_READY')
  url=service['status']['url']
  if not re.fullmatch(r'https://custoking-platform-service-dev-(?:1087017280590\.asia-south2|[a-z0-9-]+\.a)\.run\.app',url): raise SafeFailure('UNSAFE_PLATFORM_URL')
  env=service['spec']['template']['spec']['containers'][0].get('env',[])
  def val(name): return next((e.get('value') for e in env if e.get('name')==name),None)
  if val('REPORTING_PUBSUB_REQUIRE_SHARED_TOKEN')!='false': raise SafeFailure('REPORTING_OIDC_MODE_NOT_VERIFIED')
  pre=self.verify('preflight')
  if pre.get('schools')!=2 or pre.get('occupied')!=0: raise SafeFailure('FIXTURE_OWNERSHIP_OR_VACANCY_FAILED')
  self.stage('fixture-preflight',schools=2,occupied=0)
  for suffix in ('source','dlq'):
   topic=self.prefix+'-'+suffix
   self.owned.append(('topics',topic))
   cloud(['pubsub','topics','create',topic,'--quiet'])
  for suffix,topic in (('inspection',self.prefix+'-dlq'),('push',self.prefix+'-source')):
   name=self.prefix+'-'+suffix;self.owned.append(('subscriptions',name))
   args=['pubsub','subscriptions','create',name,'--topic='+topic,'--ack-deadline=10','--message-retention-duration=600s','--expiration-period=1d','--quiet']
   if suffix=='push': args+=['--push-endpoint='+url+'/api/v1/pubsub/reporting-events','--push-auth-service-account='+PUSH_SA,'--push-auth-token-audience='+url,'--dead-letter-topic='+self.prefix+'-dlq','--max-delivery-attempts=5','--min-retry-delay=1s','--max-retry-delay=5s']
   cloud(args)
  cloud(['pubsub','topics','add-iam-policy-binding',self.prefix+'-dlq','--member=serviceAccount:'+AGENT,'--role=roles/pubsub.publisher','--quiet'])
  cloud(['pubsub','subscriptions','add-iam-policy-binding',self.prefix+'-push','--member=serviceAccount:'+AGENT,'--role=roles/pubsub.subscriber','--quiet'])
  self.stage('isolated-topology-ready',temporaryTopics=2,temporarySubscriptions=2)
 def cleanup(self):
  failed=[]
  for kind,name in reversed(self.owned):
   if not re.fullmatch(re.escape(self.prefix)+r'-(source|dlq|push|inspection)',name): raise SafeFailure('UNSAFE_CLEANUP_NAME')
   try: cloud(['pubsub',kind,'delete',name,'--quiet'])
   except Exception: failed.append(name)
  self.stage('temporary-resource-cleanup',failed=len(failed))
  if failed: raise SafeFailure('TEMPORARY_CLEANUP_FAILED')
 def run_drill(self):
  published=False
  try:
   self.setup()
   original=event(self.run,0);self.publish(SOURCE,original);published=True;self.publish(SOURCE,original)
   self.stage('main-source-published',messages=2,logicalEvents=1)
   time.sleep(30)
   first=self.verify('delivery')
   if first.get('inboxRows')!=1 or first.get('processed')!=1 or first.get('projectionRows')!=1: raise SafeFailure('MAIN_DELIVERY_NOT_CONFIRMED')
   self.proof['deliveryVerified']=True;self.proof['duplicateVerified']=True;self.stage('main-delivery-and-duplicate',**first)
   poison=event(self.run,1);poison['schemaVersion']='synthetic.invalid-schema';self.publish(self.prefix+'-source',poison)
   self.stage('isolated-poison-published',messages=1)
   deadline=time.monotonic()+600;received=None
   while time.monotonic()<deadline:
    result=self.api('projects/'+PROJECT+'/subscriptions/'+self.prefix+'-inspection:pull',dict(maxMessages=1,returnImmediately=True))
    items=result.get('receivedMessages',[])
    if items: received=items[0];break
    time.sleep(10)
   if not received: raise SafeFailure('DEAD_LETTER_NOT_OBSERVED_WITHIN_BOUND')
   recovered=decode_owned(received['message'],self.run)
   if recovered['eventId']!=poison['eventId'] or recovered['schemaVersion']!='synthetic.invalid-schema': raise SafeFailure('DEAD_LETTER_OWNERSHIP_FAILED')
   self.proof['deadLetterVerified']=True;self.stage('isolated-broker-dead-letter-observed',messages=1)
   recovered['schemaVersion']='ims.event-envelope.v1';self.publish(SOURCE,recovered)
   self.api('projects/'+PROJECT+'/subscriptions/'+self.prefix+'-inspection:acknowledge',dict(ackIds=[received['ackId']]))
   self.stage('repaired-envelope-replayed',messages=1,acknowledgedAfterPublish=1)
   time.sleep(30);second=self.verify('replay')
   if second.get('inboxRows')!=2 or second.get('processed')!=2 or second.get('projectionRows')!=2: raise SafeFailure('REPLAY_PROJECTION_NOT_CONFIRMED')
   self.proof['replayVerified']=True;self.stage('replay-projection-confirmed',**second)
  finally:
   try:
    if published:
     for i in (0,1):self.publish(SOURCE,event(self.run,i,True))
     time.sleep(30);final=self.verify('cleanup')
     self.proof['cleanupVerified']=final.get('projectionRows')==0 and final.get('tombstones')==2 and final.get('processed')==final.get('inboxRows') and final.get('processed',0)>=2
     self.stage('fixture-tombstone-cleanup',**final)
   finally: self.cleanup();self.save()

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');parser.add_argument('--output',required=True);args=parser.parse_args()
 run=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+uuid.uuid4().hex[:8]
 if not args.apply: print(json.dumps(dict(project=PROJECT,syntheticOnly=True,apply=False,sourceTopic=SOURCE,schools=SCHOOLS,students=STUDENTS,temporaryTopics=2,temporarySubscriptions=2,maximumFailureWaitSeconds=600,noSharedQueuePull=True)));return
 out=pathlib.Path(args.output)
 if out.exists():raise SafeFailure('REFUSE_EVIDENCE_OVERWRITE')
 out.parent.mkdir(parents=True,exist_ok=True)
 drill=Drill(run,out)
 try:drill.run_drill()
 except Exception as failure:
  drill.proof['failure']=str(failure) if isinstance(failure,SafeFailure) else type(failure).__name__;drill.save();print(json.dumps(dict(failure=drill.proof['failure'])));return 1
 return 0
if __name__=='__main__':raise SystemExit(main())
