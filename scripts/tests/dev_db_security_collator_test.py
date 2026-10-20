import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'security/collate-dev-db-security.py'
spec = importlib.util.spec_from_file_location('collator', SOURCE)
collator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collator)


class DevDbSecurityCollatorTest(unittest.TestCase):
    def test_all_role_and_unsafe_policy_fixtures(self):
        with contextlib.redirect_stdout(io.StringIO()):
            collator.self_test()

    def test_untrusted_or_stale_capture_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'logs.json').write_text('[]')
            for summary in [
                {'project': 'custoking-prod', 'readOnly': True, 'capturedAtUtc': '2026-10-06T22:00:00Z'},
                {'project': 'custoking-dev', 'readOnly': True, 'capturedAtUtc': '2026-10-06T20:00:00Z'},
                {'project': 'custoking-dev', 'readOnly': False, 'capturedAtUtc': '2026-10-06T22:00:00Z'},
            ]:
                (root / 'summary.json').write_text(json.dumps(summary))
                with self.assertRaises(ValueError):
                    collator.load_capture(root, collator.timestamp('2026-10-06T21:03:33Z'))

    def test_rejects_ambiguous_structured_catalog_payloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'summary.json').write_text(json.dumps({'project':'custoking-dev','readOnly':True,'capturedAtUtc':'2026-10-06T22:00:00Z'}))
            (root / 'logs.json').write_text(json.dumps([{'jsonPayload':{'currentRole':'ims_identity_rt'}}, {'jsonPayload':{'currentRole':'ims_identity_rt'}}]))
            with self.assertRaises(ValueError):
                collator.load_capture(root, collator.timestamp('2026-10-06T21:03:33Z'))


if __name__ == '__main__':
    unittest.main()
