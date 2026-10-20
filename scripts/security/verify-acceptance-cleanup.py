import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,subprocess,datetime,urllib.request,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';rotation=json.loads((ROOT/'identity-managed-rotation-live-proof.json').read_text());nonce=rotation['isolatedPrivateService'].removeprefix('ims-dev-identity-rotation-');rows=[]
for kind,name in [('services',rotation['isolatedPrivateService']),('jobs','ims-dev-key-rotation-probe-'+nonce),('secrets','ims-dev-rotation-key-'+nonce),('secrets','ims-dev-rotation-fixture-'+nonce),('services','ims-dev-identity-rotation-b1239c11ca0b'),('jobs','ims-dev-key-rotation-probe-b1239c11ca0b'),('secrets','ims-dev-rotation-key-b1239c11ca0b'),('secrets','ims-dev-rotation-fixture-b1239c11ca0b')]:
 args=['gcloud.cmd','run',kind,'describe',name,'--region=asia-south2']if kind in('services','jobs')else['gcloud.cmd','secrets','describe',name]
 result=subprocess.run(args+['--project=custoking-dev','--format=json','--quiet'],capture_output=True,text=True);absent=result.returncode!=0 and('NOT_FOUND'in result.stderr or'not found'in result.stderr.lower()or'does not exist'in result.stderr.lower()or'cannot find'in result.stderr.lower());rows.append(dict(kind=kind,name=name,independentlyAbsent=absent))
 if not absent:raise RuntimeError('Cleanup absence not established: '+kind)
for file in ['security-acceptance-fixture-job.json','security-acceptance-modules-job.json','security-acceptance-data-job.json','security-acceptance-recovery-job.json','security-acceptance-disable-job.json','dashboard-state-probe-A.json','dashboard-state-probe-B.json','dashboard-auth-probe.json','dashboard-ttl-job.json']:
 name=json.loads((ROOT/file).read_text())['metadata']['name'];result=subprocess.run(['gcloud.cmd','run','jobs','describe',name,'--region=asia-south2','--project=custoking-dev','--format=json','--quiet'],capture_output=True,text=True);absent=result.returncode!=0 and('NOT_FOUND'in result.stderr or'not found'in result.stderr.lower()or'does not exist'in result.stderr.lower()or'cannot find'in result.stderr.lower());rows.append(dict(kind='jobs',name=name,independentlyAbsent=absent))
 if not absent:raise RuntimeError('Temporary fixture/probe job absence not established')
admins=json.loads((ROOT/'security-acceptance-private.json').read_text())['recoveryAdmins'];auth=[]
for admin in admins:
 request=urllib.request.Request('https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app/api/v1/auth/login',data=json.dumps(dict(email=admin['email'],password=admin['password'])).encode(),headers={'Origin':'https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app','Content-Type':'application/json'},method='POST')
 try:
  with urllib.request.urlopen(request,timeout=30)as response:status=response.status
 except urllib.error.HTTPError as error:status=error.code
 auth.append(dict(userId=admin['userId'],loginStatus=status,disabledVerified=status==401))
 if status!=401:raise RuntimeError('Disabled privileged synthetic actor login not rejected')
proof=dict(project='custoking-dev',temporaryResources=rows,privilegedRecoveryActors=auth,schoolFixtureCleanupHeldForPitrAndBroker=True,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(ROOT/'security-acceptance-cleanup-proof.json').write_text(json.dumps(proof,indent=2));print('Temporary resource absence and privileged actor rejection independently verified')
