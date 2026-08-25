#!/usr/bin/env python3
"""Generate a deterministic Cloud Asset Inventory snapshot and optional drift report."""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import pathlib
import subprocess
import sys


def execute(command: list[str]):
    invocation = subprocess.list2cmdline(command) if os.name == "nt" else command
    return subprocess.run(
        invocation, shell=os.name == "nt", capture_output=True, text=True,
        timeout=120, check=False,
    )


def read_assets(project: str, gcloud: str, fixture: str | None) -> list[dict]:
    if fixture:
        fixture_data = json.loads(pathlib.Path(fixture).read_text(encoding="utf-8"))
        if isinstance(fixture_data, dict):
            fixture_data = fixture_data.get("assets")
        if not isinstance(fixture_data, list):
            raise RuntimeError("Cloud Asset fixture must be an asset list or a saved snapshot containing assets")
        return fixture_data
    command = [
        gcloud, "asset", "search-all-resources", f"--scope=projects/{project}",
        "--order-by=assetType,name", "--format=json", "--quiet",
    ]
    completed = execute(command)
    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        raise RuntimeError(
            "Cloud Asset Inventory read failed. Confirm cloudasset.googleapis.com is enabled and the "
            "caller has cloudasset.assets.searchAllResources. Enabling the API or changing IAM is an "
            f"external authority action and this script will not perform it. Detail: {detail}"
        )
    return json.loads(completed.stdout or "[]")


def canonical_asset(asset: dict) -> dict:
    return {
        "name": asset.get("name"),
        "assetType": asset.get("assetType"),
        "project": asset.get("project"),
        "location": asset.get("location"),
        "state": asset.get("state"),
        "displayName": asset.get("displayName"),
        "folders": sorted(asset.get("folders") or []),
        "organization": asset.get("organization"),
    }


def asset_digest(assets: list[dict]) -> str:
    payload = json.dumps(assets, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def snapshot(project: str, assets: list[dict], ignored_asset_types: list[str] | None = None) -> dict:
    canonical = sorted((canonical_asset(item) for item in assets), key=lambda item: (item["assetType"] or "", item["name"] or ""))
    ignored_types = sorted(set(ignored_asset_types or []))
    tracked = [item for item in canonical if item["assetType"] not in ignored_types]
    ignored = [item for item in canonical if item["assetType"] in ignored_types]
    counts = collections.Counter(item["assetType"] or "(unknown)" for item in canonical)
    tracked_counts = collections.Counter(item["assetType"] or "(unknown)" for item in tracked)
    return {
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "readOnly": True,
        "projectId": project,
        "assetCount": len(canonical),
        "trackedAssetCount": len(tracked),
        "ignoredAssetCount": len(ignored),
        "ignoredAssetTypes": ignored_types,
        "assetDigestSha256": asset_digest(tracked),
        "countsByAssetType": dict(sorted(counts.items())),
        "countsByTrackedAssetType": dict(sorted(tracked_counts.items())),
        "assets": canonical,
        "trackedAssets": tracked,
    }


def validate_reviewed_manifest(baseline: dict, project: str) -> None:
    if baseline.get("baselineVersion") != 1 or baseline.get("status") != "approved":
        raise RuntimeError("Scheduled drift comparison requires an approved baselineVersion 1 manifest")
    if baseline.get("projectId") != project:
        raise RuntimeError(
            f"Baseline project '{baseline.get('projectId')}' does not match requested project '{project}'"
        )
    digest = str(baseline.get("assetDigestSha256") or "")
    if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest.lower()):
        raise RuntimeError("Approved baseline has no valid assetDigestSha256")
    if not str(baseline.get("reviewReference") or "").strip():
        raise RuntimeError("Approved baseline has no reviewReference")


def compare(current: dict, baseline: dict | None) -> dict:
    if baseline is None:
        return {
            "baselineProvided": False, "baselineKind": None, "driftDetected": False,
            "added": [], "removed": [], "changed": [],
        }
    if baseline.get("baselineVersion") == 1:
        expected_counts = baseline.get("countsByTrackedAssetType") or {}
        actual_counts = current.get("countsByTrackedAssetType") or {}
        count_changes = {
            asset_type: {
                "before": int(expected_counts.get(asset_type, 0)),
                "after": int(actual_counts.get(asset_type, 0)),
            }
            for asset_type in sorted(set(expected_counts) | set(actual_counts))
            if int(expected_counts.get(asset_type, 0)) != int(actual_counts.get(asset_type, 0))
        }
        matches = (
            str(baseline.get("assetDigestSha256")) == str(current.get("assetDigestSha256"))
            and int(baseline.get("trackedAssetCount", -1)) == int(current.get("trackedAssetCount", -2))
            and not count_changes
        )
        return {
            "baselineProvided": True,
            "baselineKind": "reviewed-manifest-v1",
            "driftDetected": not matches,
            "expectedDigestSha256": baseline.get("assetDigestSha256"),
            "currentDigestSha256": current.get("assetDigestSha256"),
            "expectedTrackedAssetCount": baseline.get("trackedAssetCount"),
            "currentTrackedAssetCount": current.get("trackedAssetCount"),
            "countChangesByAssetType": count_changes,
            "added": [], "removed": [], "changed": [],
        }
    before = {(item.get("assetType"), item.get("name")): item for item in baseline.get("assets", [])}
    after = {(item.get("assetType"), item.get("name")): item for item in current.get("trackedAssets", [])}
    added = [after[key] for key in sorted(after.keys() - before.keys())]
    removed = [before[key] for key in sorted(before.keys() - after.keys())]
    changed = [
        {"before": before[key], "after": after[key]}
        for key in sorted(before.keys() & after.keys()) if before[key] != after[key]
    ]
    return {
        "baselineProvided": True, "baselineKind": "full-snapshot", "driftDetected": bool(added or removed or changed),
        "added": added, "removed": removed, "changed": changed,
    }


def baseline_candidate(current: dict, review_reference: str) -> dict:
    return {
        "baselineVersion": 1,
        "status": "candidate",
        "projectId": current["projectId"],
        "candidateGeneratedAtUtc": current["generatedAtUtc"],
        "reviewReference": review_reference,
        "trackedAssetCount": current["trackedAssetCount"],
        "ignoredAssetCountAtReview": current["ignoredAssetCount"],
        "ignoredAssetTypes": current["ignoredAssetTypes"],
        "assetDigestSha256": current["assetDigestSha256"],
        "countsByTrackedAssetType": current["countsByTrackedAssetType"],
    }


def markdown(current: dict, drift: dict) -> str:
    lines = [
        "# Cloud Asset Inventory drift report",
        "",
        f"Generated: {current['generatedAtUtc']}",
        "",
        f"Project: `{current['projectId']}`  ",
        f"Assets: **{current['assetCount']}**  ",
        f"Tracked assets: **{current['trackedAssetCount']}** · Explicitly ignored volatile assets: **{current['ignoredAssetCount']}**",
        f"Drift detected: **{'yes' if drift['driftDetected'] else 'no'}**",
        "",
        "| Asset type | Count |",
        "| --- | ---: |",
    ]
    lines.extend(f"| `{kind}` | {count} |" for kind, count in current["countsByAssetType"].items())
    if drift.get("countChangesByAssetType"):
        lines.extend(["", "## Tracked asset count changes", ""])
        lines.extend(
            f"- `{kind}`: {values['before']} → {values['after']}"
            for kind, values in drift["countChangesByAssetType"].items()
        )
    if not drift["baselineProvided"]:
        lines.extend(["", "No baseline was supplied; this run establishes an inventory snapshot only."])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--gcloud", default="gcloud")
    parser.add_argument("--baseline")
    parser.add_argument("--fixture")
    parser.add_argument("--ignore-asset-type", action="append", default=[])
    parser.add_argument("--require-reviewed-baseline", action="store_true")
    parser.add_argument("--redact-assets", action="store_true")
    parser.add_argument("--write-baseline-candidate")
    parser.add_argument("--review-reference", default="")
    parser.add_argument("--output-json", default="artifacts/gcp-asset-inventory.json")
    parser.add_argument("--output-markdown", default="artifacts/gcp-asset-drift.md")
    parser.add_argument("--fail-on-drift", action="store_true")
    args = parser.parse_args()

    baseline = json.loads(pathlib.Path(args.baseline).read_text(encoding="utf-8")) if args.baseline else None
    if args.require_reviewed_baseline:
        if baseline is None:
            raise RuntimeError("--require-reviewed-baseline requires --baseline")
        validate_reviewed_manifest(baseline, args.project)
    if args.baseline and args.ignore_asset_type:
        raise RuntimeError("Ignore policy comes only from the reviewed baseline when --baseline is supplied")
    ignored_types = list(baseline.get("ignoredAssetTypes") or []) if baseline else args.ignore_asset_type
    assets = read_assets(args.project, args.gcloud, args.fixture)
    current = snapshot(args.project, assets, ignored_types)
    drift = compare(current, baseline)
    if args.write_baseline_candidate:
        if baseline and baseline.get("baselineVersion") == 1:
            validate_reviewed_manifest(baseline, args.project)
        if not args.review_reference.strip():
            raise RuntimeError("Baseline candidate generation requires --review-reference")
        candidate_path = pathlib.Path(args.write_baseline_candidate)
        candidate_path.parent.mkdir(parents=True, exist_ok=True)
        candidate_path.write_text(
            json.dumps(baseline_candidate(current, args.review_reference.strip()), indent=2) + "\n",
            encoding="utf-8",
        )
    safe_current = {key: value for key, value in current.items() if key != "trackedAssets"}
    if args.redact_assets:
        safe_current.pop("assets", None)
    result = {**safe_current, "drift": drift}

    json_path = pathlib.Path(args.output_json)
    markdown_path = pathlib.Path(args.output_markdown)
    json_path.parent.mkdir(parents=True, exist_ok=True)
    markdown_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(markdown(current, drift), encoding="utf-8")
    print(json.dumps({
        "assetCount": current["assetCount"],
        "added": len(drift["added"]),
        "removed": len(drift["removed"]),
        "changed": len(drift["changed"]),
    }, separators=(",", ":")))
    has_drift = bool(drift["driftDetected"])
    return 1 if args.fail_on_drift and has_drift else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(2)
