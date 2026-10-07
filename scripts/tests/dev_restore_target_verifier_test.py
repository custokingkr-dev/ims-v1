"""Controlled metadata/source tests; no cloud access."""
import copy
import importlib.util
import pathlib
import unittest
import sys
import threading
import time
from unittest.mock import patch

PATH = pathlib.Path(__file__).resolve().parents[1] / 'security/verify-dev-restore-target.py'
spec = importlib.util.spec_from_file_location('restore_verifier', PATH)
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
TARGET = 'custoking-dev-security-restore-20261007120000-1234abcd'

def record(name, ip, cert):
    return {'name':name,'project':'custoking-dev','region':'asia-south2','state':'RUNNABLE',
            'settings':{'ipConfiguration':{'serverCaMode':'GOOGLE_MANAGED_INTERNAL_CA','ipv4Enabled':False}},
            'ipAddresses':[{'type':'PRIVATE','ipAddress':ip}], 'serverCaCert':{'cert':cert}}

def rows():
    return [[schema, trigger, 'O', function, v.expected_body(schema,function,path), *v.TRIGGER_SHAPES[trigger]]
            for schema,trigger,function,path in v.FENCES]

class RestoreTargetTests(unittest.TestCase):
    def test_all_repository_bound_bodies_available(self):
        self.assertEqual(7,v.verify_fences(rows()))

    def test_disabled_changed_body_relation_event_and_payload_column_rejected(self):
        for index,value in ((2,'D'),(3,'different'),(4,'BEGIN RETURN NEW; END'),(5,'other'),(6,21),(7,['other'])):
            with self.subTest(index=index):
                changed=rows();changed[1][index]=value
                with self.assertRaises(ValueError):v.verify_fences(changed)

    def test_missing_fence_rejected(self):
        with self.assertRaises(ValueError):v.verify_fences(rows()[1:])

    def test_source_and_arbitrary_targets_rejected_before_api(self):
        for name in (v.SOURCE,TARGET+'x','foreign-project-clone'):
            with patch.object(v,'metadata') as api:
                with self.assertRaises(ValueError):v.run(name)
                api.assert_not_called()

    def test_metadata_only_never_connects_or_authorizes_resume(self):
        with patch.object(v,'metadata'),patch.object(v,'validate_pair',return_value=('10.1.1.2','ca','a'*64)),patch.object(v,'inspect_catalog') as sql:
            proof=v.run(TARGET)
            sql.assert_not_called()
            self.assertFalse(proof['restorationReady']);self.assertFalse(proof['deliveryResume'])
            self.assertFalse(proof['physicalCatalogConnectionVerified'])

    def test_changed_ca_after_connection_rejected(self):
        with patch.object(v,'metadata'),patch.object(v,'validate_pair',side_effect=[('10.1.1.2','ca','a'*64),('10.1.1.2','other','b'*64)]),patch.object(v,'inspect_catalog',return_value=7):
            with self.assertRaises(ValueError):v.run(TARGET,True)

    def test_shared_public_or_wrong_identity_metadata_rejected(self):
        for alteration in ('shared','public','foreign','mixed'):
            target=record(TARGET,'10.1.1.2','irrelevant')
            if alteration=='shared':target['settings']['ipConfiguration']['serverCaMode']='GOOGLE_MANAGED_CAS_CA'
            if alteration=='public':target['settings']['ipConfiguration']['ipv4Enabled']=True
            if alteration=='foreign':target['project']='other'
            if alteration=='mixed':target['ipAddresses'].append({'type':'PRIMARY','ipAddress':'1.2.3.4'})
            with self.assertRaises(ValueError):v.anchor(target,TARGET)

    def test_rotated_cert_list_rejected(self):
        import json
        target=record(TARGET,'10.1.1.2','ca')
        with patch.object(v.shutil,'which',return_value='gcloud'),patch.object(v,'bounded_command',side_effect=[json.dumps(target).encode(),b'[{"cert":"ca"},{"cert":"other"}]']):
            with self.assertRaises(ValueError):v.metadata(TARGET)

    def test_each_mandatory_runtime_check_fail_closed(self):
        v.verify_safety((True,)*8)
        for index in range(8):
            values=[True]*8;values[index]=False
            with self.assertRaises(ValueError):v.verify_safety(values)

    def test_real_descendant_holding_output_pipe_is_killed_at_deadline(self):
        code="import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)']);time.sleep(60)"
        started=time.monotonic()
        with self.assertRaises(ValueError):v.bounded_command([sys.executable,'-c',code],timeout=.5)
        self.assertLess(time.monotonic()-started,8)
        self.assertFalse(any(t.name=='restore-metadata-reader' and t.is_alive() for t in threading.enumerate()))

    def test_real_byte_cap_rejected_without_diagnostic_output(self):
        with self.assertRaises(ValueError):v.bounded_command([sys.executable,'-c',"print('x'*10000)"],limit=128)
        self.assertFalse(any(t.name=='restore-metadata-reader' and t.is_alive() for t in threading.enumerate()))

    def test_successful_bounded_command_preserves_output_and_exit_code(self):
        self.assertEqual(b'controlled-ok',v.bounded_command([sys.executable,'-c',"import sys;sys.stdout.write('controlled-ok')"]))

    def test_exited_parent_cannot_leave_descendant_pipe_reader(self):
        code="import subprocess,sys;subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'])"
        started=time.monotonic()
        try:v.bounded_command([sys.executable,'-c',code],timeout=1)
        except ValueError:pass
        self.assertLess(time.monotonic()-started,8)
        self.assertFalse(any(t.name=='restore-metadata-reader' and t.is_alive() for t in threading.enumerate()))

if __name__=='__main__':unittest.main()
