#!/usr/bin/env python3
"""Validate the repository-owned gate before a scheduled GCP audit may authenticate."""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys


SHA256 = re.compile(r"^[0-9a-f]{64}$")
DISABLED_STATUS = "disabled-pending-post-provision-baseline"
ENABLED_STATUS = "approved"
REQUIRED_POST_PROVISION_COUNTS = {
    "iam.googleapis.com/Role": 2,
    "iam.googleapis.com/ServiceAccount": 23,
}


def parse_repository_flag(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized in ("", "false"):
        return False
    if normalized == "true":
        return True
    raise RuntimeError("GCP_GOVERNANCE_AUDIT_ENABLED must be exactly 'true', 'false', or unset")


def validate(config: dict, baseline: dict, project: str, baseline_path: str, repository_flag: str) -> dict:
    if config.get("configVersion") != 1:
        raise RuntimeError("Governance audit enablement must use configVersion 1")
    if config.get("projectId") != project or baseline.get("projectId") != project:
        raise RuntimeError("Enablement config, baseline, and immutable workflow project must match")
    configured_baseline_path = pathlib.Path(str(config.get("baselinePath") or "")).resolve()
    workflow_baseline_path = pathlib.Path(baseline_path).resolve()
    if configured_baseline_path != workflow_baseline_path:
        raise RuntimeError("Enablement config does not reference the workflow's reviewed baseline path")
    if baseline.get("baselineVersion") != 1 or baseline.get("status") != "approved":
        raise RuntimeError("Governance audit requires an approved baselineVersion 1 manifest")

    baseline_digest = str(baseline.get("assetDigestSha256") or "").lower()
    configured_digest = str(config.get("approvedBaselineDigestSha256") or "").lower()
    if not SHA256.fullmatch(baseline_digest) or configured_digest != baseline_digest:
        raise RuntimeError("Enablement config digest does not match the approved baseline")

    repository_enabled = parse_repository_flag(repository_flag)
    config_enabled = config.get("enabled")
    if not isinstance(config_enabled, bool):
        raise RuntimeError("Enablement config 'enabled' must be a JSON boolean")
    required_counts = config.get("requiredPostProvisionTrackedCounts")
    if required_counts != REQUIRED_POST_PROVISION_COUNTS:
        raise RuntimeError("Enablement config must retain the exact post-provision tracked-count contract")

    if not config_enabled:
        if config.get("status") != DISABLED_STATUS:
            raise RuntimeError(f"Disabled enablement config must retain status '{DISABLED_STATUS}'")
        if config.get("activationReviewReference") not in (None, ""):
            raise RuntimeError("Disabled enablement config must not claim an activation review")
        if repository_enabled:
            raise RuntimeError(
                "Repository enable flag is true before the post-provision baseline/config review is approved"
            )
        return {
            "enabled": False,
            "reason": "repository-config-disabled-pending-post-provision-baseline",
            "projectId": project,
            "baselineDigestSha256": baseline_digest,
        }

    if config.get("status") != ENABLED_STATUS:
        raise RuntimeError(f"Enabled configuration must have status '{ENABLED_STATUS}'")
    if not str(config.get("activationReviewReference") or "").strip():
        raise RuntimeError("Enabled configuration requires a non-empty activationReviewReference")

    tracked_counts = baseline.get("countsByTrackedAssetType") or {}
    for asset_type, minimum in required_counts.items():
        if int(tracked_counts.get(asset_type, 0)) < int(minimum):
            raise RuntimeError(
                f"Approved baseline predates required provisioned asset count for {asset_type}: "
                f"expected at least {minimum}, found {tracked_counts.get(asset_type, 0)}"
            )

    return {
        "enabled": repository_enabled,
        "reason": "enabled" if repository_enabled else "repository-variable-disabled",
        "projectId": project,
        "baselineDigestSha256": baseline_digest,
        "activationReviewReference": str(config["activationReviewReference"]),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--baseline", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--repository-enabled", default="")
    args = parser.parse_args()

    config = json.loads(pathlib.Path(args.config).read_text(encoding="utf-8"))
    baseline = json.loads(pathlib.Path(args.baseline).read_text(encoding="utf-8"))
    result = validate(config, baseline, args.project, args.baseline, args.repository_enabled)
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(2)
