import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,secrets,runpy
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp'
cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud']
manifest=json.loads((ROOT/'security-acceptance-fixture-job.json').read_text());job='ims-dev-security-fixture-data-'+secrets.token_hex(8);manifest['metadata']['name']=job
sql="""BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL app.bypass_rls='on'; DO $$ BEGIN IF (SELECT count(*) FROM tenant_school.schools WHERE id IN(990007101,990007102) AND name LIKE 'SEC-ACPT-20261007-%')<>2 OR EXISTS(SELECT 1 FROM student.students WHERE id BETWEEN 990007301 AND 990007320) THEN RAISE EXCEPTION 'Synthetic ownership/collision mismatch'; END IF; END $$;
INSERT INTO tenant_school.school_classes(id,name,sort_order) VALUES('SEC-ACPT-20261007-CLASS','Synthetic Security Class',99);
INSERT INTO tenant_school.academic_years(id,label,active) VALUES('SEC-ACPT-20261007-YEAR','Synthetic Security Year',true);
INSERT INTO tenant_school.school_sections(id,name,active,school_class_id,school_id) SELECT 'SEC-ACPT-20261007-SECTION-'||school,'Synthetic Security Section',true,'SEC-ACPT-20261007-CLASS',school FROM unnest(ARRAY[990007101::bigint,990007102::bigint]) school;
INSERT INTO student.students(id,admission_no,full_name,created_at,updated_at,school_id,class_id,section_id,academic_year_id,created_by) SELECT 990007300+n,'SEC-ACPT-20261007-STUDENT-'||n,'Synthetic Security Student '||n,now(),now(),CASE WHEN n<=10 THEN 990007101 ELSE 990007102 END,'SEC-ACPT-20261007-CLASS','SEC-ACPT-20261007-SECTION-'||(CASE WHEN n<=10 THEN 990007101 ELSE 990007102 END),'SEC-ACPT-20261007-YEAR','SEC-ACPT-20261007' FROM generate_series(1,20) n;
DELETE FROM identity.passkey_credentials WHERE user_id IN(990007201,990007202);
DELETE FROM identity.session_step_up WHERE user_id IN(990007201,990007202);
DELETE FROM identity.passkey_challenges WHERE user_id IN(990007201,990007202);
INSERT INTO identity.rbac_audit_log(event_type,target_user_id,new_value,correlation_id) SELECT 'SYNTHETIC_FIXTURE_AUTHENTICATOR_RESET',id,'Owner maintenance of isolated virtual-authenticator acceptance fixtures only','SEC-ACPT-20261007' FROM identity.app_users WHERE id IN(990007201,990007202);
COMMIT;"""
manifest['spec']['template']['spec']['template']['spec']['containers'][0]['args']=['-X','-A','-t','-v','ON_ERROR_STOP=1','-c',sql];path=ROOT/'security-acceptance-data-job.json';path.write_text(json.dumps(manifest))
try:
 cloud(['run','jobs','replace',str(path),'--region=asia-south2','--quiet','--format=json']);result=cloud(['run','jobs','execute',job,'--region=asia-south2','--wait','--quiet','--format=json'])
 if not any(c.get('type')=='Completed' and c.get('status')=='True' for c in result.get('status',{}).get('conditions',[])): raise RuntimeError('Synthetic student job failed')
 print('20 isolated synthetic students created; temporary virtual factors reset with explicit maintenance audit')
finally: cloud(['run','jobs','delete',job,'--region=asia-south2','--quiet','--format=json'])
