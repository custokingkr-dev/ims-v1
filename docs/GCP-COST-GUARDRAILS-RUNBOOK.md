# GCP Cost Guardrails Runbook

Purpose: keep production onboarding cost-controlled without guessing. Use this before each new-school onboarding wave and during the weekly production review.

Primary plan: [GCP-COST-OPTIMIZATION-PLAN-2026-08.md](GCP-COST-OPTIMIZATION-PLAN-2026-08.md)

Latest incident and resource-level attribution:
[GCP-BUDGET-INCIDENT-2026-08-11.md](GCP-BUDGET-INCIDENT-2026-08-11.md).

## Operating Rules

1. Keep production Cloud Run min instances at `0` unless a measured product issue justifies a targeted change.
2. Do not upgrade Cloud SQL from `db-g1-small` without 7-30 days of CPU, memory, connection, and latency evidence.
3. Do not buy committed use discounts until the production baseline is stable for 30-60 days.
4. Treat background outbox latency as an architecture issue first. Prefer a scheduled relay job over warming every Java service.
5. Review spend before onboarding schools, not after the invoice arrives.

## Weekly Review

Run the posture export:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\export-gcp-cost-posture.ps1 `
  -ProjectId custoking `
  -Region asia-south2 `
  -OutputDirectory artifacts\gcp-cost-posture
```

Attach the generated Markdown file to the weekly review notes. The JSON file is for diffing or scripted checks.

Review these sections:

- Cloud Run: min/max instances, memory, CPU, Direct VPC, latest revision.
- Cloud SQL: tier, activation policy, storage, backups, deletion protection.
- Storage: bucket size and lifecycle policy count.
- Artifact Registry: repository size and cleanup policy count.
- Logging: retention days.
- Recommenders: Cloud SQL overprovisioned recommendations.

## Onboarding Gate

Before adding a school to prod, confirm:

- Month-to-date GCP spend is below the budget trajectory.
- Cloud SQL prod is not CPU/memory/connection constrained.
- Cloud Run gateway, identity, and school-core p95 latency is acceptable during school hours.
- Pub/Sub subscriptions have no meaningful oldest-unacked backlog.
- Outbox pending age is acceptable for reporting/dashboard freshness.
- Student photo bucket growth is expected and attributable.
- No one has set `CLOUD_RUN_DOMAIN_MIN_INSTANCES` or `CLOUD_RUN_GATEWAY_MIN_INSTANCES` without a written reason.

## Budget Setup

The live project budget is `Custoking Monthly Guardrail`, INR 5,000/month. It must use
`EXCLUDE_ALL_CREDITS` so temporary promotional credits cannot hide gross consumption.

Maintain at least one project budget:

- Scope: project `custoking`.
- Alert thresholds: current spend 50%, 80%, and 100%; forecasted spend 100%.
- Add a forecasted-spend alert if available.
- Notification target: engineering plus the business owner who approves onboarding spend.

Add a second budget filtered to Cloud SQL if the billing account supports the filter. Cloud SQL is the fixed-cost anchor, so it deserves separate visibility.

Budget alerts do not stop spend. They are escalation triggers. Apply and verify the repository,
bucket, secret-version, and budget controls with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\apply-gcp-cost-controls.ps1
powershell -ExecutionPolicy Bypass -File scripts\apply-gcp-cost-controls.ps1 -Apply
```

The guarded load wrapper queries this budget and the standard billing export before starting k6. It
reserves headroom by requiring current gross spend plus the profile estimate to remain within 80% of
the budget. `-AllowBudgetOverrun` requires explicit spending-owner approval and is recorded in evidence.

## Billing Export

Enable detailed Cloud Billing export to BigQuery. Put it in a billing/admin dataset rather than an application database.

Daily cost by service:

```sql
SELECT
  DATE(usage_start_time) AS day,
  service.description AS service,
  sku.description AS sku,
  SUM(cost) AS cost,
  SUM(IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)) AS credits
FROM `BILLING_DATASET.gcp_billing_export_resource_v1_*`
WHERE project.id = 'custoking'
GROUP BY day, service, sku
ORDER BY day DESC, cost DESC;
```

Month-to-date cost by environment label after labels are present:

```sql
SELECT
  invoice.month,
  project.id,
  (SELECT value FROM UNNEST(labels) WHERE key = 'env') AS env,
  service.description AS service,
  SUM(cost) AS gross_cost
FROM `BILLING_DATASET.gcp_billing_export_resource_v1_*`
WHERE project.id = 'custoking'
GROUP BY invoice.month, project.id, env, service
ORDER BY invoice.month DESC, gross_cost DESC;
```

## Labels

Future Cloud Run deploys now apply:

- `app=custoking-ims`
- `env=dev|prod`
- `component=cloud-run`
- `service=<service>`
- `owner=engineering`
- `cost-center=school-saas`

Add equivalent labels manually or through IaC for:

- Cloud SQL instances
- Storage buckets
- Pub/Sub topics/subscriptions
- Artifact Registry repository

Use labels for cost allocation, not access control.

## Cloud Run Min Instance Change Control

If someone proposes `min-instances=1`, require:

- affected service
- exact school/user workflow affected
- p95/p99 latency evidence
- cold-start frequency evidence
- expected monthly cost impact
- rollback command
- time window if the setting is temporary

Prefer the smallest scope:

1. gateway only
2. gateway plus identity
3. school-core during school hours
4. broader domain warming only as a last resort

## Dev Cost Control

The `Ops / GCP cost controls` workflow starts dev Cloud SQL at 08:00 IST on weekdays and stops it
at 20:00 IST daily. A dev deployment starts the database and waits until it is runnable before
deployment verification. GitHub schedule execution can be delayed by platform load.

Use the guarded helper only for dev:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\set-dev-cloudsql-state.ps1 `
  -State stop -Wait
```

Start it again before dev deploys or smokes:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\set-dev-cloudsql-state.ps1 `
  -State start -Wait
```

Do not apply this to `custoking-db-prod`.

## Source References

- Cloud Run cost optimization: https://docs.cloud.google.com/run/docs/tips/services-cost-optimization
- Cloud Run minimum instances: https://docs.cloud.google.com/run/docs/configuring/min-instances
- Cloud Run billing settings: https://docs.cloud.google.com/run/docs/configuring/billing-settings
- Cloud SQL pricing: https://cloud.google.com/sql/pricing
- Cloud Billing budgets: https://docs.cloud.google.com/billing/docs/how-to/budgets
- Cloud Billing export: https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery
