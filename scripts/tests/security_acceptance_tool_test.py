import ast
import datetime
import json
import pathlib
import subprocess
import tempfile
import types
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
HELPERS = ROOT / 'scripts/security'

class AcceptanceToolTests(unittest.TestCase):
    def functions(self, filename, names):
        tree = ast.parse((HELPERS / filename).read_text())
        return compile(ast.Module(body=[node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names], type_ignores=[]), filename, 'exec')

    def test_fixture_manifest_contains_hashes_and_references_never_passwords(self):
        with tempfile.TemporaryDirectory() as temp:
            secret = 'CONTROLLED-PLAINTEXT-PASSWORD-MUST-NOT-LOG'
            scope = dict(ROOT=pathlib.Path(temp), PROJECT='custoking-dev', REGION='asia-south2', MARKER='SEC-ACPT-20261007', json=json, datetime=datetime,
                         secrets=types.SimpleNamespace(token_urlsafe=lambda _: secret, token_hex=lambda _: 'abcdef12'),
                         bcrypt=types.SimpleNamespace(hashpw=lambda *_: b'$2b$12$CONTROLLEDHASH', gensalt=lambda **_: b'salt'))
            exec(self.functions('provision-security-acceptance.py', {'prepare'}), scope)
            job, path = scope['prepare']()
            text = path.read_text()
            self.assertNotIn(secret, text)
            manifest = json.loads(text)
            template = manifest['spec']['template']['spec']
            self.assertEqual(1, template['taskCount'])
            spec = template['template']['spec']
            self.assertEqual(0, spec['maxRetries'])
            self.assertEqual(60, spec['timeoutSeconds'])
            container = spec['containers'][0]
            self.assertIn('@sha256:', container['image'])
            self.assertEqual('ims-db-migration-dev@custoking-dev.iam.gserviceaccount.com', spec['serviceAccountName'])
            password = next(item for item in container['env'] if item['name'] == 'PGPASSWORD')
            self.assertEqual({'name': 'db-password-dev', 'key': 'latest'}, password['valueFrom']['secretKeyRef'])
            self.assertNotIn('value', password)
            self.assertIn('Reserved fixture identifiers already occupied; stop', container['args'][-1])
            self.assertRegex(job, r'^ims-dev-security-fixture-[0-9]{14}-abcdef12$')

    def test_cloud_error_redacts_both_output_streams_and_pins_project(self):
        calls = []
        def run(args, **kwargs):
            calls.append(args)
            return types.SimpleNamespace(returncode=1, stdout='CONTROLLED_SECRET', stderr='CONTROLLED_SECRET')
        scope = dict(subprocess=types.SimpleNamespace(run=run), PROJECT='custoking-dev', json=json)
        exec(self.functions('provision-security-acceptance.py', {'cloud'}), scope)
        with self.assertRaisesRegex(RuntimeError, '^Cloud operation failed; output withheld$') as error:
            scope['cloud'](['run', 'services', 'describe', 'synthetic'])
        self.assertNotIn('CONTROLLED_SECRET', str(error.exception))
        self.assertEqual('--project=custoking-dev', calls[0][-1])

    def test_all_python_entrypoints_refuse_execution_without_explicit_dev_flag(self):
        files = [name for name in json.loads((ROOT / 'docs/security-remediation/acceptance-identity-dashboard-sources.json').read_text()) if name['path'].endswith('.py')]
        for entry in files:
            with self.subTest(entry=entry['path']):
                result = subprocess.run(['python', str(ROOT / entry['path'])], capture_output=True, text=True, timeout=10)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Explicit --apply-dev is required', result.stdout + result.stderr)

    def test_cloud_deadline_redacts_partial_secret_output(self):
        def run(args, **kwargs):
            self.assertLessEqual(kwargs['timeout'], 540)
            raise subprocess.TimeoutExpired(args, kwargs['timeout'], output='CONTROLLED_SECRET', stderr='CONTROLLED_SECRET')
        scope = dict(subprocess=types.SimpleNamespace(run=run, TimeoutExpired=subprocess.TimeoutExpired), PROJECT='custoking-dev', json=json)
        exec(self.functions('provision-security-acceptance.py', {'cloud'}), scope)
        with self.assertRaisesRegex(RuntimeError, '^Cloud operation deadline exceeded; output withheld$'):
            scope['cloud'](['run', 'services', 'describe', 'synthetic'])

if __name__ == '__main__':
    unittest.main()
