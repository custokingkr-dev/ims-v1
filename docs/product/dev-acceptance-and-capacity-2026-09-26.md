# Dev acceptance and capacity preparation

This is a prepared post-deployment acceptance plan, not a passed dev acceptance or load result. This work made only read-only cloud inventory/Monitoring/SELECT calls and local helper/test/document changes. It did not execute application writes, start a load test, resize the database, modify IAM, create a billing view, or send messages.

## Current dev evidence and the capacity gate

The explicit target is `custoking-dev`, region `asia-south2`, database `custoking-db-dev`, and gateway `https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app`. The workstation's default project is production, so every cloud command must specify the dev project. Do not change the global configuration.

Read-only observation on 26 September found Cloud SQL RUNNABLE on `db-f1-micro`, 10 GiB, `max_connections=200`. The five domain services each allowed four Cloud Run instances; school-core explicitly configured a pool of 20 and the other four application defaults were 5. The potential application pool total is 160, leaving the documented 40-connection migration/operator reserve. This is an upper-bound configuration calculation, not measured concurrent capacity. Recollect after deployment; revisions and environment settings can change.

The old `custoking-dev.cost_analysis.daily_service_cost` table is stale: 234 rows, latest usage 23 August, latest computation `2026-08-23T20:36:21Z`. The current hourly `ims-cost-metric-dev` job no longer populates it. Its live configuration reads the shared account's `custoking-prod.billing_export`, filters `custoking-dev`, and publishes invoice-grade gross/net/health metrics into dev Monitoring. Execution `ims-cost-metric-dev-dlr4d` succeeded at `2026-09-26T14:30:28Z`. Its log reported both standard and detailed export availability and grade 2.

A direct detailed-export SELECT, billed to dev and filtered to dev, returned gross month-to-date **₹1,966.3141**, latest export `2026-09-26T13:38:58Z`, latest usage `2026-09-26T09:00:00Z`. The dev monthly budget was₹2,000; the existing 80% load-test guard is₹1,600. Gross spend already exceeds that guard before adding any run estimate. Near-zero credit-adjusted net spend is not a substitute. Capacity execution remains **BLOCKED by budget**, even though fresh invoice data now exists. Do not populate the old table with a fake zero, weaken freshness, or use an overrun switch without the separate spending decision required by the existing runner.

Recollect using the new read-only helper; it refuses to overwrite an evidence file:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/read-dev-capacity-evidence.ps1 `
  -OutputJson artifacts/product-followup-2026-09-26/dev-capacity-read-only-next.json
node scripts/prepare-dev-product-acceptance.mjs --capacity `
  artifacts/product-followup-2026-09-26/dev-capacity-read-only-next.json
```

The second command is an offline policy evaluation: exit 0 means its supplied evidence meets the gates, exit 2 means blocked. Neither command starts a workload. The collector queries [the read-only billing SQL](../../scripts/sql/dev-capacity-cost-preflight.sql), sums connection metrics across databases, and records no tokens. Its synthetic-fixture field intentionally stays unverified until a separate authorized fixture status inspection. The₹15 and0.01GiB probe allowances are planning caps, not observed prices. Re-evaluate with fresh cost, Logging and database evidence immediately before any separately authorized run.

## Bounded functional acceptance

The plan builder creates a reviewable ordered HTTP plan with payloads, dependencies, checks and stop rules. It has no HTTP client; the deployment operator executes the reviewed plan after root's deployment handoff. One school-scoped ADMIN token is read from `IMS_DEV_ACCEPTANCE_TOKEN`; do not put it in JSON, terminal transcripts, artifacts, or query strings.

Required inputs after authenticated read-only school 1 preflight are: a school-visible `classId`, a PUBLISHED `feeBandId` covering that class/current year with at least 100 paise payable, one of that band's active schedules, the current academic-year ID, and the selected attendance date. Never substitute an existing student or existing attendance section. A distinct `ACPT-YYYYMMDD-<6–20 lowercase random characters>` run label owns all new records.

Example configuration shape (reference IDs must be replaced with actual checked values):

```json
{
  "project": "custoking-dev",
  "baseUrl": "https://custoking-api-gateway-dev-hd4wfwk7mq-em.a.run.app",
  "schoolId": 1,
  "runId": "ACPT-20260926-abc123",
  "classId": "REPLACE_WITH_CHECKED_CLASS_ID",
  "feeBandId": "REPLACE_WITH_CHECKED_PUBLISHED_BAND_ID",
  "feeSchedule": "Annual",
  "academicYearId": "2026-27",
  "attendanceDate": "2026-09-26"
}
```

```powershell
node scripts/prepare-dev-product-acceptance.mjs artifacts/reviewed-dev-acceptance-input.json `
  artifacts/dev-acceptance-plan.json
```

The plan fixes concurrency at 1, maximum 45 HTTP calls, 30 seconds per request, and 10 minutes overall. It creates at most one synthetic contact-free student/new test section, one fee assignment for that student and one 100 paise cash test payment, one procurement request/quotation, and one annual-plan item. No guardian contact, phone, email, photo, notification/reminder/broadcast request, vendor payment or fulfillment call is included. No real money is moved, but the synthetic payment still changes dev fee totals and must be identified as test data in acceptance evidence.

Checks cover:

- Admission: reconcile the exact unique run admission number to one school-owned student; a lost response must be reconciled before another create call.
- Attendance: the test section contains only that student; repeated register PUT retains one PRESENT student/day entry. Do not submit an entire school day.
- Fees: assign a read-only existing published plan only to the new test student; exact replay returns the same payment/receipt, changed amount with the same key returns 409, and ledger/assignment totals reconcile.
- Procurement: exact request and quotation replays return their original identifiers, changed payloads return409, and repeated submission has the same confirmed status. Stop at `AWAITING_BURSAR`; do not approve or fulfill the synthetic request. Private-file capability is recorded honestly; an unavailable capability does not pass file-upload acceptance.
- Annual plan: confirmation is school/current-year-wide. **Before adding an item, block this phase if any existing plan item is not owned by this journal.** A clean dedicated synthetic school is required when school 1 already contains a plan. For an isolated plan, require the exact reviewed fingerprint, same confirmation ID/revision on replay,409 for an invalid fingerprint, and `notificationStatus=NOT_SENT`.

Persist the fixed run timestamp, exact payloads, original replay keys and confirmed synthetic IDs in a local no-overwrite journal before the first write. Do not restart an uncertain run with new keys. Stop immediately on any auth/school mismatch, unexpected status/timeout,429/5xx, non-test record in a write scope, duplicate, or outbound-delivery attempt. Preserve a blocked phase as BLOCKED, rather than marking the entire run passed.

There is no automatic cleanup. Some payment/confirmation effects have no supported undo API. Retain the exact run-owned IDs for a separate reviewed cleanup; never delete shared classes, existing students, fee bands, school plans or financial records outside the run.

## Capacity after the gates close

Existing `invoke-scale-fixture.ps1` and load assets reserve school IDs at or above 900000000. They must not be pointed at school 1. A Status action is read-only SQL but its wrapper may require a job identity/transport reconciliation; do not add `-AllowScaleWrites` merely to get past that block without reviewing the infrastructure action.

Do not run the current full load certification profiles on the shared-core database. In particular, `StaffWorkload -Hold 2m` still includes 5 minutes warmup, 20 minutes burst, 3 minutes up, 3 minutes down and 2 minutes final drain: 35 minutes total. The wrapper hardcodes 100 schools and 300,000 students. Its historical connection reducer uses a maximum across database series rather than the required sum; do not treat that as a safe small-probe guard.

The prepared first probe is a read-only diagnostic against a verified reserved fixture: 1 concurrent request, 60 seconds at 1 request/second then 60 seconds at 3 requests/second, maximum 240 requests. Mix canonical student-list(size 20), attendance daily-summary, fee structure, and procurement-list(limit 20) reads. Use an existing short-lived scoped token; no synthetic-login storm. Report actual delivered rate and latency, not an assumed target or fleet capacity.

Before execution a guard-capable runner must enforce: immediate stop for any unexpected 4xx/5xx; 10-second request timeout; stop after two consecutive responses slower than 5 seconds; CPU≥80%, Usage-memory≥90%, or total database connections≥140 for two fresh monitoring samples; stop after two missing/stale monitoring samples. Maintain the existing 80% gross-budget and 40 GiB projected Logging guards, with billing usage/export≤24hours old. No runner was started or advertised as certified here. A longer staff/fleet sizing test needs a separate reviewed database shape, fixture and cost window.

## Local verification

`node --test scripts/tests/prepare-dev-product-acceptance.test.mjs` exercises target rejection, contact-free synthetic ownership, exact key/payload replay, actual gross-budget rejection, stale/malformed monitoring/cost evidence, and no-overwrite plan generation without credentials. These are local guard tests, not deployed acceptance results.
