import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,runpy,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp'
cloud=runpy.run_path(str(pathlib.Path(__file__).resolve().with_name('provision-security-acceptance.py')))['cloud']
steps=[]
def apply(label,args):
 result=cloud(args+['--quiet','--format=json']);steps.append(label);return result
apply('firestore-api-enabled',['services','enable','firestore.googleapis.com'])
apply('dashboard-service-account-created',['iam','service-accounts','create','ims-dashboard','--display-name=Dev dashboard isolated security state'])
apply('named-native-database-delete-protected',['firestore','databases','create','--database=ims-dashboard-dev','--location=asia-south2','--type=firestore-native','--delete-protection'])
apply('least-privilege-custom-role-created',['iam','roles','create','dashboardSecurityState_dev','--title=Dashboard security state dev','--description=Read and atomically create short-lived dashboard replay/revocation records only','--permissions=datastore.entities.get,datastore.entities.create','--stage=GA'])
apply('named-database-only-conditional-binding',['projects','add-iam-policy-binding','custoking-dev','--member=serviceAccount:ims-dashboard@custoking-dev.iam.gserviceaccount.com','--role=projects/custoking-dev/roles/dashboardSecurityState_dev',"--condition=expression=resource.name == 'projects/custoking-dev/databases/ims-dashboard-dev',title=dashboard-named-database-only"])
apply('ttl-enable-requested',['firestore','fields','ttls','update','expiresAt','--database=ims-dashboard-dev','--collection-group=dashboardSecurityState','--enable-ttl','--async'])
proof=dict(schemaVersion=1,project='custoking-dev',database='ims-dashboard-dev',serviceAccount='ims-dashboard@custoking-dev.iam.gserviceaccount.com',permissions=['datastore.entities.get','datastore.entities.create'],condition="resource.name == 'projects/custoking-dev/databases/ims-dashboard-dev'",steps=steps,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),dashboardWebDeployed=False,reason='Dedicated OAuth configuration unavailable; auth controls remain enabled',asyncTtlDeletionObserved=False)
(ROOT/'dashboard-state-provision-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
