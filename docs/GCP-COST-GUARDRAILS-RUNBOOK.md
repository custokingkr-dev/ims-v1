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

## Free-trial expiry -- READ FIRST

Billing account `014C0A-C6B9AF-5FABC0`, which both `custoking-prod` and `custoking-dev` bill to, is a
**Free Trial** account. The credit expires on **2026-11-16** (confirmed by the account owner and by
Google billing support). Google's documented behaviour at trial end without an upgrade: every resource
on the account is **stopped**, billing is disabled, and data is marked for deletion after a **30-day
grace period**. For a product serving live schools that is a shutdown date, not a cost event.

The trial also ends **early** if the credit is exhausted first. Measured 2026-09-11: INR ~23,300 of
credit remained, combined burn was INR 262/day gross (~INR 240/day of credit draw once the monthly
Cloud Run free tier is used up, which happens around the 5th), and 67 days remained -- a projected
draw of INR ~16,500 against INR ~23,300. The margin is about one month of burn. A three-week runaway
consumes it.

**Upgrading has no downside.** Remaining credit stays usable until its original expiry. It needs the
account owner in the Console: Billing -> account `014C0A-C6B9AF-5FABC0` -> Activate / Upgrade. No API,
no gcloud, no automation can do it.

What reminds us, in order of reliability:

1. **A human calendar.** The account owner and the operator each hold three calendar events with
   notifications: **2026-10-16** (one month out: upgrade now), **2026-11-02** (two weeks: confirm the
   upgrade is done), **2026-11-09** (one week: if not upgraded, nothing else matters this week). This
   is the primary mechanism. Everything below is a backstop, because every automated path here
   depends on something that can silently stop -- a workflow on a branch, a channel in a project, an
   export Google can break server-side.
2. **The `trial-expiry-countdown` job** in `.github/workflows/gcp-cost-controls.yml`. Twice a day it
   prints the countdown to the run summary; from 45 days out it keeps one open GitHub issue labelled
   `trial-expiry` assigned to the repository owner; from 14 days it comments on that issue every run;
   from 7 days it fails the workflow. Scheduled workflows run from `main`, so it is live only once
   merged there.
3. **The `custoking-trial-credit-runway-to-2026-11-16` budget** (Terraform, prod root). Account-wide,
   custom period from the day the balance was read to the expiry date, amount = credit remaining,
   thresholds at 50 / 75 / 90 / 100% of the runway. This is the only signal for the credit-exhaustion
   path. Re-base `trial_credit_remaining_inr` and `trial_runway_start_date` together whenever the
   balance is re-read from the Console.

After the upgrade: close the issue, set `manage_trial_runway_budget = false`, remove the countdown job,
and record the date here.

## Budget Setup

Budgets are Terraform-managed in `deploy/gcp/observability/budget.tf`; do not create or edit them in
the Console. Two per-project monthly budgets plus the account-wide runway budget above:

| Budget | Scope | Amount | Thresholds |
| --- | --- | --- | --- |
| `custoking-prod-monthly` | project custoking-prod, calendar month, GROSS | INR 5,000 | 90%, 100%, 150% current; 100% forecast |
| `custoking-dev-monthly` | project custoking-dev, calendar month, GROSS | INR 2,000 | same |
| `custoking-trial-credit-runway-to-2026-11-16` | whole account, 2026-09-11..2026-11-16, net of every credit except the promotion | credit remaining | 50%, 75%, 90%, 100% current |

**They must measure GROSS** (`credit_types_treatment = "EXCLUDE_ALL_CREDITS"`). This runbook has said
so since it was written; the Terraform did not do it until 2026-09-11, and for the whole of August
both budgets measured a net of exactly zero and could not fire. Verify, do not assume:

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" -H "x-goog-user-project: custoking-prod"   https://billingbudgets.googleapis.com/v1/billingAccounts/014C0A-C6B9AF-5FABC0/budgets   | grep -E '"displayName"|"creditTypesTreatment"|"units"'
```

Every `creditTypesTreatment` must read `EXCLUDE_ALL_CREDITS` or `INCLUDE_SPECIFIED_CREDITS`. Never
`INCLUDE_ALL_CREDITS`.

Sizing: the prod budget puts the measured run rate (INR 4,209/month, 2026-08-27..2026-09-09) at ~84%,
so a normal month fires nothing and the 90% rule means ~7% real drift. Dev's honest floor with uptime
probes off is ~INR 1,100/month; INR 2,000 leaves room for releases and still reports the cold-start
loop (INR ~3,750/month) at 188%. Raise a budget when real load arrives, not when it alerts.

Budget alerts do not stop spend. They are escalation triggers. They notify the operator email channels
plus anything in `budget_notification_channel_ids`, and (unless `budget_notify_default_iam_recipients
= false`) every Billing Account Administrator, which is a second path that survives the Monitoring
channels being deleted.

The faster signals -- one day's gross spend, export staleness, Cloud Run instance time -- are alert
policies in `spend_anomaly_alerts.tf`; see that file and `deploy/gcp/observability/README.md`.

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
FROM `custoking-prod.billing_export.gcp_billing_export_resource_v1_*`
WHERE project.id IN ('custoking-prod', 'custoking-dev')
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

The scheduled workflow runs from the repository default branch and intentionally does not target the
branch-gated `dev` GitHub Environment. It reads only the repository-level
`DEV_GCP_PROJECT_ID`, `DEV_CLOUDSQL_INSTANCE`, `DEV_COST_WORKLOAD_IDENTITY_PROVIDER`, and
`DEV_COST_CONTROLLER_SERVICE_ACCOUNT` controls. There are no legacy fallbacks: an absent dev-only
control fails validation before authentication. The cost-controller service account must be dedicated to this workflow and limited to
Cloud SQL Editor plus Service Usage Consumer. Never point the cost-controller variable at a release or
general deployment identity.

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
