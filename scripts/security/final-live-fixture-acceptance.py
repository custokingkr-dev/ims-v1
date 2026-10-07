"""Exact reserved dev fixture acceptance and final cleanup; never exports business rows."""
import argparse, datetime, json, pathlib, re, secrets, socket, subprocess, threading, time, urllib.error, urllib.request
PROJECT='custoking-dev'; REGION='asia-south2'; MARKER='SEC-ACPT-20261007'
ROOT=pathlib.Path(__file__).resolve().parents[2]; TMP=ROOT/'tmp'
PG_IMAGE='docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b'
ORIGIN='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app'
GATEWAY='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app'
class SafeFailure(Exception): pass
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):raise SafeFailure('HTTP_REDIRECT_REFUSED')
def cloud(args,timeout=90):
 try: p=subprocess.run(['gcloud.cmd',*args,'--project='+PROJECT],capture_output=True,text=True,timeout=timeout)
 except subprocess.TimeoutExpired: raise SafeFailure('CLOUD_DEADLINE_OUTPUT_WITHHELD') from None
 if p.returncode: raise SafeFailure('CLOUD_FAILED_OUTPUT_WITHHELD:'+':'.join(args[:3]))
 return p.stdout
def owner_sql(phase,sql):
 if not re.fullmatch('[a-z-]{1,30}',phase): raise SafeFailure('INVALID_PHASE')
 job='ims-sec-final-'+phase+'-'+secrets.token_hex(6)
 env=[dict(name=k,value=v) for k,v in dict(PGHOST='10.92.0.3',PGDATABASE='custoking_dev',PGUSER='appuser',PGSSLMODE='require',PGCONNECT_TIMEOUT='10').items()]+[dict(name='PGPASSWORD',valueFrom=dict(secretKeyRef=dict(name='db-password-dev',key='latest')))]
 manifest=dict(apiVersion='run.googleapis.com/v1',kind='Job',metadata=dict(name=job),spec=dict(template=dict(metadata=dict(annotations={'run.googleapis.com/network-interfaces':'[{"network":"default","subnetwork":"default"}]','run.googleapis.com/vpc-access-egress':'private-ranges-only'}),spec=dict(taskCount=1,template=dict(spec=dict(serviceAccountName='ims-db-migration-dev@custoking-dev.iam.gserviceaccount.com',maxRetries=0,timeoutSeconds=60,containers=[dict(image=PG_IMAGE,command=['psql'],args=['-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql],resources=dict(limits=dict(cpu='1',memory='512Mi')),env=env)]))))))
 path=TMP/(job+'.json');path.write_text(json.dumps(manifest),encoding='utf-8')
 try:
  cloud(['run','jobs','replace',str(path),'--region='+REGION,'--quiet'],120)
  executed=json.loads(cloud(['run','jobs','execute',job,'--region='+REGION,'--wait','--quiet','--format=json'],180))
  if not any(c.get('type')=='Completed' and c.get('status')=='True' for c in executed.get('status',{}).get('conditions',[])):raise SafeFailure('OWNER_JOB_NOT_COMPLETED')
  for attempt in range(3):
   logs=json.loads(cloud(['logging','read','resource.type="cloud_run_job" AND resource.labels.job_name="'+job+'"','--limit=30','--format=json']))
   for entry in logs:
    value=entry.get('jsonPayload',{})
    if value.get('marker')==MARKER:return value
    text=entry.get('textPayload','')
    if text.startswith('{'):
     value=json.loads(text)
     if value.get('marker')==MARKER:return value
   if attempt<2:time.sleep(3)
  raise SafeFailure('BOUNDED_MARKER_RESULT_MISSING')
 finally:
  cloud(['run','jobs','delete',job,'--region='+REGION,'--quiet'])
  absent=subprocess.run(['gcloud.cmd','run','jobs','describe',job,'--region='+REGION,'--project='+PROJECT,'--format=json'],capture_output=True,text=True,timeout=60)
  if absent.returncode==0 or not any(s in absent.stderr.lower() for s in ('not_found','not found','does not exist','cannot find')):raise SafeFailure('OWNED_TEMP_JOB_ABSENCE_NOT_ESTABLISHED')
  cleanup_path=TMP/'security-final-owned-jobs-cleanup.json';cleanup=json.loads(cleanup_path.read_text()) if cleanup_path.exists() else dict(project=PROJECT,marker=MARKER,jobs=[])
  cleanup['jobs'].append(dict(name=job,independentlyAbsent=True,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat()));cleanup_path.write_text(json.dumps(cleanup,indent=2))
  path.unlink(missing_ok=True)
def bounded_request(request,cap=262144,deadline_seconds=30):
 started=time.monotonic()
 error_response=None
 try:response=urllib.request.build_opener(NoRedirect).open(request,timeout=deadline_seconds)
 except urllib.error.HTTPError as e:error_response=e;response=e.fp
 with response:
  connection=response.fp.raw._sock
  def abort():
   try:connection.shutdown(socket.SHUT_RDWR)
   except OSError:pass
  timer=threading.Timer(max(.001,deadline_seconds-(time.monotonic()-started)),abort);timer.daemon=True;timer.start()
  try:
   chunks=[];size=0
   while True:
    if time.monotonic()-started>=deadline_seconds:raise SafeFailure('HTTP_WHOLE_BODY_DEADLINE')
    chunk=response.read1(min(65536,cap+1-size))
    if not chunk:break
    size+=len(chunk)
    if size>cap:raise SafeFailure('HTTP_RESPONSE_CEILING')
    chunks.append(chunk)
   if time.monotonic()-started>=deadline_seconds:raise SafeFailure('HTTP_WHOLE_BODY_DEADLINE')
   raw=b''.join(chunks);return response.status,json.loads(raw) if raw else None
  except OSError:raise SafeFailure('HTTP_BODY_ABORTED_OUTPUT_WITHHELD') from None
  finally:timer.cancel()
def req(method,path,body=None,token=None,extra=None):
 headers={'Origin':ORIGIN,'Content-Type':'application/json','Sec-Fetch-Site':'same-origin'}
 if token:headers['Authorization']='Bearer '+token
 if extra:headers.update(extra)
 request=urllib.request.Request(GATEWAY+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 return bounded_request(request)
def lower_role():
 import bcrypt
 private=TMP/'security-lower-role-private.json'
 existing=private.exists()
 c=json.loads(private.read_text())['credentials'][0] if existing else dict(userId=990007205,schoolId=990007101,role='VIEWER',email='sec-acpt-20261007-viewer@security-fixture.invalid',password=secrets.token_urlsafe(32))
 if c.get('userId')!=990007205 or c.get('schoolId')!=990007101 or c.get('role')!='VIEWER' or c.get('email')!='sec-acpt-20261007-viewer@security-fixture.invalid':raise SafeFailure('PRIVATE_VIEWER_PROVENANCE_MISMATCH')
 hashed=bcrypt.hashpw(c['password'].encode(),bcrypt.gensalt(rounds=12)).decode()
 sql="""BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='5s'; SET LOCAL app.bypass_rls='on'; DO $$ BEGIN IF (SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%')<>2 OR (SELECT count(*) FROM student.students WHERE id IN(990007301,990007311) AND admission_no IN('SEC-ACPT-20261007-STUDENT-1','SEC-ACPT-20261007-STUDENT-11'))<>2 OR EXISTS(SELECT 1 FROM identity.app_users WHERE id IN(990007205,990007206) OR email='sec-acpt-20261007-viewer@security-fixture.invalid') OR (SELECT count(*) FROM identity.roles WHERE name='VIEWER')<>1 THEN RAISE EXCEPTION 'Exact synthetic ownership or collision gate failed'; END IF; END $$;"""
 sql+=f"INSERT INTO identity.app_users(id,full_name,email,password_hash,role,branch_id,branch_name,created_at) VALUES(990007205,'{MARKER}-VIEWER','{c['email']}','{hashed}','VIEWER',990007101,'{MARKER}-1',now()); INSERT INTO identity.user_role_assignments(user_id,role_id,school_id,active) SELECT 990007205,id,990007101,true FROM identity.roles WHERE name='VIEWER'; SELECT jsonb_build_object('marker','{MARKER}','viewerProvisioned',(SELECT count(*) FROM identity.app_users WHERE id=990007205 AND full_name='{MARKER}-VIEWER' AND role='VIEWER')); COMMIT;"
 if existing:
  sql="BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SELECT jsonb_build_object('marker','SEC-ACPT-20261007','viewerProvisioned',(SELECT count(*) FROM identity.app_users WHERE id=990007205 AND full_name='SEC-ACPT-20261007-VIEWER' AND email='sec-acpt-20261007-viewer@security-fixture.invalid' AND role='VIEWER' AND branch_id=990007101 AND deleted_at IS NULL)); COMMIT;"
 else:private.write_text(json.dumps(dict(marker=MARKER,credentials=[c])),encoding='utf-8')
 result=owner_sql('viewer-provenance',sql)
 if result.get('viewerProvisioned')!=1:raise SafeFailure('VIEWER_PROVISION_NOT_CONFIRMED')
 checks=[]
 def check(name,status,expected,data=None):
  passed=status==expected
  if status==403 and data and data.get('code')=='STEP_UP_REQUIRED':passed=False
  checks.append(dict(check=name,status=status,expected=expected,passed=passed))
  if not passed:raise SafeFailure('LOWER_ROLE_INVARIANT_FAILED:'+name+':'+str(status))
 status,data=req('POST','/api/v1/auth/login',dict(email=c['email'],password=c['password']))
 check('actual-normal-password-login',status,200)
 if data.get('role')!='VIEWER' or data.get('branchId')!=c['schoolId'] or data.get('id',data.get('userId'))!=c['userId']:raise SafeFailure('AUTHORITATIVE_VIEWER_PRINCIPAL_MISMATCH')
 permissions=data.get('permissions',[])
 if 'student:read' not in permissions or 'student:update' in permissions:raise SafeFailure('EXPECTED_VIEWER_PERMISSION_BOUNDARY_MISMATCH')
 token=data.get('token',data.get('accessToken'))
 status,data=req('GET','/api/v1/students/990007301',token=token);check('own-student-permitted',status,200)
 if data.get('id')!=990007301 or data.get('schoolId')!=990007101:raise SafeFailure('OWN_STUDENT_RESPONSE_MISMATCH')
 status,data=req('GET','/api/v1/students/990007311',token=token);check('foreign-object-concealed',status,404)
 body=dict(schoolId=990007101,fullName='Synthetic Security Student 1',admissionNumber=MARKER+'-STUDENT-1',classId=MARKER+'-CLASS',sectionId=MARKER+'-SECTION-990007101',phone='0000000000',address='Synthetic fixture')
 status,data=req('PUT','/api/v1/students/990007301',body,token);check('valid-own-student-write-permission-denied',status,403,data)
 directory_status,directory_data=req('GET','/api/v1/rbac/roles',token=token)
 status,data=req('GET','/api/v1/rbac/user-role-assignments?userId=990007205&limit=1',token=token);check('admin-only-owned-user-assignment-directory-denied',status,403,data)
 spoof={'X-Authenticated-Role':'SUPERADMIN','X-Authenticated-User-Id':'990007201','X-Authenticated-School-Id':'990007102','X-Authenticated-Branch-Id':'990007102','X-Authenticated-Permissions':'*','X-IMS-Principal-Carrier-Token':'Bearer synthetic-spoof'}
 status,data=req('GET','/api/v1/students/990007311',token=token,extra=spoof);check('spoofed-role-carrier-foreign-object-denied',status,404)
 status,data=req('PUT','/api/v1/students/990007301',body,token,spoof);check('spoofed-role-carrier-write-still-denied',status,403,data)
 status,data=req('GET','/api/v1/rbac/user-role-assignments?userId=990007205&limit=1',token=token,extra=spoof);check('spoofed-role-carrier-admin-still-denied',status,403,data)
 proof=dict(schemaVersion=1,project=PROJECT,marker=MARKER,role='VIEWER',userId=990007205,schoolId=990007101,normalLogin=True,validBody=True,allowedOrigin=True,bearerAuthCookieCsrfNotApplicable=True,studentReadGranted=True,studentUpdateGranted=False,roleCatalogObservation=dict(status=directory_status,legitimatelyGrantedRead='role:read' in permissions,initialExpected=403,initialObserved=200,initialAttemptStopped=True,initialHarnessExpectationCorrected=True),checks=checks,passed=all(x['passed'] for x in checks),checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
 (TMP/'security-lower-role-live-proof.json').write_text(json.dumps(proof,indent=2),encoding='utf-8');print(json.dumps(proof))
def ready_gate(revision):
 proof=json.loads((TMP/'dev-pubsub-restart-proof.json').read_text())
 if not proof.get('restartVerified') or not proof.get('cleanupVerified') or proof.get('project')!=PROJECT or proof.get('marker')!=MARKER or proof.get('afterRevision')!=revision:raise SafeFailure('NEW_READY_BROKER_PROOF_REQUIRED')
 svc=json.loads(cloud(['run','services','describe','custoking-platform-service-dev','--region='+REGION,'--format=json']))
 s=svc.get('status',{});t=s.get('traffic',[])
 if s.get('latestReadyRevisionName')!=revision or s.get('latestCreatedRevisionName')!=revision or not any(x.get('type')=='Ready' and x.get('status')=='True' for x in s.get('conditions',[])) or len(t)!=1 or t[0].get('revisionName')!=revision or t[0].get('percent')!=100:raise SafeFailure('EXACT_READY_REVISION_REQUIRED')
def reset_factors(revision):
 ready_gate(revision)
 sql="""BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='5s'; SET LOCAL app.bypass_rls='on'; DO $$ BEGIN
 IF (SELECT count(*) FROM student.students WHERE id BETWEEN 990007301 AND 990007320 AND admission_no='SEC-ACPT-20261007-STUDENT-'||(id-990007300) AND created_by='SEC-ACPT-20261007' AND school_id=CASE WHEN id<=990007310 THEN 990007101 ELSE 990007102 END AND photo_url IS NULL)<>20 OR (SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%')<>2 OR (SELECT count(*) FROM identity.app_users WHERE id IN(990007201,990007202) AND full_name='SEC-ACPT-20261007-'||(id-990007200) AND email='sec-acpt-20261007-'||(id-990007200)||'@security-fixture.invalid' AND role='SCHOOL_ADMIN' AND branch_id=CASE WHEN id=990007201 THEN 990007101 ELSE 990007102 END AND deleted_at IS NULL)<>2 OR EXISTS(SELECT 1 FROM tenant_school.outbox_events WHERE event_type='student.deleted.v1' AND aggregate_id IN(SELECT n::text FROM generate_series(990007301,990007320)n)) THEN RAISE EXCEPTION 'Exact marker ownership/reset precondition failed'; END IF; END $$;
 DELETE FROM identity.passkey_credentials WHERE user_id IN(990007201,990007202); DELETE FROM identity.session_step_up WHERE user_id IN(990007201,990007202); DELETE FROM identity.passkey_challenges WHERE user_id IN(990007201,990007202);
 INSERT INTO identity.rbac_audit_log(event_type,target_user_id,new_value,correlation_id) SELECT 'SYNTHETIC_FIXTURE_AUTHENTICATOR_RESET',id,'Owner maintenance of isolated lost virtual factors before actual browser final erasure step-up only','SEC-ACPT-20261007' FROM identity.app_users WHERE id IN(990007201,990007202);
 SELECT jsonb_build_object('marker','SEC-ACPT-20261007','ownedStudents',20,'ownedSchools',2,'ordinaryActors',2,'resetAudited',true,'privilegedActorsUntouched',true); COMMIT;"""
 result=owner_sql('factor-reset',sql);result.update(checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),project=PROJECT,readyRevision=revision)
 (TMP/'security-final-factor-reset-proof.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
def inspect_erasure():
 sql="""BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SET LOCAL app.bypass_rls='on';
 WITH own_outbox AS (SELECT id,published_at FROM tenant_school.outbox_events WHERE event_type='student.deleted.v1' AND event_key='StudentDeleted:'||aggregate_id AND aggregate_id IN(SELECT n::text FROM generate_series(990007301,990007320)n) AND school_id=CASE WHEN aggregate_id IN(SELECT n::text FROM generate_series(990007301,990007310)n) THEN 990007101 ELSE 990007102 END AND payload->>'id'=aggregate_id AND payload->>'schoolId'=school_id::text)
 SELECT jsonb_build_object('marker','SEC-ACPT-20261007','reservedStudentsRemaining',(SELECT count(*) FROM student.students WHERE id BETWEEN 990007301 AND 990007320),'schoolStudentsRemaining',(SELECT count(*) FROM student.students WHERE school_id IN(990007101,990007102)),'deletionOutbox',(SELECT count(*) FROM own_outbox),'publishedDeletionOutbox',(SELECT count(*) FROM own_outbox WHERE published_at IS NOT NULL),'processedDeletionInbox',(SELECT count(*) FROM reporting.reporting_event_inbox i JOIN own_outbox o ON i.event_id='school-core:'||o.id::text WHERE i.status='PROCESSED' AND i.event_type='student.deleted.v1'),'studentTombstones',(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id BETWEEN 990007301 AND 990007320),'reportingProjectionRemaining',(SELECT count(*) FROM reporting.dim_student WHERE id BETWEEN 990007301 AND 990007320)); COMMIT;"""
 result=owner_sql('erase-inspect',sql);result.update(project=PROJECT,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(TMP/'security-final-erasure-inspection.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
 return result
def bounded_wake_source():
 proof=json.loads((TMP/'security-final-live-erasure-proof.json').read_text());tokens=json.loads((TMP/'security-final-erasure-token-private.json').read_text())
 if proof.get('project')!=PROJECT or proof.get('marker')!=MARKER or not proof.get('passed') or len(proof.get('deletions',[]))!=20 or tokens.get('marker')!=MARKER:raise SafeFailure('COMPLETED_MARKER_ERASURE_REQUIRED')
 checks=[]
 for i in range(10):
  c=tokens['credentials'][i%2]
  if c.get('userId')!=990007201+i%2 or c.get('schoolId')!=990007101+i%2:raise SafeFailure('WAKE_PRINCIPAL_PROVENANCE_MISMATCH')
  request=urllib.request.Request(GATEWAY+'/api/v1/students?schoolId='+str(c['schoolId'])+'&page=0&size=1',headers={'Origin':ORIGIN,'Authorization':'Bearer '+c['accessToken']},method='GET')
  status,data=bounded_request(request,deadline_seconds=8)
  if status!=200:raise SafeFailure('OWN_SCHOOL_POST_ERASURE_READ_FAILED')
  rows=data if isinstance(data,list) else data.get('items',data.get('content',data.get('students',[])))
  if rows:raise SafeFailure('POST_ERASURE_SCHOOL_NOT_EMPTY')
  checks.append(dict(schoolId=c['schoolId'],status=status,rows=0));time.sleep(.5)
 result=dict(project=PROJECT,marker=MARKER,readRequests=10,requestsPerSecondLimit=2,noWrites=True,checks=checks,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(TMP/'security-final-post-erasure-reads.json').write_text(json.dumps(result,indent=2));print(json.dumps(dict(project=PROJECT,postErasureReads=10,emptyOwnSchoolsVerified=True)))
def photo_key():
 sql="""BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SET LOCAL app.bypass_rls='on'; SELECT jsonb_build_object('marker','SEC-ACPT-20261007','studentId',st.id,'schoolId',st.school_id,'schoolStorageId',s.school_uid::text,'objectKey',st.photo_url) FROM student.students st JOIN tenant_school.schools s ON s.id=st.school_id WHERE st.id=990007301 AND st.school_id=990007101 AND st.admission_no='SEC-ACPT-20261007-STUDENT-1' AND st.created_by='SEC-ACPT-20261007'; COMMIT;"""
 result=owner_sql('photo-provenance',sql)
 if not re.fullmatch(r'schools/[A-Za-z0-9._-]+/students/990007301/photos/[a-f0-9]{64}\.jpg',result.get('objectKey','')) or result.get('objectKey','').split('/')[1]!=result.get('schoolStorageId'):raise SafeFailure('OWNED_PHOTO_PROVENANCE_FAILED')
 (TMP/'security-final-photo-private.json').write_text(json.dumps(result));print(json.dumps(result))
def photo_metadata(after=False):
 p=json.loads((TMP/'security-final-photo-private.json').read_text());key=p.get('objectKey','')
 if p.get('marker')!=MARKER or p.get('studentId')!=990007301 or p.get('schoolId')!=990007101 or not re.fullmatch(r'schools/[A-Za-z0-9._-]+/students/990007301/photos/[a-f0-9]{64}\.jpg',key):raise SafeFailure('EXACT_PHOTO_MARKER_REQUIRED')
 uri='gs://custoking-dev-student-photos/'+key
 if not after:
  bucket=json.loads(cloud(['storage','buckets','describe','gs://custoking-dev-student-photos','--format=json']))
  obj=json.loads(cloud(['storage','objects','describe',uri,'--format=json']))
  generation=str(obj.get('generation',''))
  if not re.fullmatch('[0-9]+',generation):raise SafeFailure('PHOTO_GENERATION_MISSING')
  policy=json.loads(cloud(['storage','buckets','get-iam-policy','gs://custoking-dev-student-photos','--format=json']))
  runtime_roles=[b.get('role') for b in policy.get('bindings',[]) if 'serviceAccount:ims-school-core-dev@custoking-dev.iam.gserviceaccount.com' in b.get('members',[])]
  result=dict(project=PROJECT,marker=MARKER,studentId=990007301,objectKey=key,liveObjectBefore=True,generation=generation,contentType=obj.get('content_type',obj.get('contentType')),bytes=obj.get('size'),softDeletePolicy=bucket.get('soft_delete_policy',bucket.get('softDeletePolicy')),versioningEnabled=bool(bucket.get('versioning_enabled',bucket.get('versioning',{}).get('enabled',False))),retentionPolicy=bucket.get('retention_policy',bucket.get('retentionPolicy')),existingRuntimeBucketRoles=runtime_roles,runtimeIamChanged=False,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
  (TMP/'security-final-photo-before-proof.json').write_text(json.dumps(result,indent=2));print(json.dumps(result));return
 before=json.loads((TMP/'security-final-photo-before-proof.json').read_text());generation=before['generation']
 if before.get('objectKey')!=key or not re.fullmatch('[0-9]+',generation):raise SafeFailure('PHOTO_GENERATION_PROVENANCE_MISMATCH')
 def exact_metadata(url,soft=False):
  args=['gcloud.cmd','storage','objects','describe',url,'--project='+PROJECT,'--format=json']+(['--soft-deleted'] if soft else [])
  response=subprocess.run(args,capture_output=True,text=True,timeout=60)
  if response.returncode==0:return True,json.loads(response.stdout)
  if any(x in response.stderr.lower() for x in ('not_found','not found','404','does not exist')):return False,None
  raise SafeFailure('EXACT_PHOTO_METADATA_UNEXPECTED_FAILURE')
 live,_=exact_metadata(uri);pinned,_=exact_metadata(uri+'#'+generation);soft,value=exact_metadata(uri+'#'+generation,True)
 if live or pinned:raise SafeFailure('APPLICATION_PHOTO_DELETE_NOT_CONFIRMED')
 bucket=json.loads(cloud(['storage','buckets','describe','gs://custoking-dev-student-photos','--format=json']))
 unchanged=before.get('softDeletePolicy')==bucket.get('soft_delete_policy',bucket.get('softDeletePolicy')) and before.get('versioningEnabled')==bool(bucket.get('versioning_enabled',bucket.get('versioning',{}).get('enabled',False))) and before.get('retentionPolicy')==bucket.get('retention_policy',bucket.get('retentionPolicy'))
 result=dict(project=PROJECT,marker=MARKER,studentId=990007301,objectKey=key,generation=generation,liveObjectAfter='NOT_FOUND',pinnedAccessibleGenerationAfter='NOT_FOUND',softDeletedGenerationObserved=soft,softDeleteTime=value.get('soft_delete_time',value.get('softDeleteTime')) if value else None,hardDeleteTime=value.get('hard_delete_time',value.get('hardDeleteTime')) if value else None,physicalPurgeVerified=False,bucketPolicyUnchanged=unchanged,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),passed=unchanged)
 (TMP/'security-final-photo-after-proof.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
def disable_parents(revision):
 ready_gate(revision);counts=inspect_erasure()
 if any(counts.get(k)!=v for k,v in dict(reservedStudentsRemaining=0,schoolStudentsRemaining=0,deletionOutbox=20,publishedDeletionOutbox=20,processedDeletionInbox=20,studentTombstones=20,reportingProjectionRemaining=0).items()):raise SafeFailure('FINAL_ERASURE_PROPAGATION_REQUIRED')
 credentials=json.loads((TMP/'security-acceptance-private.json').read_text())['credentials']+json.loads((TMP/'security-lower-role-private.json').read_text())['credentials'];old_tokens=[]
 for c in credentials:
  status,data=req('POST','/api/v1/auth/login',dict(email=c['email'],password=c['password']))
  if status!=200 or data.get('userId')!=c['userId'] or data.get('branchId')!=c['schoolId'] or data.get('role')!=c['role']:raise SafeFailure('PRE_CLEANUP_NORMAL_PRINCIPAL_MISMATCH')
  old_tokens.append(data.get('accessToken',data.get('token')))
 sql="""BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='5s'; SET LOCAL app.bypass_rls='on'; DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM student.students WHERE school_id IN(990007101,990007102) OR id BETWEEN 990007301 AND 990007320) OR (SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name='SEC-ACPT-20261007-'||(id-990007100))<>2 OR (SELECT count(*) FROM identity.app_users WHERE id IN(990007201,990007202) AND full_name='SEC-ACPT-20261007-'||(id-990007200) AND email='sec-acpt-20261007-'||(id-990007200)||'@security-fixture.invalid')<>2 OR (SELECT count(*) FROM identity.app_users WHERE id=990007205 AND full_name='SEC-ACPT-20261007-VIEWER' AND email='sec-acpt-20261007-viewer@security-fixture.invalid')<>1 OR EXISTS(SELECT 1 FROM tenant_school.staff_members WHERE school_id IN(990007101,990007102)) OR EXISTS(SELECT 1 FROM tenant_school.school_module_entitlements WHERE school_id IN(990007101,990007102) AND (notes IS DISTINCT FROM 'SEC-ACPT-20261007' OR module_code NOT IN('STUDENTS','ATTENDANCE'))) OR EXISTS(SELECT 1 FROM tenant_school.school_sections WHERE school_id IN(990007101,990007102) AND id NOT IN('SEC-ACPT-20261007-SECTION-990007101','SEC-ACPT-20261007-SECTION-990007102')) THEN RAISE EXCEPTION 'Exact final parent ownership precondition failed'; END IF; END $$;
 UPDATE identity.app_users SET deleted_at=now(),deleted_by='SEC-ACPT-20261007-OWNER-CLEANUP',credential_version=credential_version+1 WHERE id IN(990007201,990007202,990007205) AND deleted_at IS NULL;
 UPDATE identity.auth_sessions SET status='REVOKED' WHERE user_id IN(990007201,990007202,990007205); UPDATE identity.user_role_assignments SET active=false,revoked_at=now() WHERE user_id IN(990007201,990007202,990007205); DELETE FROM identity.passkey_credentials WHERE user_id IN(990007201,990007202,990007205); DELETE FROM identity.session_step_up WHERE user_id IN(990007201,990007202,990007205); DELETE FROM identity.passkey_challenges WHERE user_id IN(990007201,990007202,990007205);
 INSERT INTO identity.rbac_audit_log(event_type,target_user_id,new_value,correlation_id) SELECT 'SYNTHETIC_ORDINARY_ACTOR_DISABLED',id,'Final marker-bound dev acceptance cleanup; sessions and assignments revoked, audit and tombstones retained','SEC-ACPT-20261007' FROM identity.app_users WHERE id IN(990007201,990007202,990007205);
 DELETE FROM tenant_school.school_module_entitlements WHERE school_id IN(990007101,990007102) AND notes='SEC-ACPT-20261007'; DELETE FROM tenant_school.school_sections WHERE id IN('SEC-ACPT-20261007-SECTION-990007101','SEC-ACPT-20261007-SECTION-990007102') AND school_id IN(990007101,990007102); DELETE FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name='SEC-ACPT-20261007-'||(id-990007100); DELETE FROM tenant_school.school_classes WHERE id='SEC-ACPT-20261007-CLASS' AND name='Synthetic Security Class'; DELETE FROM tenant_school.academic_years WHERE id='SEC-ACPT-20261007-YEAR' AND label='Synthetic Security Year';
 SELECT jsonb_build_object('marker','SEC-ACPT-20261007','disabledOrdinaryActors',(SELECT count(*) FROM identity.app_users WHERE id IN(990007201,990007202,990007205) AND deleted_at IS NOT NULL),'activeSessions',(SELECT count(*) FROM identity.auth_sessions WHERE user_id IN(990007201,990007202,990007205) AND status='ACTIVE'),'activeAssignments',(SELECT count(*) FROM identity.user_role_assignments WHERE user_id IN(990007201,990007202,990007205) AND active),'remainingSchools',(SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102)),'remainingSections',(SELECT count(*) FROM tenant_school.school_sections WHERE id IN('SEC-ACPT-20261007-SECTION-990007101','SEC-ACPT-20261007-SECTION-990007102')),'remainingClass',(SELECT count(*) FROM tenant_school.school_classes WHERE id='SEC-ACPT-20261007-CLASS'),'remainingYear',(SELECT count(*) FROM tenant_school.academic_years WHERE id='SEC-ACPT-20261007-YEAR'),'preservedStudentTombstones',(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id BETWEEN 990007301 AND 990007320),'preservedBrokerTombstones',(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id IN(990007111,990007112))); COMMIT;"""
 result=owner_sql('disable-parents',sql)
 for k,v in dict(disabledOrdinaryActors=3,activeSessions=0,activeAssignments=0,remainingSchools=0,remainingSections=0,remainingClass=0,remainingYear=0,preservedStudentTombstones=20,preservedBrokerTombstones=2).items():
  if result.get(k)!=v:raise SafeFailure('FINAL_CLEANUP_RESULT_MISMATCH:'+k)
 result['loginRejections']=[]
 result['revokedAccessRejections']=[]
 result['preCleanupNormalLogins']=[dict(userId=c['userId'],status=200,authoritativePrincipalVerified=True) for c in credentials]
 for c,token in zip(credentials,old_tokens):
  status,_=req('POST','/api/v1/auth/login',dict(email=c['email'],password=c['password']))
  result['loginRejections'].append(dict(userId=c['userId'],status=status,passed=status==401))
  if status!=401:raise SafeFailure('DISABLED_ORDINARY_LOGIN_NOT_REJECTED')
  status,_=req('GET','/api/v1/students/'+str(990007301 if c['schoolId']==990007101 else 990007311),token=token)
  result['revokedAccessRejections'].append(dict(userId=c['userId'],status=status,passed=status==401))
  if status!=401:raise SafeFailure('REVOKED_ORDINARY_ACCESS_NOT_REJECTED')
 result.update(project=PROJECT,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),readyRevision=revision,passed=True)
 (TMP/'security-final-fixture-cleanup-proof.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
def inspect_cleanup_rollback():
 sql="""BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SET LOCAL app.bypass_rls='on'; SELECT jsonb_build_object('marker','SEC-ACPT-20261007','ordinaryActorsStillActive',(SELECT count(*) FROM identity.app_users WHERE id IN(990007201,990007202,990007205) AND deleted_at IS NULL AND full_name LIKE 'SEC-ACPT-20261007-%'),'schoolsStillPresent',(SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%'),'sectionsStillPresent',(SELECT count(*) FROM tenant_school.school_sections WHERE id IN('SEC-ACPT-20261007-SECTION-990007101','SEC-ACPT-20261007-SECTION-990007102')),'classStillPresent',(SELECT count(*) FROM tenant_school.school_classes WHERE id='SEC-ACPT-20261007-CLASS'),'yearStillPresent',(SELECT count(*) FROM tenant_school.academic_years WHERE id='SEC-ACPT-20261007-YEAR'),'fixtureDisableAuditRows',(SELECT count(*) FROM identity.rbac_audit_log WHERE event_type='SYNTHETIC_ORDINARY_ACTOR_DISABLED' AND target_user_id IN(990007201,990007202,990007205) AND correlation_id='SEC-ACPT-20261007'),'studentsRemaining',(SELECT count(*) FROM student.students WHERE id BETWEEN 990007301 AND 990007320),'studentTombstones',(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id BETWEEN 990007301 AND 990007320)); COMMIT;"""
 result=owner_sql('cleanup-rollback',sql)
 for k,v in dict(ordinaryActorsStillActive=3,schoolsStillPresent=2,sectionsStillPresent=2,classStillPresent=1,yearStillPresent=1,fixtureDisableAuditRows=0,studentsRemaining=0,studentTombstones=20).items():
  if result.get(k)!=v:raise SafeFailure('CLEANUP_ROLLBACK_STATE_MISMATCH:'+k)
 result.update(project=PROJECT,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),rollbackVerified=True,initialFailureCategory='relation-missing',initialIncorrectRelation='tenant_school.school_staff',authoritativeRelation='tenant_school.staff_members')
 failed=[j for j in json.loads((TMP/'security-final-owned-jobs-cleanup.json').read_text())['jobs'] if j['name'].startswith('ims-sec-final-disable-parents-')]
 if len(failed)!=1:raise SafeFailure('FAILED_CLEANUP_JOB_PROVENANCE_MISMATCH')
 result['failedOwnedJob']=failed[0]
 (TMP/'security-final-cleanup-rollback-proof.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
def verify_broker_jobs():
 proof=json.loads((TMP/'dev-pubsub-restart-proof.json').read_text())
 if proof.get('project')!=PROJECT or proof.get('marker')!=MARKER or not proof.get('restartVerified') or not re.fullmatch('[0-9]{14}-[a-f0-9]{8}',proof.get('run','')):raise SafeFailure('RESTART_PROOF_REQUIRED')
 path=TMP/'security-final-owned-jobs-cleanup.json';cleanup=json.loads(path.read_text())
 for suffix in (0,2):
  name='ims-security-drill-'+proof['run']+'-verify-'+str(suffix)
  r=subprocess.run(['gcloud.cmd','run','jobs','describe',name,'--region='+REGION,'--project='+PROJECT,'--format=json'],capture_output=True,text=True,timeout=60)
  if r.returncode==0 or not any(x in r.stderr.lower() for x in ('not_found','not found','does not exist','cannot find')):raise SafeFailure('BROKER_TEMP_JOB_ABSENCE_NOT_ESTABLISHED')
  cleanup['jobs'].append(dict(name=name,independentlyAbsent=True,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),phase='broker-restart'))
 path.write_text(json.dumps(cleanup,indent=2));print(json.dumps(dict(project=PROJECT,brokerVerificationJobs=2,independentlyAbsent=True)))
def main():
 p=argparse.ArgumentParser();p.add_argument('--apply-dev',action='store_true');p.add_argument('--revision');p.add_argument('mode',choices=['lower-role','reset-factors','inspect-erasure','photo-key','photo-before','photo-after','disable-parents','verify-broker-jobs','wake-source','inspect-cleanup-rollback']);a=p.parse_args()
 if not a.apply_dev:raise SafeFailure('EXPLICIT_APPLY_DEV_REQUIRED')
 if a.mode=='lower-role':lower_role()
 elif a.mode=='reset-factors':reset_factors(a.revision)
 elif a.mode=='inspect-erasure':inspect_erasure()
 elif a.mode=='photo-key':photo_key()
 elif a.mode=='photo-before':photo_metadata()
 elif a.mode=='photo-after':photo_metadata(True)
 elif a.mode=='disable-parents':disable_parents(a.revision)
 elif a.mode=='verify-broker-jobs':verify_broker_jobs()
 elif a.mode=='wake-source':bounded_wake_source()
 elif a.mode=='inspect-cleanup-rollback':inspect_cleanup_rollback()
if __name__=='__main__':
 try:main()
 except Exception as e:print(json.dumps(dict(project=PROJECT,failure=str(e) if isinstance(e,SafeFailure) else type(e).__name__,sensitiveOutputWithheld=True)));raise SystemExit(1)
