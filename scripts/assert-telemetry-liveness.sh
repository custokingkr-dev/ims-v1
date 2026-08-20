#!/usr/bin/env bash
# Asserts that every log-based metric this project depends on currently has data.
#
# WHY THIS EXISTS, AND WHY IT IS NOT AN ALERT POLICY
#
# The obvious implementation is a Cloud Monitoring metric-absence condition. It does not work for the
# failure this guards against. Google's documentation is explicit: a metric-absence condition "requires
# at least one successful measurement -- one that retrieves data -- within the maximum period of time
# after the policy was installed or modified."
#
# The bug this exists to catch is a metric that has NEVER emitted a point. On 2026-08-19 five log-based
# metrics were found to have collected zero time series across twenty days, because logback lacked an
# <arguments/> provider and StructuredArguments.kv() rendered into message text rather than a JSON field.
# The metric existed, the alert policy existed, the dashboard rendered, and nothing anywhere errored. A
# metric-absence policy on those metrics could not have fired, because the required first successful
# measurement never happened.
#
# So this inverts the check. Rather than waiting for data to stop, it treats the list of metrics as a
# declaration of what OUGHT to exist and validates that declaration against the live backend -- the same
# inversion Cloudflare's `pint` applies to Prometheus rules, where the alert rule is the schema and
# something must check the schema against reality.
#
# Deliberately a scheduled assertion rather than a dashboard panel: an empty chart is indistinguishable
# from a quiet system, which is precisely how the original bug survived for months.
set -euo pipefail

PROJECT="${TELEMETRY_PROJECT:?TELEMETRY_PROJECT is required}"
ENVIRONMENT="${TELEMETRY_ENV:-prod}"
# Long enough that a genuinely idle metric is not reported, short enough to catch a break within a day.
WINDOW_HOURS="${TELEMETRY_WINDOW_HOURS:-24}"

# Metrics that MUST have data. Gateway traffic metrics are excluded on purpose: with ~600 requests a day
# a genuinely quiet window is normal for those, and a check that cries wolf gets ignored, which would
# reproduce the failure it is meant to prevent.
#
# These five are safe to assert because their emitters are schedulers, not user traffic: they publish on a
# fixed interval whenever the service is alive, so silence means the pipeline is broken rather than that
# nobody logged in.
REQUIRED_METRICS=(
  "custoking/${ENVIRONMENT}/outbox_pending_count"
  "custoking/${ENVIRONMENT}/outbox_dead_letter_count"
  "custoking/${ENVIRONMENT}/outbox_oldest_pending_age_seconds"
  "custoking/${ENVIRONMENT}/notification_inbox_backlog_count"
  "custoking/${ENVIRONMENT}/session_active_users"
)

# Resolve a JSON interpreter BEFORE querying anything, and prove it works.
#
# This block exists because of a real false alarm. On Windows, `python3` resolves to a Microsoft Store
# stub that prints an installation notice and exits non-zero. Every query therefore failed, and the
# script reported five broken metrics against a backend that was serving data perfectly well.
#
# That failure is the same shape as the bug this script hunts: a harness that is broken reads exactly
# like a system that is broken. So the interpreter is probed once, up front, and a missing one exits
# with a DISTINCT status and message. A check that cries wolf gets ignored, and an ignored check is
# worth less than no check at all.
PY_BIN=""
for candidate in python3 python py; do
  command -v "${candidate}" >/dev/null 2>&1 || continue
  if printf '{"timeSeries":[1]}' | "${candidate}" -c "import sys,json; sys.exit(0 if len(json.load(sys.stdin)['timeSeries'])==1 else 1)" >/dev/null 2>&1; then
    PY_BIN="${candidate}"
    break
  fi
done

if [ -z "${PY_BIN}" ]; then
  echo "harness error: no working python interpreter found (tried python3, python, py)." >&2
  echo "This is a problem with THIS SCRIPT'S environment, not with telemetry. Metrics were never queried." >&2
  exit 2
fi

TOKEN=$(gcloud auth print-access-token)
END=$(date -u +%Y-%m-%dT%H:%M:%SZ)
START=$(date -u -d "${WINDOW_HOURS} hours ago" +%Y-%m-%dT%H:%M:%SZ)

echo "asserting telemetry liveness in ${PROJECT} over the last ${WINDOW_HOURS}h"
echo

failures=0
for metric in "${REQUIRED_METRICS[@]}"; do
  count=$(curl -s -G \
    -H "Authorization: Bearer ${TOKEN}" \
    --data-urlencode "filter=metric.type=\"logging.googleapis.com/user/${metric}\"" \
    --data-urlencode "interval.startTime=${START}" \
    --data-urlencode "interval.endTime=${END}" \
    "https://monitoring.googleapis.com/v3/projects/${PROJECT}/timeSeries" \
    | "${PY_BIN}" -c "import sys,json; print(len(json.load(sys.stdin).get('timeSeries',[])))" 2>/dev/null || echo "ERR")

  if [ "${count}" = "ERR" ]; then
    printf '  %-56s QUERY FAILED\n' "${metric}"
    failures=$((failures + 1))
  elif [ "${count}" = "0" ]; then
    printf '  %-56s NO DATA\n' "${metric}"
    failures=$((failures + 1))
  else
    printf '  %-56s ok (%s series)\n' "${metric}" "${count}"
  fi
done

echo
if [ "${failures}" -gt 0 ]; then
  cat >&2 <<MSG
${failures} metric(s) have no data in the last ${WINDOW_HOURS}h.

This does not mean the system is idle. These metrics are emitted by schedulers on a fixed interval, so
an empty result means the telemetry pipeline is broken -- most likely the structured-logging provider,
the log-metric filter, or the value extractor. Any alert policy built on an empty metric is silently
inert: it cannot fire, and its dashboard panel renders as a healthy flat line.

Check, in order:
  1. Is the emitting service running and logging at all?
  2. Does the log entry carry the value as a JSON FIELD, not inside the message text?
     gcloud logging read 'jsonPayload.health:*' --project=${PROJECT} --limit=1 --format=json
  3. Does the metric's filter still match the log shape it was written against?
MSG
  exit 1
fi

echo "all ${#REQUIRED_METRICS[@]} required metrics are reporting"
