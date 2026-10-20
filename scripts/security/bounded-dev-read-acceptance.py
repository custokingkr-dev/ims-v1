"""Fixed dev synthetic read acceptance, not population/soak certification.

No writes, credential output, guard overrides, redirects or response persistence.
"""
import argparse, concurrent.futures, datetime, json, math, pathlib, shutil, socket, subprocess, threading, time, urllib.error, urllib.parse, urllib.request

ORIGIN='https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app'
MARKER='SEC-ACPT-20261007'
IDENTITIES={990007101:990007201,990007102:990007202}
UTC=datetime.timezone.utc

def date(value):
    parsed=datetime.datetime.fromisoformat(value.replace('Z','+00:00'))
    if parsed.tzinfo is None: raise ValueError('Timezone required')
    return parsed

def load(path): return json.loads(pathlib.Path(path).read_text(encoding='utf-8-sig'))

def budget_guard(proof,now):
    if proof.get('project')!='custoking-dev' or proof.get('instance')!='custoking-db-dev' or not datetime.timedelta(0)<=now-date(proof['capturedAt'])<=datetime.timedelta(minutes=30): raise ValueError('Fresh exact dev preflight required')
    billing=proof['billing']; logging=proof['logging']
    if billing.get('currency')!='INR' or billing.get('scopeProject')!='custoking-dev' or billing['grossMonthInr']<0 or billing['grossMonthInr']+15>=billing['budgetInr']*.8 or not datetime.timedelta(0)<=now-date(billing['latestExportAt'])<=datetime.timedelta(hours=24): raise ValueError('Budget/freshness guard rejected')
    if logging['monthGib']<0 or logging['monthGib']+.01>=50*.8 or not datetime.timedelta(0)<=now-date(logging['observedAt'])<=datetime.timedelta(hours=2): raise ValueError('Logging allowance/freshness guard rejected')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): raise ValueError('Redirect refused')

def read(url,token,cap=524288,deadline_seconds=8):
    request=urllib.request.Request(url,headers={'Authorization':'Bearer '+token,'Accept':'application/json'},method='GET')
    started=time.monotonic()
    with urllib.request.build_opener(NoRedirect).open(request,timeout=deadline_seconds) as response:
        connection=response.fp.raw._sock
        def abort():
            try: connection.shutdown(socket.SHUT_RDWR)
            except OSError: pass
        timer=threading.Timer(max(.001,deadline_seconds-(time.monotonic()-started)),abort);timer.daemon=True;timer.start()
        try:
            chunks=[];size=0
            while True:
                if time.monotonic()-started>=deadline_seconds: raise ValueError('Whole-body deadline reached')
                chunk=response.read1(min(65536,cap+1-size))
                if not chunk: break
                chunks.append(chunk);size+=len(chunk)
                if size>cap: raise ValueError('Body cap exceeded')
            if time.monotonic()-started>=deadline_seconds: raise ValueError('Whole-body deadline reached')
            if response.status!=200: raise ValueError('Unexpected HTTP status')
            return json.loads(b''.join(chunks))
        except OSError: raise ValueError('HTTP body aborted or transport failed') from None
        finally: timer.cancel()

def monitor(token):
    now=datetime.datetime.now(UTC); values={}
    for label,metric,extra in [('cpu','cpu/utilization',''),('memoryUsagePercent','memory/components',' AND metric.labels.component="Usage"'),('connections','postgresql/num_backends','')]:
        filt='metric.type="cloudsql.googleapis.com/database/'+metric+'" AND resource.type="cloudsql_database" AND resource.labels.database_id="custoking-dev:custoking-db-dev"'+extra
        query=urllib.parse.urlencode({'filter':filt,'interval.startTime':(now-datetime.timedelta(minutes=10)).isoformat(),'interval.endTime':now.isoformat(),'view':'FULL','pageSize':1000})
        data=read('https://monitoring.googleapis.com/v3/projects/custoking-dev/timeSeries?'+query,token)
        if data.get('nextPageToken'): raise ValueError('Monitoring truncation refused')
        samples=[]
        for series in data.get('timeSeries',[]):
            points=sorted(series.get('points',[]),key=lambda p:date(p['interval']['endTime']),reverse=True)
            if not points or not datetime.timedelta(0)<=now-date(points[0]['interval']['endTime'])<=datetime.timedelta(minutes=5): raise ValueError('Monitoring freshness guard rejected')
            value=points[0]['value']; samples.append(float(value.get('doubleValue',value.get('int64Value'))))
        if not samples: raise ValueError('Monitoring missing')
        values[label]=sum(samples) if label=='connections' else max(samples)
    if any(value<0 for value in values.values()) or values['cpu']>=.8 or values['memoryUsagePercent']>=90 or values['connections']>=140: raise ValueError('Database stop threshold reached')
    return {'at':now.isoformat(),**values}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--token-file',required=True);parser.add_argument('--fixture-proof',required=True);parser.add_argument('--preflight',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
    output=pathlib.Path(args.output)
    if output.exists(): raise ValueError('Output overwrite refused')
    now=datetime.datetime.now(UTC);budget_guard(load(args.preflight),now)
    fixture=load(args.fixture_proof)
    if fixture.get('project')!='custoking-dev' or fixture.get('marker')!=MARKER or fixture.get('completed') is not True or fixture.get('reservedOnly') is not True or set(fixture['schoolIds'])!=set(IDENTITIES): raise ValueError('Exact reserved fixture proof required')
    credentials=load(args.token_file)['credentials']
    if len(credentials)!=2 or {p.get('schoolId') for p in credentials}!=set(IDENTITIES) or any(p.get('userId')!=IDENTITIES[p['schoolId']] or p.get('role')!='SCHOOL_ADMIN' for p in credentials): raise ValueError('Exact nonadmin principals required')
    command=shutil.which('gcloud.cmd') or shutil.which('gcloud')
    auth=subprocess.run([command,'auth','print-access-token','--project=custoking-dev'],capture_output=True,text=True,timeout=20)
    if auth.returncode: raise ValueError('Monitoring authentication failed')
    monitoring_token=auth.stdout.strip()
    measurements=[monitor(monitoring_token)]
    for p in credentials:
        data=read(ORIGIN+'/api/v1/students?'+urllib.parse.urlencode({'schoolId':p['schoolId'],'page':0,'size':5}),p['accessToken'])
        rows=data if isinstance(data,list) else data.get('items',data.get('content',data.get('students',[])))
        if not rows or any(not str(row.get('admissionNumber',row.get('admissionNo',row.get('admission_no','')))).startswith(MARKER) for row in rows): raise ValueError('Nonempty synthetic row provenance required')
    started=time.monotonic(); stop=threading.Event(); latencies=[]; statuses={};failure=None
    def request(index):
        if stop.is_set(): return None
        p=credentials[index%2]; before=time.monotonic()
        try:
            read(ORIGIN+'/api/v1/students?'+urllib.parse.urlencode({'schoolId':p['schoolId'],'page':0,'size':5}),p['accessToken'])
            return time.monotonic()-before
        except Exception:
            stop.set(); raise RuntimeError('Request failed; response and credentials suppressed')
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            pending=[]; last_monitor=time.monotonic()
            for index in range(240):
                if stop.is_set() or time.monotonic()-started>180: raise ValueError('Bounded run stopped')
                if time.monotonic()-last_monitor>=20: measurements.append(monitor(monitoring_token));last_monitor=time.monotonic()
                pending=[f for f in pending if not f.done()]
                if len(pending)>=2: raise ValueError('Request concurrency backpressure reached; do not increase load')
                future=executor.submit(request,index);pending.append(future)
                future.add_done_callback(lambda f: latencies.append(f.result()) if not f.exception() and f.result() is not None else stop.set())
                time.sleep(.5)
            for future in pending: future.result(timeout=10)
        measurements.append(monitor(monitoring_token))
    except Exception as error:
        stop.set();failure=str(error) if isinstance(error,ValueError) else 'Bounded request failure; details suppressed'
    elapsed=time.monotonic()-started;ordered=sorted(latencies)
    result={'scope':'Dev bounded synthetic school-admin student-list reads; not scale/soak certification','startedAtUtc':now.isoformat(),'success':failure is None and len(latencies)==240,'requestCount':len(latencies),'targetRequestCount':240,'requestsPerSecondLimit':2,'concurrencyLimit':2,'elapsedSeconds':round(elapsed,3),'p95Milliseconds':round(ordered[max(0,math.ceil(len(ordered)*.95)-1)]*1000,3) if ordered else None,'databaseObservations':measurements,'guardThresholds':{'cpuRatio':.8,'memoryUsagePercent':90,'connections':140},'syntheticSchools':sorted(IDENTITIES),'principalRole':'SCHOOL_ADMIN','writeRequests':0,'secretsPersisted':False,'businessResponseRowsPersisted':False,'failure':failure}
    output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'success':result['success'],'requestCount':len(latencies),'p95Milliseconds':result['p95Milliseconds']}));return 0 if result['success'] else 1

if __name__=='__main__':
    try: raise SystemExit(main())
    except (ValueError,KeyError,urllib.error.URLError,subprocess.TimeoutExpired): raise SystemExit('Acceptance preflight refused; no workload certificate produced.')
