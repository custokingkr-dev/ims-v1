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

# Three separate concerns, because they are genuinely three different projects in the general case.
#
# A billing account exports to exactly ONE dataset, so environments sharing an account cannot each own
# their own export -- custoking-dev and custoking-prod share account 014C0A, and the export lands in
# custoking-prod. Dev therefore READS from prod's dataset, FILTERS to its own project, and PUBLISHES its
# metric into its own project so its dashboard is self-contained.
BQ_PROJECT="${COST_METRIC_BQ_PROJECT:-$PROJECT}"           # where the billing export dataset lives
PUBLISH_PROJECT="${COST_METRIC_PUBLISH_PROJECT:-$PROJECT}" # where the custom metric is written

# Which project's spend to report. Defaults to the project publishing the metric, because a dashboard in
# custoking-prod must show what custoking-prod costs -- not whatever else happens to share the billing
# export. Without this filter the export is summed across every project it covers, and prod's dashboard
# reported the old project's INR 22,516 as if it were its own.
#
# Set to "*" for a cumulative view across every project in the export; the metric is then labelled per
# project so one series exists for each.
SCOPE="${COST_METRIC_SCOPE_PROJECT:-$PUBLISH_PROJECT}"  # whose spend to report; "*" for all

echo "read=${BQ_PROJECT}.${DATASET}  scope=${SCOPE}  publish=${PUBLISH_PROJECT}"

# Export health is published even before the standard usage table exists. This is important because a
# newly configured billing export can take many hours to create gcp_billing_export_v1_*, while a dataset
# containing only cloud_pricing_export looks deceptively healthy in the BigQuery console. The dashboard
# can use export_available=0 and export_lag_hours=-1 to show that spend is not ready yet instead of
# leaving an old cost value on screen with no explanation.
TOKEN=$(gcloud auth print-access-token)
export TOKEN
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)

for SUFFIX in gross_yesterday net_yesterday gross_month_to_date net_month_to_date; do
  curl -s -o /dev/null -X POST \
    "https://monitoring.googleapis.com/v3/projects/${PUBLISH_PROJECT}/metricDescriptors" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"custom.googleapis.com/custoking/cost/${SUFFIX}\",
         \"metricKind\":\"GAUGE\",\"valueType\":\"DOUBLE\",
         \"description\":\"Billing-export spend (${SUFFIX}) for this project.\",
         \"labels\":[{\"key\":\"billing_account\",\"valueType\":\"STRING\"},
                    {\"key\":\"project_id\",\"valueType\":\"STRING\"},
                    {\"key\":\"currency\",\"valueType\":\"STRING\"}]}" || true
done

for HEALTH_DESCRIPTOR in \
  'export_available|1 when an invoice-grade standard or detailed billing usage export exists; otherwise 0.' \
  'standard_export_available|1 when the standard billing usage export exists; otherwise 0.' \
  'detailed_export_available|1 when the resource-level detailed billing usage export exists; otherwise 0.' \
  'billing_data_grade|0 means estimated-only, 1 standard invoice-grade, 2 detailed invoice-grade.' \
  'export_lag_hours|Hours since the newest selected billing export delivery; -1 when no matching row exists.' \
  'usage_lag_hours|Hours since usage_end_time in the newest selected billing row; -1 when no matching row exists.'; do
  SUFFIX="${HEALTH_DESCRIPTOR%%|*}"
  DESCRIPTION="${HEALTH_DESCRIPTOR#*|}"
  curl -s -o /dev/null -X POST \
    "https://monitoring.googleapis.com/v3/projects/${PUBLISH_PROJECT}/metricDescriptors" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"type\":\"custom.googleapis.com/custoking/cost/${SUFFIX}\",
         \"metricKind\":\"GAUGE\",\"valueType\":\"DOUBLE\",
         \"description\":\"${DESCRIPTION}\",
         \"labels\":[{\"key\":\"project_id\",\"valueType\":\"STRING\"},
                    {\"key\":\"read_project\",\"valueType\":\"STRING\"},
                    {\"key\":\"dataset\",\"valueType\":\"STRING\"}]}" || true
done

publish_export_health() {
  local available="$1"
  local standard_available="$2"
  local detailed_available="$3"
  local data_grade="$4"
  local lag_hours="$5"
  local usage_lag_hours="$6"
  local payload response http_code body

  payload=$(python3 - "${PUBLISH_PROJECT}" "${NOW}" "${SCOPE}" "${BQ_PROJECT}" "${DATASET}" \
    "${available}" "${standard_available}" "${detailed_available}" "${data_grade}" \
    "${lag_hours}" "${usage_lag_hours}" <<'PY'
import json
import sys

project, now, scope, read_project, dataset, available, standard_available, detailed_available, data_grade, lag_hours, usage_lag_hours = sys.argv[1:]
labels = {
    "project_id": scope,
    "read_project": read_project,
    "dataset": dataset,
}

def series(suffix, value):
    return {
        "metric": {
            "type": "custom.googleapis.com/custoking/cost/" + suffix,
            "labels": labels,
        },
        "resource": {"type": "global", "labels": {"project_id": project}},
        "points": [{"interval": {"endTime": now}, "value": {"doubleValue": float(value)}}],
    }

print(json.dumps({"timeSeries": [
    series("export_available", available),
    series("standard_export_available", standard_available),
    series("detailed_export_available", detailed_available),
    series("billing_data_grade", data_grade),
    series("export_lag_hours", lag_hours),
    series("usage_lag_hours", usage_lag_hours),
]}))
PY
  )

  response=$(curl -sS -w $'\n%{http_code}' -X POST \
    "https://monitoring.googleapis.com/v3/projects/${PUBLISH_PROJECT}/timeSeries" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "${payload}")
  http_code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [ "${http_code}" != "200" ] && [ "${http_code}" != "204" ]; then
    echo "ERROR: monitoring health write failed (${http_code}): ${body:0:400}" >&2
    return 1
  fi
  echo "published export health: available=${available} grade=${data_grade} standard=${standard_available} detailed=${detailed_available} export_lag_hours=${lag_hours} usage_lag_hours=${usage_lag_hours}"
}

# Query INFORMATION_SCHEMA instead of probing the wildcard directly. BigQuery treats a wildcard with no
# matching table as a hard query error, which previously terminated the job before it could explain that
# only the pricing export existed. This inventory query remains valid as soon as the dataset exists.
read -r -d '' INVENTORY_QUERY <<SQL || true
SELECT
  COUNTIF(STARTS_WITH(table_name, 'gcp_billing_export_v1_')) AS standard_table_count,
  COUNTIF(STARTS_WITH(table_name, 'gcp_billing_export_resource_v1_')) AS detailed_table_count,
  COUNTIF(STARTS_WITH(table_name, 'cloud_pricing_export')) AS pricing_table_count
FROM \`${BQ_PROJECT}.${DATASET}.INFORMATION_SCHEMA.TABLES\`
SQL

INVENTORY_ERROR=$(mktemp)
trap 'rm -f "${INVENTORY_ERROR}"' EXIT
if ! INVENTORY=$(bq query --project_id="${PUBLISH_PROJECT}" --nouse_legacy_sql --format=json --quiet \
  "${INVENTORY_QUERY}" 2>"${INVENTORY_ERROR}"); then
  echo "ERROR: unable to inspect billing export dataset ${BQ_PROJECT}.${DATASET}." >&2
  sed 's/^/       /' "${INVENTORY_ERROR}" >&2
  publish_export_health 0 0 0 0 -1 -1 || true
  exit 1
fi

STANDARD_TABLE_COUNT=$(printf '%s' "${INVENTORY}" | python3 -c \
  'import json,sys; rows=json.load(sys.stdin); print(int(rows[0]["standard_table_count"]) if rows else 0)')
PRICING_TABLE_COUNT=$(printf '%s' "${INVENTORY}" | python3 -c \
  'import json,sys; rows=json.load(sys.stdin); print(int(rows[0]["pricing_table_count"]) if rows else 0)')
DETAILED_TABLE_COUNT=$(printf '%s' "${INVENTORY}" | python3 -c \
  'import json,sys; rows=json.load(sys.stdin); print(int(rows[0]["detailed_table_count"]) if rows else 0)')

if [ "${STANDARD_TABLE_COUNT}" -eq 0 ] && [ "${DETAILED_TABLE_COUNT}" -eq 0 ]; then
  echo "WARNING: ${BQ_PROJECT}.${DATASET} has no standard gcp_billing_export_v1_* or" >&2
  echo "         detailed gcp_billing_export_resource_v1_* usage table" >&2
  echo "         (pricing tables found: ${PRICING_TABLE_COUNT}). Enable Standard usage cost export for the" >&2
  echo "         billing account or wait for its first delivery; pricing export alone contains rates, not" >&2
  echo "         project usage. The standard wildcard will be discovered automatically when it appears." >&2
  publish_export_health 0 0 0 0 -1 -1
  exit 0
fi

if [ "${DETAILED_TABLE_COUNT}" -gt 0 ]; then
  USAGE_TABLE_PREFIX="gcp_billing_export_resource_v1_"
  BILLING_DATA_GRADE=2
else
  USAGE_TABLE_PREFIX="gcp_billing_export_v1_"
  BILLING_DATA_GRADE=1
fi


# Gross and net are both emitted on purpose. Net alone would have read as ~zero for this project's whole
# history because free-trial credit covered it, hiding the real consumption that becomes payable the
# moment that credit ends.
read -r -d '' QUERY <<SQL || true
SELECT
  _TABLE_SUFFIX AS billing_account,
  IFNULL(project.id, "(unattributed)") AS project_id,
  currency,
  ROUND(SUM(IF(DATE(usage_start_time) = CURRENT_DATE() - 1, cost, 0)), 4) AS gross_yesterday,
  ROUND(SUM(IF(DATE(usage_start_time) = CURRENT_DATE() - 1,
    cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0), 0)), 4) AS net_yesterday,
  ROUND(SUM(IF(DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH), cost, 0)), 4) AS gross_mtd,
  ROUND(SUM(IF(DATE(usage_start_time) >= DATE_TRUNC(CURRENT_DATE(), MONTH),
    cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0), 0)), 4) AS net_mtd,
  ROUND(TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(export_time), MINUTE) / 60.0, 2) AS export_lag_hours,
  ROUND(TIMESTAMP_DIFF(CURRENT_TIMESTAMP(), MAX(usage_end_time), MINUTE) / 60.0, 2) AS usage_lag_hours
FROM \`${BQ_PROJECT}.${DATASET}.${USAGE_TABLE_PREFIX}*\`
WHERE DATE(usage_start_time) >= DATE_SUB(CURRENT_DATE(), INTERVAL 62 DAY)
  AND ("${SCOPE}" = "*" OR project.id = "${SCOPE}")
GROUP BY 1, 2, 3
SQL

# The query job runs in the PUBLISHING project, not the one holding the data. The table is fully
# qualified either way, so this only decides which project is billed for the query and which needs
# bigquery.jobUser -- and that should be the project whose service account is running, otherwise every
# environment needs job-running rights on the project that happens to own the export.
QUERY_ERROR=$(mktemp)
trap 'rm -f "${INVENTORY_ERROR}" "${QUERY_ERROR}"' EXIT
if ! ROWS=$(bq query --project_id="${PUBLISH_PROJECT}" --nouse_legacy_sql --format=json --quiet \
  "${QUERY}" 2>"${QUERY_ERROR}"); then
  echo "ERROR: standard billing export query failed for ${BQ_PROJECT}.${DATASET}." >&2
  sed 's/^/       /' "${QUERY_ERROR}" >&2
  publish_export_health 0 "$([ "${STANDARD_TABLE_COUNT}" -gt 0 ] && echo 1 || echo 0)" \
    "$([ "${DETAILED_TABLE_COUNT}" -gt 0 ] && echo 1 || echo 0)" "${BILLING_DATA_GRADE}" -1 -1 || true
  exit 1
fi

if [ -z "${ROWS}" ] || [ "${ROWS}" = "[]" ]; then
  # An empty result is a legitimate state, not a failure: a newly enabled export writes nothing until its
  # first table appears, which can take a day. Exiting non-zero here would produce a job that alerts every
  # hour about a condition nobody can act on.
  #
  # But "legitimate" is not the same as "fine", and the difference is diagnosable. This job ran hourly and
  # reported success for 19 hours while publishing nothing, because the scope filter matched zero rows and
  # both cases took this same silent branch. The cost panels sat on stale data and the exit code said 0.
  # So before exiting, establish WHICH empty this is -- the export has produced nothing at all, or it has
  # produced data that this scope excludes. Those need completely different fixes.
  DIAG=$(bq query --project_id="${PUBLISH_PROJECT}" --nouse_legacy_sql --format=csv --quiet "
    SELECT _TABLE_SUFFIX AS billing_account,
           IFNULL(project.id, '(unattributed)') AS project_id,
           COUNT(*) AS rows_62d,
           CAST(MAX(DATE(usage_start_time)) AS STRING) AS newest_usage
    FROM \`${BQ_PROJECT}.${DATASET}.${USAGE_TABLE_PREFIX}*\`
    WHERE DATE(usage_start_time) >= DATE_SUB(CURRENT_DATE(), INTERVAL 62 DAY)
    GROUP BY 1, 2 ORDER BY 3 DESC" 2>/dev/null || echo "")

  # Command substitution removes trailing newlines, so counting newlines misclassified a CSV header plus
  # one data row as "header only". Inspect the content after the header instead.
  if [ -z "${DIAG}" ] || ! printf '%s\n' "${DIAG}" | sed '1d' | grep -q '[^[:space:]]'; then
    echo "WARNING: the billing export dataset ${BQ_PROJECT}.${DATASET} contains NO rows for ANY project" >&2
    echo "         in the last 62 days. This is not 'no data yet for us' -- the export itself is producing" >&2
    echo "         nothing. Check that the billing account has an active BigQuery export configured, and" >&2
    echo "         note that a newly enabled export does not backfill." >&2
  else
    echo "WARNING: the export HAS data, but none of it matches scope='${SCOPE}'. Spend for this project is" >&2
    echo "         not being published and the dashboard will show stale figures. What the export holds:" >&2
    printf '%s
' "${DIAG}" | sed 's/^/           /' >&2
  fi
  publish_export_health 0 "$([ "${STANDARD_TABLE_COUNT}" -gt 0 ] && echo 1 || echo 0)" \
    "$([ "${DETAILED_TABLE_COUNT}" -gt 0 ] && echo 1 || echo 0)" "${BILLING_DATA_GRADE}" -1 -1
  exit 0
fi

# Use the freshest matching row across billing-account/currency groups. The per-row lag is derived from
# export_time (delivery time), not usage_start_time, so it measures the BigQuery pipeline rather than the
# age of the workload being billed.
EXPORT_LAG_HOURS=$(printf '%s' "${ROWS}" | python3 -c \
  'import json,sys; rows=json.load(sys.stdin); lags=[float(r["export_lag_hours"]) for r in rows if r.get("export_lag_hours") is not None]; print(min(lags) if lags else -1)')
USAGE_LAG_HOURS=$(printf '%s' "${ROWS}" | python3 -c \
  'import json,sys; rows=json.load(sys.stdin); lags=[float(r["usage_lag_hours"]) for r in rows if r.get("usage_lag_hours") is not None]; print(min(lags) if lags else -1)')
publish_export_health 1 "$([ "${STANDARD_TABLE_COUNT}" -gt 0 ] && echo 1 || echo 0)" \
  "$([ "${DETAILED_TABLE_COUNT}" -gt 0 ] && echo 1 || echo 0)" "${BILLING_DATA_GRADE}" \
  "${EXPORT_LAG_HOURS}" "${USAGE_LAG_HOURS}"

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
    # project_id is a label, not a filter baked into the metric name, so the same metric serves both a
    # single-project dashboard and a cumulative one across the organisation.
    labels = {
        "billing_account": row["billing_account"],
        "project_id": row["project_id"],
        "currency": row["currency"],
    }
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
    print("  " + row["project_id"] + " (" + row["billing_account"] + ") " + row["currency"]
          + ": yesterday gross=" + str(row["gross_yesterday"]) + " net=" + str(row["net_yesterday"])
          + ", mtd gross=" + str(row["gross_mtd"]) + " net=" + str(row["net_mtd"]))
PUBLISHER_EOF

printf '%s' "${ROWS}" | python3 "${PUBLISHER}" "${PUBLISH_PROJECT}" "${NOW}"

# Deployed as Cloud Run job `ims-cost-metric-prod` in custoking-prod/asia-south2, running as
# cost-metric-exporter@custoking-prod (bigquery.jobUser, bigquery.dataViewer, monitoring.metricWriter),
# triggered every three hours by Cloud Scheduler job `cost-metric-export-prod`. The schedule lives in
# asia-south1 because Cloud Scheduler is not offered in asia-south2; the trigger's region has no bearing
# on where the work runs. The script is delivered to the job base64-encoded in SCRIPT_B64 so no container
# image has to be built or maintained for it.
