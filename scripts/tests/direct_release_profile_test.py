"""Execute the real direct-release preflight using read-only local gcloud fixtures."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
POWERSHELL=shutil.which('pwsh') or shutil.which('powershell.exe')
IMAGE='asia-south2-docker.pkg.dev/custoking-dev/custoking/identity@sha256:'+'a'*64

class DirectReleaseProfileTest(unittest.TestCase):
    def fixture(self,case,selected_service='identity-service'):
        with tempfile.TemporaryDirectory(prefix='ims-direct-profile-') as folder:
            directory=Path(folder);receipt=directory/'calls.jsonl';fixture=directory/'gcloud.py'
            fixture.write_text("""import json,os,sys
a=sys.argv[1:]
with open(os.environ['IMS_PROFILE_RECEIPT'],'a') as f:f.write(json.dumps(a)+'\\n')
case=os.environ['IMS_PROFILE_CASE'];image=os.environ['IMS_PROFILE_IMAGE']
if a[:3]==['run','services','describe']:
 service=a[3];gateway='api-gateway' in service;frontend='frontend' in service
 prefix=service.removeprefix('custoking-').removesuffix('-dev').removesuffix('-service')
 settings={'OTEL_RESOURCE_ATTRIBUTES':'gcp.project_id=custoking-dev,deployment.environment.name=dev,service.version='+'b'*40}
 if gateway:settings.update(GATEWAY_AUTH_MODE='enforce',GATEWAY_CLOUD_RUN_AUTH='auto',GATEWAY_LOCAL_JWT_VERIFY='disabled')
 elif not frontend:settings.update(APP_MIGRATIONS_ENABLED='false',SPRING_DATASOURCE_USERNAME='ims_'+prefix.replace('-','_')+'_rt',RUNTIME_DB_ROLE='ims_'+prefix.replace('-','_')+'_rt',DB_POOL_MAX='8' if prefix=='school-core' else '3')
 if case=='runtime-enabled' and not gateway:settings['APP_MIGRATIONS_ENABLED']='true'
 if case=='owner-env' and not gateway:settings['FLYWAY_URL']='jdbc:postgresql://fixture-only/app'
 if case=='shared-role' and not gateway:settings['SPRING_DATASOURCE_USERNAME']='app_rt'
 if case=='wrong-pool' and not gateway:settings['DB_POOL_MAX']='20'
 if case=='gateway-jwt' and gateway:settings['APP_JWT_SECRET']='controlled-fixture-only'
 env=[{'name':k,'value':v} for k,v in settings.items()]
 if not gateway and not frontend:env.append({'name':'SPRING_DATASOURCE_PASSWORD','valueFrom':{'secretKeyRef':{'name':'db-password-dev' if case=='owner-secret' else prefix+'-runtime-db-password-dev','key':'latest'}}})
 traffic={'latestRevision':False,'revisionName':'v1','percent':100} if case=='pinned-gateway' and gateway else {'latestRevision':True,'percent':100}
 print(json.dumps({'spec':{'traffic':[traffic],'template':{'spec':{'containers':[{'env':env,'ports':[{'containerPort':80 if (case=='gateway-port' and gateway) or (case=='frontend-port' and frontend) else 8080}]}]}}},'status':{'latestReadyRevisionName':'v1','latestCreatedRevisionName':'v1','traffic':[{'revisionName':'v1','percent':100}]}}))
elif a[:3]==['run','revisions','describe']:print(json.dumps({'status':{'imageDigest':image}}))
else:print('MUTATION ATTEMPT REFUSED BY FIXTURE',file=sys.stderr);sys.exit(9)
""")
            command=directory/('gcloud.cmd' if os.name=='nt' else 'gcloud')
            if os.name=='nt':command.write_text(f'@echo off\n"{sys_executable()}" "{fixture}" %*\nexit /b %errorlevel%\n')
            else:command.write_text(f'#!/bin/sh\nexec "{sys_executable()}" "{fixture}" "$@"\n');command.chmod(0o700)
            images=directory/'images.json'
            service='frontend' if case=='frontend-port' else selected_service
            images.write_text(json.dumps({'commit':'b'*40,'services':[{'service':service,'immutableRef':IMAGE,'runtimeRef':IMAGE}]}))
            environment=os.environ.copy();environment.update(PATH=str(directory)+os.pathsep+environment['PATH'],IMS_PROFILE_RECEIPT=str(receipt),IMS_PROFILE_CASE=case,IMS_PROFILE_IMAGE=IMAGE)
            result=subprocess.run([POWERSHELL,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/invoke-direct-cloudrun-release.ps1'),'-ProjectId','custoking-dev','-Region','asia-south2','-Environment','dev','-ImagesJson',str(images),'-OutputPath',str(directory/'deployment.json')],capture_output=True,text=True,env=environment)
            calls=[json.loads(line) for line in receipt.read_text().splitlines()]
            self.assertTrue(all(call[:3] in [['run','services','describe'],['run','revisions','describe']] for call in calls),'Preflight fixture must never mutate cloud resources')
            return result,calls

    def test_secure_current_profile_can_skip_without_cloud_mutation(self):
        result,calls=self.fixture('valid')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIn('alreadyCurrent=1',result.stdout)
        self.assertEqual(3,len(calls))

    def test_old_runtime_profiles_fail_before_any_image_or_migration_update(self):
        for case in ['runtime-enabled','owner-env','shared-role','owner-secret','wrong-pool']:
            with self.subTest(case=case):
                result,calls=self.fixture(case)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(2,len(calls))

    def test_all_five_dedicated_role_secret_and_pool_mappings_accept(self):
        for service in ['identity-service','school-core-service','operations-service','billing-service','platform-service']:
            with self.subTest(service=service):
                result,calls=self.fixture('valid',service)
                self.assertEqual(0,result.returncode,result.stderr)
                self.assertEqual(3,len(calls))

    def test_gateway_profile_and_pins_fail_even_for_backend_only_release(self):
        for case in ['pinned-gateway','gateway-port','gateway-jwt']:
            with self.subTest(case=case):
                result,calls=self.fixture(case)
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(1,len(calls))

    def test_frontend_port80_requires_reconciliation(self):
        result,calls=self.fixture('frontend-port')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('port8080',result.stderr)
        self.assertEqual(2,len(calls))

def sys_executable():
    import sys
    return sys.executable

if __name__=='__main__':unittest.main()
