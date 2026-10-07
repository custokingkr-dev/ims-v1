import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,urllib.request,urllib.error,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';base='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app';origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';config=json.loads((ROOT/'security-acceptance-private.json').read_text())['credentials'];checks=[]
def req(path,token=None,body=None,extra=None):
 headers={'Origin':origin,'Content-Type':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if extra:headers.update(extra)
 request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method='GET'if body is None else'POST')
 try:
  with urllib.request.urlopen(request,timeout=30)as r:
   text=r.read(262145)
   if len(text)>262144:raise RuntimeError('Response ceiling')
   return r.status,json.loads(text)if text else None
 except urllib.error.HTTPError as e:return e.code,None
for index,c in enumerate(config):
 status,user=req('/api/v1/auth/login',body=dict(email=c['email'],password=c['password']))
 if status!=200 or user['branchId']!=c['schoolId']:raise RuntimeError('Synthetic principal mismatch')
 token=user.get('token',user.get('accessToken'));own=990007301 if index==0 else 990007311;foreign=990007311 if index==0 else 990007301
 for name,student,extra in [('own-student-object',own,None),('foreign-student-object-denied',foreign,None),('forged-foreign-branch-headers-denied',foreign,{'X-Authenticated-User-Id':str(config[1-index]['userId']),'X-Authenticated-Branch-Id':str(config[1-index]['schoolId']),'X-IMS-Principal-Carrier-Token':'Bearer spoofed'})]:
  status,data=req('/api/v1/students/'+str(student),token=token,extra=extra);passed=(status==200 and data.get('id')==own and data.get('schoolId')==c['schoolId'])if name=='own-student-object'else status in(403,404)
  checks.append(dict(check=name,actorSchoolId=c['schoolId'],studentId=student,status=status,passed=passed))
  if not passed:raise RuntimeError('Object authorization invariant failed '+name+' status '+str(status))
proof=dict(schemaVersion=1,project='custoking-dev',marker='SEC-ACPT-20261007',checks=checks,passed=all(c['passed']for c in checks),checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(ROOT/'identity-object-authorization-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
