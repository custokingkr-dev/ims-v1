#!/usr/bin/env python3
"""Verify immutable check evidence and exact GitHub branch governance contexts.

The live path is read-only. A fixture path exists so policy semantics remain executable in CI
without GitHub credentials or repository-administrator access.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import sys
from typing import Any


DEFAULT_REQUIRED_CHECKS = (
    "summary",
    "analyze (java-kotlin)",
    "analyze (javascript-typescript)",
)
DEFAULT_BRANCHES = ("main", "dev")
FULL_SHA = re.compile(r"^[0-9a-fA-F]{40}$")


def execute(command: list[str]) -> subprocess.CompletedProcess[str]:
    invocation: str | list[str] = subprocess.list2cmdline(command) if os.name == "nt" else command
    return subprocess.run(
        invocation,
        shell=os.name == "nt",
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )


def gh_json(gh: str, endpoint: str, *, allow_not_found: bool = False) -> Any:
    completed = execute([
        gh,
        "api",
        "-H", "Accept: application/vnd.github+json",
        "-H", "X-GitHub-Api-Version: 2022-11-28",
        endpoint,
    ])
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        if allow_not_found and re.search(r"(?:HTTP\s+404|status\s*code\s*404)", detail, re.IGNORECASE):
            return None
        raise RuntimeError(f"Read-only GitHub API request failed for {endpoint}: {detail}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"GitHub API returned invalid JSON for {endpoint}: {error}") from error


def paged_check_runs(repository: str, commit: str, gh: str) -> dict[str, Any]:
    runs: list[dict[str, Any]] = []
    page = 1
    while True:
        response = gh_json(
            gh,
            f"repos/{repository}/commits/{commit}/check-runs?filter=latest&per_page=100&page={page}",
        )
        batch = list(response.get("check_runs") or [])
        runs.extend(batch)
        if len(batch) < 100:
            return {"total_count": len(runs), "check_runs": runs}
        page += 1


def paged_rulesets(repository: str, gh: str) -> list[dict[str, Any]]:
    summaries: list[dict[str, Any]] = []
    page = 1
    while True:
        batch = list(gh_json(
            gh,
            f"repos/{repository}/rulesets?includes_parents=true&per_page=100&page={page}",
        ) or [])
        summaries.extend(batch)
        if len(batch) < 100:
            return summaries
        page += 1


def live_evidence(repository: str, commit: str, branches: list[str], gh: str) -> dict[str, Any]:
    repository_metadata = gh_json(gh, f"repos/{repository}")
    protections = {
        branch: gh_json(
            gh,
            f"repos/{repository}/branches/{branch}/protection",
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
    contexts = {str(item) for item in required.get("contexts") or []}
    contexts.update(
        str(item.get("context"))
        for item in required.get("checks") or []
        if item.get("context")
    )
    return {
        "kind": "classic",
        "name": "classic branch protection",
        "contexts": sorted(contexts),
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
            contexts = sorted({
                str(item.get("context"))
                for item in parameters.get("required_status_checks") or []
                if item.get("context")
            })
            sources.append({
                "kind": "ruleset",
                "id": ruleset.get("id"),
                "name": str(ruleset.get("name") or ruleset.get("id") or "unnamed ruleset"),
                "contexts": contexts,
                "strict": parameters.get("strict_required_status_checks_policy") is True,
            })
    return sources, pattern_errors


def latest_runs_by_name(check_runs: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    selected: dict[str, dict[str, Any]] = {}
    for run in check_runs:
        name = str(run.get("name") or "")
        if not name:
            continue
        rank = (
            str(run.get("completed_at") or run.get("started_at") or run.get("created_at") or ""),
            int(run.get("id") or 0),
        )
        existing = selected.get(name)
        existing_rank = (
            str(existing.get("completed_at") or existing.get("started_at") or existing.get("created_at") or ""),
            int(existing.get("id") or 0),
        ) if existing else ("", -1)
        if rank > existing_rank:
            selected[name] = run
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

    latest = latest_runs_by_name(list((evidence.get("checkRuns") or {}).get("check_runs") or []))
    checks: list[dict[str, Any]] = []
    for name in required_checks:
        run = latest.get(name)
        conclusion = str((run or {}).get("conclusion") or "")
        status = str((run or {}).get("status") or "")
        head_sha = str(((run or {}).get("check_suite") or {}).get("head_sha") or (run or {}).get("head_sha") or "")
        exists = run is not None
        immutable_match = exists and head_sha.lower() == commit.lower()
        successful = status == "completed" and conclusion == "success" and immutable_match
        if not exists:
            blockers.append(f"commit is missing required check '{name}'")
        elif not immutable_match:
            blockers.append(f"required check '{name}' is not bound to commit {commit}")
        elif not successful:
            blockers.append(f"required check '{name}' is not completed successfully")
        checks.append({
            "name": name,
            "present": exists,
            "status": status or None,
            "conclusion": conclusion or None,
            "headShaMatches": immutable_match,
            "successful": successful,
        })

    expected = set(required_checks)
    protections = evidence.get("branchProtection") or {}
    rulesets = list(evidence.get("rulesets") or [])
    branch_results: list[dict[str, Any]] = []
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
        exact = bool(sources) and not missing and not unexpected and strict
        for pattern_error in pattern_errors:
            blockers.append(f"branch '{branch}' cannot evaluate {pattern_error}")
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
            "strict": strict,
            "patternErrors": pattern_errors,
            "exact": exact and not pattern_errors,
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
