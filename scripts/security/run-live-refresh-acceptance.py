import sys
if __name__ == '__main__' and '--apply-dev' not in sys.argv:
 raise SystemExit('Explicit --apply-dev is required; this tool targets isolated custoking-dev synthetic acceptance only')
import json,pathlib,urllib.request,urllib.error,http.cookiejar,datetime,concurrent.futures
ROOT=pathlib.Path(__file__).resolve().parents[2]/'tmp';config=json.loads((ROOT/'security-acceptance-private.json').read_text())['credentials'][1];origin='https://custoking-frontend-dev-hd4wfwk7mq-em.a.run.app';base='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app';jar=http.cookiejar.CookieJar();opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar));checks=[]
def req(method,path,body=None,cookie=None,token=None,requestOrigin=origin):
 headers={'Content-Type':'application/json','Origin':requestOrigin}
 if cookie:headers['Cookie']='refresh_token='+cookie
 if token:headers['Authorization']='Bearer '+token
 request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 client=opener if cookie is None else urllib.request.build_opener()
 try:
  with client.open(request,timeout=30)as r:
   text=r.read(262144);return r.status,json.loads(text)if text else None
 except urllib.error.HTTPError as e:return e.code,None
def check(name,result,expected):
 checks.append(dict(check=name,status=result[0],expected=expected,passed=result[0]==expected))
 if result[0]!=expected:raise RuntimeError('Refresh check failed: '+name+' status '+str(result[0]))
login=req('POST','/api/v1/auth/login',dict(email=config['email'],password=config['password']));check('synthetic-password-login',login,200);oldAccess=login[1].get('token',login[1].get('accessToken'));oldCookie=next(c.value for c in jar if c.name=='refresh_token')
check('foreign-origin-refresh-rejected-before-rotation',req('POST','/api/v1/auth/refresh',{},cookie=oldCookie,requestOrigin='https://attacker.invalid'),403)
rotated=req('POST','/api/v1/auth/refresh',{});check('allowlisted-origin-cookie-refresh',rotated,200);newAccess=rotated[1].get('token',rotated[1].get('accessToken'))
check('rotated-access-authoritative-accepted',req('GET',f"/api/v1/students?schoolId={config['schoolId']}&page=0&size=1",token=newAccess),200)
check('old-refresh-cookie-reuse-denied',req('POST','/api/v1/auth/refresh',{},cookie=oldCookie),401)
check('reuse-fences-old-access',req('GET',f"/api/v1/students?schoolId={config['schoolId']}&page=0&size=1",token=oldAccess),401)
check('reuse-fences-descendant-access',req('GET',f"/api/v1/students?schoolId={config['schoolId']}&page=0&size=1",token=newAccess),401)
second=req('POST','/api/v1/auth/login',dict(email=config['email'],password=config['password']));check('race-fixture-fresh-session',second,200);raceCookie=next(c.value for c in jar if c.name=='refresh_token')
with concurrent.futures.ThreadPoolExecutor(max_workers=2)as pool: race=list(pool.map(lambda _:req('POST','/api/v1/auth/refresh',{},cookie=raceCookie),range(2)))
statuses=sorted(r[0]for r in race);checks.append(dict(check='concurrent-refresh-single-winner-family-reuse-fenced',statuses=statuses,expected=[200,401],passed=statuses==[200,401]))
if statuses!=[200,401]:raise RuntimeError('Concurrent refresh unexpected status distribution')
winner=next(r[1]for r in race if r[0]==200);check('concurrent-reuse-fences-winner-access',req('GET',f"/api/v1/students?schoolId={config['schoolId']}&page=0&size=1",token=winner.get('token',winner.get('accessToken'))),401)
proof=dict(schemaVersion=1,project='custoking-dev',marker='SEC-ACPT-20261007',userId=config['userId'],checks=checks,passed=all(x['passed']for x in checks),checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat());(ROOT/'identity-refresh-live-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
