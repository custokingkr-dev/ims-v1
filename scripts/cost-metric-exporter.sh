#!/usr/bin/env bash
# Publishes Cloud Billing spend into Cloud Monitoring as custom metrics.
#
# Cloud Monitoring cannot query BigQuery, and billing data only exists in BigQuery. Without this bridge
# the Live Operations dashboard can show cost DRIVERS but never actual money. This closes that gap at the
# only cadence the underlying data supports.
#
# Do not mistake these metrics for live spend. Billing export lands in BigQuery hours after the usage it
# describes, so the newest figure here is always several hours stale no matter how often this runs. The
# metric answers "what did we spend", not "what are we spending"; the dashboard's instance-time and
# egress charts answer the latter.
#
# The query deliberately scans `gcp_billing_export_v1_*` rather than a single table. Each billing account
# gets its own table, and the migrated projects moved to a different account from the source project, so
# a fixed table name would silently report only one of them. The wildcard also means a newly enabled
# export starts being reported the moment its first table appears, with no change here.
set -euo pipefail

PROJECT="${COST_METRIC_PROJECT:?COST_METRIC_PROJECT is required}"
DATASET="${COST_METRIC_DATASET:-billing_export}"

echo "querying ${PROJECT}.${DATASET}.gcp_billing_export_v1_*"

# Gross and net are both emitted on purpose. Net alone would have read as ~zero for this project's whole
# history because free-trial credit covered it, hiding the real consumption that becomes payable the
# moment that credit ends.
read -r -d '' QUERY <<SQL || true
SELECT
  _TABLE_SUFFIX AS billing_account,
  currency,
  ROUND(SUM(IF(DATE(usage_start_time) = CURRENT_DATE() - 1, cost, 0)), 4) AS gross_yesterday,
  ROUND(SUM(IF(DATE(usage_start_time) = CURRENT_DATE() - 1,
    cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0), 0)), 4) AS net_yesterday,
  ROUND(SUM(IF(DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH), cost, 0)), 4) AS gross_mtd,
  ROUND(SUM(IF(DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH),
    cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0), 0)), 4) AS net_mtd
FROM \`${PROJECT}.${DATASET}.gcp_billing_export_v1_*\`
WHERE DATE(usage_start_time) >= DATE_SUB(CURRENT_DATE(), INTERVAL 62 DAY)
GROUP BY 1, 2
SQL

ROWS=$(bq query --project_id="${PROJECT}" --nouse_legacy_sql --format=json --quiet "${QUERY}")

if [ -z "${ROWS}" ] || [ "${ROWS}" = "[]" ]; then
  # An empty result is a legitimate state, not a failure: a newly enabled export writes nothing until its
  # first table appears, which can take a day. Exiting non-zero here would produce a job that alerts every
  # hour about a condition nobody can act on.
  echo "no billing rows in range yet; nothing to publish"
  exit 0
fi

# Exported because the publisher reads it from the environment. A bare shell variable is not visible to
# the child process, and the write would fail as unauthenticated rather than as a missing variable.
TOKEN=$(gcloud auth print-access-token)
export TOKEN
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# Written to a file rather than piped as a heredoc: the row JSON has to arrive on stdin, and a command
# cannot take both its script and its input that way -- the second redirect silently wins and the script
# body is never read at all.
PUBLISHER="$(mktemp)"
trap 'rm -f "${PUBLISHER}"' EXIT

cat > "${PUBLISHER}" <<'PUBLISHER_EOF'
import json, os, subprocess, sys

project, now = sys.argv[1], sys.argv[2]
rows = json.load(sys.stdin)

METRICS = [
    ("cost/gross_yesterday", "gross_yesterday"),
    ("cost/net_yesterday", "net_yesterday"),
    ("cost/gross_month_to_date", "gross_mtd"),
    ("cost/net_month_to_date", "net_mtd"),
]

series = []
for row in rows:
    labels = {"billing_account": row["billing_account"], "currency": row["currency"]}
    for suffix, column in METRICS:
        series.append({
            "metric": {"type": "custom.googleapis.com/custoking/" + suffix, "labels": labels},
            # `global` rather than a per-service resource: spend is an account-level fact and does not
            # belong to any single Cloud Run revision.
            "resource": {"type": "global", "labels": {"project_id": project}},
            "points": [{
                "interval": {"endTime": now},
                "value": {"doubleValue": float(row[column] or 0)},
            }],
        })

# The API rejects payloads over 200 series. Silently accepting a truncated write would understate spend
# with no signal that anything was dropped.
for start in range(0, len(series), 200):
    chunk = {"timeSeries": series[start:start + 200]}
    proc = subprocess.run(
        ["curl", "-s", "-w", "%{http_code}", "-X", "POST",
         "https://monitoring.googleapis.com/v3/projects/" + project + "/timeSeries",
         "-H", "Authorization: Bearer " + os.environ["TOKEN"],
         "-H", "Content-Type: application/json",
         "-d", json.dumps(chunk)],
        capture_output=True, text=True)
    body = proc.stdout
    if body[-3:] not in ("200", "204"):
        print("monitoring write failed (" + body[-3:] + "): " + body[:400], file=sys.stderr)
        sys.exit(1)
    print("published " + str(len(chunk["timeSeries"])) + " series")

for row in rows:
    print("  " + row["billing_account"] + " " + row["currency"]
          + ": yesterday gross=" + str(row["gross_yesterday"]) + " net=" + str(row["net_yesterday"])
          + ", mtd gross=" + str(row["gross_mtd"]) + " net=" + str(row["net_mtd"]))
PUBLISHER_EOF

printf '%s' "${ROWS}" | python3 "${PUBLISHER}" "${PROJECT}" "${NOW}"

# Deployed as Cloud Run job `ims-cost-metric-prod` in custoking-prod/asia-south2, running as
# cost-metric-exporter@custoking-prod (bigquery.jobUser, bigquery.dataViewer, monitoring.metricWriter),
# triggered every three hours by Cloud Scheduler job `cost-metric-export-prod`. The schedule lives in
# asia-south1 because Cloud Scheduler is not offered in asia-south2; the trigger's region has no bearing
# on where the work runs. The script is delivered to the job base64-encoded in SCRIPT_B64 so no container
# image has to be built or maintained for it.
