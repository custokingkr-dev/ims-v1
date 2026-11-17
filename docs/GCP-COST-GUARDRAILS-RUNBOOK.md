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

**The first trial ran out and stopped production.** Billing account `014C0A-C6B9AF-5FABC0`, a Free
Trial account created on 2026-08-18, expired on its stated date, 2026-11-16, at about 18:50 UTC (00:19
IST on the 17th). Google disabled billing on both projects: Cloud SQL went `SUSPENDED` and every public
URL returned 500. **Production was down 00:19-02:36 IST on 2026-11-17 (2 h 17 min).** Both projects were
relinked to a new billing account at 20:32 UTC (02:02 IST); the recovery is written up under *If billing
lapses again* below. No data was lost. The reminders did not prevent it: issue #308 was open, and the
countdown job failed every weekday run in the final week. A reminder is not a decision.

**The account the projects bill to now is ALSO a free trial.** Since 2026-11-16 `custoking-prod` and
`custoking-dev` bill to a second Free Trial account. The Console banner read at 2026-11-15T19:31Z showed
INR 28,569 of credit and 90 days remaining, so it expires on **2027-02-13** (creation date + 90 days,
the same rule the first account followed; owner action 1 below confirms it). 2027-02-13 is a Saturday.
If Google follows the first account's pattern, it stops resources at
about 18:50 UTC that day, about 00:20 IST on Sunday 14 February. That is after the last weekday
countdown run. On 2026-11-17 the owner chose not to upgrade yet.

This repository is public. Never write the new account's ID, or any part of it, into a committed file, an
issue, a PR, a step summary or a log line. Call it "the billing account that custoking-prod and
custoking-dev bill to", and look it up when you need it, either in Console -> Billing -> Account
management or with:

```powershell
gcloud billing projects describe custoking-prod --billing-project=custoking-prod --format="value(billingAccountName)"
```

Google's documented behaviour at trial end without an upgrade: every resource on the account is
**stopped**, billing is disabled, and data is marked for deletion after a **30-day grace period**. On
2026-11-16 that happened as documented, on the stated date. For a product serving live schools the
expiry is a shutdown date, not a cost event.

The trial also ends **early** if the credit is exhausted first, and every project linked to the account
draws on the same credit. None of the following was measured on the new account: nothing can be measured
there until its export exists, so the rates come from the first account's export. The promotion draw was
about INR 192/day over 2026-10-17..2026-11-15 (gross: prod ~INR 132/day, dev ~INR 76/day). Over
2026-11-12..15 it was INR 230-302/day, because the monthly Cloud Run free tier ran out and dev Artifact
Registry egress jumped. INR 28,569 over the 89 days from 2026-11-16 to 2027-02-13 covers up to about INR
320/day. At ~INR 190/day, about 60% is used and the date is what ends the trial. At ~INR 300/day, about
93% is used, which leaves less than a week of margin. A runaway (one always-on instance costs ~INR
240/day) ends the trial early.

**Upgrading has no downside.** Remaining credit stays usable until its original expiry. It needs the
account owner in the Console: Billing -> the account custoking-prod is linked to (see the lookup above)
-> Activate / Upgrade. No API, no gcloud, no automation can do it.

Owner actions still open on 2026-11-17. The ops identity holds only Billing Account User on the new
account, so it cannot do 2 or 3. That role does include `billing.credits.list`, so the operator can
probably read the Credits page and confirm 1 as well:

1. **Confirm the expiry date** in Console -> Billing -> Credits. If it is not 2027-02-13, change
   `TRIAL_EXPIRY_DATE` in `.github/workflows/gcp-cost-controls.yml` and `trial_expiry_date` in
   `deploy/gcp/observability/variables.tf` together. If unsure, use the earlier date.
2. **Enable the BigQuery billing export before 2026-12-31.** See *Billing Export* for the target and
   for why the deadline exists.
3. **Grant the ops identity Billing Account Costs Manager** (`roles/billing.costsManager`) on the account,
   so that the budgets can be re-created (see *Budget Setup*). Alternatively, create them in the Console.
4. **Decide the dev monthly budget amount** before it is re-created (see *Budget Setup*).
5. **Decide when to upgrade, and record that date here.**

What reminds us, in order of reliability:

1. **A human calendar.** The account owner and the operator each create three calendar events with
   notifications: **2027-01-14** (one month out: upgrade now), **2027-01-29** (two weeks, a Friday:
   confirm the upgrade is done), **2027-02-05** (one week out, a Friday: if not upgraded, nothing
   else matters this week), and **2027-02-12** (the last working day before the Saturday expiry). This is the primary mechanism. Everything below is a backstop, because every
   automated path here depends on something that can silently stop: a workflow on a branch, a channel
   in a project, an export Google can break server-side.
2. **The `trial-expiry-countdown` job** in `.github/workflows/gcp-cost-controls.yml`. It runs once each
   weekday and never at weekends. The cron is 02:30 UTC (08:00 IST), and GitHub often starts it hours
   late. Every run prints the countdown to the run summary. It then closes any open `trial-expiry` issue
   whose title does not carry the current expiry date, before any check that could end the run early.
   It touches issues only when it runs from `main`; a manual run from another branch only prints the
   countdown and applies the thresholds.
   From 60 days out it keeps
   one open issue labelled `trial-expiry`, with the expiry date in its title, assigned to the repository
   owner. From 21 days it comments on that issue every run. From 10 days it fails the workflow. Once the
   date has passed, it fails with a message that says so.
   Scheduled workflows run from `main`, so the version aimed at 2027-02-13 goes live only with the next
   dev -> main promotion, and promotion is on hold. Until then, main's copy still counts down to
   2026-11-16, fails every weekday and comments "-N day(s)" on issue #308. **Leave #308 open.** If it is
   closed by hand, the old job opens a fresh issue naming the old account and date on its next run. The
   first run of the new version closes #308 itself. Promote by **2026-12-15** to keep the full 60-day
   lead. A later promotion still opens the issue on its first run.
3. **The `custoking-trial-credit-runway-to-2027-02-13` budget** (Terraform, prod root). It is
   account-wide, runs over a custom period from the day the balance was read (2026-11-15) to the expiry
   date, takes the credit remaining (INR 28,569) as its amount, and has thresholds at 50 / 75 / 90 / 100%
   of the runway. It is the only signal for the credit-exhaustion path, and **it does not exist yet**. A
   budget belongs to a billing account. The one that exists today,
   `custoking-trial-credit-runway-to-2026-11-16`, sits on the closed first account and its period has
   ended. The new one exists only after owner action 3 and the targeted apply in *Budget Setup*. At
   ~INR 190/day, expect only the 50% threshold, around the end of January. Reaching 75% before the
   expiry date means a sustained ~INR 240/day, and 90% means ~INR 290/day. Re-base
   `trial_credit_remaining_inr` and `trial_runway_start_date` together whenever the balance is re-read
   from the Console.

After the upgrade: close the issue, set `manage_trial_runway_budget = false`, remove the countdown job,
delete any budgets still left on the closed first account, and record the date here.

## If billing lapses again

Billing lapses when a trial expires without an upgrade, when the credit runs out, or when the account is
closed or unlinked. This is what happened on 2026-11-17 (IST), for calibration:

| Time (IST) | Event |
| --- | --- |
| 00:19 | Trial ends; billing disabled on both projects; Cloud SQL `SUSPENDED`; every public URL returns 500 |
| 02:02 | Both projects relinked to an open billing account |
| 02:17 | Prod Cloud SQL `RUNNABLE` again |
| 02:36 | Cloud Run serving again (34 minutes after the relink) |

Nothing in the application is wrong. Do not redeploy, roll back, or change IAM or ingress: none of it
helps, and each one is another change to unwind afterwards. If gcloud's configured quota project is not
one of ours, add `--billing-project=custoking-prod` to the commands below.

1. **Relink both projects, prod first,** to an open billing account. You need Billing Account User on the
   account and Project Billing Manager (or Owner) on the project. In the Console: Billing -> Account
   management -> the project -> Change billing. Or run the following, with the ID taken from
   `gcloud billing accounts list` (`OPEN` = `True`). Type it locally and never paste it anywhere public.
   Linking to a closed account disables billing again.

   ```powershell
   gcloud billing projects link custoking-prod --billing-account=<ACCOUNT_ID>
   gcloud billing projects link custoking-dev --billing-account=<ACCOUNT_ID>
   gcloud billing projects describe custoking-prod --billing-project=custoking-prod --format="value(billingEnabled)"
   ```

2. **Wait for Cloud SQL** (about 15 minutes last time). It resumes by itself through an `UPDATE`
   operation. Wait until `state` reads `RUNNABLE`:

   ```powershell
   gcloud sql instances describe custoking-db-prod --project=custoking-prod --format="value(state)"
   gcloud sql operations list --instance=custoking-db-prod --project=custoking-prod --limit=5
   ```

3. **Wait out the Cloud Run front end,** which can take up to ~35 minutes after the relink. Google
   documents this as a Cloud Run known issue, "Delayed service recovery after lifting an exceeded spend
   cap or re-enabling billing": up to 30 minutes of 429/503 responses, instances may fail to start, and
   "There is no manual workaround." On 2026-11-17 the front end returned Google's own 503 page ("The
   service you requested is not available yet") and dropped most requests before they reached any
   container. While that lasts, there are no request logs.
4. **Expect, and ignore:**
   - Services that started before the database was `RUNNABLE` exit 1 on Flyway "The connection attempt
     failed". The next request starts a fresh instance, which succeeds.
   - Every Cloud Scheduler job's history is reset (`lastAttemptTime` cleared, status code -1). This is
     benign: the next scheduled run fills it in.
5. **Check the backup.** The automated Cloud SQL backup does not run while the instance is suspended
   (the 02:00 IST one was skipped on 2026-11-17). Take an on-demand backup:

   ```powershell
   gcloud sql backups list --instance=custoking-db-prod --project=custoking-prod --limit=5
   gcloud sql backups create --instance=custoking-db-prod --project=custoking-prod --description="after billing relink"
   ```

6. **Verify recovery.** The gateway smoke must report `UP`. Then sign in through the frontend and open a
   school page, and confirm that Cloud Run request logs are arriving again.

   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\smoke-gateway-health.ps1 `
     -Environment prod -ProjectId custoking-prod -Region asia-south2 `
     -OutputPath artifacts\billing-recovery\gateway-smoke-prod.json
   ```

   Repeat steps 2, 5 and 6 for dev (`custoking-db-dev`, `custoking-dev`, `-Environment dev`).
7. **Afterwards:** if the projects are now on a different account, its budgets and billing export do
   not exist yet. Re-aim them (*Budget Setup*, *Billing Export*). If the new account is another trial,
   update this section, `TRIAL_EXPIRY_DATE` and the `trial_*` values.

## Budget Setup

Budgets are Terraform-managed in `deploy/gcp/observability/budget.tf`; do not create or edit them in
the Console. The one exception is an owner creating them on a new account that the ops identity cannot
reach yet; those must then be imported (see below). Two per-project monthly budgets plus the
account-wide runway budget above:

| Budget | Scope | Amount | Thresholds |
| --- | --- | --- | --- |
| `custoking-prod-monthly` | project custoking-prod, calendar month, GROSS | INR 5,000 | 90%, 100%, 150% current; 100% forecast |
| `custoking-dev-monthly` | project custoking-dev, calendar month, GROSS | INR 2,000 | same |
| `custoking-trial-credit-runway-to-2027-02-13` | whole account, 2026-11-15..2027-02-13, net of every credit except the promotion | credit remaining (INR 28,569) | 50%, 75%, 90%, 100% current |

**On 2026-11-17 none of these is watching anything.** A budget belongs to a billing account. The three
that exist (`custoking-prod-monthly`, `custoking-dev-monthly`, `custoking-trial-credit-runway-to-2026-11-16`)
sit on the closed first account, which no longer carries any custoking spend. They have to be re-created
on the account the projects bill to now. The ops identity cannot do that yet: it holds only Billing
Account User there, so "does not have permission" is a real denial (see
`docs/operations/foundation-controls-runbook.md`). The owner either grants it Billing Account Costs
Manager (`roles/billing.costsManager`) on that account, or creates the budgets in the Console with the
names and values above. Console-created budgets must be brought into Terraform state before the next
apply of either root, or that apply creates duplicates. Each address still holds the old account's
budget, and Terraform will not import into an address it already manages, so for each root: `terraform
state rm 'google_billing_budget.environment[0]'` (and `'google_billing_budget.trial_runway[0]'` in prod),
delete the orphaned budget on the closed first account in the Console, then `terraform import` the new
budget into that address.

**Dev budget: owner decision.** Since the dev database became always-on (~2026-10-15), dev's gross has
been about INR 2,292 per 30 days, above its INR 2,000 budget. Re-created unchanged, that budget fires its
90% and 100% rules every month, and the load-certification guard (`load-tests/README.md`) blocks for
part of every month. The amount stays INR 2,000 until the owner decides otherwise.

**Re-aiming, once the grant exists.** Use a checkout of `dev` that includes the 2026-11-17 re-base of
the `trial_*` defaults and holds the gitignored `custoking-prod.tfvars` and `custoking-dev.tfvars`. Put
the account ID in those files (`billing_account_id`) and nowhere else, and check that they do not
override `trial_*`.

- `-target` keeps prod clear of the dev-only observability changes that are held for promotion.
- `billing_account` cannot change in place, so the plan replaces each budget: it deletes the budget on
  the old account and creates it on the new one. The plan should touch nothing beyond what the budgets
  depend on (the project data source and the operator e-mail channels).
- The plan prints the account ID. Read it in the terminal, never `-out` it to a file, and never paste
  it into an issue, PR or chat.
- Quote every argument that contains a dot. Windows PowerShell 5.1 splits an unquoted
  `-var-file=custoking-prod.tfvars` into two arguments.

```powershell
$tok = gcloud auth print-access-token
$env:GOOGLE_OAUTH_ACCESS_TOKEN = $tok
$env:USER_PROJECT_OVERRIDE     = "true"
$env:GOOGLE_BILLING_PROJECT    = "custoking-prod"
terraform -chdir=deploy/gcp/observability init -reconfigure `
  -backend-config="bucket=custoking-prod-terraform-state" `
  -backend-config="prefix=observability/prod" `
  -backend-config="access_token=$tok"
terraform -chdir=deploy/gcp/observability plan "-var-file=custoking-prod.tfvars" `
  "-target=google_billing_budget.environment" "-target=google_billing_budget.trial_runway"
terraform -chdir=deploy/gcp/observability apply "-var-file=custoking-prod.tfvars" `
  "-target=google_billing_budget.environment" "-target=google_billing_budget.trial_runway"
```

For dev, set `$env:GOOGLE_BILLING_PROJECT = "custoking-dev"`, run `init -reconfigure` against
`custoking-dev-terraform-state` / `observability/dev`, and use `"-var-file=custoking-dev.tfvars"` with
`"-target=google_billing_budget.environment"` only, because the runway budget is prod-only. Deleting a
budget on a closed account is untested. If the delete fails, run `terraform state rm` on that budget,
delete it in the Console, and apply again.

**They must measure GROSS** (`credit_types_treatment = "EXCLUDE_ALL_CREDITS"`). This runbook has said
so since it was written; the Terraform did not do it until 2026-09-11, and for the whole of August
both budgets measured a net of exactly zero and could not fire. Verify, do not assume. The command
below looks the account up, so no ID is written down. Until the owner grants a budgets role it returns
403 `PERMISSION_DENIED`:

```bash
ACCT=$(gcloud billing projects describe custoking-prod --billing-project=custoking-prod --format='value(billingAccountName)' | tr -d '\r')
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" -H "x-goog-user-project: custoking-prod" \
  "https://billingbudgets.googleapis.com/v1/${ACCT}/budgets" | grep -E '"displayName"|"creditTypesTreatment"|"units"|"message"'
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
policies in `spend_anomaly_alerts.tf`; see that file and `deploy/gcp/observability/README.md`. On
2026-11-17 the two export-based ones are blind. The cost jobs have failed since 2026-11-03, when the
floating `google/cloud-sdk:slim` image lost python3; the image pin is merged on `dev` (PR #381) but has
not been applied. The new account also has no export yet. Until both are fixed, only
`cloud-run-instance-time-runaway` works.

## Billing Export

Enable detailed Cloud Billing export to BigQuery. Put it in a billing/admin dataset rather than an application database.

**State on 2026-11-17: the account the projects bill to has no export.** The first account's two tables
in `custoking-prod.billing_export` stop with usage on 2026-11-16. Keep them: they are the only
invoice-grade record of the projects up to the move. The new account's owner enables its export in the
Console (Billing -> Billing export -> BigQuery export; there is no API for export configuration). Enable
both **Standard usage cost** and **Detailed usage cost**, into project `custoking-prod`, dataset
`billing_export`. Using the same dataset means no code change: the queries below, the cost-metric
exporter and the dashboards all read `gcp_billing_export_v1_*` / `gcp_billing_export_resource_v1_*`.
They pick up the new table automatically and add both accounts together across the move. The dataset is
in the US multi-region, where a first export backfills only from the start of the previous month.
Enabled in December, it covers 2026-11-16..30. Enabled on or after 2027-01-01, that half-month never
reaches BigQuery. **Enable it before 2026-12-31**, and allow a few days for the first delivery.

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
FROM `custoking-prod.billing_export.gcp_billing_export_resource_v1_*`
WHERE project.id IN ('custoking-prod', 'custoking-dev')
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

The `Ops / GCP cost controls` workflow starts dev Cloud SQL at 08:00 IST on weekdays. **The nightly
stop was removed on 2026-09-24** and stopping is now a deliberate manual act, via the workflow's
`dev_database_action` input or the helper below. A dev deployment still starts the database, waits
until it is runnable, and returns it to stopped only if it found it stopped.

The stop was removed because it made dev unusable for most of the working day. GitHub schedule
execution can be delayed by platform load, and here it ran **4 to 5.5 hours late every day**: the
08:00/20:00 IST crons produced an actual window of roughly 13:20 to 24:00 IST, so a morning login
failed with every service unable to reach the database (`FlywaySqlUnableToConnectToDbException`,
SQL state 08001). That reads as an authentication bug rather than a stopped instance. The weekday
start remains, so an instance stopped by hand comes back on the next working day.

**Cost:** dev Cloud SQL is `db-f1-micro` and now runs continuously rather than roughly half the
day. Stop it by hand during any long idle period. Dev's gross measured INR 2,292 over the 30 days
2026-10-17..2026-11-15. The projects bill to a second free trial that expires about 2027-02-13 (see
*Free-trial expiry*).

The scheduled workflow runs from the repository default branch and intentionally does not target the
branch-gated `dev` GitHub Environment. It reads only the repository-level
`DEV_GCP_PROJECT_ID`, `DEV_CLOUDSQL_INSTANCE`, `DEV_COST_WORKLOAD_IDENTITY_PROVIDER`, and
`DEV_COST_CONTROLLER_SERVICE_ACCOUNT` controls. There are no legacy fallbacks: an absent dev-only
control fails validation before authentication. The cost-controller service account must be dedicated to this workflow and limited to
Cloud SQL Editor plus Service Usage Consumer. Never point the cost-controller variable at a release or
general deployment identity. The identity exists only in projects that host `dev`
(`infra/terraform/cicd`, `identity_environment.cost_controller = "dev"`): it must never be created
in a production project, because this workflow runs outside the reviewer-gated Environments and
would otherwise be an unreviewed path to production Cloud SQL.

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
- Cloud Run known issues (delayed recovery after re-enabling billing): https://docs.cloud.google.com/run/docs/known-issues
