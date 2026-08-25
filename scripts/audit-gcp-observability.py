#!/usr/bin/env python3
"""Read-only inventory and validation for Monitoring dashboards and alert policies."""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


RESOURCE_FILTER = re.compile(
    r'resource\.labels?\.(bucket_name|service_name|subscription_id|database_id)\s*=\s*"([^"]+)"'
)


def execute(command: list[str], timeout: int):
    # gcloud is a .cmd shim on Windows; CreateProcess cannot launch it directly. list2cmdline preserves
    # each already-separated argument while shell=True selects cmd.exe only on that platform.
    invocation = subprocess.list2cmdline(command) if os.name == "nt" else command
    return subprocess.run(
        invocation, shell=os.name == "nt", capture_output=True, text=True,
        timeout=timeout, check=False,
    )


def run_json(command: list[str]):
    completed = execute(command, 60)
    if completed.returncode != 0:
        raise RuntimeError(f"{' '.join(command[:3])} failed: {completed.stderr.strip()}")
    return json.loads(completed.stdout or "[]")


def access_token(gcloud: str) -> str:
    completed = execute([gcloud, "auth", "print-access-token"], 30)
    if completed.returncode != 0 or not completed.stdout.strip():
        raise RuntimeError("Could not obtain a short-lived token for read-only Monitoring inventory")
    return completed.stdout.strip()


def api_json(url: str, token: str):
    request = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        body = error.read(800).decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {error.code}: {body}") from error


def paged(url: str, collection: str, token: str) -> list[dict]:
    items: list[dict] = []
    page_token = ""
    while True:
        separator = "&" if "?" in url else "?"
        page_url = url + (f"{separator}pageToken={urllib.parse.quote(page_token)}" if page_token else "")
        page = api_json(page_url, token)
        items.extend(page.get(collection, []))
        page_token = page.get("nextPageToken", "")
        if not page_token:
            return items


def collect_filters(value, source: str, found: list[dict]):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "filter" and isinstance(child, str) and "metric.type" in child:
                found.append({"source": source, "filter": child})
            else:
                collect_filters(child, source, found)
    elif isinstance(value, list):
        for child in value:
            collect_filters(child, source, found)


def normalized_resources(project: str, inventory: dict) -> dict[str, set[str]]:
    buckets = {item.get("name", "").removeprefix("gs://") for item in inventory.get("buckets", [])}
    services = {
        item.get("metadata", {}).get("name") or item.get("name", "").split("/")[-1]
        for item in inventory.get("services", [])
    }
    subscriptions = {item.get("name", "").split("/")[-1] for item in inventory.get("subscriptions", [])}
    databases = {
        f"{project}:{item.get('name')}" for item in inventory.get("sqlInstances", []) if item.get("name")
    }
    return {
        "bucket_name": {item for item in buckets if item},
        "service_name": {item for item in services if item},
        "subscription_id": {item for item in subscriptions if item},
        "database_id": databases,
    }


def validate_resource_references(filters: list[dict], resources: dict[str, set[str]]) -> list[dict]:
    missing = []
    seen = set()
    for entry in filters:
        for label, resource_id in RESOURCE_FILTER.findall(entry["filter"]):
            key = (entry["source"], label, resource_id)
            if key in seen:
                continue
            seen.add(key)
            if resource_id not in resources.get(label, set()):
                missing.append({"source": entry["source"], "label": label, "resourceId": resource_id})
    return missing


def query_filter(project: str, token: str, metric_filter: str, lookback_days: int) -> dict:
    end = dt.datetime.now(dt.timezone.utc)
    start = end - dt.timedelta(days=lookback_days)
    params = urllib.parse.urlencode({
        "filter": metric_filter,
        "interval.startTime": start.isoformat().replace("+00:00", "Z"),
        "interval.endTime": end.isoformat().replace("+00:00", "Z"),
        "view": "HEADERS",
        "pageSize": "1",
    })
    try:
        result = api_json(
            f"https://monitoring.googleapis.com/v3/projects/{project}/timeSeries?{params}", token,
        )
        return {"filter": metric_filter, "seriesPresent": bool(result.get("timeSeries")), "error": None}
    except Exception as error:  # report every bad filter together instead of stopping at the first
        return {"filter": metric_filter, "seriesPresent": False, "error": str(error)}


def live_inventory(project: str, region: str, gcloud: str) -> tuple[dict, str]:
    token = access_token(gcloud)
    dashboards = paged(
        f"https://monitoring.googleapis.com/v1/projects/{project}/dashboards?pageSize=1000",
        "dashboards", token,
    )
    policies = paged(
        f"https://monitoring.googleapis.com/v3/projects/{project}/alertPolicies?pageSize=1000",
        "alertPolicies", token,
    )
    inventory = {
        "dashboards": dashboards,
        "alertPolicies": policies,
        "buckets": run_json([gcloud, "storage", "buckets", "list", f"--project={project}", "--format=json"]),
        "services": run_json([gcloud, "run", "services", "list", f"--project={project}", f"--region={region}", "--format=json"]),
        "subscriptions": run_json([gcloud, "pubsub", "subscriptions", "list", f"--project={project}", "--format=json"]),
        "sqlInstances": run_json([gcloud, "sql", "instances", "list", f"--project={project}", "--format=json"]),
    }
    return inventory, token


def render_markdown(report: dict) -> str:
    summary = report["summary"]
    lines = [
        "# GCP observability inventory and validation",
        "",
        f"Generated: {report['generatedAtUtc']}",
        "",
        "| Check | Result |",
        "| --- | ---: |",
        f"| Dashboards | {summary['dashboardCount']} |",
        f"| Enabled alert policies | {summary['enabledAlertPolicyCount']} |",
        f"| Unique Monitoring filters | {summary['uniqueFilterCount']} |",
        f"| Unique dashboard filters | {summary['dashboardFilterCount']} |",
        f"| Unique alert filters | {summary['alertFilterCount']} |",
        f"| Filters with recent series | {summary['filtersWithData']} |",
        f"| Filters with no recent series | {summary['filtersWithoutData']} |",
        f"| Filter query errors | {summary['filterQueryErrors']} |",
        f"| Missing referenced resources | {summary['missingResourceReferences']} |",
        "",
        "No-data is reported separately from a malformed filter. Some incident counters legitimately have",
        "no series during a healthy lookback window; use `--fail-on-no-data` only for dashboards whose",
        "contract requires continuous telemetry.",
    ]
    if report["missingResourceReferences"]:
        lines.extend(["", "## Missing resource references", ""])
        lines.extend(
            f"- `{item['source']}` references missing {item['label']} `{item['resourceId']}`."
            for item in report["missingResourceReferences"]
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--region", default="asia-south2")
    parser.add_argument("--gcloud", default="gcloud")
    parser.add_argument("--lookback-days", type=int, default=7)
    parser.add_argument("--output-json", default="artifacts/gcp-observability-audit.json")
    parser.add_argument("--output-markdown", default="artifacts/gcp-observability-audit.md")
    parser.add_argument("--fixture", help="Offline inventory fixture; skips all GCP calls")
    parser.add_argument("--fail-on-no-data", action="store_true")
    args = parser.parse_args()

    if args.fixture:
        fixture = json.loads(pathlib.Path(args.fixture).read_text(encoding="utf-8"))
        inventory = fixture["inventory"]
        token = ""
    else:
        inventory, token = live_inventory(args.project, args.region, args.gcloud)

    filters: list[dict] = []
    for dashboard in inventory.get("dashboards", []):
        collect_filters(dashboard, f"dashboard:{dashboard.get('displayName', dashboard.get('name', 'unknown'))}", filters)
    for policy in inventory.get("alertPolicies", []):
        collect_filters(policy, f"alert:{policy.get('displayName', policy.get('name', 'unknown'))}", filters)

    unique_filters = sorted({entry["filter"] for entry in filters})
    dashboard_filters = {entry["filter"] for entry in filters if entry["source"].startswith("dashboard:")}
    alert_filters = {entry["filter"] for entry in filters if entry["source"].startswith("alert:")}
    resources = normalized_resources(args.project, inventory)
    missing = validate_resource_references(filters, resources)

    if args.fixture:
        fixture_results = fixture.get("timeSeriesByFilter", {})
        queries = [
            {"filter": item, "seriesPresent": bool(fixture_results.get(item)), "error": None}
            for item in unique_filters
        ]
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            queries = list(executor.map(
                lambda item: query_filter(args.project, token, item, args.lookback_days), unique_filters,
            ))

    query_errors = [item for item in queries if item["error"]]
    no_data = [item for item in queries if not item["error"] and not item["seriesPresent"]]
    report = {
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
        "readOnly": True,
        "projectId": args.project,
        "region": args.region,
        "lookbackDays": args.lookback_days,
        "summary": {
            "dashboardCount": len(inventory.get("dashboards", [])),
            "alertPolicyCount": len(inventory.get("alertPolicies", [])),
            "enabledAlertPolicyCount": sum(
                1 for item in inventory.get("alertPolicies", []) if item.get("enabled", True)
            ),
            "uniqueFilterCount": len(unique_filters),
            "dashboardFilterCount": len(dashboard_filters),
            "alertFilterCount": len(alert_filters),
            "filtersWithData": len(queries) - len(query_errors) - len(no_data),
            "filtersWithoutData": len(no_data),
            "filterQueryErrors": len(query_errors),
            "missingResourceReferences": len(missing),
            "bucketCount": len(resources["bucket_name"]),
            "cloudRunServiceCount": len(resources["service_name"]),
            "subscriptionCount": len(resources["subscription_id"]),
            "cloudSqlInstanceCount": len(resources["database_id"]),
        },
        "missingResourceReferences": missing,
        "filterQueries": queries,
    }
    for filename, content in (
        (args.output_json, json.dumps(report, indent=2) + "\n"),
        (args.output_markdown, render_markdown(report)),
    ):
        path = pathlib.Path(filename)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    print(json.dumps(report["summary"], separators=(",", ":")))
    failed = bool(query_errors or missing or (args.fail_on_no_data and no_data))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
