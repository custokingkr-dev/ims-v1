# Reporting Outbox Spike - Findings and Go/No-Go (Phase 3, Task 3.1)

## Outcome: GO

The transactional-outbox -> relay -> Pub/Sub -> event-inbox -> projection pattern was
proven end-to-end on the billing-to-reporting invoice flow and fully decoupled billing
from reporting: billing no longer requires reporting-service (now consolidated into
`platform-service`) to read `billing.superadmin_invoices` directly. Zero remaining reads
of `billing.superadmin_invoices` from the reporting code path -- invoice metrics and the
invoice list endpoint both read `reporting.billing_invoice_read` exclusively. The pattern
is recommended for rollout to the other 12 cross-schema reporting flows, subject to the
hardening items below.

## What the pattern took (billing -> reporting invoice flow)

1. **Transactional outbox table** (`billing.outbox_events`, `V3__outbox_events.sql`) --
   generic envelope columns (`event_key`, `event_type`, `aggregate_type`,
   `aggregate_id`, `school_id`, `payload`, `published_at`, `attempts`), partial index on
   unpublished rows.
2. **Outbox writer** (`OutboxWriter.append`) -- a thin JdbcClient insert that
   participates in the caller existing `@Transactional` connection, so the outbox row
   commits (or rolls back) atomically with the domain write. `BillingInvoiceService`
   calls it from inside `create`/`update`, building the full 15-column invoice
   payload (`appendInvoiceOutbox`).
3. **`@Scheduled` relay + `DomainEventPublisher` seam** (`OutboxRelay`) -- polls
   unpublished rows oldest-first, converts each to a canonical `EventEnvelope`
   (`ims.event-envelope.v1`), publishes via the `DomainEventPublisher` interface (a
   `LoggingDomainEventPublisher` stub in the spike; a real Pub/Sub impl is a prod-deploy
   open item), then marks `published_at` -- publish-before-mark, so a mid-flight crash
   causes a harmless re-publish rather than a silent drop.
4. **Reporting read model + idempotent projection** -- `reporting.billing_invoice_read`
   (`V9__billing_invoice_read.sql`, `V10__billing_invoice_read_detail.sql`, all 15
   invoice columns) populated by `ReportingEventInboxProcessor.projectBillingInvoice`,
   keyed by `upsert(id, ...)` so replays/re-publishes are no-ops.
5. **Read-swap** -- the invoice metrics query and the invoice list endpoint in
   `ReportingReadRepository` were repointed from `billing.superadmin_invoices` to
   `reporting.billing_invoice_read`.
6. **Backfill** (`scripts/backfill-billing-invoice-outbox.sql`, this task) -- seeds the
   read model for pre-existing invoices by emitting outbox rows through the same
   pipeline (not a cross-schema data copy), guarded idempotently by `event_key`.

Reusable recipe for the next flow: owning-service outbox table + writer wired into
the existing write transaction -> relay (can likely be a shared/generic component,
parameterized by schema) -> reporting read table + projection handler keyed by the new
`event_type` -> read-swap the reporting query -> one-off backfill SQL keyed the same way.

## Rollout-hardening items found during the spike

These are NOT blockers for the GO decision but must be addressed before/while rolling
out to the remaining 12 flows:

1. **Relay batch atomicity.** `OutboxRelay.publishBatch()` is one `@Transactional`
   covering the whole batch. If `publisher.publish()` throws on row k, the transaction
   rolls back -- including the `published_at` marks already set for rows 1..k-1 in that
   batch -- causing a redundant re-publish of those rows on the next tick. Harmless today
   because the projection `upsert` is idempotent and the inbox dedupes by `eventId`,
   but wasteful at scale. Fix for rollout: move to a per-row transaction (publish +
   mark in one small transaction per row) or catch-and-skip a failing row so the rest of
   the batch still commits.
2. **No row-locking across relay replicas.** `publishBatch()`'s `SELECT ... WHERE
   published_at IS NULL` has no `FOR UPDATE SKIP LOCKED`. Running multiple relay
   instances (e.g. multiple Cloud Run revisions/replicas) could cause two instances to
   pick up and publish the same row concurrently. Consumer-side dedup by `eventId`
   absorbs this today, but it should be called out explicitly for any multi-instance
   deploy, and `FOR UPDATE SKIP LOCKED` should be added if/when the relay runs with
   `minInstances > 1`.
3. **Missing test coverage for the failure path.** There is currently no test exercising
   "publisher.publish() throws -> row stays unpublished (and, ideally, is retried
   cleanly)". Add one before rollout so the at-least-once guarantee is regression-tested,
   not just documented in a code comment.
4. **Silent no-op on malformed payload.** `ReportingEventInboxProcessor.processBatch()`
   catches `RuntimeException` per event and calls `inbox.markFailed(...)`, but
   `projectBillingInvoice` itself simply returns (with no side effect, no log) when
   `id` is null/blank in the payload -- i.e. a malformed billing payload is marked
   processed with zero observability into the fact that nothing was projected. Fix:
   add a `log.warn(...)` (with the event id) on that early-return path before rollout so
   malformed payloads are visible instead of silently swallowed.
5. **Dead code to clean up.** `BillingInvoiceReadRepository`'s 5-argument `upsert`
   overload (`id, schoolId, status, total, occurredAt`) is unused now that
   `ReportingEventInboxProcessor` always calls the full 15-parameter overload -- remove it
   (or repurpose it if a future flow genuinely needs a partial upsert).

## Effort estimate for the remaining 12 cross-schema flows

Grouped by the schema currently read cross-service by reporting today (per
`docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json`'s `allowedExternalSchemas.reporting-service`:
`attendance`, `catalog`, `fee`, `firefighting`, `notification`, `student`,
`tenant_school`). Sizes assume the shared relay/inbox infrastructure from this spike is
reused as-is (no reinvention per flow); an owning service only adds its own outbox table
+ writer + event type(s), and platform-service adds its own read table(s) + projection
branch(es) + read-swap.

### school-core (owns `student`, `attendance`, `fee`, `catalog`, `tenant_school` - ~8 tables/entities)

- **student** (student directory reads for dashboards/lists) -- **M**. Single entity,
  but reporting reads both aggregate counts (metric) and detail lists (student search/
  filter), so the read model needs to support both access patterns, similar to the
  invoice case but with more filter dimensions (school, zone, class/section).
- **attendance** (daily summaries) -- **S**. Reporting mostly reads pre-aggregated
  daily/period summaries (metric-shaped), not row-level detail -- closest analogue to the
  invoice flow, so the recipe applies almost directly.
- **fee** (assignments/payments) -- **L**. Reporting needs both fee-collection metrics
  (aggregate) and payment-detail joins (which items, which students, which bands) --
  effectively 2-3 event types/read tables (assignment-upserted, payment-recorded) rather
  than one, plus join logic that today happens in a single cross-schema SQL query and
  would need to move into the projection or into a reporting-side join across two owned
  read tables.
- **catalog** (orders/supply/annual plans) -- **M-L**. Multiple related entities (orders,
  order lines, annual plans) feeding one dashboard view; likely 2 event types and 2 read
  tables, with a reporting-side join similar to fee.
- **tenant_school** (schools/zones/classes/sections - mostly reference/dimension data,
  not fact data) -- **S**. Low change frequency, small row counts; likely the easiest of
  the five because reporting mostly needs it as slowly-changing dimension lookups
  (could even be cached/denormalized without much lag concern).

  Overall school-core: **L** (5 sub-domains, at least one of which -- fee -- is
  genuinely multi-entity with joins).

### operations (owns `firefighting`)

- **firefighting** (requests/quotes/approvals lifecycle) -- **M**. One aggregate
  (request) with a multi-step workflow status, so the outbox event is
  status-transition-shaped rather than a single upsert; the read model needs enough
  history/state to answer "how many open/overdue" metrics plus a detail list -- one event
  type, one read table, comparable complexity to the fee flow's simpler axis.

### platform in-service (owns `notification`)

- **notification** (delivery status feeding reporting) -- **S**. Same-service as the
  reporting/platform consolidation (`platform-service` owns both `notification` and
  `reporting` schemas per the Phase 2 consolidation), so this flow does NOT need the
  outbox -> Pub/Sub -> inbox hop at all -- a direct in-process projection (e.g. an
  `@EventListener`/direct repository write inside the same transaction, or a lightweight
  same-schema view) is simpler and lower-latency than standing up an outbox for a
  same-service dependency. Recommend explicitly NOT using the full outbox pattern
  here -- it would be over-engineering a same-service call.

Total: 12 flows is roughly 1 S (attendance) + 1 S (tenant_school) + 1 S (firefighting) + 1 S
(notification, direct-projection shortcut) + 2 M (student, catalog lower bound) + 1 L
(fee) + catalog upper bound M-L. Realistically this is several weeks of sequential
work if one engineer does them one at a time, or can be parallelized across
services since each flow's outbox table/writer is independent per owning service (only
the platform-service side -- read tables + projection + read-swap -- has any shared
contention, and even that is additive per event type).

## Eventual-consistency implications for dashboards

Reporting metrics/lists lag the source-of-truth write by, at minimum, the sum of:

- **Outbox relay fixed-delay** -- default `${billing.outbox.relay.fixed-delay-ms:10000}`
  (10s), i.e. how long an outbox row can sit before the relay's next tick picks it up.
- **Reporting event-projection fixed-delay** -- default
  `${reporting.event-projection.fixed-delay-ms:10000}` (10s), i.e. how long a
  received-but-unprocessed inbox event can sit before the next projection tick.
- Plus real Pub/Sub delivery latency (sub-second typically, not simulated in the spike
  since it used a logging stub publisher).

Worst case is roughly 20s staleness end-to-end (two 10s fixed-delays back-to-back); typical case
much less since both schedulers run independently and continuously. This is acceptable
for reporting/dashboard use cases (not used for real-time transactional decisions), and
is a deliberate trade for full schema decoupling. If a future flow needs tighter
freshness, both delays are independently configurable via env vars
(`REPORTING_EVENT_PROJECTION_FIXED_DELAY_MS`, and the billing-side equivalent) without
code changes.

## Prod-deploy open items (NOT done in this spike -- for the go/deploy step)

1. Confirm or create the Pub/Sub topic and a push subscription targeting
   `platform-service`'s `/reporting-events` endpoint (or equivalent ingress path for the
   event inbox).
2. Implement and wire a real Pub/Sub-backed `DomainEventPublisher` in billing-service,
   replacing (or overriding, via profile/conditional bean) the spike's
   `LoggingDomainEventPublisher`.
3. Run `scripts/backfill-billing-invoice-outbox.sql` against the billing Cloud SQL
   instance once the relay/subscription are live, so pre-existing invoices seed the read
   model.
4. Verify end-to-end: create/update an invoice, confirm an `outbox_events` row appears,
   confirm the relay publishes it, confirm `reporting.billing_invoice_read` reflects it,
   and confirm the invoice metrics/list endpoints in the reporting UI show the updated
   data within the expected staleness window.
5. Apply the rollout-hardening fixes above (per-row relay transactions or
   catch-and-skip, malformed-payload warning log, dead-code removal, failure-path test)
   before or during the first non-spike flow's rollout -- not strictly required to flip
   this one flow live, but should not be deferred past the first additional flow.

## GO/NO-GO recommendation and rollout order

GO. The pattern works, fully decouples billing from reporting for the invoice flow,
and its remaining gaps are known, small, and enumerated above (not open questions).

Recommended rollout order for the remaining 12 flows, cheapest/lowest-risk first to
build confidence and shared tooling before tackling the multi-entity joins:

1. **notification** (platform in-service direct projection -- no outbox needed; validates
   the "same-service, skip the hop" carve-out).
2. **attendance** and **tenant_school** (S, single-entity, metric-shaped or
   slowly-changing dimension -- closest analogues to the proven invoice flow).
3. **firefighting** (M, first multi-step-status flow -- validates the pattern beyond a
   simple upsert).
4. **student** and **catalog** (M/M-L, first flows needing both aggregate-metric and
   detail-list reads with multiple sub-entities).
5. **fee** (L, most complex -- multiple event types, cross-entity joins -- do last so the
   relay/inbox/hardening fixes are already battle-tested on simpler flows first).

Apply the hardening fixes (per-row transaction/catch-and-skip in the relay,
malformed-payload logging, dead-code removal, failure-path test) once, generically,
before step 2, so every subsequent flow inherits the fix rather than each flow needing a
follow-up patch.
