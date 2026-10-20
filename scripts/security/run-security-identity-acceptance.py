import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,urllib.request,urllib.error,http.cookiejar,datetime
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp'
BASE='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app'
ORIGIN='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app'
credentials=json.loads((ROOT/'security-acceptance-private.json').read_text())['credentials']
checks=[];tokens=[]
def request(opener,method,path,body=None,token=None):
 headers={'Origin':ORIGIN,'Content-Type':'application/json'}
 if token: headers['Authorization']='Bearer '+token
 req=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 try:
  with opener.open(req,timeout=30) as r:
   data=r.read(262145)
   if len(data)>262144: raise RuntimeError('Response ceiling')
   return r.status,json.loads(data) if data else None
 except urllib.error.HTTPError as e: return e.code,None
for c in credentials:
 jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
 status,data=request(opener,'POST','/api/v1/auth/login',dict(email=c['email'],password=c['password']))
 if status!=200: raise RuntimeError('Synthetic login status '+str(status))
 if data.get('branchId')!=c['schoolId'] or data.get('role')!='SCHOOL_ADMIN' or data.get('id',data.get('userId'))!=c['userId']: raise RuntimeError('Authoritative login principal mismatch')
 token=data['token'] if 'token' in data else data['accessToken']
 checks.append(dict(check='login-authoritative-school-role',schoolId=c['schoolId'],status=status,passed=True))
 for school in (c['schoolId'],990007102 if c['schoolId']==990007101 else 990007101):
  status,rows=request(opener,'GET',f'/api/v1/students?schoolId={school}&page=0&size=1',token=token)
  expected=200 if school==c['schoolId'] else 403
  checks.append(dict(check='own-school-read' if expected==200 else 'foreign-school-denied',actorSchoolId=c['schoolId'],requestedSchoolId=school,status=status,passed=status==expected))
  if status!=expected: raise RuntimeError('Tenant check failed '+str(status))
 tokens.append(dict(schoolId=c['schoolId'],userId=c['userId'],role=c['role'],accessToken=token))
 status,_=request(opener,'POST','/api/v1/auth/logout',{})
 if status!=204: raise RuntimeError('Logout failed '+str(status))
 status,_=request(opener,'GET',f"/api/v1/students?schoolId={c['schoolId']}&page=0&size=1",token=token)
 checks.append(dict(check='logout-revokes-access-session',schoolId=c['schoolId'],status=status,passed=status==401))
 if status!=401: raise RuntimeError('Revoked access accepted')
 # Fresh session reserved for coordinated bounded acceptance, never retained in permanent evidence.
 status,data=request(opener,'POST','/api/v1/auth/login',dict(email=c['email'],password=c['password']))
 if status!=200: raise RuntimeError('Synthetic relogin failed')
 tokens[-1]['accessToken']=data.get('token',data.get('accessToken'))
proof=dict(schemaVersion=1,project='custoking-dev',marker='SEC-ACPT-20261007',checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),checks=checks,passed=all(x['passed'] for x in checks),syntheticOnly=True)
(ROOT/'security-acceptance-token-private.json').write_text(json.dumps(dict(credentials=tokens)))
(ROOT/'security-identity-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
