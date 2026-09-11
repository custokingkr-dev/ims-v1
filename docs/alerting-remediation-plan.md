# Alerting remediation plan

Measured 2026-09-11 against `custoking-prod` (182609177023) and `custoking-dev` (1087017280590),
billing account `014C0A-C6B9AF-5FABC0`. Every number below was queried, not estimated; where something
could not be measured it says so. Companion changes in the same PR: `deploy/gcp/observability/budget.tf`,
`spend_anomaly_alerts.tf`, the `trial-expiry-countdown` job in `.github/workflows/gcp-cost-controls.yml`,
and `docs/GCP-COST-GUARDRAILS-RUNBOOK.md`.

Nothing in this document has been applied to GCP. Section 7 is the apply order.

---

## 1. The situation in five findings

1. **Production runs on a free trial that ends 2026-11-16, and the credit margin is one month of
   burn, not three.** Combined gross spend is **INR 262/day** (prod 138, dev 124), measured over the
   14 complete days 2026-08-27..2026-09-09 from the billing export. Credit remaining is **~INR 23,300**
   (INR 28,262.87 reported 2026-08-20 minus INR 4,958.57 of promotion credit drawn since). Projected
   gross to 2026-11-16 (67 days) is **INR 17,561**; projected *credit draw* is ~INR 16,500 because the
   monthly Cloud Run free tier absorbs ~INR 500/month before the promotion is touched (measured INR
   498.76 for 2026-09-01..09 and INR 499.26 for 2026-08-17..31). Margin: ~INR 6,800, about 26 days.
   The trial ends at the EARLIER of the date and credit exhaustion. A three-week runaway ends it
   early.

2. **Both budgets that were supposed to watch this could never fire.** They exist in Terraform and in
   the account (`custoking-prod-monthly` INR 6,000, `custoking-dev-monthly` INR 2,000, four thresholds
   each) but carry `creditTypesTreatment: INCLUDE_ALL_CREDITS` -- the provider default -- so they
   measure net cost, and net cost on this account is **INR -0.0015** month-to-date in prod and **INR
   -0.0008** in dev. The runbook has said "must use EXCLUDE_ALL_CREDITS" since it was written. Dev ran
   at **188% of its budget** for the whole of August and nothing was sent. Fixed in this PR.

3. **Dev costs as much as prod, and two-thirds of it is a defect.** Dev's five domain services
   receive exactly 4,040 requests/week each -- six uptime checker regions x 96 probes/day -- and
   nothing else. Dev's Cloud SQL is stopped outside weekday hours; Spring Boot cannot start without it
   (`Application run failed` -> `instance could not start successfully`), so every probe buys a fresh
   ~23-second failed JVM boot. Measured 2026-09-03..2026-09-10: **1,107-1,723 container startups per
   service per week** (prod: 3-6), 68% of probes answered 5xx, 26,000-41,000 billable seconds per
   service per week (prod: 3,000-7,000). That is **INR 83/day of Cloud Run CPU in dev, INR ~2,500/month,
   ~INR 5,500 between now and the trial cliff** -- 80% of the credit margin -- spent proving that a
   database which is meant to be off is off, to a set of alert policies that notify nobody. Dev's
   cold starts also exhaust the shared monthly Cloud Run free tier by the 5th (this month: CPU on
   09-05, memory on 09-09), after which prod's own usage draws credit at full price.

4. **The alerting estate has grown, not shrunk, and most of it is silent by design now.** 144
   policies (prod 73, dev 71), not the 126 from the previous audit. In prod, 24 notify, 42 are
   enabled with no channel (they open incidents in the console and nothing else), 7 are disabled. In
   dev, all 71 have no channel. The 2026-08 audit's "latches open forever" defect is fixed --
   `auto_close` is set on every policy -- but the deeper latch remains (section 4). `gcloud alpha
   monitoring policies list` still returns a wrong count (6); use the REST API.

5. **The log-metric fix holds.** All five outbox/notification metrics have live series in prod (9 outbox
   series across billing/operations/school-core, 30,261 samples in 7 days; 3 notification series,
   10,106 samples) and in dev. The alert aggregation reads 0.500 on every one, which is the documented
   encoding of a true zero. The `<arguments/>` provider fix of 2026-08-19 is confirmed working.

---

## 2. Spend, measured

### 2.1 Run rate (gross, 2026-08-27..2026-09-09, 14 complete days)

| | INR/day | INR/month (30.4 d) | to 2026-11-16 (67 d) |
| --- | ---: | ---: | ---: |
| custoking-prod | 138.46 | 4,209 | 9,277 |
| custoking-dev | 123.64 | 3,759 | 8,284 |
| **total** | **262.11** | **7,968** | **17,561** |

Net is INR 0.00 on both projects: the promotion `FreeTrial:Credit-014C0A-C6B9AF-5FABC0` absorbs
everything the Cloud Run free tier does not. Total gross since the export began (2026-08-17):
INR 6,474.13; promotion drawn INR 5,476.11; free-tier discounts INR 998.02.

### 2.2 Top line items (same window)

| # | Project | Line | INR/14d | INR/day | Note |
| --- | --- | --- | ---: | ---: | --- |
| 1 | prod | Cloud SQL db-g1-small instance | 1,348.87 | 96.35 | flat; CV = 0 |
| 2 | dev | Cloud Run Services CPU (Tier 2) | 1,166.32 | 83.31 | **the cold-start loop** |
| 3 | prod | Cloud Run Services CPU (Tier 2) | 264.62 | 18.90 | real traffic + probes |
| 4 | dev | Cloud SQL db-f1-micro instance | 128.82 | 9.20 | weekday hours only |
| 5 | dev | Cloud Run Services Memory (Tier 2) | 121.17 | 8.65 | same loop |
| 6 | dev | Artifact Registry internet egress | 96.37 | 6.88 | 9.0 GB |
| 7 | prod + dev | Cloud SQL storage (10 GiB each) | 179.76 | 12.84 | |
| 8 | prod | Cloud Run Jobs CPU | 74.15 | 5.30 | cost-metric, liveness, cost-analysis jobs |
| 9 | prod | Artifact Registry internet egress | 52.51 | 3.75 | 4.9 GB |
| 10 | prod + dev | Secret Manager version storage | 97.79 | 6.98 | 44 secrets x replicas |

### 2.3 Against the baseline

| Baseline claim | Measured now | Verdict |
| --- | --- | --- |
| zero-user floor ~INR 4,450/month, 78% Cloud SQL | prod INR 4,209/month, **74% Cloud SQL** | prod is at the floor; no drift |
| dev ~INR 7/day (SQL stopped) | dev INR 124/day | **regression: +INR 117/day**, cold-start loop (finding 3) |
| loaded INR 3.06/student/month, INR 0.51 variable | prod unchanged; dev's loop is pure fixed waste | holds for prod |
| AR egress 28 GB / INR 323 per day before the fix, 0 after | 66.4 GB / INR 710 over 24 days = 2.8 GB / INR 30 per day average; one 18.9 GB / INR 202 day on 2026-08-25 | **no regression**: 08-25 had 30+ `build-release` runs (dev pushes + main merges) at ~0.63 GB / INR 6.7 each; egress scales with merge count, as designed. The reporter that would have told you this has been **skipped on every scheduled run** since 2026-08-19 because `BILLING_EXPORT_TABLE` is unset (section 6). |
| AR cost 95% egress / 5% storage | 83% / 17% (egress fell, storage did not) | fine |

### 2.4 What the numbers mean for the cliff

At the current rate the credit survives to the date with ~26 days to spare. That margin disappears
under any of: a load test week (~INR 612/day measured in August), a min-instances regression on any
service (one instance 24x7 ~ INR 240/day), or a duplicate database (INR ~104/day). The upgrade to a
paid account is the only real fix and costs nothing; the budgets and policies in this PR exist so
that the weeks before it are visible.

---

## 3. The 144 policies, classified

Counts are per project. `notify` = has notification channels attached today.

| Class | Prod | Dev | Notify (prod) | Verdict |
| --- | ---: | ---: | --- | --- |
| Per-service uptime (`*-uptime`) | 7 | 7 | yes | **keep**: the only dead-service detector that works (value 0 keeps flowing when the service dies). In dev, no data once probes are off. |
| Per-service 5xx ratio (`*-5xx-rate`) | 7 | 7 | no | **delete**: ratio over request_count that is ~85% probe traffic; replaced by `server-errors-users-hit`. Silent since 2026-08-21. |
| SLO burn-rate, 4 per service | 28 | 28 | no | **delete all 56**: the SLIs are Cloud Run basic availability/latency which count probe traffic (overnight one failed probe = 50% error rate). They open silent incidents and mean nothing. Keep the SLO objects for the dashboards. |
| Per-service max-instance saturation | 7 | 7 | no | **keep in prod and make it notify**: prod max is 2; two instances busy for 5 minutes is a real capacity signal, not noise. Delete in dev. |
| Per-service p95 latency | 7 | 7 | disabled | **delete**: disabled since the long-running-import false positives; carrying disabled policies in state is dead code. Re-create when a path-aware latency metric exists. |
| Cloud SQL cpu / memory | 2 | 2 | yes | **keep prod** (cpu max 8.6% in 7 days: quiet, correct). Delete dev (instance is stopped most of the time). |
| Cloud SQL connections | 1 | 1 | yes | **inert -- fix or delete**: `postgresql/num_backends` reads **0 for every database, including during school hours** (120/120 one-minute samples on 2026-09-09 05:00-07:00Z). Either the metric does not reflect app connections on this instance or the filter is wrong; the threshold of 140 (flag max_connections=200) is unreachable either way. Not diagnosed further here. |
| Storage growth (100 GiB / 24 h) | 1 | 1 | yes | **pointless at this threshold**: the bucket is 72 MB. Lower to 2 GiB or delete. |
| Pub/Sub backlog + oldest-unacked, 2 subs | 4 | 4 | yes | **keep prod** oldest-unacked (real staleness signal); backlog-count is redundant with it -- fold into one. Delete dev. |
| Outbox pending / dead-letter / oldest-age | 3 | 3 | yes | **keep prod**, metrics confirmed live. Add re-notification (section 4). Delete dev. |
| Notification inbox backlog / dead-letter | 2 | 2 | yes | same |
| async-scheduler-failure | 1 | 1 | yes | keep prod (0 series in 7 days = no failures; a counter, so absence is fine) |
| trace-export-failure | 1 | 1 | yes | **demote to no-notify**: observability of observability; nobody gets out of bed for it |
| nobody-can-use-the-product | 1 | -- | yes | **keep**: the best policy in the estate, the only symptom-level one |
| server-errors-users-hit | 1 | -- | yes | **keep**: absolute count of real 5xx at the gateway, probe traffic excluded |
| **total** | **73** | **71** | **24** | |

**Genuinely actionable in prod today: 15 of 73** -- 7 uptime, server-errors-users-hit,
nobody-can-use-the-product, Cloud SQL cpu + memory, 2 Pub/Sub oldest-unacked, outbox dead-letter,
notification-inbox dead-letter. Another 4 are useful but secondary (outbox pending/age, inbox backlog,
async-scheduler). **54 are pure noise or dead** (7 5xx ratio, 28 burn-rate, 7 disabled p95, 7 silent
saturation -- pending promotion, 1 inert connections, 1 storage growth that cannot fire, 1 trace
export, 2 redundant Pub/Sub backlog). **In dev, 71 of 71 notify nobody** and 56 of them measure a
system that is deliberately off two-thirds of the time. After this plan: prod ~22 policies (15 kept +
7 saturation promoted, plus 3 new spend policies and 3 dead-man policies), dev 3-5 (spend only).

---

## 4. The three structural defects, and the fix for each

### 4.1 `evaluationMissingData` -- silence looks like health

Every existing condition leaves it at the default (`NO_OP`: "do not evaluate the condition if there
is no data"), so a metric that stops arriving neither opens nor closes anything. This is the exact
mechanism by which the log metrics collected nothing for months without a single alert. The audit's
framing -- "dead services never fire" -- is narrower than it sounded: a dead *service* does fire,
because the uptime policies keep receiving `check_passed = 0`. What never fires is a dead *feed*:

| Feed that can die silently | What stops | Fix |
| --- | --- | --- |
| The product-liveness Cloud Run job | `product_liveness_failures` is a failure counter; no job = no failures = green | a `condition_absent` policy (duration 2 h) on a "liveness check ran" log metric, or on `run.googleapis.com/job/completed_execution_count` for `ims-product-liveness-prod` |
| Domain-service health logs (the 2026-08-19 failure class) | the five outbox/notification metrics go empty | a `condition_absent` policy per family on `outbox_pending_count` and `notification_inbox_backlog_count`, duration 6 h. Not `EVALUATION_MISSING_DATA_ACTIVE` on the existing threshold policies -- prod's domain services cold-start 3-6 times a week, so they are *almost* never idle, and "almost" would page at 3 am the one night they are |
| Cloud SQL instance (deleted, stopped, tier change gone wrong) | every `cloudsql.googleapis.com/*` series | a `condition_absent` on `cloudsql.googleapis.com/database/up`, duration 15 min |
| The cost-metric exporter | `custom.googleapis.com/custoking/cost/*` | **done in this PR**: `billing-export-stale` uses `EVALUATION_MISSING_DATA_ACTIVE` because absence *is* its condition |

The three new spend policies in this PR set the field explicitly in both directions; every policy
written from now on should, and the rule belongs in a Terraform `check` block or a review checklist.

### 4.2 Latching -- one incident swallows every later one

`auto_close` is now on every policy (30 min for log-metric policies, 60 min for the rest), which
closes the audit's version of the defect. The version that remains: Cloud Monitoring notifies on
incident *creation* only. A dead-letter row nobody cleans keeps `outbox_dead_letter_count` above
0.5 forever, the incident stays open (auto_close only acts once the condition clears), and the
second, third and tenth dead letters arrive into an incident that already notified once, a week
ago. Same for Pub/Sub oldest-unacked and both inbox policies.

Fixes, cheapest first:

1. `alert_strategy { notification_channel_strategy { renotify_interval = "86400s" } }` on the five
   log-metric policies and the two oldest-unacked policies: re-notify daily while open. One block
   each in `log_metrics.tf` and `infrastructure_alerts.tf`.
2. A runbook rule: a dead-letter incident is resolved (replayed or discarded) the same day, so the
   level returns to zero and the next one can notify. Already the intent of the documentation text on
   the policy; make it a checklist item in `docs/runbooks/3am.md`.
3. Longer term: emit a log line per dead-lettering *event* and alert on the delta (new dead letters
   in the last hour), not the level. A code change in the three domain services' outbox relay; not
   worth it until 1 and 2 have been shown insufficient.

### 4.3 Notification channels -- two Gmail inboxes are not a paging system

Both projects have exactly two channels, both `email`, both personal Gmail addresses, both enabled,
and every notifying policy plus both budgets go to both. Problems, in order of how much they matter:

- **No push, no escalation, no acknowledgement.** Email is read when it is read. Gmail's own
  filtering has already put automated GCP mail in a tab nobody opens at least once; there is no
  second attempt and no one to escalate to.
- **One failure domain.** Both channels are the same provider under the same organisation's Google
  account. A Google account lockout takes both out at once, and the budgets are attached to the
  *prod project's* channels -- if that project ever went away, so would the notification path for
  the account-wide runway budget (`budget_notify_default_iam_recipients = true` in this PR adds the
  billing-admin path as a second route that does not depend on Monitoring channels).
- **Personal, not role, addresses.** Handover means editing Terraform and re-applying.

Recommendation, in the order to do it (all Console-created; paste the channel names into
`budget_notification_channel_ids` / `notification_channel_ids`):

1. **Cloud Monitoring Mobile App channel** for both operators. Free, push notifications, five
   minutes, and it is a Monitoring channel so budgets can use it too. The single largest improvement
   per minute spent.
2. **SMS channel** for the primary operator, attached only to: `server-errors-users-hit`,
   `nobody-can-use-the-product`, the two public-entry uptime policies (frontend, gateway), and all
   spend signals. Verify at setup that Cloud Monitoring SMS supports the number's country.
3. **A Google Chat space webhook** (or Slack) for everything that notifies, as the shared, searchable
   record. Email stays as the audit trail.
4. Do **not** build night routing with snoozes -- a snooze *closes* the incidents and destroys the
   overnight record (previous finding).

---

## 5. Ranked plan

Ranked by (rupees or risk removed) / (minutes to do). Items 1-3 are this week.

| # | Action | Effort | Value | How |
| --- | --- | --- | --- | --- |
| 1 | **Apply this PR to prod** (budget credit fix, runway budget, 3 spend-anomaly policies) | 30 min | restores the only spend signals; makes the credit-exhaustion path visible | section 7 |
| 2 | **Dev: `enable_uptime_checks = false`**, apply dev root | 10 min | **INR ~2,500/month; ~INR 5,500 of runway (~3 weeks of margin)**; ends 1,100-1,700 failed boots/service/week; stops dev eating the shared free tier | one line in `custoking-dev.tfvars` (example already updated) |
| 3 | **Calendar entries + merge to `main`** so the countdown job goes live | 10 min | the shutdown date cannot arrive unnoticed | runbook section "Free-trial expiry" |
| 4 | **Upgrade the billing account** | 5 min, owner only | removes the cliff entirely; credit is kept | Console -> Billing -> Activate |
| 5 | Mobile App + SMS channels; attach to spend + the 4 symptom/entry policies | 30 min | the first path that can actually wake someone | section 4.3 |
| 6 | Dead-man policies: liveness job ran, outbox health present, Cloud SQL up | 1 h | closes the "silence looks like health" class for good | section 4.1; three `condition_absent` resources |
| 7 | Delete the noise: 56 burn-rate, 14 5xx-ratio, 14 disabled p95; promote saturation to notify in prod; delete dev's SQL/Pub-Sub/log-metric/storage policies | 1 h | 84 fewer policies; the incidents page becomes readable | `enable_*` gates already exist for some; add `enable_per_service_slo_alerts` and `enable_engineering_alerts` (false in dev) |
| 8 | `renotify_interval` on the 7 level-based policies; 3am runbook rule for dead letters | 20 min | second dead letter is no longer masked by the first | section 4.2 |
| 9 | Set `BILLING_EXPORT_TABLE` repo variable to `custoking-prod.billing_export.gcp_billing_export_v1_014C0A_C6B9AF_5FABC0` and grant `github-cost-controller@custoking-dev` `roles/bigquery.dataViewer` on that dataset + `jobUser` in custoking-dev | 15 min | the AR egress table resumes after 3 weeks of silent skipping | the job's own `if:` guard un-skips it |
| 10 | Fix or delete `cloud-sql-connections` (metric reads 0); lower `storage-growth` to 2 GiB | 30 min | two inert policies become honest | `infrastructure_alerts.tf`, `operational_alerts.tf` |
| 11 | Unlink billing from `project-39c0067e-053d-451b-97f` ("My First Project", created 2026-08-17, INR 0 so far) | 5 min | one fewer unmonitored spend surface on the account | Console |
| 12 | Line endings: prod was applied from a CRLF checkout, so three policies' documentation text carries `\r\n`; the next apply from an LF checkout "changes" them | 0 | cosmetic; expect it in the plan | -- |

Not on the list, deliberately: rewriting the SLOs to exclude probe traffic. The right answer is the
one already taken -- `server-errors-users-hit` on the gateway request log with health paths excluded.
SLO-based alerting at ~600 real requests/day is a category error; keep the SLO objects as dashboard
furniture and stop alerting on them.

---

## 6. What was verified and how

- **Policy counts**: Monitoring REST API `projects/*/alertPolicies?pageSize=500`, no next page. Prod
  73, dev 71. `gcloud alpha monitoring policies list` returned 6 -- still wrong, still do not use it.
- **Budgets**: `billingbudgets.googleapis.com/v1/billingAccounts/014C0A-C6B9AF-5FABC0/budgets` with
  `x-goog-user-project: custoking-prod`. Two budgets, both `INCLUDE_ALL_CREDITS`, both notifying the
  two email channels, `disableDefaultIamRecipients: true`. Terraform plan against the live prod state
  (read-only, `-lock=false`) shows exactly the intended diff: treatment, amount 6000 -> 5000,
  thresholds, IAM recipients; plus the runway budget and three policies to create. Five other
  in-place changes in that plan (cost-metric job client label, four dashboards' JSON normalisation)
  appear identically when planning the unmodified `dev` branch -- pre-existing drift, not this PR.
- **Spend**: `custoking-prod.billing_export.gcp_billing_export_v1_014C0A_C6B9AF_5FABC0` (108,675 rows,
  usage through 2026-09-10 17:00Z, exported 21:07Z; ~11.5 h usage lag) and the `_resource_v1_` table
  for per-service attribution. The usage-cost export that was "unfixable server-side" on 2026-08-20
  is delivering; the memory saying otherwise is superseded.
- **Log metrics**: `timeSeries.list` on each `logging.googleapis.com/user/custoking/prod/*` metric,
  7 days, `ALIGN_DELTA`; then the alert's own aggregation (`ALIGN_PERCENTILE_95`, 300 s, `REDUCE_MAX`)
  over the last 3 hours. Data present; values 0.500 = encoded zero.
- **Cost custom metrics**: `custom.googleapis.com/custoking/cost/gross_yesterday` etc. -- 48 hourly
  points in the last 48 h in both projects; `export_available = 1`, `billing_data_grade = 2`.
- **Dev loop**: `run.googleapis.com/container/startup_latencies` count per service (7 d),
  `request_count` by `response_code_class`, `billable_instance_time` by service, Cloud Run service
  config (both envs min 0, request-based billing; dev lacks startup CPU boost), Cloud SQL state
  (`custoking-db-dev` STOPPED, activation NEVER), and the service log (`Application run failed`).
- **Not measured**: the credit *balance* (no API; derived from the 2026-08-20 support figure minus
  export-measured draw, so it may be up to ~INR 520 high if that figure was itself a day stale);
  why `num_backends` reads zero; whether SMS is available for the operators' numbers.

---

## 7. Apply order for this PR (human, after merge)

```powershell
# prod root -- budgets need the Cloud Billing quota project or they 403 naming a random project number
$tok = gcloud auth print-access-token
$env:GOOGLE_OAUTH_ACCESS_TOKEN = $tok
$env:USER_PROJECT_OVERRIDE     = "true"
$env:GOOGLE_BILLING_PROJECT    = "custoking-prod"
terraform -chdir=deploy/gcp/observability init -reconfigure `
  -backend-config="bucket=custoking-prod-terraform-state" `
  -backend-config="prefix=observability/prod" `
  -backend-config="access_token=$tok"
terraform -chdir=deploy/gcp/observability plan  -var-file=custoking-prod.tfvars   # expect 4 add / 6 change / 0 destroy
terraform -chdir=deploy/gcp/observability apply -var-file=custoking-prod.tfvars

# dev root -- add `enable_uptime_checks = false` to custoking-dev.tfvars first (item 2)
$env:GOOGLE_BILLING_PROJECT = "custoking-dev"
terraform -chdir=deploy/gcp/observability init -reconfigure `
  -backend-config="bucket=custoking-dev-terraform-state" `
  -backend-config="prefix=observability/dev" `
  -backend-config="access_token=$tok"
terraform -chdir=deploy/gcp/observability plan  -var-file=custoking-dev.tfvars    # see note below before applying
terraform -chdir=deploy/gcp/observability apply -var-file=custoking-dev.tfvars
```

**Dev state has pre-existing drift unrelated to this PR.** A read-only plan on 2026-09-11 (before the
uptime change) showed 4 add / 15 change / 1 destroy: this PR accounts for the budget update and the
three new policies; the rest is `dev`-branch code that was never applied to the dev root -- seven
`service_p95_latency` policies (the `enabled` gate), `scheduler_failure`, the compliance sink, the
cost-metric job's client label, four dashboards, and the storage-growth policy moving from the retired
`custoking-student-photos-dev` bucket to `custoking-dev-student-photos`. All of it is intended code;
review it in the plan rather than being surprised by it. With `enable_uptime_checks = false` the plan
additionally destroys 7 uptime checks, 7 uptime policies and the service-agent invoker grants.

Then, within a day, confirm each new policy can see its metric (query the series, require non-empty),
and expect the dev budget to notify immediately: dev is at 188% of INR 2,000 until item 2 lands, and
that first email is the fix working, not a false alarm.
