import copy
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import threading
import unittest
from unittest.mock import patch


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "verify-github-governance-checks.py"
FIXTURE = ROOT / "scripts" / "tests" / "fixtures" / "github-governance-checks.json"
COMMIT = "1" * 40


def load_module():
    spec = importlib.util.spec_from_file_location("github_governance_checks", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class GitHubGovernanceChecksTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_module()
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_live_pagination_is_bounded_and_repeated_or_malformed_evidence_rejected(self):
        from unittest.mock import patch
        module = self.module
        full = [{"id": n} for n in range(1, 101)]
        with patch.object(module, "gh_json", side_effect=[{"check_runs": full}, {"check_runs": [{"id": 101}]}]) as read:
            result = module.paged_check_runs("custokingkr-dev/ims-v1", COMMIT, "gh")
            self.assertEqual(101, result["total_count"])
            self.assertEqual(2, read.call_count)
        for response in ({}, {"check_runs": None}, {"check_runs": [{"id": True}]}, {"check_runs": full + [{"id": 101}]}):
            with patch.object(module, "gh_json", return_value=response):
                with self.assertRaises(RuntimeError): module.paged_check_runs("repo", COMMIT, "gh")
        with patch.object(module, "gh_json", side_effect=[full, full]) as read:
            with self.assertRaises(RuntimeError): module.paged_rulesets("repo", "gh")
            self.assertEqual(2, read.call_count)
        with patch.object(module, "gh_json", side_effect=lambda gh, endpoint: [{"id": 100 * int(endpoint.rsplit("=", 1)[1]) + n} for n in range(100)]) as read:
            with self.assertRaisesRegex(RuntimeError, "pagination limit exceeded"):
                module.paged_rulesets("repo", "gh")
            self.assertEqual(50, read.call_count)

    def test_fractional_string_boolean_and_nonpositive_producer_ids_fail_closed(self):
        for value in (True, False, 1001.1, 1001.0, "1001", 0, -1, None):
            self.assertIsNone(self.module.producer_id(value))
            evidence = copy.deepcopy(self.fixture)
            evidence["checkRuns"]["check_runs"][0]["app"]["id"] = value
            self.assertFalse(self.module.verify(evidence, "custokingkr-dev/ims-v1", COMMIT,
                ["main", "dev"], list(self.module.DEFAULT_REQUIRED_CHECKS))["ready"])
        self.assertEqual(1001, self.module.producer_id(1001))

    def test_classic_and_active_ruleset_exact_contexts_are_ready(self):
        result = self.module.verify(
            copy.deepcopy(self.fixture),
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertTrue(result["ready"], result["blockers"])
        self.assertEqual(result["blockerCount"], 0)
        self.assertEqual([branch["exact"] for branch in result["branches"]], [True, True])

    def test_missing_failed_and_mistyped_contexts_fail_closed(self):
        evidence = copy.deepcopy(self.fixture)
        evidence["checkRuns"]["check_runs"][0]["conclusion"] = "failure"
        evidence["branchProtection"]["main"]["required_status_checks"]["contexts"][0] = "Summary"
        evidence["branchProtection"]["main"]["required_status_checks"]["checks"][0]["context"] = "Summary"
        result = self.module.verify(
            evidence,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(result["ready"])
        self.assertIn("required check 'summary' is not completed successfully", result["blockers"])
        self.assertIn("branch 'main' is missing required contexts: summary", result["blockers"])
        self.assertIn("branch 'main' has unexpected required contexts: Summary", result["blockers"])

    def test_wrong_or_missing_check_run_producer_fails_closed(self):
        wrong = copy.deepcopy(self.fixture)
        wrong["checkRuns"]["check_runs"][0]["app"]["id"] = 9001
        wrong_result = self.module.verify(
            wrong,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(wrong_result["ready"])
        self.assertIn(
            "required check 'summary' was produced by app(s) 9001; configured app is 1001",
            wrong_result["blockers"],
        )

        missing = copy.deepcopy(self.fixture)
        missing["checkRuns"]["check_runs"][0].pop("app")
        missing_result = self.module.verify(
            missing,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(missing_result["ready"])
        self.assertIn("required check 'summary' has no check-run producer app id", missing_result["blockers"])

    def test_missing_or_ambiguous_configured_producer_fails_closed(self):
        missing = copy.deepcopy(self.fixture)
        missing["branchProtection"]["main"]["required_status_checks"]["checks"][0].pop("app_id")
        missing_result = self.module.verify(
            missing,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(missing_result["ready"])
        self.assertIn(
            "branch 'main' required context 'summary' has no configured producer app/integration id",
            missing_result["blockers"],
        )

        partially_missing = copy.deepcopy(self.fixture)
        partially_missing["branchProtection"]["main"]["required_status_checks"]["checks"].append({
            "context": "summary",
        })
        partially_missing_result = self.module.verify(
            partially_missing,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(partially_missing_result["ready"])
        self.assertIn(
            "branch 'main' required context 'summary' has missing or invalid producer metadata",
            partially_missing_result["blockers"],
        )

        ambiguous = copy.deepcopy(self.fixture)
        ambiguous["rulesets"][0]["rules"][0]["parameters"]["required_status_checks"][0]["integration_id"] = 9001
        ambiguous_result = self.module.verify(
            ambiguous,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["main", "dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(ambiguous_result["ready"])
        self.assertIn(
            "required context 'summary' has inconsistent configured producers across branches: 1001, 9001",
            ambiguous_result["blockers"],
        )

    def test_excluded_or_evaluate_rulesets_do_not_count_as_enforcement(self):
        for enforcement in ("evaluate", "active"):
            evidence = copy.deepcopy(self.fixture)
            evidence["branchProtection"]["main"] = None
            evidence["rulesets"][0]["enforcement"] = enforcement
            if enforcement == "active":
                evidence["rulesets"][0]["conditions"]["ref_name"]["exclude"] = ["~DEFAULT_BRANCH"]
            result = self.module.verify(
                evidence,
                "custokingkr-dev/ims-v1",
                COMMIT,
                ["main"],
                list(self.module.DEFAULT_REQUIRED_CHECKS),
            )
            self.assertFalse(result["ready"])
            self.assertIn("branch 'main' has no active required-status-check protection", result["blockers"])

    def test_unsupported_ruleset_pattern_fails_closed(self):
        evidence = copy.deepcopy(self.fixture)
        evidence["rulesets"][0]["conditions"]["ref_name"]["include"] = ["refs/heads/[dm]*"]
        result = self.module.verify(
            evidence,
            "custokingkr-dev/ims-v1",
            COMMIT,
            ["dev"],
            list(self.module.DEFAULT_REQUIRED_CHECKS),
        )
        self.assertFalse(result["ready"])
        self.assertIn("unsupported GitHub ref pattern", "\n".join(result["blockers"]))

    def test_cli_requires_immutable_sha_and_fixture_path_never_calls_github(self):
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory) / "report.json"
            valid = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--commit", COMMIT,
                    "--fixture", str(FIXTURE),
                    "--gh", "must-not-run",
                    "--output-json", str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(valid.returncode, 0, valid.stderr)
            self.assertTrue(json.loads(output.read_text(encoding="utf-8"))["ready"])

            mutable = subprocess.run(
                [sys.executable, str(SCRIPT), "--commit", "main", "--fixture", str(FIXTURE)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(mutable.returncode, 2)
            self.assertIn("immutable full 40-character", mutable.stderr)

            failing_evidence = copy.deepcopy(self.fixture)
            failing_evidence["branchProtection"]["main"] = None
            failing_fixture = pathlib.Path(directory) / "failing-fixture.json"
            failing_fixture.write_text(json.dumps(failing_evidence), encoding="utf-8")
            failed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--commit", COMMIT,
                    "--fixture", str(failing_fixture),
                    "--output-json", str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(failed.returncode, 1, failed.stderr)
            self.assertFalse(json.loads(output.read_text(encoding="utf-8"))["ready"])

    def test_fixed_get_native_executable_and_literal_arguments(self):
        module = self.module
        endpoint = "repos/owner/repo/branches/a%26echo%20literal/protection"
        command = ["gh", *module.GET_ARGUMENTS, endpoint]
        with patch.object(module, "native_executable", return_value="native-gh.exe"), \
                patch.object(module, "capture_process") as capture:
            module.execute(command)
            self.assertEqual(["native-gh.exe", *module.GET_ARGUMENTS, endpoint], capture.call_args.args[0])
        for command in (["gh", "api", "--method", "POST", "repos/owner/repo"],
                        ["gh", *module.GET_ARGUMENTS, "--hostname=other.invalid"],
                        ["gh", *module.GET_ARGUMENTS, "https://other.invalid"]):
            with patch.object(module, "capture_process") as capture:
                with self.assertRaises(RuntimeError): module.execute(command)
                capture.assert_not_called()
        with tempfile.TemporaryDirectory() as directory:
            for suffix in ("cmd", "bat"):
                wrapper = pathlib.Path(directory) / ("gh." + suffix)
                wrapper.write_text("@echo must-not-run\n", encoding="utf-8")
                with patch.object(module.shutil, "which", return_value=str(wrapper)), \
                        patch.object(module, "capture_process") as capture:
                    with self.assertRaisesRegex(RuntimeError, "shell wrappers"):
                        module.execute([str(wrapper), *module.GET_ARGUMENTS, endpoint])
                    capture.assert_not_called()

    def test_shell_metacharacters_remain_literal_in_actual_native_child(self):
        module = self.module
        with tempfile.TemporaryDirectory() as directory:
            sentinel = pathlib.Path(directory) / "must-not-exist"
            argument = f'& echo wrong > "{sentinel}" | echo other %PATH% $(echo wrong)'
            result = module.capture_process([sys.executable, "-c",
                "import json,sys;print(json.dumps(sys.argv[1]))", argument], module.ReadBudget())
            self.assertEqual(argument, json.loads(result.stdout))
            self.assertFalse(sentinel.exists())

    def test_branch_path_segments_encoded_without_rejecting_valid_ref_characters(self):
        module = self.module
        branch = "feature/a&b#percent%"
        endpoints = []
        def response(gh, endpoint, **kwargs):
            endpoints.append(endpoint)
            return {} if endpoint.endswith("protection") or endpoint == "repos/owner/repo" else []
        with patch.object(module, "gh_json", side_effect=response), \
                patch.object(module, "paged_check_runs", return_value={"check_runs": []}):
            module.live_evidence("owner/repo", COMMIT, [branch], "gh")
        self.assertIn("repos/owner/repo/branches/feature%2Fa%26b%23percent%25/protection", endpoints)
        self.assertIsNone(module.LIVE_BUDGET.get())
        with patch.object(module, "gh_json") as read:
            with self.assertRaises(RuntimeError): module.live_evidence("owner/repo&echo", COMMIT, ["dev"], "gh")
            read.assert_not_called()

    def test_actual_child_timeout_and_stdout_stderr_limits_are_bounded(self):
        module = self.module
        started = time.monotonic()
        with patch.object(module, "API_CALL_SECONDS", 0.15):
            with self.assertRaisesRegex(RuntimeError, "deadline"):
                module.capture_process([sys.executable, "-c", "import time;time.sleep(10)"], module.ReadBudget())
        self.assertLess(time.monotonic() - started, 3)
        for stream, limit in (("stdout", "API_STDOUT_BYTES"), ("stderr", "API_STDERR_BYTES")):
            with patch.object(module, limit, 128):
                with self.assertRaisesRegex(RuntimeError, "output budget"):
                    module.capture_process([sys.executable, "-c",
                        f"import sys;sys.{stream}.write('x'*1024);sys.{stream}.flush()"], module.ReadBudget())

    def test_total_output_and_whole_run_deadline_span_multiple_calls(self):
        module = self.module
        with patch.object(module, "API_RUN_BYTES", 50):
            budget = module.ReadBudget()
            module.capture_process([sys.executable, "-c", "print('x'*30)"], budget)
            with self.assertRaisesRegex(RuntimeError, "output budget"):
                module.capture_process([sys.executable, "-c", "print('x'*30)"], budget)
        with patch.object(module, "API_RUN_SECONDS", 0):
            with patch.object(module.subprocess, "Popen") as start:
                with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
                    module.capture_process([sys.executable], module.ReadBudget())
                start.assert_not_called()
        with patch.object(module, "API_RUN_SECONDS", 0.08), \
                patch.object(module, "API_CALL_SECONDS", 1):
            budget = module.ReadBudget()
            started = time.monotonic()
            with self.assertRaisesRegex(RuntimeError, "deadline"):
                module.capture_process([sys.executable, "-c", "import time;time.sleep(10)"], budget)
            self.assertLess(time.monotonic() - started, 3)

    def test_raw_api_errors_are_not_logged_and_404_remains_optional(self):
        module = self.module
        detail = "HTTP 403 confidential-metadata-must-not-log"
        with patch.object(module, "execute", return_value=subprocess.CompletedProcess([], 1, "", detail)):
            with self.assertRaisesRegex(RuntimeError, r"failed \(HTTP 403\)$"):
                module.gh_json("gh", "repos/owner/repo")

    def test_live_budget_is_restored_on_error_and_not_reset_by_subsequent_calls(self):
        module = self.module
        seen = []
        def failed_read(*args, **kwargs):
            seen.append(module.LIVE_BUDGET.get())
            raise RuntimeError("bounded failure")
        with patch.object(module, "gh_json", side_effect=failed_read):
            with self.assertRaises(RuntimeError): module.live_evidence("owner/repo", COMMIT, ["dev"], "gh")
        self.assertIsNotNone(seen[0])
        self.assertIsNone(module.LIVE_BUDGET.get())
        budget = module.ReadBudget()
        token = module.LIVE_BUDGET.set(budget)
        try:
            with patch.object(module, "native_executable", return_value="gh.exe"), \
                    patch.object(module, "capture_process") as capture:
                command = ["gh", *module.GET_ARGUMENTS, "repos/owner/repo"]
                module.execute(command)
                module.execute(command)
                self.assertEqual([budget, budget], [c.args[1] for c in capture.call_args_list])
        finally:
            module.LIVE_BUDGET.reset(token)

    def test_windows_only_accepts_resolved_native_gh_exe(self):
        module = self.module
        import types
        with tempfile.TemporaryDirectory() as directory:
            other = pathlib.Path(directory) / "powershell.exe"
            other.write_bytes(b"MZ-controlled-non-executable-fixture")
            with patch.object(module, "os", types.SimpleNamespace(name="nt")), \
                    patch.object(module.shutil, "which", return_value=str(other)):
                with self.assertRaisesRegex(RuntimeError, "native executable"):
                    module.native_executable(str(other))

    def assert_owned_process_stopped(self, pid):
        if os.name == "nt":
            import ctypes
            from ctypes import wintypes
            api = ctypes.WinDLL("kernel32", use_last_error=True)
            api.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            api.OpenProcess.restype = wintypes.HANDLE
            api.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            api.WaitForSingleObject.restype = wintypes.DWORD
            api.CloseHandle.argtypes = [wintypes.HANDLE]
            handle = api.OpenProcess(0x00100000, False, pid)
            if handle:
                try: self.assertEqual(0, api.WaitForSingleObject(handle, 0))
                finally: api.CloseHandle(handle)
            else:
                self.assertEqual(87, ctypes.get_last_error())  # Exact owned process already gone.
        else:
            try: os.kill(pid, 0)
            except ProcessLookupError: return
            # A terminated orphan can remain a zombie until init reaps it; it cannot hold pipes.
            state = pathlib.Path(f"/proc/{pid}/stat")
            self.assertTrue(state.exists() and state.read_text().rsplit(")", 1)[1].strip().startswith("Z"))

    def test_actual_descendant_holding_pipes_is_stopped_after_parent_exit(self):
        module = self.module
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / "exact-owned-child-pid"
            child = "import time;time.sleep(10)"
            parent = "import pathlib,subprocess,sys;p=subprocess.Popen([sys.executable,'-c',sys.argv[1]]);pathlib.Path(sys.argv[2]).write_text(str(p.pid))"
            before = {t.ident for t in threading.enumerate()}
            started = time.monotonic()
            with patch.object(module, "API_CALL_SECONDS", 0.8):
                with self.assertRaisesRegex(RuntimeError, "read deadline"):
                    module.capture_process([sys.executable, "-c", parent, child, str(marker)], module.ReadBudget())
            self.assertTrue(marker.exists(), "The actual descendant must exist, not merely a parent timeout")
            self.assert_owned_process_stopped(int(marker.read_text()))
            self.assertLess(time.monotonic() - started, 4)
            self.assertFalse(any(t.ident not in before and t.name.startswith("governance-owned-") for t in threading.enumerate()))

    def test_second_reader_start_failure_cleans_actual_process_and_pipes(self):
        module = self.module
        real_start = threading.Thread.start
        real_popen = subprocess.Popen
        for raise_after_start in (False, True):
            processes = []
            def create(*args, **kwargs):
                process = real_popen(*args, **kwargs)
                processes.append(process)
                return process
            def start(thread):
                if thread.name == "governance-owned-stderr":
                    if raise_after_start: real_start(thread)
                    raise RuntimeError("controlled second-reader setup failure")
                return real_start(thread)
            before = {t.ident for t in threading.enumerate()}
            with patch.object(module.subprocess, "Popen", side_effect=create), \
                    patch.object(module.threading.Thread, "start", start):
                with self.assertRaisesRegex(RuntimeError, "controlled second-reader"):
                    module.capture_process([sys.executable, "-c", "import time;time.sleep(10)"], module.ReadBudget())
            self.assertEqual(1, len(processes))
            process = processes[0]
            self.assertIsNotNone(process.poll())
            self.assertTrue(process.stdout.closed and process.stderr.closed)
            self.assertFalse(any(t.ident not in before and t.name.startswith("governance-owned-") for t in threading.enumerate()))

    def test_platform_containment_setup_is_fail_closed(self):
        module = self.module
        if os.name != "nt":
            with patch.object(module.subprocess, "Popen", side_effect=OSError("controlled session creation failure")) as create:
                with self.assertRaisesRegex(OSError, "controlled session creation failure"):
                    module.capture_process([sys.executable, "-c", "raise RuntimeError('must not run')"], module.ReadBudget())
                self.assertTrue(create.call_args.kwargs["start_new_session"])
            child = module.capture_process([sys.executable, "-c", "import os,json;print(json.dumps([os.getpid(),os.getsid(0),os.getpgrp()]))"], module.ReadBudget())
            pid, sid, group = json.loads(child.stdout)
            self.assertEqual([pid, pid], [sid, group])
            return
        with tempfile.TemporaryDirectory() as directory:
            marker = pathlib.Path(directory) / "must-not-execute"
            processes = []
            real_popen = subprocess.Popen
            def create(*args, **kwargs):
                process = real_popen(*args, **kwargs)
                processes.append(process)
                return process
            with patch.object(module.subprocess, "Popen", side_effect=create), \
                    patch.object(module.WindowsJob, "assign_and_resume", side_effect=RuntimeError("controlled assignment failure")):
                with self.assertRaisesRegex(RuntimeError, "controlled assignment failure"):
                    module.capture_process([sys.executable, "-c", "import pathlib,sys;pathlib.Path(sys.argv[1]).write_text('wrong')", str(marker)], module.ReadBudget())
            self.assertFalse(marker.exists())
            self.assertIsNotNone(processes[0].poll())
            self.assertTrue(processes[0].stdout.closed and processes[0].stderr.closed)
        with patch.object(module, "execute", return_value=subprocess.CompletedProcess([], 1, "", "HTTP 404")):
            self.assertIsNone(module.gh_json("gh", "repos/owner/repo", allow_not_found=True))
        with patch.object(module, "execute", return_value=subprocess.CompletedProcess([], 0, '{"ok":1} trailing', "")):
            with self.assertRaisesRegex(RuntimeError, "invalid JSON"):
                module.gh_json("gh", "repos/owner/repo")


    def test_completion_during_last_sleep_cannot_return_success_after_deadline(self):
        module = self.module
        real_popen = subprocess.Popen
        real_monotonic = time.monotonic
        processes = []
        clock_offset = [0.0]
        advanced = [False]
        def create(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            processes.append(process)
            return process
        def finish_during_sleep(seconds):
            # Actual owned child and pipe readers finish between loop iterations.
            processes[0].wait(timeout=3)
            for thread in threading.enumerate():
                if thread.name.startswith("governance-owned-"):
                    thread.join(timeout=3)
                    self.assertFalse(thread.is_alive())
            if not advanced[0]:
                clock_offset[0] = module.API_CALL_SECONDS + 1
                advanced[0] = True
        with patch.object(module.subprocess, "Popen", side_effect=create), \
                patch.object(module.time, "monotonic", side_effect=lambda: real_monotonic()+clock_offset[0]), \
                patch.object(module.time, "sleep", side_effect=finish_during_sleep):
            with self.assertRaisesRegex(RuntimeError, "read deadline"):
                module.capture_process([sys.executable, "-c", "import time;time.sleep(0.1);print('{}')"], module.ReadBudget())
        self.assertTrue(advanced[0], "The controlled clock must cross the deadline after actual completion")
        self.assertIsNotNone(processes[0].poll())
        self.assertTrue(processes[0].stdout.closed and processes[0].stderr.closed)


if __name__ == "__main__":
    unittest.main()
