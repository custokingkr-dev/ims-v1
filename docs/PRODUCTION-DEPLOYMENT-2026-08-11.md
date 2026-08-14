# Production Deployment Record - 2026-08-11

> **2026-08-12 reconciliation:** all 14 Cloud Run services remain Ready and use dedicated runtime
> identities. Production SQL remains `RUNNABLE` on zonal `db-g1-small` with encrypted-only connections,
> backups/PITR, deletion protection and `max_connections=200`. Production reporting uses dedicated OIDC
> identity `ims-reporting-push-prod`, but has no DLQ. Production notification still has no subscription or
> DLQ and remains logging/dry-run. See [REMAINING-WORK-2026-08-12.md](REMAINING-WORK-2026-08-12.md) for
> the current launch gates; this file remains the immutable deployment record.

Project: `custoking` (`305630109861`)

Region: `asia-south2`

Production branch: `main`

Deployed application commit: `3b47abe41fed77dcc0cf702372508da61784c855`

Final production configuration commit: `4482ff2a588ce95cead821f85057ff40b672730e`

## 1. Executive status

The reviewed application and infrastructure changes were promoted to production on 2026-08-11. All
seven Cloud Run services completed immutable-digest HIGH/CRITICAL container gates, serial Cloud Deploy
canaries at 5%, 25%, 50%, and stable traffic, post-deployment service verification, and gateway health.
All seven currently serve 100% of traffic from the revisions recorded below.

The deployment also completed the following production hardening:

- seven dedicated Cloud Run runtime service accounts replaced the default Compute Engine identity;
- Cloud Deploy uses a dedicated production deployer with resource-scoped Artifact Registry access and
  `actAs` only on the seven production runtime identities;
- production reporting Pub/Sub push authentication moved from a query-string shared credential to a
  dedicated OIDC identity and exact audience;
- Cloud SQL changed from permissive transport to `ENCRYPTED_ONLY` after every observed client backend was
  proven encrypted, then passed the same audit after enforcement;
- GitHub production release, configuration, and rollback workflows received separate workload identities,
  and the workload identity provider now restricts immutable repository/owner IDs, branch, and exact
  workflow references.

This is a successful production deployment, not a certification to onboard all 100-150 schools at once.
The 10,000-student import path passed in dev, and 200,000-300,000 stored student rows are not themselves a
database-volume concern. The current zonal `db-g1-small` production database has not passed the required
arrival-rate mixed school-day workload. Broad onboarding remains gated on capacity, HA, operational, legal,
provider, and canary-school decisions in section 10.

## 2. Promotion and workflow evidence

| Purpose | Commit | GitHub Actions run | Result |
| --- | --- | --- | --- |
| Initial main CodeQL | `3b47abe41fed` | `31521278352` | passed |
| Expected fail-closed release before config reconciliation | `3b47abe41fed` | `31521278533` | service release correctly blocked |
| Production target/config reconciliation | `3b47abe41fed` | `31521381512` | passed |
| Full seven-service production release | `3b47abe41fed` | `31521611035` | passed |
| Reporting shared-token target change reconciliation | `b966b24426cc` | `31525602077` | passed |
| Platform OIDC configuration release | `4482ff2a588c` | `31525782557` | passed |
| CodeQL after OIDC configuration commit | `4482ff2a588c` | `31525782165` | passed |
| Dedicated configuration WIF canary | `4482ff2a588c` | `31527513872` | passed without a rollout |
| Dedicated release WIF authentication canary | `4482ff2a588c` | `31527680027` | force-cancelled before Cloud Deploy; see section 10 |
| Initial post-release stable-category container scan | `4482ff2a588c` | `31529062330` | seven jobs passed; identified superseded category streams |
| Final container scan and guarded legacy-category cleanup | `0be79e0bda36` | `31529782575` | seven gates and cleanup passed; 0 CRITICAL/HIGH open |

The main production release is available at
https://github.com/custokingkr-dev/ims-v1/actions/runs/31521611035. It scanned all seven exact digests,
uploaded all seven SARIF results, promoted each service serially, verified the resulting revisions and
digests, and passed the public gateway health smoke.

The platform-only release is available at
https://github.com/custokingkr-dev/ims-v1/actions/runs/31525782557. It moved the live reporting endpoint to
OIDC-only mode after the subscription configuration was reconciled, avoiding a fail-open interval.

## 3. Live Cloud Run inventory

Verified read-only after all production changes:

| Service | Live revision | Immutable digest | Runtime identity | Traffic / scaling |
| --- | --- | --- | --- | --- |
| API gateway | `custoking-api-gateway-prod-msp0jrss` | `sha256:bbd049e9ae3c4f31f0c57c9a6248d7bd31553ce1154473ba76cf9f8af68655be` | `ims-api-gateway-prod` | 100%; min 0, max 3, concurrency 80 |
| Billing | `custoking-billing-service-prod-msp0akj3` | `sha256:17000b94e204f981e497f957408bb82c5d4036a9d83ad50924b7235063491eb6` | `ims-billing-prod` | 100%; min 0, max 2, concurrency 80 |
| Frontend | `custoking-frontend-prod-msp0nvv3` | `sha256:ded77b0ad69b84ad02ca8d4a693b29b3080b3c5b80c53bac02e9e29b6ec077e9` | `ims-frontend-prod` | 100%; min 0, max 2, concurrency 80 |
| Identity | `custoking-identity-service-prod-msp00xhk` | `sha256:6fbbce5f58c3b78ad8c2a5340ae7f09be1e90c5ff076580b402c1777ab432150` | `ims-identity-prod` | 100%; min 0, max 2, concurrency 80 |
| Operations | `custoking-operations-service-prod-msp05r0q` | `sha256:d735894f3fc7b6b0dd53004afd5f6671ca268d512a10f354fd8f0815f8e6e39b` | `ims-operations-prod` | 100%; min 0, max 2, concurrency 80 |
| Platform | `custoking-platform-service-prod-msp15r9b` | `sha256:68ede2c3667d6031ec31f9e314c4f7c35cb13f6a2e2843c2e5f5f17203eec96c` | `ims-platform-prod` | 100%; min 0, max 2, concurrency 80 |
| School core | `custoking-school-core-service-prod-msozvpi0` | `sha256:399265ee33b13f568d75a586649efe962e7dbf68e7610e055b5d662a01ea2d02` | `ims-school-core-prod` | 100%; min 0, max 2, concurrency 80 |

All runtime identities are in `@custoking.iam.gserviceaccount.com`. Production startup CPU boost remains
enabled to limit Java cold-start latency while minimum instances remain zero to minimize idle Cloud Run
cost. Only the frontend and gateway retain public invocation; the five Java backend services are private.

## 4. Runtime and deployment IAM

`scripts/configure-runtime-service-accounts.ps1 -Environment prod -Apply -AllowProduction` created and
applied the seven service-specific runtime identities. Permissions were granted by required resource and
service rather than inheriting the default Compute Engine service account's broad project permissions.

Cloud Deploy production targets now use:

- deploy identity: `clouddeploy-prod-deployer@custoking.iam.gserviceaccount.com`;
- project roles: `roles/clouddeploy.jobRunner` and `roles/run.developer`;
- Artifact Registry: `roles/artifactregistry.reader` on repository `custoking` only;
- service-account impersonation: `roles/iam.serviceAccountUser` only on the seven production runtime
  identities.

Three GitHub identities separate workflow duties:

- `github-release-prod@custoking.iam.gserviceaccount.com`;
- `github-config-prod@custoking.iam.gserviceaccount.com`;
- `github-rollback-prod@custoking.iam.gserviceaccount.com`.

The production GitHub Environment variables point to those identities. The Workload Identity Federation
provider condition verifies repository ID `1207086249`, owner ID `274906704`, `main`, and the exact approved
workflow reference for release, configuration, rollback, cost, or recovery operations. The legacy
`github-actions-sa` federation binding remains available as a documented emergency fallback; it is no longer
selected by the normal production environment variables.

## 5. Reporting Pub/Sub OIDC migration

The migration was deliberately performed in two guarded changes:

1. target configuration commit `b966b24426cc0a5df077dc4cd3e483992e24e222` allowed the platform to
   accept OIDC without requiring the old shared query token;
2. after reconciliation, commit `4482ff2a588ce95cead821f85057ff40b672730e` deployed the platform with
   `REPORTING_PUBSUB_REQUIRE_SHARED_TOKEN=false`.

Live subscription `ims-reporting-service-push-prod` now has:

- topic `ims-reporting-events-v1-prod`;
- endpoint `https://custoking-platform-service-prod-l7mhms5c2a-em.a.run.app/api/v1/pubsub/reporting-events`;
- no query string or shared credential in the endpoint;
- OIDC service account `ims-reporting-push-prod@custoking.iam.gserviceaccount.com`;
- audience `https://custoking-platform-service-prod-l7mhms5c2a-em.a.run.app`;
- acknowledgement deadline 30 seconds.

The undelivered-message metric was zero after migration. No production reporting event arrived during the
observation window, so this record does not claim an end-to-end production delivery. The infrastructure,
identity, audience, platform mode, and empty backlog are verified; the first consented production event must
be observed before closing that operational acceptance item.

The unrelated notification ingress remains in shared-token safe mode and production MSG91 delivery remains
logging/dry-run until its separate provider and consent approval.

## 6. Cloud SQL transport and recovery posture

Live instance `custoking-db-prod` is:

- `RUNNABLE`, PostgreSQL on `db-g1-small`;
- private-IP-only (`ipv4Enabled=false`);
- `ENCRYPTED_ONLY`;
- zonal, not regional HA;
- backups enabled, PITR enabled, 14 retained backups and seven transaction-log days;
- deletion protection enabled.

Before enforcement, every five database-backed Cloud Run services used JDBC/Flyway URLs with
`sslmode=require`. The runtime SQL job also used `PGSSLMODE=require`; the gateway smoke SQL job was
reconciled from `disable` to `require`. A PII-free pre-change audit observed 9 client backends, all 9
encrypted and 0 unencrypted. After:

```text
gcloud sql instances patch custoking-db-prod --ssl-mode=ENCRYPTED_ONLY --quiet
```

the fresh audit again observed 9 client backends, 9 encrypted and 0 unencrypted, with no violations.
Evidence is retained locally under `artifacts/cloudsql-transport-prod-20260811T191412467Z.json` and
`artifacts/cloudsql-transport-prod-20260811T191610701Z.json`; these PII-free operational artifacts are
intentionally ignored by Git.

Google documents that encrypted-only mode rejects new unencrypted connections and that existing
unencrypted connections can remain until disconnected. That risk was removed here by proving zero
unencrypted clients immediately before enforcement:
https://docs.cloud.google.com/sql/docs/postgres/configure-ssl-instance.

## 7. Post-deployment verification

The following checks passed after the final service and SQL changes:

- gateway `GET /gateway-health`: HTTP 200 with `UP`;
- frontend root response: HTTP 200;
- seven latest-ready revisions match the recorded exact digests;
- seven services route 100% to the recorded revisions;
- all seven have minimum scale zero and bounded maximum scale;
- no Cloud Run HTTP 5xx was present in the post-deployment 30-minute log query;
- reporting subscription backlog was zero;
- SQL transport audit was compliant before and after enforcement;
- CodeQL passed on the application and final OIDC configuration commits;
- final Trivy run `31529782575` passed all seven HIGH/CRITICAL gates and uploaded seven stable-category
  SARIF analyses. Its guarded cleanup verified all seven analyses at the current commit before deleting the
  six superseded auto-generated analysis categories. This was analysis-history cleanup, not alert dismissal.

After cleanup, the open `main` Trivy inventory is 269 findings: 0 CRITICAL, 0 HIGH, 239 MEDIUM, 30 LOW,
and 0 unknown. MEDIUM/LOW findings remain visible for ownership and remediation.

Two non-request errors were observed: OTLP span-export timeouts from platform and billing. They did not
produce HTTP 5xx or fail health checks, but telemetry egress/export must be repaired and monitored because
missing traces reduce incident visibility.

## 8. Budget and cost state

The live budget `Custoking Monthly Guardrail` is INR 5,000/month for project `305630109861`. Its filter is
`EXCLUDE_ALL_CREDITS`, so it intentionally compares the threshold with gross usage rather than the current
credited invoice subtotal. Thresholds are 50%, 80%, and 100% actual spend plus 100% forecast spend.

The standard billing export, queried after deployment, reported:

| Measure | August 2026 value |
| --- | ---: |
| Gross cost | INR 5,042.06 |
| Credits | INR -5,042.07 |
| Net cost | approximately INR 0.00 |
| Latest usage represented | 2026-08-11 12:00 UTC |
| Latest export timestamp | 2026-08-11 15:40:33 UTC |

The guardrail has therefore been crossed by INR 42.06 gross even though credits currently offset the
payable subtotal. This is expected from the configured credit treatment, not a newly discovered runaway.
The detailed attribution in `docs/GCP-BUDGET-INCIDENT-2026-08-11.md` identifies the dominant causes as the
deliberate multi-million-request dev certification work, production SQL baseline, an already-corrected
production polling incident, temporary dev database sizing, and one-time build activity.

Current containment remains valid: dev SQL is stopped and downsized, dev relay schedules are paused, all
services have zero minimum instances, no load generator is active, Artifact Registry cleanup is enabled,
and no safe redundant secret versions exist. Production SQL must not be stopped merely to clear a budget
alert. The INR 5,000 figure is an alerting guardrail, not a demonstrated operating envelope for 100-150
schools. Google also documents that ordinary Cloud Billing budgets alert but do not cap usage:
https://docs.cloud.google.com/billing/docs/how-to/budgets.

## 9. Rollback record

The exact pre-promotion revisions retained for service rollback are:

| Service | Pre-promotion revision |
| --- | --- |
| API gateway | `custoking-api-gateway-prod-msg1rcnp` |
| Billing | `custoking-billing-service-prod-msg1hrl6` |
| Frontend | `custoking-frontend-prod-msgd3rq4` |
| Identity | `custoking-identity-service-prod-msg17seu` |
| Operations | `custoking-operations-service-prod-msg1cz4c` |
| Platform | `custoking-platform-service-prod-msgczd9t` |
| School core | `custoking-school-core-service-prod-msgcuzgs` |

Use the guarded `CD / Rollback target` workflow for normal rollback. It must select the exact service,
environment `prod`, and approved prior revision, and then re-run health verification. For an emergency
manual traffic rollback, the equivalent operation is:

```text
gcloud run services update-traffic SERVICE --region=asia-south2 --project=custoking --to-revisions=REVISION=100
```

For an OIDC-only application rollback, platform revision
`custoking-platform-service-prod-msp0fds3` is the last platform revision before
`REPORTING_PUBSUB_REQUIRE_SHARED_TOKEN=false`. A complete credential rollback also requires restoring the
captured subscription configuration through the guarded script and an approved secret; never place the old
token in a command line, URL, log, or document.

Only if a verified client incompatibility is caused by encrypted-only SQL transport, revert with:

```text
gcloud sql instances patch custoking-db-prod --ssl-mode=ALLOW_UNENCRYPTED_AND_ENCRYPTED --quiet
```

Then investigate and return to `ENCRYPTED_ONLY`; permissive transport is not an acceptable steady state.
For a GitHub WIF incident, restore production Environment variables to the retained emergency
`github-actions-sa` identity, preserve audit evidence, and repair the dedicated identity before returning
normal releases to service.

## 10. Remaining gates and risks

| Item | Verified current state | Required closure |
| --- | --- | --- |
| Capacity at 100-150 schools | 10k-student import and attendance soak passed in dev, but the closed-loop MixedMorning run reached 99.45% CPU even on 8 vCPU | Define a school-day arrival-rate workload, resolve remaining 5xx/query telemetry, pass it, then choose production DB and autoscaling limits |
| Production database HA | `db-g1-small`, zonal, PITR/backups enabled | Spending/production owners approve size, regional HA, RTO, RPO, and cost envelope before broad rollout |
| Branch protection | GitHub rulesets/classic protection are absent; current operator lacks repository admin | Repository admin must require reviews/checks and negatively test direct/force pushes on `main` and `dev` |
| Container alert closure | Final run `31529782575` passed all seven gates; `main` has 0 CRITICAL/HIGH, 239 MEDIUM and 30 LOW after six superseded analysis streams were deleted without dismissing findings | Assign owners and remediation windows for the visible MEDIUM/LOW backlog; keep scheduled scanning enabled |
| Production job identities | `ims-app-rt-prod` and `ims-gateway-smoke-sql-prod` still use the default Compute identity; a legacy platform invoker binding remains | Create job-specific identities, observe one school day, then remove the legacy binding and broad default-compute roles |
| Reporting delivery | OIDC configuration and zero backlog verified; no event arrived during observation | Observe a consented event, HTTP acknowledgement, projection, and empty backlog |
| Notification/MSG91 | Production notification subscription absent; provider remains logging/dry-run | Approve sender/template/commercials/consent and run a bounded real-recipient canary |
| WIF live proofs | Config identity passed; release identity authenticated and resolved artifacts, but its explicit-SHA canary was force-cancelled before Cloud Deploy; rollback identity not exercised | Add a non-deploying release authorization probe or use the next approved release; execute a controlled rollback drill |
| Monitoring | Health/5xx checks passed; OTLP export timeouts observed; mailbox receipt not human-verified | Repair trace export, verify alert email delivery, and complete 24-hour/one-school-day observation |
| Product/legal operations | Retention, consent, export/offboarding and canary-school acceptance remain external decisions | Named owners approve and record evidence before onboarding waves |
| Branch synchronization | `main` includes two production-only reporting commits; `dev` intentionally remains at `3b47abe41fed` to avoid an unnecessary dev deployment/startup cost | Reconcile the production configuration changes into the next planned dev change without starting billable certification work unintentionally |

Run `31527680027` was force-cancelled because repository dispatch logic treats any explicit `commit_sha` as
a full-fleet rebuild even when `force_full_deploy=false`. It proved federation as
`github-release-prod` and immutable Artifact Registry resolution, but it did not create a Cloud Deploy
release and is not recorded as a deployment success. Latest successful releases remain
`rel-prod-3b47abe41fed-1` for the seven-service fleet and `rel-prod-4482ff2a588c-1` for the platform-only
configuration change.

## 11. Operational conclusion

The production release and the authorized security/configuration cutovers are complete. The system is
healthy for controlled testing and a named canary; it is not yet approved for simultaneous onboarding of
100-150 schools. Do not remove the remaining default-compute permissions, resize SQL, enable real messaging,
raise the budget, or begin broad onboarding without the specific evidence and owners listed above.
