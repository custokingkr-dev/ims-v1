import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,secrets,bcrypt,subprocess,datetime,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp'
PROJECT='custoking-dev';REGION='asia-south2';MARKER='SEC-ACPT-20261007'
def cloud(args):
 timeout=540 if 'execute' in args and '--wait' in args else 300 if 'replace' in args else 60
 try:p=subprocess.run(['gcloud.cmd',*args,'--project='+PROJECT],capture_output=True,text=True,timeout=timeout)
 except subprocess.TimeoutExpired:raise RuntimeError('Cloud operation deadline exceeded; output withheld')from None
 if p.returncode: raise RuntimeError('Cloud operation failed; output withheld')
 return json.loads(p.stdout) if p.stdout.strip() else {}
def prepare():
 credentials=[]; inserts=[]
 for index,school in enumerate((990007101,990007102)):
  email=f'sec-acpt-20261007-{index+1}@security-fixture.invalid';uid=990007201+index
  password=secrets.token_urlsafe(32);hashed=bcrypt.hashpw(password.encode(),bcrypt.gensalt(rounds=12)).decode()
  credentials.append(dict(schoolId=school,userId=uid,email=email,password=password,role='SCHOOL_ADMIN'))
  inserts.append(f"INSERT INTO tenant_school.schools(id,name,short_code,active,created_at) VALUES({school},'{MARKER}-{index+1}','SECACPT0710{index+1}',true,now()); INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled,notes) VALUES({school},'STUDENTS',true,'{MARKER}'),({school},'ATTENDANCE',true,'{MARKER}'); INSERT INTO identity.app_users(id,full_name,email,password_hash,role,branch_id,branch_name,created_at) VALUES({uid},'{MARKER}-{index+1}','{email}','{hashed}','SCHOOL_ADMIN',{school},'{MARKER}-{index+1}',now()); INSERT INTO identity.user_role_assignments(user_id,role_id,school_id,active) SELECT {uid},id,{school},true FROM identity.roles WHERE name='SCHOOL_ADMIN';")
 sql="BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='5s'; SET LOCAL app.bypass_rls='on'; DO $$ BEGIN IF EXISTS(SELECT 1 FROM tenant_school.schools WHERE id IN(990007101,990007102)) OR EXISTS(SELECT 1 FROM identity.app_users WHERE id IN(990007201,990007202)) THEN RAISE EXCEPTION 'Reserved fixture identifiers already occupied; stop'; END IF; IF (SELECT count(*) FROM identity.roles WHERE name='SCHOOL_ADMIN')<>1 THEN RAISE EXCEPTION 'Required role missing'; END IF; END $$;"+''.join(inserts)+"SELECT jsonb_build_object('marker','SEC-ACPT-20261007','schools',(SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%'),'users',(SELECT count(*) FROM identity.app_users WHERE id IN(990007201,990007202) AND email LIKE 'sec-acpt-20261007-%@security-fixture.invalid'),'assignments',(SELECT count(*) FROM identity.user_role_assignments WHERE user_id IN(990007201,990007202) AND active)); COMMIT;"
 job='ims-dev-security-fixture-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
 env=[dict(name=k,value=v) for k,v in dict(PGHOST='10.92.0.3',PGDATABASE='custoking_dev',PGUSER='appuser',PGSSLMODE='require',PGCONNECT_TIMEOUT='10').items()]+[dict(name='PGPASSWORD',valueFrom=dict(secretKeyRef=dict(name='db-password-dev',key='latest')))]
 manifest=dict(apiVersion='run.googleapis.com/v1',kind='Job',metadata=dict(name=job),spec=dict(template=dict(metadata=dict(annotations={'run.googleapis.com/network-interfaces':'[{"network":"default","subnetwork":"default"}]','run.googleapis.com/vpc-access-egress':'private-ranges-only'}),spec=dict(taskCount=1,template=dict(spec=dict(serviceAccountName='ims-db-migration-dev@custoking-dev.iam.gserviceaccount.com',maxRetries=0,timeoutSeconds=60,containers=[dict(image='docker.io/library/postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b',command=['psql'],args=['-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql],resources=dict(limits=dict(cpu='1',memory='512Mi')),env=env)]))))))
 (ROOT/'security-acceptance-private.json').write_text(json.dumps(dict(marker=MARKER,credentials=credentials)))
 path=ROOT/'security-acceptance-fixture-job.json';path.write_text(json.dumps(manifest));return job,path
if __name__=='__main__':
 job,path=prepare();attempted=False
 try:
  attempted=True;cloud(['run','jobs','replace',str(path),'--region='+REGION,'--quiet','--format=json'])
  result=cloud(['run','jobs','execute',job,'--region='+REGION,'--wait','--quiet','--format=json'])
  if not any(c.get('type')=='Completed' and c.get('status')=='True' for c in result.get('status',{}).get('conditions',[])): raise RuntimeError('Fixture execution not successful')
  proof=dict(project=PROJECT,marker=MARKER,job=job,completed=True,schoolIds=[990007101,990007102],userIds=[990007201,990007202],role='SCHOOL_ADMIN',reservedOnly=True,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
  (ROOT/'security-acceptance-fixture-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
 finally:
  if attempted: cloud(['run','jobs','delete',job,'--region='+REGION,'--quiet','--format=json'])
