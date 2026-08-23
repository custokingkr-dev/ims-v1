# Production Canary and Async Readiness Evidence — 2026-08-24

Evidence window: 2026-08-23 20:08–20:23 UTC
GCP project: `custoking-prod`
Regions: Cloud Run `asia-south2`, Cloud Scheduler `asia-south1`

This is an execution record, not a broad-production approval. No real notification was sent, no
dead-letter message was pulled or acknowledged, and no named-school data was changed during the
canary checks. A live provider or school canary remains blocked until an approved recipient and
named tenant are supplied.

## Result summary

| Track | Result | Evidence | Remaining gate |
| --- | --- | --- | --- |
| Gateway | Pass | `/gateway-health` returned `UP` at `2026-08-23T20:15:23Z`; machine-readable evidence is in `artifacts/production-canary-readiness-2026-08-24/gateway-health.json` | This proves platform reachability, not a school workflow |
| Async relay | Pass | Four minute-cadence OIDC Scheduler jobs are enabled. After transient IAM-propagation 403s, school-core, operations, billing and platform returned HTTP 200; all four were still 200 in the 20:15 UTC cycle | Observe a real reporting event and retain the event ID/projection reconciliation |
| Outbox/inbox idle state | Pass | Latest structured health logs reported pending/backlog/dead-letter counts of zero for school-core, operations, billing and notification inbox | Idle-zero does not prove poison handling or business-event delivery |
| Reporting Pub/Sub | Topology pass | Push subscription is `ACTIVE`, dedicated OIDC identity is configured, DLQ is attached, inspection subscription is `ACTIVE`, and one-hour Monitoring backlog was zero for source and inspection subscriptions | No DLQ item existed, so replay was inspected but not exercised |
| Notification Pub/Sub | Topology pass | Push subscription is `ACTIVE`, dedicated OIDC identity is configured, DLQ is attached, inspection subscription is `ACTIVE`, and one-hour Monitoring backlog was zero for source and inspection subscriptions | No approved notification producer/recipient canary was configured |
| Provider delivery | Correctly blocked | Production has `NOTIFICATION_DELIVERY_PROVIDER=logging` and `MSG91_DRY_RUN=true`. Secret `msg91-auth-key-prod` has an enabled version, but no canary-recipient configuration or approval artifact exists | Consent, sender/template/commercial approval, bounded recipient and provider receipt reconciliation |
| Named-school canary | Blocked | No `IMS_CANARY_SCHOOL_ID`, `IMS_CANARY_SCHOOL_NAME`, production smoke tokens, or production smoke login credentials were configured; repository search found no signed named-school artifact | Business must name the school, contacts/support window and acceptance owner |
| Recovery | Pass | A guarded production PITR clone reached `RUNNABLE`, produced a schema-only validation export, and completed validation in 603.96 seconds | Business must approve RTO/RPO and the eventual production SQL/HA shape |
| Drive continuity | Pass for deployed access path | A disposable Cloud Run job under the production school-core runtime identity proved read, child-list and write capability on the configured Drive root; the job was deleted | Name data-custody and deletion owners; legacy source project is already `DELETE_REQUESTED` |
| Capacity load | Correctly blocked | Development was seeded to 300,000 students with Query Insights during evidence capture, but governed cost preflight found INR 2,601.07 month-to-date gross cost against the INR 2,000 budget; SQL was then returned to stopped `db-f1-micro` with Insights disabled | Spending owner must explicitly approve two bounded load passes or wait for a new budget window |
| Attendance partitioning | Rehearsal pass | PostgreSQL 16 migrated and rolled back 25,000,000 rows in 761.52 seconds with equal counts/checksums and all uniqueness, FK/check, RLS/bypass, pruning/default/index gates passing | Prototype must become reviewed Flyway DDL and threshold monitoring before production execution |

## Exact async topology observed

Both inspection subscriptions exist under the split-project names:

- `reporting-dead-letter-inspection-prod`
- `notifications-dead-letter-inspection-prod`

The older helper scripts looked only for legacy `ims-*-dead-letter-inspection-prod` names and therefore
reported false negatives. The scripts now resolve the split-project names first and keep the legacy names
as compatible aliases.

Before reconciliation, both source subscriptions used five delivery attempts, no explicit retry-policy
values and a `2678400s` expiration TTL. The guarded production apply was explicitly authorized and completed.
Independent read-back now shows:

| Subscription | State | Attempts | Retry | Retention | Expiry | Ack deadline |
| --- | --- | ---: | --- | --- | --- | ---: |
| `ims-reporting-service-push-prod` | `ACTIVE` | 10 | 10–600 seconds | 7 days | none | 60 seconds |
| `ims-notification-service-push-prod` | `ACTIVE` | 10 | 10–600 seconds | 7 days | none | 30 seconds |

Each source subscription retains its dedicated OIDC service account, expected push endpoint/audience and
attached dead-letter topic. Cloud Monitoring reported zero undelivered messages for both source and both
inspection subscriptions again at 20:23 UTC after reconciliation.

## Replay inspection

The following commands completed successfully in `-InspectOnly` mode:

```powershell
./scripts/replay-pubsub-dead-letter.ps1 -ProjectId custoking-prod -Pipeline reporting -Environment prod -AllowProduction -InspectOnly
./scripts/replay-pubsub-dead-letter.ps1 -ProjectId custoking-prod -Pipeline notifications -Environment prod -AllowProduction -InspectOnly
```

Each resolved its `ACTIVE` inspection subscription and reported zero messages pulled and acknowledged.
Cloud Monitoring independently reported `num_undelivered_messages=0` for both source subscriptions and
both inspection subscriptions at 20:08 UTC. A destructive poison-event test was not manufactured in
production merely to populate a DLQ.

## Scheduler propagation observation

The guarded Scheduler configuration initially received HTTP 403 while the new Cloud Run invoker IAM
bindings propagated. Its configured retry sequence (10-second minimum backoff, three retries) recovered:

- school-core and operations first returned 200 at 20:11:32 UTC;
- platform returned 200 at 20:12:12 UTC;
- billing returned 200 at 20:12:13 UTC; and
- all four targets returned 200 again in the 20:15 UTC scheduled cycle.

`configure-async-relay-scheduler.ps1` now triggers one bounded post-configuration verification run and waits
for a 2xx request from every target, so a successful IAM write followed by only transient 403 requests cannot
be reported as a successful rollout even when the configured cron interval is longer than the wait window.

## Recovery and cleanup evidence

The guarded production recovery drill restored `custoking-db-prod` to a temporary PITR clone, waited until
the clone was `RUNNABLE`, and exported a schema-only validation artifact. Restore readiness took 576.27
seconds and end-to-end validation took 603.96 seconds. The final evidence record is stored locally under
`outputs/prod-readiness/recovery/` and is intentionally ignored by Git.

The drill removed the temporary Cloud SQL instance, validation object, and temporary bucket IAM grant.
Independent post-drill inspection found zero temporary clones and zero residual grants. Earlier failed
attempts also cleaned up; they exposed and led to repairs for split-project bucket naming, native-command
error handling, and null Cloud SQL API error payloads.

## Source and Drive continuity evidence

Production school-core is configured for service-account photo-import credentials. A temporary Cloud Run
job used the actual `ims-school-core-prod` runtime identity and confirmed that its configured Drive root was
reachable, not trashed, listable, and writable. The disposable job was then deleted. No OAuth credential,
secret, Drive item, or browser setting was created or changed.

The legacy source GCP project was already in `DELETE_REQUESTED` state when inspected. This work did not
request, cancel, or otherwise change that state. Formal billing-export, backup/log-retention, and deletion
custody still need named owners even though the deployed Drive access path is independent and verified.

## Capacity cost boundary

The development SQL instance was temporarily reconciled to a two-vCPU shape with Query Insights, and the
least-privilege scale fixture seeded 100 schools and 300,000 students (largest school 10,000) in 78.97
seconds. The load-certification preflight then stopped before k6 because month-to-date development gross
cost was INR 2,601.07 against the INR 2,000 budget. Projected logging volume remained below its guard.

The preflight is deliberately fail-closed. No budget override was inferred from a technical execution
request: the two required arrival-rate passes need explicit spending-owner approval or a new budget window.
After seeding and preflight evidence were captured, `custoking-db-dev` was returned to `db-f1-micro`, Query
Insights was disabled, activation policy was set to `NEVER`, and live read-back reported `STOPPED`.

## Attendance partition rehearsal

The production-volume rehearsal passed with 25,000,000 source, partitioned, and rollback rows in 761.52
seconds. Forward and rollback checksums matched. The run proved global ID and attendance-daily/student
uniqueness, foreign keys, status checks, tenant RLS and controlled bypass, year pruning, the default
partition, 24 child indexes, and rollback reconstruction across four partitions.

Logical measured sizes were 6.01 GiB source, 7.84 GiB partitioned target, 3.46 GiB global identity registry,
and 6.01 GiB rollback, with 11.22 GiB peak coexistence under the low-peak phase order. The final launcher
uses a uniquely named/labeled disposable Docker volume, verifies ownership labels before exact removal, and
guards the actual Docker VHD backing drive. Post-run and cleanup-smoke checks found zero rehearsal
containers, named volumes, or bind paths. No global Docker prune or VHD compaction was performed.

This is prototype rehearsal evidence, not production Flyway DDL. DATA-01 still requires reviewed online
rollout DDL plus active row-count, relation/index-growth, and sequential-scan thresholds before execution.

## Safe next execution

1. Record an approved notification canary recipient by reference (do not commit the address/phone), consent
   basis, sender/template IDs and maximum send count.
2. Record a named school ID/name, school owner, support window and rollback/acceptance owners through the
   protected operations evidence channel.
3. Run the canonical notification under logging/dry-run first and reconcile its inbox, attempt and audit IDs.
4. Enable a single real provider send only after the approval in step 1, then reconcile provider acceptance
   and delivery callback to the same IDs.
5. Run `smoke-deployment-readiness.ps1` against the named tenant without `-RunPhotoUploadSmoke`; add write
   probes only inside the approved school window.
6. Prove reporting DLQ/replay with an approved synthetic event or retain the explicit zero-DLQ block; never
   replay an unknown production item before its cause is understood.
