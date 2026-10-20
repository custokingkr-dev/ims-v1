#!/usr/bin/env python3
"""Verify immutable check evidence and exact GitHub branch governance contexts.

The live path is read-only. A fixture path exists so policy semantics remain executable in CI
without GitHub credentials or repository-administrator access.
"""

from __future__ import annotations

import argparse
import contextvars
import datetime as dt
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from typing import Any
from urllib.parse import quote


DEFAULT_REQUIRED_CHECKS = (
    "summary",
    "analyze (java-kotlin)",
    "analyze (javascript-typescript)",
)
DEFAULT_BRANCHES = ("main", "dev")
FULL_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
API_CALL_SECONDS = 35
API_RUN_SECONDS = 300
API_STDOUT_BYTES = 2 * 1024 * 1024
API_STDERR_BYTES = 64 * 1024
API_RUN_BYTES = 16 * 1024 * 1024
API_CLEANUP_SECONDS = 3
GET_ARGUMENTS = [
    "api", "--method", "GET",
    "-H", "Accept: application/vnd.github+json",
    "-H", "X-GitHub-Api-Version: 2022-11-28",
]


class ReadBudget:
    def __init__(self) -> None:
        self.deadline = time.monotonic() + API_RUN_SECONDS
        self.bytes_read = 0
        self.lock = threading.Lock()

    def consume(self, size: int) -> bool:
        with self.lock:
            self.bytes_read += size
            return self.bytes_read <= API_RUN_BYTES


LIVE_BUDGET: contextvars.ContextVar[ReadBudget | None] = contextvars.ContextVar("github_read_budget", default=None)


def native_executable(gh: str) -> str:
    resolved = shutil.which(gh)
    if not resolved:
        raise RuntimeError("GitHub CLI executable is unavailable")
    executable = pathlib.Path(resolved).resolve(strict=True)
    # Windows may implicitly dispatch batch files through CMD even with shell=False.
    if executable.suffix.lower() in (".cmd", ".bat") or (os.name == "nt" and executable.name.lower() != "gh.exe"):
        raise RuntimeError("GitHub CLI must be a native executable; shell wrappers are rejected")
    return str(executable)


class WindowsJob:
    """Unnamed, non-inheritable job; associate a suspended child before it runs.

    Uses documented Win32 job/Toolhelp APIs. No breakaway flags or global PID kills.
    """
    def __init__(self) -> None:
        import ctypes as c
        from ctypes import wintypes as w
        self.c = c
        self.kernel = c.WinDLL("kernel32", use_last_error=True)
        signatures = {
            "CreateJobObjectW": ([w.LPVOID, w.LPCWSTR], w.HANDLE),
            "SetInformationJobObject": ([w.HANDLE, c.c_int, w.LPVOID, w.DWORD], w.BOOL),
            "AssignProcessToJobObject": ([w.HANDLE, w.HANDLE], w.BOOL),
            "TerminateJobObject": ([w.HANDLE, w.UINT], w.BOOL),
            "QueryInformationJobObject": ([w.HANDLE, c.c_int, w.LPVOID, w.DWORD, w.LPVOID], w.BOOL),
            "CreateToolhelp32Snapshot": ([w.DWORD, w.DWORD], w.HANDLE),
            "Thread32First": ([w.HANDLE, w.LPVOID], w.BOOL),
            "Thread32Next": ([w.HANDLE, w.LPVOID], w.BOOL),
            "OpenThread": ([w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
            "GetProcessIdOfThread": ([w.HANDLE], w.DWORD),
            "ResumeThread": ([w.HANDLE], w.DWORD),
            "CloseHandle": ([w.HANDLE], w.BOOL),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.kernel, name)
            function.argtypes = arguments
            function.restype = result

        class Basic(c.Structure):
            _fields_ = [("process_time", c.c_longlong), ("job_time", c.c_longlong),
                        ("flags", w.DWORD), ("min_ws", c.c_size_t), ("max_ws", c.c_size_t),
                        ("process_limit", w.DWORD), ("affinity", c.c_size_t),
                        ("priority", w.DWORD), ("scheduling", w.DWORD)]
        class Limits(c.Structure):
            _fields_ = [("basic", Basic), ("io", c.c_ulonglong * 6),
                        ("process_memory", c.c_size_t), ("job_memory", c.c_size_t),
                        ("peak_process_memory", c.c_size_t), ("peak_job_memory", c.c_size_t)]
        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise RuntimeError("Owned process job creation failed")
        limits = Limits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE; no breakaway.
        if not self.kernel.SetInformationJobObject(self.handle, 9, c.byref(limits), c.sizeof(limits)):
            self.kernel.CloseHandle(self.handle)
            self.handle = None
            raise RuntimeError("Owned process job limits failed")

    def assign_and_resume(self, process: subprocess.Popen[bytes], deadline: float) -> None:
        c = self.c
        from ctypes import wintypes as w
        if not self.kernel.AssignProcessToJobObject(self.handle, int(process._handle)):
            raise RuntimeError("Owned suspended process job assignment failed")
        class ThreadEntry(c.Structure):
            _fields_ = [("size", w.DWORD), ("usage", w.DWORD), ("thread", w.DWORD),
                        ("owner", w.DWORD), ("priority", w.LONG), ("delta", w.LONG), ("flags", w.DWORD)]
        snapshot = self.kernel.CreateToolhelp32Snapshot(4, 0)  # Read-only thread metadata.
        if snapshot == c.c_void_p(-1).value:
            raise RuntimeError("Owned suspended thread discovery failed")
        thread_ids = []
        try:
            entry = ThreadEntry()
            entry.size = c.sizeof(entry)
            available = self.kernel.Thread32First(snapshot, c.byref(entry))
            while available:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Owned suspended thread discovery deadline exceeded")
                if entry.owner == process.pid:
                    thread_ids.append(entry.thread)
                entry.size = c.sizeof(entry)
                available = self.kernel.Thread32Next(snapshot, c.byref(entry))
            if c.get_last_error() != 18:  # ERROR_NO_MORE_FILES; reject partial metadata.
                raise RuntimeError("Owned suspended thread discovery incomplete")
        finally:
            self.kernel.CloseHandle(snapshot)
        if len(thread_ids) != 1:
            raise RuntimeError("Owned suspended primary thread is ambiguous")
        thread = self.kernel.OpenThread(0x0002 | 0x0800, False, thread_ids[0])
        if not thread:
            raise RuntimeError("Owned suspended primary thread cannot be opened")
        try:
            if self.kernel.GetProcessIdOfThread(thread) != process.pid or self.kernel.ResumeThread(thread) != 1:
                raise RuntimeError("Owned suspended primary thread cannot be resumed")
        finally:
            self.kernel.CloseHandle(thread)

    def terminate_and_close(self, deadline: float) -> None:
        c = self.c
        from ctypes import wintypes as w
        class Accounting(c.Structure):
            _fields_ = [("times", c.c_longlong * 4), ("faults", w.DWORD),
                        ("total", w.DWORD), ("active", w.DWORD), ("terminated", w.DWORD)]
        try:
            if not self.kernel.TerminateJobObject(self.handle, 1):
                raise RuntimeError("Owned process tree termination failed")
            while True:
                accounting = Accounting()
                if not self.kernel.QueryInformationJobObject(self.handle, 1, c.byref(accounting), c.sizeof(accounting), None):
                    raise RuntimeError("Owned process tree completion cannot be read")
                if accounting.active == 0:
                    return
                if time.monotonic() >= deadline:
                    raise RuntimeError("Owned process tree cleanup deadline exceeded")
                time.sleep(0.01)
        finally:
            self.kernel.CloseHandle(self.handle)  # Also kills on exceptional termination paths.
            self.handle = None


def capture_process(command: list[str], budget: ReadBudget) -> subprocess.CompletedProcess[str]:
    # The OS process-creation operation itself may not be interruptible. The read deadline
    # applies immediately before and after it; cleanup has a separate bounded allowance.
    deadline = min(budget.deadline, time.monotonic() + API_CALL_SECONDS)
    if time.monotonic() >= deadline:
        raise RuntimeError("GitHub read budget exhausted")
    process = None
    job = None
    threads: list[threading.Thread] = []
    started_threads: list[threading.Thread] = []
    buffers = [bytearray(), bytearray()]
    failed = threading.Event()

    def read_pipe(index: int, stream: Any, limit: int) -> None:
        try:
            while True:
                chunk = stream.read1(8192)
                if not chunk:
                    return
                if len(buffers[index]) + len(chunk) > limit or not budget.consume(len(chunk)):
                    failed.set()
                    return
                buffers[index].extend(chunk)
        except OSError:
            failed.set()
        finally:
            stream.close()

    try:
        options: dict[str, Any] = {"start_new_session": True}
        if os.name == "nt":
            job = WindowsJob()
            options = {"creationflags": 0x00000004 | subprocess.CREATE_NO_WINDOW}  # CREATE_SUSPENDED.
        process = subprocess.Popen(command, shell=False, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={**os.environ, "GH_PROMPT_DISABLED": "1"}, **options)
        if job:
            job.assign_and_resume(process, deadline)
        threads = [
            threading.Thread(target=read_pipe, args=(0, process.stdout, API_STDOUT_BYTES),
                             name="governance-owned-stdout", daemon=True),
            threading.Thread(target=read_pipe, args=(1, process.stderr, API_STDERR_BYTES),
                             name="governance-owned-stderr", daemon=True),
        ]
        for thread in threads:
            started_threads.append(thread)
            thread.start()
        while process.poll() is None or any(thread.is_alive() for thread in threads):
            if failed.is_set():
                raise RuntimeError("GitHub CLI response exceeds output budget or cannot be read")
            if time.monotonic() >= deadline:
                raise RuntimeError("GitHub CLI read deadline exceeded")
            time.sleep(0.01)
        if failed.is_set():
            raise RuntimeError("GitHub CLI response exceeds output budget or cannot be read")
        if time.monotonic() >= deadline:
            raise RuntimeError("GitHub CLI read deadline exceeded")
        try:
            return subprocess.CompletedProcess(command, process.returncode,
                buffers[0].decode("utf-8"), buffers[1].decode("utf-8"))
        except UnicodeDecodeError as error:
            raise RuntimeError("GitHub CLI response is not UTF-8") from error
    finally:
        cleanup_deadline = time.monotonic() + API_CLEANUP_SECONDS
        cleanup_error = None
        try:
            if job:
                job.terminate_and_close(cleanup_deadline)
            elif process:
                try:
                    os.killpg(process.pid, signal.SIGKILL)  # Only the newly owned session/group.
                except ProcessLookupError:
                    pass
        except (OSError, RuntimeError) as error:
            cleanup_error = error
        finally:
            if process:
                # Assignment failure leaves a suspended, never-executed child outside the job.
                if process.poll() is None:
                    process.kill()
                try:
                    process.wait(timeout=max(0.01, cleanup_deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    cleanup_error = RuntimeError("Owned direct process cleanup deadline exceeded")
                for thread in started_threads:
                    if thread.ident is not None:
                        thread.join(timeout=max(0, cleanup_deadline - time.monotonic()))
                for thread, stream in zip(threads, (process.stdout, process.stderr)):
                    if thread.ident is None:
                        stream.close()
                if not threads:
                    process.stdout.close()
                    process.stderr.close()
                if any(thread.is_alive() for thread in started_threads):
                    cleanup_error = RuntimeError("Owned pipe reader cleanup deadline exceeded")
        if cleanup_error:
            raise cleanup_error


def execute(command: list[str]) -> subprocess.CompletedProcess[str]:
    if len(command) != 9 or command[1:8] != GET_ARGUMENTS or not command[-1].startswith("repos/"):
        raise RuntimeError("Only fixed read-only GitHub API commands are permitted")
    budget = LIVE_BUDGET.get() or ReadBudget()
    if time.monotonic() >= budget.deadline:
        raise RuntimeError("GitHub read budget exhausted")
    return capture_process([native_executable(command[0]), *command[1:]], budget)


def gh_json(gh: str, endpoint: str, *, allow_not_found: bool = False) -> Any:
    completed = execute([
        gh,
        *GET_ARGUMENTS,
        endpoint,
    ])
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        if allow_not_found and re.search(r"(?:HTTP\s+404|status\s*code\s*404)", detail, re.IGNORECASE):
            return None
        status = re.search(r"(?:HTTP\s+|status\s*code\s*)(\d{3})", detail, re.IGNORECASE)
        suffix = f" (HTTP {status.group(1)})" if status else ""
        raise RuntimeError(f"Read-only GitHub API request failed{suffix}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError("GitHub API returned invalid JSON") from error


MAX_API_PAGES = 50
MAX_API_PAGE_ITEMS = 100


def bounded_pages(repository: str, endpoint: str, gh: str, *, envelope: str | None = None) -> list[dict[str, Any]]:
    collected: list[dict[str, Any]] = []
    seen: set[int] = set()
    for page in range(1, MAX_API_PAGES + 1):
        response = gh_json(gh, f"repos/{repository}/{endpoint}&page={page}")
        if envelope is not None:
            if not isinstance(response, dict) or not isinstance(response.get(envelope), list):
                raise RuntimeError("Invalid GitHub pagination response")
            batch = response[envelope]
        else:
            batch = response
        if not isinstance(batch, list) or len(batch) > MAX_API_PAGE_ITEMS:
            raise RuntimeError("Invalid GitHub pagination response")
        for item in batch:
            if not isinstance(item, dict) or type(item.get("id")) is not int or item["id"] <= 0 or item["id"] in seen:
                raise RuntimeError("Invalid or repeated GitHub pagination identity")
            seen.add(item["id"])
            collected.append(item)
        if len(batch) < MAX_API_PAGE_ITEMS:
            return collected
    raise RuntimeError("GitHub pagination limit exceeded; incomplete evidence rejected")


def paged_check_runs(repository: str, commit: str, gh: str) -> dict[str, Any]:
    runs = bounded_pages(repository, f"commits/{commit}/check-runs?filter=latest&per_page=100", gh, envelope="check_runs")
    return {"total_count": len(runs), "check_runs": runs}


def paged_rulesets(repository: str, gh: str) -> list[dict[str, Any]]:
    return bounded_pages(repository, "rulesets?includes_parents=true&per_page=100", gh)


def live_evidence(repository: str, commit: str, branches: list[str], gh: str) -> dict[str, Any]:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+", repository) or not FULL_SHA.fullmatch(commit):
        raise RuntimeError("Repository and immutable commit metadata are invalid")
    token = LIVE_BUDGET.set(ReadBudget())
    try:
        return read_live_evidence(repository, commit, branches, gh)
    finally:
        LIVE_BUDGET.reset(token)


def read_live_evidence(repository: str, commit: str, branches: list[str], gh: str) -> dict[str, Any]:
    repository_metadata = gh_json(gh, f"repos/{repository}")
    protections = {
        branch: gh_json(
            gh,
            f"repos/{repository}/branches/{quote(branch, safe='')}/protection",
            allow_not_found=True,
        )
        for branch in branches
    }
    summaries = paged_rulesets(repository, gh)
    rulesets = [
        gh_json(gh, f"repos/{repository}/rulesets/{item['id']}?includes_parents=true")
        for item in summaries
        if item.get("id") is not None
    ]
    return {
        "repository": repository_metadata,
        "checkRuns": paged_check_runs(repository, commit, gh),
        "branchProtection": protections,
        "rulesets": rulesets,
    }


def ref_pattern_matches(pattern: str, ref: str, default_ref: str) -> bool:
    if pattern == "~ALL":
        return True
    if pattern == "~DEFAULT_BRANCH":
        return ref == default_ref
    if "[" in pattern or "]" in pattern or "\\" in pattern:
        raise ValueError(f"unsupported GitHub ref pattern '{pattern}'")

    # GitHub ref rules use fnmatch-style patterns where `*` does not cross `/` and `**` does.
    pieces: list[str] = []
    index = 0
    while index < len(pattern):
        character = pattern[index]
        if character == "*":
            if index + 1 < len(pattern) and pattern[index + 1] == "*":
                pieces.append(".*")
                index += 2
            else:
                pieces.append("[^/]*")
                index += 1
        elif character == "?":
            pieces.append("[^/]")
            index += 1
        else:
            pieces.append(re.escape(character))
            index += 1
    return re.fullmatch("".join(pieces), ref) is not None


def ruleset_applies(ruleset: dict[str, Any], branch: str, default_branch: str) -> bool:
    if ruleset.get("target") != "branch" or ruleset.get("enforcement") != "active":
        return False
    ref = f"refs/heads/{branch}"
    default_ref = f"refs/heads/{default_branch}"
    condition = ((ruleset.get("conditions") or {}).get("ref_name") or {})
    includes = list(condition.get("include") or ["~ALL"])
    excludes = list(condition.get("exclude") or [])
    return (
        any(ref_pattern_matches(str(pattern), ref, default_ref) for pattern in includes)
        and not any(ref_pattern_matches(str(pattern), ref, default_ref) for pattern in excludes)
    )


def classic_status_source(protection: dict[str, Any] | None) -> dict[str, Any] | None:
    if protection is None:
        return None
    required = protection.get("required_status_checks")
    if not required:
        return None
    checks = list(required.get("checks") or [])
    contexts = {str(item) for item in required.get("contexts") or []}
    contexts.update(str(item.get("context")) for item in checks if item.get("context"))
    producer_ids, invalid_producer_contexts = producer_configuration(checks, "app_id")
    return {
        "kind": "classic",
        "name": "classic branch protection",
        "contexts": sorted(contexts),
        "producerIds": producer_ids,
        "invalidProducerContexts": invalid_producer_contexts,
        "strict": required.get("strict") is True,
    }


def ruleset_status_sources(
    rulesets: list[dict[str, Any]], branch: str, default_branch: str,
) -> tuple[list[dict[str, Any]], list[str]]:
    sources: list[dict[str, Any]] = []
    pattern_errors: list[str] = []
    for ruleset in rulesets:
        try:
            applies = ruleset_applies(ruleset, branch, default_branch)
        except ValueError as error:
            pattern_errors.append(f"ruleset '{ruleset.get('name') or ruleset.get('id')}' uses {error}")
            continue
        if not applies:
            continue
        for rule in ruleset.get("rules") or []:
            if rule.get("type") != "required_status_checks":
                continue
            parameters = rule.get("parameters") or {}
            checks = list(parameters.get("required_status_checks") or [])
            contexts = sorted({str(item.get("context")) for item in checks if item.get("context")})
            producer_ids, invalid_producer_contexts = producer_configuration(checks, "integration_id")
            sources.append({
                "kind": "ruleset",
                "id": ruleset.get("id"),
                "name": str(ruleset.get("name") or ruleset.get("id") or "unnamed ruleset"),
                "contexts": contexts,
                "producerIds": producer_ids,
                "invalidProducerContexts": invalid_producer_contexts,
                "strict": parameters.get("strict_required_status_checks_policy") is True,
            })
    return sources, pattern_errors


def producer_id(value: Any) -> int | None:
    # GitHub app/integration identifiers are JSON integers, never coercible labels.
    return value if type(value) is int and value > 0 else None


def producer_configuration(
    checks: list[dict[str, Any]], field: str,
) -> tuple[dict[str, list[int]], list[str]]:
    producers: dict[str, set[int]] = {}
    invalid_contexts: set[str] = set()
    for check in checks:
        context = str(check.get("context") or "")
        if not context:
            continue
        producers.setdefault(context, set())
        configured = producer_id(check.get(field))
        if configured is None:
            invalid_contexts.add(context)
        else:
            producers[context].add(configured)
    return (
        {context: sorted(values) for context, values in sorted(producers.items())},
        sorted(invalid_contexts),
    )


def latest_runs_by_name_and_producer(
    check_runs: list[dict[str, Any]],
) -> dict[tuple[str, int], dict[str, Any]]:
    selected: dict[tuple[str, int], dict[str, Any]] = {}
    for run in check_runs:
        name = str(run.get("name") or "")
        run_producer = producer_id((run.get("app") or {}).get("id"))
        if not name or run_producer is None:
            continue
        key = (name, run_producer)
        rank = (
            str(run.get("completed_at") or run.get("started_at") or run.get("created_at") or ""),
            int(run.get("id") or 0),
        )
        existing = selected.get(key)
        existing_rank = (
            str(existing.get("completed_at") or existing.get("started_at") or existing.get("created_at") or ""),
            int(existing.get("id") or 0),
        ) if existing else ("", -1)
        if rank > existing_rank:
            selected[key] = run
    return selected


def verify(
    evidence: dict[str, Any], repository: str, commit: str,
    branches: list[str], required_checks: list[str],
) -> dict[str, Any]:
    blockers: list[str] = []
    repository_metadata = evidence.get("repository") or {}
    default_branch = str(repository_metadata.get("default_branch") or "")
    if not default_branch:
        blockers.append("repository default branch is unavailable")

    expected = set(required_checks)
    protections = evidence.get("branchProtection") or {}
    rulesets = list(evidence.get("rulesets") or [])
    branch_results: list[dict[str, Any]] = []
    expected_producers: dict[str, set[int]] = {name: set() for name in required_checks}
    for branch in branches:
        sources: list[dict[str, Any]] = []
        classic = classic_status_source(protections.get(branch))
        if classic:
            sources.append(classic)
        ruleset_sources, pattern_errors = ruleset_status_sources(rulesets, branch, default_branch)
        sources.extend(ruleset_sources)
        configured = {context for source in sources for context in source["contexts"]}
        missing = sorted(expected - configured)
        unexpected = sorted(configured - expected)
        strict = bool(sources) and all(source["strict"] for source in sources)
        configured_producers = {
            context: sorted({
                producer
                for source in sources
                for producer in source["producerIds"].get(context, [])
            })
            for context in sorted(configured)
        }
        producer_errors: list[str] = []
        for context, producers in configured_producers.items():
            if not producers:
                producer_errors.append(
                    f"branch '{branch}' required context '{context}' has no configured producer app/integration id"
                )
                continue
            invalid_metadata = any(
                context in source["invalidProducerContexts"] for source in sources
            )
            if invalid_metadata:
                producer_errors.append(
                    f"branch '{branch}' required context '{context}' has missing or invalid producer metadata"
                )
            if len(producers) > 1:
                producer_errors.append(
                    f"branch '{branch}' has ambiguous configured producers for context '{context}': "
                    + ", ".join(str(item) for item in producers)
                )
            elif not invalid_metadata and context in expected:
                expected_producers[context].add(producers[0])
        for pattern_error in pattern_errors:
            blockers.append(f"branch '{branch}' cannot evaluate {pattern_error}")
        blockers.extend(producer_errors)
        if not sources:
            blockers.append(f"branch '{branch}' has no active required-status-check protection")
        if missing:
            blockers.append(f"branch '{branch}' is missing required contexts: {', '.join(missing)}")
        if unexpected:
            blockers.append(f"branch '{branch}' has unexpected required contexts: {', '.join(unexpected)}")
        if sources and not strict:
            blockers.append(f"branch '{branch}' does not require strict up-to-date status checks")
        branch_results.append({
            "branch": branch,
            "sources": sources,
            "configuredContexts": sorted(configured),
            "missingContexts": missing,
            "unexpectedContexts": unexpected,
            "configuredProducers": configured_producers,
            "strict": strict,
            "patternErrors": pattern_errors,
            "producerErrors": producer_errors,
            "exact": bool(sources) and not missing and not unexpected and strict
            and not pattern_errors and not producer_errors,
        })

    resolved_producers: dict[str, int | None] = {}
    for name in required_checks:
        producers = sorted(expected_producers[name])
        if not producers:
            blockers.append(f"required context '{name}' has no unambiguous configured producer")
            resolved_producers[name] = None
        elif len(producers) > 1:
            blockers.append(
                f"required context '{name}' has inconsistent configured producers across branches: "
                + ", ".join(str(item) for item in producers)
            )
            resolved_producers[name] = None
        else:
            resolved_producers[name] = producers[0]

    check_runs = list((evidence.get("checkRuns") or {}).get("check_runs") or [])
    latest = latest_runs_by_name_and_producer(check_runs)
    checks: list[dict[str, Any]] = []
    for name in required_checks:
        configured_producer = resolved_producers[name]
        run = latest.get((name, configured_producer)) if configured_producer is not None else None
        conclusion = str((run or {}).get("conclusion") or "")
        status = str((run or {}).get("status") or "")
        head_sha = str(((run or {}).get("check_suite") or {}).get("head_sha") or (run or {}).get("head_sha") or "")
        exists = run is not None
        immutable_match = exists and head_sha.lower() == commit.lower()
        successful = status == "completed" and conclusion == "success" and immutable_match
        named_runs = [candidate for candidate in check_runs if str(candidate.get("name") or "") == name]
        observed_producers = sorted({
            parsed
            for candidate in named_runs
            if (parsed := producer_id((candidate.get("app") or {}).get("id"))) is not None
        })
        has_missing_producer = any(
            producer_id((candidate.get("app") or {}).get("id")) is None for candidate in named_runs
        )
        if configured_producer is not None and not exists:
            if named_runs and not observed_producers:
                blockers.append(f"required check '{name}' has no check-run producer app id")
            elif named_runs:
                blockers.append(
                    f"required check '{name}' was produced by app(s) "
                    + ", ".join(str(item) for item in observed_producers)
                    + f"; configured app is {configured_producer}"
                )
            else:
                blockers.append(f"commit is missing required check '{name}'")
        elif exists and not immutable_match:
            blockers.append(f"required check '{name}' is not bound to commit {commit}")
        elif exists and not successful:
            blockers.append(f"required check '{name}' is not completed successfully")
        checks.append({
            "name": name,
            "configuredProducerId": configured_producer,
            "observedProducerIds": observed_producers,
            "missingProducerMetadataObserved": has_missing_producer,
            "present": exists,
            "status": status or None,
            "conclusion": conclusion or None,
            "headShaMatches": immutable_match,
            "successful": successful,
        })

    return {
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "readOnly": True,
        "repository": repository,
        "commitSha": commit.lower(),
        "defaultBranch": default_branch or None,
        "requiredContexts": required_checks,
        "commitChecks": checks,
        "branches": branch_results,
        "ready": not blockers,
        "blockers": blockers,
        "blockerCount": len(blockers),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default="custokingkr-dev/ims-v1")
    parser.add_argument("--commit", required=True, help="Immutable 40-character commit SHA")
    parser.add_argument("--branch", action="append", dest="branches")
    parser.add_argument("--required-check", action="append", dest="required_checks")
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--fixture", help="Use saved API evidence instead of calling GitHub")
    parser.add_argument("--output-json", default="artifacts/github-governance-checks.json")
    parser.add_argument("--report-only", action="store_true", help="Return zero even when blockers exist")
    args = parser.parse_args()

    if not FULL_SHA.fullmatch(args.commit):
        parser.error("--commit must be an immutable full 40-character hexadecimal SHA")
    branches = list(dict.fromkeys(args.branches or DEFAULT_BRANCHES))
    required_checks = list(dict.fromkeys(args.required_checks or DEFAULT_REQUIRED_CHECKS))
    if not branches or not required_checks or any(not value.strip() for value in branches + required_checks):
        parser.error("branches and required checks must be non-empty")

    if args.fixture:
        evidence = json.loads(pathlib.Path(args.fixture).read_text(encoding="utf-8"))
    else:
        evidence = live_evidence(args.repository, args.commit.lower(), branches, args.gh)
    result = verify(evidence, args.repository, args.commit, branches, required_checks)

    output = pathlib.Path(args.output_json)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "ready": result["ready"],
        "blockerCount": result["blockerCount"],
        "output": str(output),
    }, separators=(",", ":")))
    return 0 if result["ready"] or args.report_only else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(2)
