import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,secrets,runpy
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp'
cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud']
manifest=json.loads((ROOT/'security-acceptance-fixture-job.json').read_text())
job='ims-dev-security-fixture-modules-'+secrets.token_hex(8);manifest['metadata']['name']=job
sql="BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL app.bypass_rls='on'; DO $$ BEGIN IF (SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%')<>2 THEN RAISE EXCEPTION 'Synthetic ownership mismatch'; END IF; END $$; DELETE FROM tenant_school.school_module_entitlements WHERE school_id IN(990007101,990007102) AND module_code='ERP' AND notes='SEC-ACPT-20261007'; INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled,notes) SELECT school,code,true,'SEC-ACPT-20261007' FROM unnest(ARRAY[990007101::bigint,990007102::bigint]) school CROSS JOIN unnest(ARRAY['STUDENTS','ATTENDANCE']) code ON CONFLICT(school_id,module_code) DO NOTHING; COMMIT;"
manifest['spec']['template']['spec']['template']['spec']['containers'][0]['args']=['-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql]
path=ROOT/'security-acceptance-modules-job.json';path.write_text(json.dumps(manifest))
try:
 cloud(['run','jobs','replace',str(path),'--region=asia-south2','--quiet','--format=json'])
 result=cloud(['run','jobs','execute',job,'--region=asia-south2','--wait','--quiet','--format=json'])
 if not any(c.get('type')=='Completed' and c.get('status')=='True' for c in result.get('status',{}).get('conditions',[])): raise RuntimeError('Modules job failed')
 print('Synthetic STUDENTS/ATTENDANCE entitlements provisioned')
finally: cloud(['run','jobs','delete',job,'--region=asia-south2','--quiet','--format=json'])
