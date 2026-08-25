#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
EXPORTER="${ROOT}/scripts/cost-metric-exporter.sh"
TEST_TMP=$(mktemp -d)
trap 'rm -rf "${TEST_TMP}"' EXIT
if [ -z "${TEST_PYTHON:-}" ]; then
  if command -v python3 >/dev/null 2>&1 && python3 -c 'import json' >/dev/null 2>&1; then
    TEST_PYTHON=$(command -v python3)
  elif command -v python >/dev/null 2>&1 && python -c 'import json' >/dev/null 2>&1; then
    # Git Bash on Windows can expose the Microsoft Store python3 shim even when a real `python`
    # installation is present. Verify the interpreter instead of trusting command discovery.
    TEST_PYTHON=$(command -v python)
  else
    echo "A working Python 3 interpreter is required." >&2
    exit 1
  fi
fi
export TEST_PYTHON

mkdir -p "${TEST_TMP}/bin"

cat > "${TEST_TMP}/bin/gcloud" <<'EOF'
#!/usr/bin/env bash
printf 'test-token\n'
EOF

cat > "${TEST_TMP}/bin/python3" <<'EOF'
#!/usr/bin/env bash
exec "${TEST_PYTHON}" "$@"
EOF

cat > "${TEST_TMP}/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${CURL_LOG}"

write_format=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = "-w" ]; then
    write_format="$2"
    shift 2
  else
    shift
  fi
done

case "${write_format}" in
  *'\\n%{http_code}'*) printf '\n204' ;;
  *'%{http_code}'*) printf '204' ;;
esac
EOF

cat > "${TEST_TMP}/bin/bq" <<'EOF'
#!/usr/bin/env bash
query="${*: -1}"
printf '%s\n' "${query}" >> "${BQ_LOG}"

if [[ "${query}" == *'INFORMATION_SCHEMA.TABLES'* ]]; then
  case "${SCENARIO}" in
    missing) printf '[{"standard_table_count":"0","detailed_table_count":"0","pricing_table_count":"1"}]\n' ;;
    detailed_scope_empty) printf '[{"standard_table_count":"0","detailed_table_count":"1","pricing_table_count":"1"}]\n' ;;
    *) printf '[{"standard_table_count":"1","detailed_table_count":"0","pricing_table_count":"1"}]\n' ;;
  esac
  exit 0
fi

if [[ "${query}" == *'ROUND(TIMESTAMP_DIFF'* ]]; then
  case "${SCENARIO}" in
    query_error)
      printf 'Access Denied: test failure\n' >&2
      exit 1
      ;;
    scope_empty|detailed_scope_empty) printf '[]\n' ;;
  esac
  exit 0
fi

# Empty-scope diagnostic query.
printf 'billing_account,project_id,rows_62d,newest_usage\n014C0A,other-project,100,2026-08-24\n'
EOF

chmod +x "${TEST_TMP}/bin/gcloud" "${TEST_TMP}/bin/python3" \
  "${TEST_TMP}/bin/curl" "${TEST_TMP}/bin/bq"

run_exporter() {
  local scenario="$1"
  local output_file="$2"
  : > "${CURL_LOG}"
  : > "${BQ_LOG}"
  if ! SCENARIO="${scenario}" \
    PATH="${TEST_TMP}/bin:${PATH}" \
    COST_METRIC_PROJECT=report-project \
    COST_METRIC_BQ_PROJECT=billing-project \
    COST_METRIC_PUBLISH_PROJECT=report-project \
    COST_METRIC_SCOPE_PROJECT=report-project \
      bash "${EXPORTER}" >"${output_file}" 2>&1; then
    return 1
  fi
}

assert_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -Fq -- "${expected}" "${file}"; then
    echo "Expected '${expected}' in ${file}:" >&2
    sed 's/^/  /' "${file}" >&2
    exit 1
  fi
}

export CURL_LOG="${TEST_TMP}/curl.log"
export BQ_LOG="${TEST_TMP}/bq.log"

missing_output="${TEST_TMP}/missing.out"
run_exporter missing "${missing_output}"
assert_contains "${missing_output}" 'has no standard gcp_billing_export_v1_*'
assert_contains "${missing_output}" 'detailed gcp_billing_export_resource_v1_* usage table'
assert_contains "${missing_output}" 'published export health: available=0 grade=0 standard=0 detailed=0 export_lag_hours=-1 usage_lag_hours=-1'
assert_contains "${CURL_LOG}" 'custom.googleapis.com/custoking/cost/export_available'
assert_contains "${CURL_LOG}" 'custom.googleapis.com/custoking/cost/billing_data_grade'
assert_contains "${CURL_LOG}" '"doubleValue": 0.0'

empty_output="${TEST_TMP}/empty.out"
run_exporter scope_empty "${empty_output}"
assert_contains "${empty_output}" "none of it matches scope='report-project'"
assert_contains "${empty_output}" 'published export health: available=0 grade=1 standard=1 detailed=0 export_lag_hours=-1 usage_lag_hours=-1'

detailed_output="${TEST_TMP}/detailed.out"
run_exporter detailed_scope_empty "${detailed_output}"
assert_contains "${detailed_output}" 'published export health: available=0 grade=2 standard=0 detailed=1 export_lag_hours=-1 usage_lag_hours=-1'
assert_contains "${BQ_LOG}" 'gcp_billing_export_resource_v1_*'

error_output="${TEST_TMP}/error.out"
if run_exporter query_error "${error_output}"; then
  echo 'Expected a real BigQuery query failure to return non-zero' >&2
  exit 1
fi
assert_contains "${error_output}" 'standard billing export query failed'
assert_contains "${error_output}" 'Access Denied: test failure'

echo 'cost-metric-exporter tests passed'
