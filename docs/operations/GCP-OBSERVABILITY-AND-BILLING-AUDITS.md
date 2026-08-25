# GCP observability, inventory, and billing audits

These audits are read-only. They do not apply Terraform, enable APIs, alter IAM, trigger jobs, or mutate
Cloud resources. Store generated output under `artifacts/`; do not manually copy access tokens or full
resource payloads into documentation.

## Monitoring dashboards, alerts, and referenced resources

```powershell
python scripts/audit-gcp-observability.py `
  --project custoking-prod `
  --region asia-south2 `
  --output-json artifacts/gcp-observability-prod.json `
  --output-markdown artifacts/gcp-observability-prod.md
```

The audit inventories dashboards, enabled alert policies, Cloud Run services, Cloud SQL instances,
Pub/Sub subscriptions, and buckets. It extracts every native Monitoring filter, queries filters in
parallel over a seven-day window, and distinguishes three conditions:

- a malformed/unauthorized Monitoring query (failure);
- an exact resource reference that does not exist (failure); and
- a valid filter with no recent time series (reported, but not failed by default because healthy error
  counters can legitimately be absent).

Use `--fail-on-no-data` only for a continuous-telemetry certification window. The audit catches stale
exact resource filters such as the pre-migration photo bucket even when an empty chart looks healthy.

## Billing export evidence grade and freshness

```powershell
pwsh -File scripts/report-billing-export-health.ps1 `
  -ProjectId custoking-prod `
  -ScopeProject custoking-prod `
  -OutputJson artifacts/billing-export-health-prod.json `
  -OutputMarkdown artifacts/billing-export-health-prod.md
```

The report uses an explicit evidence grade:

| Grade | Meaning |
| ---: | --- |
| 0 | `ESTIMATED_ONLY`: pricing or modeled run-rate only; never invoice-grade |
| 1 | `INVOICE_GRADE_STANDARD`: standard usage-cost export exists |
| 2 | `INVOICE_GRADE_DETAILED`: resource-level detailed usage export exists |

For grades 1 and 2 it reports both delivery lag (`export_time`) and newest billed-usage age
(`usage_end_time`), scoped row count, gross/net month-to-date values, and currency. A pricing export by
itself remains grade 0 because it contains rates, not this project's billed usage.

Enabling standard or detailed Cloud Billing export requires billing-account authority outside this
repository. A first-time export to a US/EU multi-region dataset backfills from the start of the previous
month and can take up to five days to catch up; a supported regional dataset starts at enablement. Moving
or re-enabling an export does not automatically restore data from the previous location or disabled gap.

## Cloud Asset Inventory drift

```powershell
python scripts/export-gcp-asset-drift.py `
  --project custoking-prod `
  --baseline artifacts/baselines/gcp-assets-prod.json `
  --output-json artifacts/gcp-assets-prod.json `
  --output-markdown artifacts/gcp-assets-prod.md
```

The script creates a canonical asset snapshot and compares asset additions, removals, and selected field
changes with an optional baseline. It deliberately refuses to enable `cloudasset.googleapis.com` or grant
`cloudasset.assets.searchAllResources`; those are separately approved owner/IAM actions. An initial run
without `--baseline` establishes the candidate baseline for review.

## Repository verification

```powershell
node --test tools/live-dashboard/*.test.mjs
python -m unittest scripts.tests.test_audit_gcp_observability scripts.tests.test_export_gcp_asset_drift
pwsh -File scripts/tests/report-billing-export-health-test.ps1
& 'C:\Program Files\Git\bin\bash.exe' scripts/tests/cost-metric-exporter-test.sh
terraform -chdir=deploy/gcp/observability fmt -check
terraform -chdir=deploy/gcp/observability validate
```
