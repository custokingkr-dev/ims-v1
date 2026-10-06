import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.request

SOURCE = Path(__file__).resolve().parents[1] / 'security/revoke-dev-runtime-secret-access.py'
spec = importlib.util.spec_from_file_location('revocation', SOURCE)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

def fixture_spec(service):
    prefix, role = module.SERVICES[service]
    env = []
    if role:
        env = [{'name': 'APP_MIGRATIONS_ENABLED', 'value': 'false'}, {'name': 'SPRING_DATASOURCE_USERNAME', 'value': role},
            {'name': 'SPRING_DATASOURCE_PASSWORD', 'valueFrom': {'secretKeyRef': {'name': prefix + '-runtime-db-password-dev', 'key': 'latest'}}}]
    if service == 'api-gateway':
        env = [{'name': 'GATEWAY_AUTH_MODE', 'value': 'enforce'}, {'name': 'GATEWAY_LOCAL_JWT_VERIFY', 'value': 'disabled'}]
    return {'serviceAccountName': module.member(prefix).split(':', 1)[1], 'containers': [{'env': env, 'ports': [{'containerPort': 8080}]}]}

class FakeClient:
    def __init__(self):
        self.services = {}; self.revisions = {}; self.writes = []
        self.ancestor_policies = [{'bindings': []}]
        self.calls = []
        self.conflict = False
        for service in module.SERVICES:
            name = 'custoking-' + service + '-dev'; revision = name + '-00042-fixture'
            spec = fixture_spec(service)
            self.services[name] = {'metadata': {'generation': 42}, 'spec': {'template': {'spec': spec}, 'traffic': [{'latestRevision': True, 'percent': 100}]},
                'status': {'observedGeneration': 42, 'conditions': [{'type': 'Ready', 'status': 'True'}], 'latestReadyRevisionName': revision,
                           'latestCreatedRevisionName': revision, 'traffic': [{'revisionName': revision, 'percent': 100}]}}
            self.revisions[revision] = {'metadata': {'name': revision}, 'spec': copy.deepcopy(spec), 'status': {'conditions': [{'type': 'Ready', 'status': 'True'}]}}
        self.policies = {}
        for secret, removals in module.REMOVALS.items():
            required = module.MIGRATOR if secret == 'db-password-dev' else module.member('identity') if secret == 'jwt-secret-dev' else 'user:legacy-maintainer@example.test'
            self.policies[secret] = {'version': 3, 'etag': 'fixture-etag-' + secret, 'auditConfigs': [], 'bindings': [
                {'role': module.ACCESSOR, 'members': sorted(removals) + [required]},
                {'role': module.ACCESSOR, 'members': [sorted(removals)[0], 'user:conditional-operator@example.test'],
                 'condition': {'title': 'preserve-condition', 'description': 'unaltered', 'expression': 'request.time < timestamp("2030-01-01T00:00:00Z")'}},
                {'role': 'roles/viewer', 'members': [module.member('identity'), 'user:metadata-reader@example.test']}]}
    def gcloud(self, args):
        self.calls.append(args)
        if args[:3] == ['run', 'services', 'describe']: return copy.deepcopy(self.services[args[3]])
        if args[:3] == ['run', 'revisions', 'describe']: return copy.deepcopy(self.revisions[args[3]])
        if args[:2] == ['projects', 'get-ancestors']:
            return [{'type': 'project', 'id': 'project-number'}] + ([{'type': 'folder', 'id': 'fixture-folder'}] if len(self.ancestor_policies) > 1 else [])
        if args[:2] == ['projects', 'get-iam-policy']: return copy.deepcopy(self.ancestor_policies[0])
        if args[:3] == ['resource-manager', 'folders', 'get-iam-policy']: return copy.deepcopy(self.ancestor_policies[1])
        if args[:3] == ['iam', 'roles', 'describe']:
            return {'includedPermissions': ['secretmanager.versions.access'] if args[3] in ('roles/secretmanager.secretAccessor', 'customUnsafe') else []}
        raise AssertionError('Unexpected native operation: ' + str(args))
    def rest(self, secret, policy=None):
        if policy is not None:
            if self.conflict: raise module.Blocked('IAM REST etag conflict')
            assert policy['etag'] == self.policies[secret]['etag']
            self.writes.append((secret, copy.deepcopy(policy)))
            self.policies[secret] = copy.deepcopy(policy); self.policies[secret]['etag'] = 'new-' + policy['etag']
        return copy.deepcopy(self.policies[secret])

class RevocationTests(unittest.TestCase):
    def test_default_dry_run_validates_seven_but_never_writes(self):
        client = FakeClient(); result = module.execute(client)
        self.assertFalse(result['apply']); self.assertEqual(7, result['servicesVerified']); self.assertEqual([], client.writes)
        self.assertEqual({'db-password-dev': 6, 'app-rt-password-dev': 6, 'jwt-secret-dev': 2}, result['plannedMembershipRemovals'])
        self.assertNotIn('members', json.dumps(result)); self.assertFalse(result['secretValuesAccessed'])

    def test_apply_preserves_every_other_role_member_condition_audit_and_etag(self):
        client = FakeClient(); originals = copy.deepcopy(client.policies)
        result = module.execute(client, True)
        self.assertTrue(result['apply']); self.assertEqual(3, len(client.writes))
        for secret, written in client.writes:
            expected, _ = module.revoke_policy(originals[secret], module.REMOVALS[secret])
            self.assertEqual(expected, written)
            self.assertEqual(originals[secret]['bindings'][1]['condition'], written['bindings'][1]['condition'])
            self.assertEqual(originals[secret]['bindings'][2], written['bindings'][2])
        self.assertTrue(module.retained(client.policies['db-password-dev'], module.MIGRATOR))
        self.assertTrue(module.retained(client.policies['jwt-secret-dev'], module.member('identity')))

    def test_false_runtime_guards_block_before_any_policy_write(self):
        cases = ['migration', 'owner_env', 'legacy_secret', 'role', 'db_secret', 'stale_live', 'traffic', 'generation', 'tag', 'pending_revision', 'gateway_auth', 'gateway_jwt', 'gateway_port']
        for case in cases:
            with self.subTest(case=case):
                client = FakeClient(); current = client.services['custoking-identity-service-dev']; env = current['spec']['template']['spec']['containers'][0]['env']
                gateway = client.services['custoking-api-gateway-dev']['spec']['template']['spec']['containers'][0]
                if case == 'migration': env[0]['value'] = 'true'
                elif case == 'owner_env': env.append({'name': 'FLYWAY_PASSWORD', 'value': 'must-not-print'})
                elif case == 'legacy_secret': env.append({'name': 'UNUSED_OWNER', 'valueFrom': {'secretKeyRef': {'name': 'db-password-dev', 'key': 'latest'}}})
                elif case == 'role': env[1]['value'] = 'app_rt'
                elif case == 'db_secret': env[2]['valueFrom']['secretKeyRef']['name'] = 'billing-runtime-db-password-dev'
                elif case == 'stale_live': client.revisions[current['status']['latestReadyRevisionName']]['spec']['containers'][0]['env'][0]['value'] = 'true'
                elif case == 'traffic': current['status']['traffic'][0]['percent'] = 99
                elif case == 'generation': current['status']['observedGeneration'] = 41
                elif case == 'tag': current['status']['traffic'][0]['tag'] = 'old-owner'
                elif case == 'pending_revision': current['status']['latestCreatedRevisionName'] = 'pending-new-revision'
                elif case == 'gateway_auth': gateway['env'][0]['value'] = 'off'
                elif case == 'gateway_jwt': gateway['env'].append({'name': 'JWT_SECRET', 'value': 'must-not-print'})
                elif case == 'gateway_port': gateway['ports'][0]['containerPort'] = 80
                with self.assertRaises(module.Blocked) as error: module.execute(client, True)
                self.assertNotIn('must-not-print', str(error.exception)); self.assertEqual([], client.writes)

    def test_inherited_custom_public_and_unknown_group_capabilities_fail_closed(self):
        for role, principal in [('roles/secretmanager.secretAccessor', module.member('billing')), ('projects/custoking-dev/roles/customUnsafe', module.member('frontend')), ('roles/secretmanager.secretAccessor', 'group:unknown@example.test')]:
            with self.subTest(role=role, principal=principal):
                client = FakeClient(); client.ancestor_policies.append({'bindings': [{'role': role, 'members': [principal]}]})
                with self.assertRaises(module.Blocked): module.execute(client, True)
                self.assertEqual([], client.writes)

    def test_missing_required_signer_migrator_and_unexpected_accessors_block(self):
        for case in ['migrator', 'signer', 'gateway_owner', 'public']:
            client = FakeClient()
            if case == 'migrator': client.policies['db-password-dev']['bindings'][0]['members'].remove(module.MIGRATOR)
            elif case == 'signer': client.policies['jwt-secret-dev']['bindings'][0]['members'].remove(module.member('identity'))
            elif case == 'gateway_owner': client.policies['db-password-dev']['bindings'][0]['members'].append(module.member('api-gateway'))
            else: client.policies['db-password-dev']['bindings'][0]['members'].append('allAuthenticatedUsers')
            with self.assertRaises(module.Blocked): module.execute(client, True)
            self.assertEqual([], client.writes)

    def test_etag_conflict_and_live_revision_drift_never_retry_writes(self):
        client = FakeClient(); client.conflict = True
        with self.assertRaises(module.Blocked): module.execute(client, True)
        self.assertEqual([], client.writes)
        client = FakeClient(); original = client.gcloud; calls = 0
        def drift(args):
            nonlocal calls
            if args[:3] == ['run', 'services', 'describe']:
                calls += 1
                if calls == 8: client.services[args[3]]['status']['latestCreatedRevisionName'] = 'changed'
            return original(args)
        client.gcloud = drift
        with self.assertRaises(module.Blocked): module.execute(client, True)
        self.assertEqual([], client.writes)

    def test_native_fixture_is_bounded_and_never_discloses_cli_errors(self):
        with tempfile.TemporaryDirectory() as folder:
            command = Path(folder) / 'fake_cli.py'
            command.write_text('import sys,json\nif "fail" in sys.argv:\n print("password-and-token-must-not-print",file=sys.stderr);sys.exit(41)\nprint(json.dumps({"safe":True}))\n')
            client = module.Client([sys.executable, str(command)])
            self.assertEqual({'safe': True}, client.gcloud(['controlled-read']))
            with self.assertRaises(module.Blocked) as error: client.native(['fail'])
            self.assertNotIn('password-and-token', str(error.exception))
            with mock.patch.object(module.subprocess, 'run', side_effect=subprocess.TimeoutExpired('fixture', 60)) as call:
                with self.assertRaises(module.Blocked): client.native(['read'])
                self.assertEqual(60, call.call_args.kwargs['timeout'])

    def test_actual_rest_wire_requests_version_three_and_preserves_etag_then_blocks_conflict(self):
        client = module.Client(); client.token = 'fixture-access-token'
        state = {'policy': FakeClient().policies['db-password-dev'], 'requests': [], 'conflict': False}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                state['requests'].append(('GET', self.path, self.headers['Authorization']))
                self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(state['policy']).encode())
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                state['requests'].append(('POST', self.path, body))
                if state['conflict']:
                    self.send_response(409); self.end_headers(); return
                self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(body['policy']).encode())
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        actual_open = urllib.request.urlopen
        def controlled(request, timeout):
            from urllib.parse import urlsplit
            parts = urlsplit(request.full_url)
            self.assertEqual('secretmanager.googleapis.com', parts.hostname)
            redirected = urllib.request.Request(f'http://127.0.0.1:{server.server_port}{parts.path}?{parts.query}', data=request.data, headers=dict(request.header_items()), method=request.method)
            return actual_open(redirected, timeout=timeout)
        try:
            with mock.patch.object(module.urllib.request, 'urlopen', side_effect=controlled):
                fetched = client.rest('db-password-dev'); planned, _ = module.revoke_policy(fetched, module.JAVA_MEMBERS)
                client.rest('db-password-dev', planned)
                self.assertIn('options.requestedPolicyVersion=3', state['requests'][0][1])
                self.assertEqual('Bearer fixture-access-token', state['requests'][0][2])
                self.assertEqual(planned, state['requests'][1][2]['policy'])
                state['conflict'] = True
                with self.assertRaises(module.Blocked): client.rest('db-password-dev', planned)
                self.assertEqual(3, len(state['requests']))
        finally: server.shutdown(); server.server_close(); thread.join(2)

    def test_rest_trickle_body_is_cancelled_by_whole_body_deadline(self):
        client = module.Client(); client.token = 'fixture-token'; client.body_timeout = 0.15
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                self.send_response(200); self.send_header('Content-Length', '500'); self.end_headers()
                try:
                    for _ in range(500): self.wfile.write(b' '); self.wfile.flush(); time.sleep(0.04)
                except OSError: pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        actual_open = urllib.request.urlopen
        def controlled(request, timeout): return actual_open(f'http://127.0.0.1:{server.server_port}/controlled', timeout=timeout)
        started = time.monotonic()
        try:
            with mock.patch.object(module.urllib.request, 'urlopen', side_effect=controlled):
                with self.assertRaises(module.Blocked): client.rest('db-password-dev')
            self.assertLess(time.monotonic() - started, 1)
        finally: server.shutdown(); server.server_close(); thread.join(2)

if __name__ == '__main__': unittest.main()
