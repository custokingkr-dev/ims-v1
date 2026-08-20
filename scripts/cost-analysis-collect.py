#!/usr/bin/env python3
"""Per-service daily cost, computed from Cloud Monitoring usage priced at real SKU rates.

WHY THIS EXISTS

The Cloud Billing usage-cost export for account 014C0A-C6B9AF-5FABC0 cannot be enabled. Six attempts
across every configuration available on our side -- original dataset, clean dataset, pre-granted
dataset, asia-south2 (rejected as an unsupported region), and a virgin config after clearing all three
exports -- fail with a generic server error, while the PRICING export succeeds every time on the same
account and dataset. Google's own docs put pricing at strictly higher privilege than usage cost, so the
export needing MORE permission works and the one needing less does not. That isolates the fault to a
broken config record on their side, and cloudbilling v1/v1beta expose no export-configuration methods,
so there is no scripted workaround either.

So cost is reconstructed from what each service actually consumed.

WHY PYTHON RATHER THAN NODE

This runs both on a workstation and as a Cloud Run job on google/cloud-sdk:slim, which carries python3
and bq but no node. One implementation that runs in both places beats two that drift apart.

WHAT IT GIVES UP AND GAINS

Gives up true SKU granularity, sustained- and committed-use discounts, tax, and anything unmodelled --
it is an estimate. Gains attribution per Cloud Run SERVICE rather than per SKU, which is the more
useful cut for deciding what to optimise, and freshness: billing export lands hours late and carries
"no delivery or latency guarantees", while metrics are near real time.
"""
import json
import os
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

PROJECT = os.environ.get("COST_ANALYSIS_PROJECT", "custoking-prod")
DATASET = os.environ.get("COST_ANALYSIS_DATASET", "cost_analysis")
TABLE = "daily_service_cost"
INR_PER_USD = float(os.environ.get("INR_PER_USD", "88"))

# asia-south2 rates from cloud_pricing_export, priced 2026-08-19. Held here rather than queried per run
# so this keeps working while the pricing export is disabled or its dataset is being rebuilt.
RATE = {
    "cpuSeconds": 0.0000216,   # Services CPU (Instance-based billing) in asia-south2
    "gibSeconds": 0.0000024,   # Services Memory (Instance-based billing) in asia-south2
    "requests": 0.40 / 1e6,
}
EGRESS_USD_PER_GIB = 0.12

# Cloud SQL is a MEASURED constant, not a computed one. It dominates the bill and is exactly fixed: the
# same tier in the same region billed INR 96.41/day for the instance plus INR 6.29/day for storage
# across thirteen consecutive days with a coefficient of variation of zero. That is what Google actually
# charged, discounts included -- better than re-deriving it from a catalogue that lists MySQL and
# PostgreSQL tiers in ways that are easy to mis-pick.
CLOUD_SQL_INR_PER_DAY = 102.70

SERIES = [
    ("run.googleapis.com/container/cpu/allocation_time", "cpuSeconds", "vCPU-second"),
    ("run.googleapis.com/container/memory/allocation_time", "gibSeconds", "GiB-second"),
    ("run.googleapis.com/request_count", "requests", "request"),
    ("run.googleapis.com/container/network/sent_bytes_count", "egressBytes", "byte"),
]

SCHEMA = ("usage_date:DATE,project:STRING,component:STRING,component_kind:STRING,"
          "metric:STRING,quantity:FLOAT,unit:STRING,cost_usd:FLOAT,cost_inr:FLOAT,"
          "method:STRING,computed_at:TIMESTAMP")


def access_token():
    """Metadata server on Cloud Run, gcloud on a workstation."""
    if os.environ.get("K_SERVICE") or os.environ.get("CLOUD_RUN_JOB"):
        req = urllib.request.Request(
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token",
            headers={"Metadata-Flavor": "Google"})
        with urllib.request.urlopen(req, timeout=10) as res:
            return json.load(res)["access_token"]
    return subprocess.run(["gcloud", "auth", "print-access-token"],
                          capture_output=True, text=True, shell=(os.name == "nt"),
                          check=True).stdout.strip()


def timeseries(token, metric, day):
    params = [
        ("filter", f'metric.type="{metric}"'),
        ("interval.startTime", f"{day}T00:00:00Z"),
        ("interval.endTime", f"{day}T23:59:59Z"),
        # Hourly, not daily. A 24h alignment window on request_count -- which carries many label
        # combinations -- times out server-side with "shorten the time interval". Points are summed
        # below anyway, so a finer window costs nothing and simply succeeds.
        ("aggregation.alignmentPeriod", "3600s"),
        ("aggregation.perSeriesAligner", "ALIGN_SUM"),
        ("aggregation.crossSeriesReducer", "REDUCE_SUM"),
        ("aggregation.groupByFields", "resource.label.service_name"),
    ]
    url = (f"https://monitoring.googleapis.com/v3/projects/{PROJECT}/timeSeries?"
           + urllib.parse.urlencode(params))
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    for attempt in (1, 2):
        try:
            with urllib.request.urlopen(req, timeout=120) as res:
                return json.load(res).get("timeSeries", [])
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", "replace")
            # Retried once: Monitoring returns transient timeouts on wide queries, and losing a whole
            # day of cost history to one flaky call would be a silent gap in a table meant to be
            # authoritative.
            if attempt == 1 and "timed out" in body.lower():
                continue
            raise SystemExit(f"monitoring query failed for {metric}: {body[:300]}")
    return []


def collect_day(token, day):
    per_service = {}
    for metric, field, _unit in SERIES:
        for series in timeseries(token, metric, day):
            name = series.get("resource", {}).get("labels", {}).get("service_name") or "(unattributed)"
            total = 0.0
            for point in series.get("points", []):
                v = point.get("value", {})
                total += float(v.get("doubleValue") or v.get("int64Value") or 0)
            if total:
                per_service.setdefault(name, {}).setdefault(field, 0.0)
                per_service[name][field] += total

    stamp = datetime.now(timezone.utc).isoformat()
    rows = []
    for service, usage in per_service.items():
        for _metric, field, unit in SERIES:
            qty = usage.get(field)
            if not qty:
                continue
            usd = (qty / 1024 ** 3) * EGRESS_USD_PER_GIB if field == "egressBytes" else qty * RATE[field]
            rows.append({
                "usage_date": day, "project": PROJECT, "component": service,
                "component_kind": "cloud_run", "metric": field, "quantity": qty, "unit": unit,
                "cost_usd": usd, "cost_inr": usd * INR_PER_USD,
                "method": "monitoring_usage_x_sku_rate", "computed_at": stamp,
            })

    rows.append({
        "usage_date": day, "project": PROJECT, "component": "custoking-db-prod",
        "component_kind": "cloud_sql", "metric": "instance_day", "quantity": 1, "unit": "day",
        "cost_usd": CLOUD_SQL_INR_PER_DAY / INR_PER_USD, "cost_inr": CLOUD_SQL_INR_PER_DAY,
        # Named differently on purpose: a reader must see at a glance which rows are measured and which
        # are computed, without going back to the source.
        "method": "measured_constant", "computed_at": stamp,
    })
    return rows


def main():
    days = sys.argv[1:]
    if not days:
        days = [(datetime.now(timezone.utc) - timedelta(days=1)).strftime("%Y-%m-%d")]

    token = access_token()
    rows = []
    for day in days:
        day_rows = collect_day(token, day)
        total = sum(r["cost_inr"] for r in day_rows)
        print(f"  {day}: {len(day_rows)} rows, INR {total:.2f}")
        rows.extend(day_rows)

    if not rows:
        print("nothing to load")
        return

    # Idempotent by DELETE-then-append, deliberately, rather than by partition decorator.
    #
    # The first attempt wrote to `table$YYYYMMDD` with --replace, which is the idiomatic BigQuery way
    # to make a daily load idempotent. It worked on a workstation and SILENTLY APPENDED inside the
    # Cloud Run container -- same script, different bq version, and a day quietly doubled to INR 222.80.
    # Nothing errored, which is the failure shape this whole project keeps producing.
    #
    # So the day is removed explicitly first. DELETE + load is two operations rather than one atomic
    # replace, and if the load fails after the delete the day is briefly missing -- but a missing day is
    # visible and a re-run repairs it, whereas a doubled day looks exactly like a real one. Preferring
    # the loud failure over the quiet corruption is the whole point.
    by_day = {}
    for row in rows:
        by_day.setdefault(row["usage_date"], []).append(row)

    for day, day_rows in sorted(by_day.items()):
        # Skipped when the table does not exist yet: DELETE against a missing table is an error, and
        # the first ever run has nothing to clear.
        probe = subprocess.run(["bq", "show", f"--project_id={PROJECT}", f"{PROJECT}:{DATASET}.{TABLE}"],
                               capture_output=True, text=True, shell=(os.name == "nt"))
        if probe.returncode == 0:
            purge = subprocess.run(
                ["bq", "query", f"--project_id={PROJECT}", "--location=US", "--nouse_legacy_sql", "--quiet",
                 f"DELETE FROM `{PROJECT}.{DATASET}.{TABLE}` WHERE usage_date = DATE '{day}'"],
                capture_output=True, text=True, shell=(os.name == "nt"))
            if purge.returncode != 0:
                raise SystemExit(f"failed clearing {day}: " + (purge.stderr or purge.stdout)[:300])

        handle, path = tempfile.mkstemp(suffix=".ndjson")
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as fh:
            for row in day_rows:
                fh.write(json.dumps(row) + "\n")

        cmd = ["bq", "load", f"--project_id={PROJECT}", "--location=US",
               "--source_format=NEWLINE_DELIMITED_JSON", "--noreplace",
               "--time_partitioning_field=usage_date",
               f"{PROJECT}:{DATASET}.{TABLE}", path, SCHEMA]
        result = subprocess.run(cmd, capture_output=True, text=True, shell=(os.name == "nt"))
        os.unlink(path)
        if result.returncode != 0:
            raise SystemExit(f"bq load failed for {day}: " + (result.stderr or result.stdout)[:400])
        print(f"  {day}: cleared and loaded {len(day_rows)} rows")

    # Verified rather than assumed. A load that reports success while writing a duplicate day is
    # exactly what happened before, so the run ends by reading the table back and refusing to claim
    # success if any day holds more rows than were just written for it.
    check = subprocess.run(
        ["bq", "query", f"--project_id={PROJECT}", "--location=US", "--nouse_legacy_sql",
         "--format=csv", "--quiet",
         f"SELECT CAST(usage_date AS STRING), COUNT(*) FROM `{PROJECT}.{DATASET}.{TABLE}` "
         f"WHERE usage_date IN ({','.join(chr(39) + d + chr(39) for d in by_day)}) GROUP BY 1"],
        capture_output=True, text=True, shell=(os.name == "nt"))
    if check.returncode == 0:
        for line in check.stdout.strip().split("\n")[1:]:
            if not line.strip():
                continue
            day, count = line.split(",")
            expected = len(by_day.get(day, []))
            state = "ok" if int(count) == expected else f"MISMATCH expected {expected}"
            print(f"  verify {day}: {count} rows in table ({state})")
            if int(count) != expected:
                raise SystemExit(f"post-load verification failed for {day}")

    print(f"{len(rows)} rows across {len(by_day)} day(s) in {PROJECT}:{DATASET}.{TABLE}")


if __name__ == "__main__":
    main()
