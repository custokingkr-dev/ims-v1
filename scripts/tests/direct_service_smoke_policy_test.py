"""Exercise the actual embedded smoke Python with controlled HTTP responses; no cloud access."""
import contextlib
import io
import os
from pathlib import Path
import textwrap
import unittest
from unittest.mock import patch
import urllib.error

SOURCE = Path(__file__).resolve().parents[2] / "deploy/gcp/direct-service-smoke-job.template.yaml"
CODE = compile(textwrap.dedent(SOURCE.read_text().split("                - |\n", 1)[1].split("              env:", 1)[0]), str(SOURCE), "exec")

class Response(io.BytesIO):
    status = 200

class SmokePolicyTest(unittest.TestCase):
    def run_smoke(self, user="", school="", permit_contextless=False):
        calls = []
        def fetch(request, timeout):
            calls.append(request)
            if "metadata.google.internal" in request.full_url:
                return Response(b"controlled-identity-token")
            if not request.get_header("X-authenticated-user-id") and not permit_contextless:
                raise urllib.error.HTTPError(request.full_url, 403, "denied", {}, io.BytesIO())
            return Response(b"{}")
        env = {"CATALOG_URL":"https://catalog.example","TENANT_URL":"https://tenant.example","CATALOG_TOKEN":"controlled-catalog-token","TENANT_TOKEN":"controlled-tenant-token","SMOKE_USER_ID":user,"SMOKE_SCHOOL_ID":school}
        with patch.dict(os.environ, env), patch("urllib.request.urlopen", fetch), contextlib.redirect_stdout(io.StringIO()):
            exec(CODE, {})
        return calls

    def test_default_proves_denial_and_never_writes_or_invents_actor(self):
        calls = self.run_smoke()
        self.assertEqual(4, len(calls))
        self.assertTrue(all(request.get_method() == "GET" and request.data is None for request in calls))
        self.assertTrue(all(request.get_header("X-authenticated-user-id") is None for request in calls))

    def test_explicit_actor_only_reads_its_school(self):
        calls = self.run_smoke("8", "10")
        business = [request for request in calls if "metadata.google.internal" not in request.full_url]
        self.assertEqual(4, len(business))
        self.assertTrue(all(request.get_method() == "GET" and request.data is None for request in business))
        authorized = [request for request in business if request.get_header("X-authenticated-user-id")]
        self.assertEqual(2, len(authorized))
        self.assertTrue(all(request.get_header("X-authenticated-role") == "SCHOOL_ADMIN" and request.get_header("X-authenticated-school-id") == "10" for request in authorized))

    def test_missing_business_user_boundary_fails_smoke(self):
        with self.assertRaisesRegex(RuntimeError, "expected denial"):
            self.run_smoke(permit_contextless=True)

    def test_zero_or_missing_school_cannot_be_positive_actor(self):
        for user, school in [("0", "10"), ("8", ""), ("8", "0")]:
            with self.assertRaisesRegex(RuntimeError, "positive IDs"):
                self.run_smoke(user, school)

if __name__ == "__main__":
    unittest.main()
