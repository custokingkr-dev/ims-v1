import copy
import datetime
import importlib.util
import json
from pathlib import Path
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('bounded_read',Path(__file__).resolve().parents[1]/'security/bounded-dev-read-acceptance.py')
MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)

class BoundedReadAcceptanceTest(unittest.TestCase):
    def test_budget_logging_project_and_freshness_fail_closed(self):
        now=datetime.datetime.now(datetime.timezone.utc)
        proof={'project':'custoking-dev','instance':'custoking-db-dev','capturedAt':now.isoformat(),'billing':{'currency':'INR','scopeProject':'custoking-dev','grossMonthInr':100,'budgetInr':2000,'latestExportAt':now.isoformat()},'logging':{'monthGib':1,'observedAt':now.isoformat()}}
        MODULE.budget_guard(proof,now)
        for keys,value in [(('project',),'custoking-prod'),(('capturedAt',),(now+datetime.timedelta(minutes=1)).isoformat()),(('billing','grossMonthInr'),1590),(('billing','latestExportAt'),(now-datetime.timedelta(hours=25)).isoformat()),(('logging','monthGib'),40),(('logging','observedAt'),(now-datetime.timedelta(hours=3)).isoformat())]:
            candidate=copy.deepcopy(proof);target=candidate
            for key in keys[:-1]:target=target[key]
            target[keys[-1]]=value
            with self.subTest(keys=keys),self.assertRaises(ValueError):MODULE.budget_guard(candidate,now)

    def test_monitor_stop_thresholds_missing_and_stale_samples(self):
        now=datetime.datetime.now(datetime.timezone.utc)
        def sample(value,at=now):return {'timeSeries':[{'points':[{'interval':{'endTime':at.isoformat()},'value':{'doubleValue':value}}]}]}
        with patch.object(MODULE,'read',side_effect=[sample(.1),sample(20),sample(10)]):self.assertEqual(10,MODULE.monitor('synthetic')['connections'])
        for values in [(.8,20,10),(.1,90,10),(.1,20,140)]:
            with patch.object(MODULE,'read',side_effect=[sample(v) for v in values]),self.assertRaises(ValueError):MODULE.monitor('synthetic')
        for response in [{'timeSeries':[]},sample(.1,now-datetime.timedelta(minutes=6)),sample(.1,now+datetime.timedelta(minutes=1))]:
            with patch.object(MODULE,'read',return_value=response),self.assertRaises(ValueError):MODULE.monitor('synthetic')

    def test_actual_http_redirect_cap_and_trickle_deadline(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                if self.path=='/redirect':self.send_response(302);self.send_header('Location','/destination');self.end_headers();return
                self.send_response(200);self.end_headers()
                try:
                    if self.path=='/trickle':
                        for _ in range(50):self.wfile.write(b' ');self.wfile.flush();time.sleep(.03)
                    else:self.wfile.write(json.dumps({'synthetic':True}).encode())
                except OSError:pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        origin=f'http://127.0.0.1:{server.server_port}'
        try:
            self.assertEqual({'synthetic':True},MODULE.read(origin+'/ok','fixture-only'))
            with self.assertRaises(ValueError):MODULE.read(origin+'/redirect','fixture-only')
            with self.assertRaises(ValueError):MODULE.read(origin+'/ok','fixture-only',cap=5)
            started=time.monotonic()
            with self.assertRaises(ValueError):MODULE.read(origin+'/trickle','fixture-only',deadline_seconds=.15)
            self.assertLess(time.monotonic()-started,1)
        finally:server.shutdown();server.server_close();worker.join(timeout=1)

if __name__=='__main__':unittest.main()
