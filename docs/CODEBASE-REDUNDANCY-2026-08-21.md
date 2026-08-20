# Codebase, GCP and CI/CD dive — 2026-08-21

Findings from a systematic pass. Fixed items are marked; the rest are recorded with enough evidence to
act on later without repeating the analysis.

## Fixed

**`Ops / GCP cost controls` had failed every scheduled run since 2026-08-19.** The
`artifact-registry-egress` job's env ended in `|| 'custoking.billing_export.…'` — the pre-split
project. With the variable unset it did not fail loudly; it queried a table this identity could not
read, and after the project was deleted could not exist at all.

The sharper problem: **the fallback defeated its own guard.** The job runs a "Validate cost-report
configuration" step specifically to catch a missing `BILLING_EXPORT_TABLE`, but a default guarantees
the value is always present, so the check passed every time and the failure surfaced later as an opaque
BigQuery error. *A guard placed downstream of a default validates nothing.* The job now skips when no
table is configured and resumes by itself when one is set.

**`SCAN_EVIDENCE_BUCKET || 'custoking-scan-evidence'`** — that bucket returns 404, having died with the
project. Removed; empty is the correct degraded state, because the code already skips the cache and
scans normally. A wrong bucket name is worse than none.

**`migration-operator@custoking-prod` disabled.** It held `roles/cloudsql.client` — standing production
database access — with no repo references and no audited activity. Disabled rather than deleted because
Data Access audit logging is off, so "no activity" is weaker evidence than it looks; a Cloud SQL
connection would not necessarily appear in Admin Activity logs. Disabling is instantly reversible and
fails visibly if something did depend on it. If nothing breaks by roughly 2026-08-28, delete it.

## Checked and deliberately NOT changed

Recorded so nobody re-investigates these.

| Suspicion | Verdict |
| --- | --- |
| 7 scripts hardcode `custoking` | **Correct.** `$Repository = "custoking"` is the Artifact Registry *repository* name, which genuinely is still `custoking`; `$ServicePrefix` matches `custoking-*` service naming. Two others are historical comments. |
| `ARTIFACT_REGISTRY_REPOSITORY \|\| 'custoking'` | **Correct**, same reason. Keep. |
| `PROD_CLOUDSQL_INSTANCE \|\| 'custoking-db-prod'` | **Correct** — verified RUNNABLE in `custoking-prod`. |
| `ims-notification-push-prod`, `ims-reporting-push-prod` look unreferenced | **Live.** Each is attached to a Pub/Sub subscription. A literal grep missed them because Terraform names the *subscription* `ims-reporting-service-push-${env}` while the *service account* is `ims-reporting-push-${env}`. Inconsistent naming, not a defect. |
| `TenantScope` is 33 lines in billing vs 116 in school-core | **Legitimate.** billing-service has zero operator surface (`grep operatorSchools` → nothing), so the extra logic would be dead code there. Only identity and school-core handle operators. |
| 3 scripts referenced by nothing | All committed 2026-08-19 and operational in nature (`abandon-stale-clouddeploy-releases`, `invoke-gcp-microservice-deployment`, `remove-expired-photo-import-sources`). Recent and deliberate; left alone. |

## Open: cross-service duplication

There is **no shared module.** Six services, and infrastructure classes are copy-pasted between them.

**Pure duplication — identical apart from `package`/`import`: 7 classes, 16 redundant files, ~636 lines.**

| Class | Copies | Lines each | Redundant |
| --- | --- | --- | --- |
| `GcpOtlpTraceExporterAuthConfig` | 5 | 86 | 344 |
| `EventEnvelope` | 3 | 38 | 76 |
| `TenantDataSourceConfig` | 4 | 25 | 75 |
| `LoggingDomainEventPublisher` | 3 | 24 | 48 |
| `RuntimeDbRoleGuard` | 2 | 37 | 37 |
| `OutboxPublisherConfiguration` | 3 | 17 | 34 |
| `DomainEventPublisher` | 3 | 11 | 22 |

**Diverged duplication — 18 classes share a name across services with differing bodies.** This is the
more dangerous category, because a fix applied to one copy does not reach the others. `TenantContext`,
`TenantContextFilter` and `TenantScope` each exist as 5 copies in 4 variants. The variance was checked
and is currently *justified* by differing feature surfaces — but nothing enforces that, and the next
change to tenant handling has four places to remember.

The whole outbox cluster (`OutboxRelayTriggerController`, `OutboxPublisherConfiguration`,
`OutboxPublisherStartupCheck`, `OutboxAsyncHealthReporter`, `PubSubDomainEventPublisher`,
`LoggingDomainEventPublisher`, plus tests) is replicated across billing, operations and school-core.

### Why this was not fixed here

Extracting a shared module touches five Maven modules and the build. It is the right change, and it is
not one to make at the end of a long session without running the full test suite. The sequencing that
would make it safe:

1. Start with the **7 pure duplicates** — no behavioural decisions, just relocation.
2. `GcpOtlpTraceExporterAuthConfig` alone removes 344 lines and is pure infrastructure config.
3. Leave the **diverged** classes until last, and unify them only where the variance is provably
   incidental rather than a real difference in what each service needs.
