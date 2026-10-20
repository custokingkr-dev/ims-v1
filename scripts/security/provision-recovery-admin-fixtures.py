import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,secrets,bcrypt,runpy
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud']
private=json.loads((ROOT/'security-acceptance-private.json').read_text());admins=[];statements=[]
for uid in (990007203,990007204):
 email=f'sec-acpt-20261007-recovery-{uid}@security-fixture.invalid';password=secrets.token_urlsafe(32);hashed=bcrypt.hashpw(password.encode(),bcrypt.gensalt(rounds=12)).decode();admins.append(dict(userId=uid,email=email,password=password,schoolId=990007101,role='SUPERADMIN'))
 statements.append(f"INSERT INTO identity.app_users(id,full_name,email,password_hash,role,branch_id,branch_name,created_at) VALUES({uid},'SEC-ACPT-20261007-RECOVERY-{uid}','{email}','{hashed}','SUPERADMIN',990007101,'SEC-ACPT-20261007-1',now()); INSERT INTO identity.user_role_assignments(user_id,role_id,school_id,active) SELECT {uid},id,990007101,true FROM identity.roles WHERE name='SUPERADMIN';")
sql="BEGIN; SET LOCAL statement_timeout='15s'; DO $$ BEGIN IF EXISTS(SELECT 1 FROM identity.app_users WHERE id IN(990007203,990007204)) OR (SELECT count(*) FROM identity.roles WHERE name='SUPERADMIN')<>1 THEN RAISE EXCEPTION 'Recovery fixture collision/role mismatch'; END IF; END $$;"+''.join(statements)+"COMMIT;"
private['recoveryAdmins']=admins;(ROOT/'security-acceptance-private.json').write_text(json.dumps(private))
manifest=json.loads((ROOT/'security-acceptance-fixture-job.json').read_text());job='ims-dev-security-fixture-recovery-'+secrets.token_hex(8);manifest['metadata']['name']=job;manifest['spec']['template']['spec']['template']['spec']['containers'][0]['args']=['-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql];path=ROOT/'security-acceptance-recovery-job.json';path.write_text(json.dumps(manifest))
try:
 cloud(['run','jobs','replace',str(path),'--region=asia-south2','--quiet','--format=json']);result=cloud(['run','jobs','execute',job,'--region=asia-south2','--wait','--quiet','--format=json'])
 if not any(c.get('type')=='Completed' and c.get('status')=='True' for c in result.get('status',{}).get('conditions',[])): raise RuntimeError('Recovery fixtures failed')
 print('Two reserved independent recovery actors created; no existing identity touched')
finally: cloud(['run','jobs','delete',job,'--region=asia-south2','--quiet','--format=json'])
