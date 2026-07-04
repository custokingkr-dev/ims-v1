# Reporting Outbox Spike (billing invoices → reporting) — Design (Phase 3, Task 3.1 spike phase)

**Source task:** `docs/superpowers/plans/2026-06-28-architecture-remediation-program.md` § Phase 3, Task 3.1 (`P1-2`, `[EXPAND]`) — "Reporting outbox + Pub/Sub projections … reporting issues zero cross-schema SQL." **Scope of THIS spec = the spike only:** prove the pattern end-to-end on one flow, then a go/no-go before rolling out the remaining flows.

## Decisions (locked during brainstorming)

1. **Spike one full flow, then go/no-go** — build the complete outbox → Pub/Sub → reporting-projection → read-model-swap pipeline for **billing invoices → reporting's invoice metrics**, then decide whether to roll out the other 12 cross-schema reads.
2. **Flow = billing** — the simplest standalone service; `billing.superadmin_invoices` is reporting's most-referenced cross-schema read (×11); reporting uses it only as aggregate metrics (counts by status + `SUM(total)`, global + per-school) — a clean pattern proof without domain complexity.
3. **Branch/CI-validated during the spike; prod deploy (Pub/Sub topic/sub + service deploys) deferred to the go/no-go** — keeps prod untouched until the pattern is approved (mirrors the SB4 spike).
4. **Relay = a simple `@Scheduled` outbox poller** (at-least-once). The program plan mandates "No Kafka/Debezium at this scale."

## Current state (measured/read 2026-07-04)

- **Inbound event pipeline already exists in platform-service:** `ReportingPubSubPushController` (`POST /reporting-events`) validates the canonical envelope (`schemaVersion=ims.event-envelope.v1`, `eventId`, `eventType`, `aggregateType`, `aggregateId`, `schoolId`, `occurredAt`, `payload`), **dedupes by `eventId`**, and records to `reporting_event_inbox`; `ReportingEventInboxProcessor` projects the inbox into read-model tables (`command_center_*`, `academic_events`). Envelope contract: `docs/EVENT-ENVELOPE-CONTRACT.md`.
- **No outbox/publish side exists anywhere** — grep for `Publisher`/`outbox`/`publishEvent` across all services is empty. Reporting therefore reads 8 external schemas synchronously (13 tables) for its reporting queries.
- **What reporting reads from billing** (`ReportingReadRepository`): only aggregate counts/sums — `SELECT count(*) FROM billing.superadmin_invoices`, `… WHERE LOWER(status)='paid'`, `… WHERE LOWER(status)='awaiting payment'`, `SELECT COALESCE(SUM(total),0) …`, and the same `WHERE school_id = :schoolId` per-school variants.
- **Billing write path:** `BillingInvoiceService` (application) + `BillingInvoiceRepository` (persistence) + `BillingInvoiceController`.
- **Data is tiny** (invoices: single digits) — so this is an architectural-decoupling proof, not a performance need; the read-swap must be behavior-equivalent (same metric values).

## Architecture (the flow)

```
billing-service (write side)
  BillingInvoiceService.create/update(invoice)          [one @Transactional]
    ├─ BillingInvoiceRepository upsert (billing.superadmin_invoices)
    └─ OutboxWriter.append(billing.outbox_events row)   ── same tx, atomic
  OutboxRelay (@Scheduled)                               [separate, at-least-once]
    ── poll unpublished billing.outbox_events → publish canonical envelope
       (eventType billing.invoice-upserted.v1) to Pub/Sub topic → mark published

Pub/Sub topic ── push subscription ──▶ platform-service POST /reporting-events (existing)
                                          ├─ validate envelope + dedup by eventId (existing)
                                          ├─ record to reporting_event_inbox (existing)
                                          └─ ReportingEventInboxProcessor:
                                               NEW handler for billing.invoice-upserted.v1
                                               ── upsert reporting.billing_invoice_read

reporting reads (ReportingReadRepository)
  invoice metrics now: FROM reporting.billing_invoice_read   (was: FROM billing.superadmin_invoices)
```

### Components

1. **Billing outbox table + writer** (billing-service)
   - Flyway migration in the `billing` schema: `billing.outbox_events (id UUID PK, event_key TEXT, event_type TEXT, aggregate_type TEXT, aggregate_id TEXT, school_id BIGINT, occurred_at TIMESTAMPTZ, payload JSONB, published_at TIMESTAMPTZ NULL, attempts INT DEFAULT 0, created_at TIMESTAMPTZ)`; index on `published_at` (partial, `WHERE published_at IS NULL`) for the relay poll.
   - An `OutboxWriter` component: `append(eventType, eventKey, aggregateType, aggregateId, schoolId, payload)` inserts one row. Called by `BillingInvoiceService` create/update **in the same transaction** as the invoice write, so the outbox row commits atomically with the invoice (the transactional-outbox guarantee).
   - Event: `eventType = billing.invoice-upserted.v1`, `aggregateType = SuperadminInvoice`, `aggregateId = <invoice id>`, `eventKey = InvoiceUpserted:<id>`, `payload = {id, schoolId, status, total, …}`.

2. **Outbox relay** (billing-service)
   - A `@Scheduled` poller: select up to N `billing.outbox_events WHERE published_at IS NULL ORDER BY created_at`, build the canonical envelope per `EVENT-ENVELOPE-CONTRACT`, publish to the Pub/Sub topic (with the envelope fields also as Pub/Sub attributes for routing), then `UPDATE published_at = now()`. At-least-once (crash after publish before mark → re-publish → consumer idempotent by `eventId`). Increment `attempts`; leave the row unpublished on publish failure (retried next tick).
   - Publishing is behind an interface (`DomainEventPublisher`) so it can be stubbed in tests and swapped for the real Pub/Sub client.

3. **Reporting read model + projection** (platform-service)
   - Flyway migration in the `reporting` schema: `reporting.billing_invoice_read (id TEXT PK, school_id BIGINT, status TEXT, total NUMERIC, occurred_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)`.
   - Extend `ReportingEventInboxProcessor` with a handler for `billing.invoice-upserted.v1` that **upserts** (`INSERT … ON CONFLICT (id) DO UPDATE`) the read-model row from the payload. Idempotent (inbox already dedups by `eventId`; the upsert is also naturally idempotent).

4. **Read-swap** (platform-service)
   - Change the invoice-metric queries in `ReportingReadRepository` from `FROM billing.superadmin_invoices` → `FROM reporting.billing_invoice_read` (same `count`/`SUM(total)`/status/`school_id` shapes — behavior-equivalent once the read model is populated).
   - Remove `billing` from `reporting-service`'s `allowedExternalSchemas` in `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` (billing only — the other 7 remain until rollout).

5. **Backfill** (one-off, idempotent)
   - Seed `reporting.billing_invoice_read` from existing invoices by **re-emitting outbox events for all current `billing.superadmin_invoices` rows** (an idempotent `INSERT INTO billing.outbox_events SELECT … FROM billing.superadmin_invoices` guarded so it runs once), which the relay publishes → reporting projects. Reuses the pipeline; no one-off cross-schema copy. Runs at the prod-deploy step.

6. **Pub/Sub topic + push subscription** (prod-infra, deferred to go/no-go/deploy)
   - A topic billing's relay publishes to, and a **push subscription** delivering to platform's `/reporting-events` (with the `reporting:ingest` token). Reuse the existing reporting-events topic/subscription if one exists; otherwise create it (gcloud, like the existing `ims-notification-service-push` sub).

## Validation (branch/CI — no prod during the spike)

The real Pub/Sub delivery is already proven in prod by the existing inbound pipeline, so the spike proves the new pieces with focused tests:
- **billing:** `OutboxWriter` inserts a row; `BillingInvoiceService` create/update writes the invoice **and** the outbox row in one transaction (Testcontainers — assert both committed, or neither on rollback); `OutboxRelay` selects unpublished rows, publishes the correct canonical envelope via a stubbed `DomainEventPublisher`, and marks `published_at` (crash-before-mark re-publish covered by the idempotency contract).
- **platform:** the projection handler upserts `reporting.billing_invoice_read` from a `billing.invoice-upserted.v1` envelope and is idempotent on replay (same `eventId` twice → one row); `ReportingReadRepository`'s invoice metrics compute the same values from `reporting.billing_invoice_read` as they did from `billing.superadmin_invoices` (given equivalent seed rows).
- Each service's full suite stays green at its baseline count + the new tests.

## Go/no-go output

A findings doc (`docs/REPORTING-OUTBOX-SPIKE-FINDINGS.md`): what the pattern took (outbox table/writer/relay + projection + read-swap + backfill), the reusable recipe, an **effort estimate for the remaining 12 flows** grouped by owning service (school-core: student/attendance/fee/catalog/tenant_school — ~8 tables; operations: firefighting; platform in-service: notification), the eventual-consistency implications for dashboards, and a **GO/NO-GO** for the rollout. Then (post-decision) the prod deploy of the billing flow as the first rollout increment.

## Testing / rollback

- Behavior-equivalent read-swap: the reporting invoice metrics must return the same values after the swap (verified by the read-model tests + the prod backfill-then-verify at deploy).
- The spike lives on a branch; not deployed until the go/no-go. Rollback = abandon the branch; prod untouched.
- At deploy time, the read-swap is safe because the backfill seeds the read model before/at cutover; if the projection lags, metrics are eventually-consistent (acceptable for reporting/dashboards) — documented.

## Non-goals (YAGNI)

The other 12 cross-schema flows (rollout, post go/no-go); changing the `EVENT-ENVELOPE-CONTRACT`; Kafka/Debezium/CDC; a generic multi-service outbox framework (build billing's concretely; generalize during rollout if warranted); prod deployment during the spike.
