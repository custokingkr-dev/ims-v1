# GCP Budget Incident — 2026-08-11

Project: `custoking`

Billing account: `018AC9-E669C1-2FC9B8`

Guardrail: `Custoking Monthly Guardrail`, INR 5,000 monthly gross cost, project scoped

## Verified status

The live Cloud Billing budget page reported `INR 5,016.73 / INR 5,000.00` on 2026-08-11. The
current-month report showed INR 5,016.74 usage cost and zero payable subtotal after temporary Free Trial
credits. The budget deliberately excludes credits, so crossing it is real even though the current payable
amount is zero. The console forecast was INR 6,431.43 gross. Later in the same audit, the standard export
had caught up to INR 5,042.06 through usage ending 2026-08-11 12:00 UTC (exported 15:40:33 UTC). Cloud
Billing surfaces can lag independently; the timestamped values are retained instead of presenting one as
permanently exact.

## Evidence-backed cause

The detailed BigQuery export contained INR 4,462.32 through usage ending 2026-08-11 10:00 UTC; the
newer console total was INR 554.41 higher. Exported August cost was:

| Category | Gross INR | Interpretation |
| --- | ---: | --- |
| Cloud Run dev services/jobs | 1,504.42 | Primarily deliberate scale, soak, mixed-load and onboarding certification |
| Cloud SQL production baseline | 1,026.94 | Continuously available `db-g1-small`; expected fixed production cost |
| Cloud Run production services/jobs | 1,005.38 | Mostly the earlier authenticated dashboard polling incident; current daily spend is much lower after its fix |
| Cloud SQL dev | 412.78 | Normal dev runtime plus temporary 4/8-vCPU capacity experiments |
| Cloud Build | 306.96 | One-time build activity on August 3 |
| Artifact Registry | 82.38 | Retained deploy/rollback images; cleanup policies are active |
| Secret Manager | 61.62 | Forty-four secrets, exactly one enabled version each |
| Compute Engine networking | 41.17 | Mostly inter-zone transfer associated with the dev tests |
| Cloud SQL drills/clones/backups | 15.62 | Temporary recovery/history certification resources, subsequently removed |
| Cloud Storage | 2.57 | Normal storage and operations |

August 11 export rows alone attributed INR 1,072.58 to dev Cloud Run and INR 118.85 to dev Cloud SQL.
The largest items were 32.96 GB of dev gateway internet egress (INR 352.32), 4.61 million gateway
requests (INR 176.20), 4.59 million school-core requests (INR 175.42), and gateway/school-core CPU.
This aligns with the retained 4.18-million-request soak and subsequent capacity work; it is not an
unidentified production spike.

## Current containment

- `custoking-db-dev` is `STOPPED`, tier `db-f1-micro`, activation policy `NEVER`.
- All four dev relay Scheduler jobs are `PAUSED`.
- All fourteen Cloud Run services use zero minimum instances; dev startup CPU boost is disabled.
- No local k6/JMeter/Locust process is running.
- Recent dev gateway activity is limited to small health checks.
- Artifact Registry already deletes images older than seven days while keeping the three most recent
  versions per service. The current repository size reflects recent builds and should not be manually
  purged at the expense of rollback evidence.
- All 44 secrets have exactly one enabled version; there is no safe superseded-version saving available.

There is no active runaway resource to shut down. Production Cloud SQL remains the largest unavoidable
fixed component and must not be stopped as a budget reaction.

## Corrective control

`scripts/invoke-dev-load-certification.ps1` now fails closed before Docker starts unless the current
BigQuery gross spend plus a profile estimate remains within 80% of the live project budget. It rejects
missing/stale billing data and records the preflight in load evidence. `-AllowBudgetOverrun` requires an
explicit spending-owner decision; it does not change performance thresholds.

Future full-volume load generation should run from an approved same-region Google Cloud runner when
practical. Google documents that same-region transfer between Google Cloud resources is free, whereas
responses to the external local load generator produced the observed Cloud Run internet-egress charge.

## Decision boundary

INR 5,000 is a warning guardrail, not a viable proven production envelope for 100–150 schools. The measured
low-idle baseline already consumes most of it, before normal school traffic and required availability
headroom. Do not raise the budget merely to silence the alert. A spending owner must approve a new envelope
after the arrival-rate capacity rerun and the production database/HA decision. A Cloud Run spend-cap budget
is currently Preview and would pause the shared project's selected service, including production, so it is
not appropriate while dev and production share this project.

## Authoritative references

- Google Cloud Billing budgets: https://docs.cloud.google.com/billing/docs/how-to/budgets
- Google Cloud spend-cap budgets (Preview): https://docs.cloud.google.com/billing/docs/how-to/budgets-spend-caps
- Detailed billing export and reporting latency: https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-tables/detailed-usage
- Cloud Run pricing and same-region transfer: https://cloud.google.com/run/pricing
