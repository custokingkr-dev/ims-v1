"""Execute the dashboard release shell with a fake Docker; no daemon or registry access."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/live-dashboard/release.sh"
DIGEST = "a" * 64


def shell_path(path):
    value = Path(path).resolve().as_posix()
    if os.name == "nt":
        return "/" + value[0].lower() + value[2:]
    return value


class DashboardReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        git_bash = Path("C:/Program Files/Git/bin/bash.exe")
        cls.bash = str(git_bash) if os.name == "nt" and git_bash.is_file() else shutil.which("bash")
        if not cls.bash:
            raise RuntimeError("Bash is required for actual fake-Docker release safety tests")

    def run_release(self, arguments, **overrides):
        parent = ROOT / "tmp"
        parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="dashboard-release-test-", dir=parent) as directory:
            task_directory = Path(directory).resolve()
            if task_directory.parent != parent.resolve():
                raise RuntimeError("Test scratch escaped the intended workspace/tmp directory")
            fake = task_directory / "docker"
            log = task_directory / "calls.log"
            fake.write_text("""#!/usr/bin/env bash
set -euo pipefail
printf '%s\\037' "$@" >> "$DASHBOARD_DOCKER_LOG"
printf '\\n' >> "$DASHBOARD_DOCKER_LOG"
case "$1" in
  build) exit "${DASHBOARD_BUILD_EXIT:-0}" ;;
  run) exit "${DASHBOARD_SCAN_EXIT:-0}" ;;
  push) exit "${DASHBOARD_PUSH_EXIT:-0}" ;;
  image)
    if [ "$2" != inspect ]; then exit 97; fi
    printf '%s\\n' "${DASHBOARD_INSPECT_OUTPUT:-}"
    ;;
  *) exit 98 ;;
esac
""", encoding="utf-8", newline="\n")
            fake.chmod(0o700)
            environment = os.environ.copy()
            environment.update({"DASHBOARD_DOCKER_LOG": shell_path(log),
                                "DASHBOARD_INSPECT_OUTPUT": f"asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-dashboard@sha256:{DIGEST}"})
            environment.update(overrides)
            # The first PATH entry is the only Docker that this test can invoke.
            launch = [self.bash, "--noprofile", "--norc", "-c",
                      'export PATH="$1:$PATH"; exec "$BASH" "$2" "${@:3}"',
                      "dashboard-release-test", shell_path(task_directory), shell_path(SCRIPT), *arguments]
            result = subprocess.run(launch, cwd=ROOT, env=environment, capture_output=True, timeout=20, check=False)
            calls = [line.rstrip("\x1f").split("\x1f") for line in log.read_text(encoding="utf-8").splitlines()] if log.exists() else []
            return result, calls

    def assert_no_calls(self, arguments):
        result, calls = self.run_release(arguments)
        self.assertNotEqual(0, result.returncode, result.stdout.decode(errors="replace"))
        self.assertEqual([], calls, json.dumps(calls))

    def test_legacy_implicit_push_is_rejected_before_docker(self):
        self.assert_no_calls(["v11"])

    def test_misspelled_action_is_rejected_before_docker(self):
        self.assert_no_calls(["v11", "--scan-onyl"])

    def test_missing_duplicate_unknown_or_ambiguous_arguments_never_call_docker(self):
        for arguments in [[], ["--project", "custoking-dev", "v11"], ["--scan-only", "v11"],
                          ["--project"], ["--project", "custoking-dev", "--project", "custoking-dev", "--scan-only", "v11"],
                          ["--project", "custoking-dev", "--scan-only", "--push", "v11"],
                          ["--project", "custoking-dev", "--scan-only", "--scan-only", "v11"],
                          ["--project", "custoking-dev", "--scan-only", "v11", "v12"],
                          ["--project", "custoking-dev", "--scan-only", "--unknown", "v11"]]:
            with self.subTest(arguments=arguments):
                self.assert_no_calls(arguments)

    def test_unapproved_project_and_invalid_tags_never_call_docker(self):
        for project in ["custoking", "arbitrary-project", "custoking-dev/other", "", "custoking-prod "]:
            with self.subTest(project=project):
                self.assert_no_calls(["--project", project, "--scan-only", "v11"])
        for tag in ["", "-v11", "bad/tag", "v11:other", "v11@sha256:a", "v11 with space", "a" * 129, "é"]:
            with self.subTest(tag=tag):
                self.assert_no_calls(["--project", "custoking-dev", "--scan-only", tag])

    def test_dev_scan_only_routes_build_and_gate_to_dev_and_never_pushes(self):
        result, calls = self.run_release(["--project", "custoking-dev", "--scan-only", "v11"])
        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))
        self.assertEqual(["build", "run"], [call[0] for call in calls])
        image = "asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-dashboard:v11"
        self.assertEqual(image, calls[0][2])
        self.assertEqual(image, calls[1][-1])
        self.assertNotIn(b"custoking-prod", result.stdout)
        self.assertNotIn("--ignore-unfixed", calls[1])
        self.assertEqual("HIGH,CRITICAL", calls[1][calls[1].index("--severity") + 1])

    def test_explicit_dev_push_reports_only_exact_registry_digest(self):
        result, calls = self.run_release(["v11", "--push", "--project=custoking-dev"])
        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))
        self.assertEqual(["build", "run", "push", "image"], [call[0] for call in calls])
        self.assertEqual(calls[0][2], calls[2][1])
        self.assertEqual(calls[0][2], calls[3][-1])
        self.assertIn(f"dashboard_image = \"asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-dashboard@sha256:{DIGEST}\"".encode(), result.stdout)
        self.assertIn(b"custoking-dev.tfvars", result.stdout)
        self.assertNotIn(b"terraform apply", result.stdout)
        self.assertNotIn(b"custoking-prod", result.stdout)

    def test_explicit_prod_scan_only_is_intentional_and_still_never_pushes(self):
        result, calls = self.run_release(["--project=custoking-prod", "--scan-only", "v12"])
        self.assertEqual(0, result.returncode, result.stderr.decode(errors="replace"))
        self.assertEqual(["build", "run"], [call[0] for call in calls])
        self.assertEqual("asia-south2-docker.pkg.dev/custoking-prod/custoking/custoking-dashboard:v12", calls[0][2])

    def test_scan_failure_refuses_push_or_digest_reporting(self):
        result, calls = self.run_release(["--project", "custoking-dev", "--push", "v11"], DASHBOARD_SCAN_EXIT="42")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["build", "run"], [call[0] for call in calls])
        self.assertNotIn(b"dashboard_image =", result.stdout)

    def test_build_failure_refuses_scan_push_or_digest_reporting(self):
        result, calls = self.run_release(["--project", "custoking-dev", "--push", "v11"], DASHBOARD_BUILD_EXIT="42")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["build"], [call[0] for call in calls])

    def test_push_failure_refuses_digest_reporting(self):
        result, calls = self.run_release(["--project", "custoking-dev", "--push", "v11"], DASHBOARD_PUSH_EXIT="42")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["build", "run", "push"], [call[0] for call in calls])
        self.assertNotIn(b"dashboard_image =", result.stdout)

    def test_missing_wrong_repository_or_malformed_digest_refuses_deployment_reference(self):
        for output in ["", f"example.invalid/dashboard@sha256:{DIGEST}",
                       "asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-dashboard@sha256:short",
                       f"asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-dashboard@sha256:malformed@sha256:{DIGEST}",
                       f"asia-south2-docker.pkg.dev/custoking-prod/custoking/custoking-dashboard@sha256:{DIGEST}",
                       f"asia-south2-docker.pkg.dev/custoking-dev/custoking/custoking-dashboard@sha256:{DIGEST}\n" * 2]:
            with self.subTest(output=output):
                result, calls = self.run_release(["--project", "custoking-dev", "--push", "v11"], DASHBOARD_INSPECT_OUTPUT=output)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(["build", "run", "push", "image"], [call[0] for call in calls])
                self.assertNotIn(b"dashboard_image =", result.stdout)


if __name__ == "__main__":
    unittest.main()
