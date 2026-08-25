#!/usr/bin/env python3
"""Generate a deterministic Cloud Asset Inventory snapshot and optional drift report."""

from __future__ import annotations

import argparse
import collections
import datetime as dt
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
        return json.loads(pathlib.Path(fixture).read_text(encoding="utf-8"))
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


def snapshot(project: str, assets: list[dict]) -> dict:
    canonical = sorted((canonical_asset(item) for item in assets), key=lambda item: (item["assetType"] or "", item["name"] or ""))
    counts = collections.Counter(item["assetType"] or "(unknown)" for item in canonical)
    return {
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "readOnly": True,
        "projectId": project,
        "assetCount": len(canonical),
        "countsByAssetType": dict(sorted(counts.items())),
        "assets": canonical,
    }


def compare(current: dict, baseline: dict | None) -> dict:
    if baseline is None:
        return {"baselineProvided": False, "added": [], "removed": [], "changed": []}
    before = {(item.get("assetType"), item.get("name")): item for item in baseline.get("assets", [])}
    after = {(item.get("assetType"), item.get("name")): item for item in current.get("assets", [])}
    added = [after[key] for key in sorted(after.keys() - before.keys())]
    removed = [before[key] for key in sorted(before.keys() - after.keys())]
    changed = [
        {"before": before[key], "after": after[key]}
        for key in sorted(before.keys() & after.keys()) if before[key] != after[key]
    ]
    return {"baselineProvided": True, "added": added, "removed": removed, "changed": changed}


def markdown(current: dict, drift: dict) -> str:
    lines = [
        "# Cloud Asset Inventory drift report",
        "",
        f"Generated: {current['generatedAtUtc']}",
        "",
        f"Project: `{current['projectId']}`  ",
        f"Assets: **{current['assetCount']}**  ",
        f"Added: **{len(drift['added'])}** · Removed: **{len(drift['removed'])}** · Changed: **{len(drift['changed'])}**",
        "",
        "| Asset type | Count |",
        "| --- | ---: |",
    ]
    lines.extend(f"| `{kind}` | {count} |" for kind, count in current["countsByAssetType"].items())
    if not drift["baselineProvided"]:
        lines.extend(["", "No baseline was supplied; this run establishes an inventory snapshot only."])
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--gcloud", default="gcloud")
    parser.add_argument("--baseline")
    parser.add_argument("--fixture")
    parser.add_argument("--output-json", default="artifacts/gcp-asset-inventory.json")
    parser.add_argument("--output-markdown", default="artifacts/gcp-asset-drift.md")
    parser.add_argument("--fail-on-drift", action="store_true")
    args = parser.parse_args()

    assets = read_assets(args.project, args.gcloud, args.fixture)
    current = snapshot(args.project, assets)
    baseline = json.loads(pathlib.Path(args.baseline).read_text(encoding="utf-8")) if args.baseline else None
    drift = compare(current, baseline)
    result = {**current, "drift": drift}

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
    has_drift = bool(drift["added"] or drift["removed"] or drift["changed"])
    return 1 if args.fail_on_drift and has_drift else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(2)
