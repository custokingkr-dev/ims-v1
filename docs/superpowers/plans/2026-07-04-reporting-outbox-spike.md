# Reporting Outbox Spike (billing invoices → reporting) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the transactional-outbox → Pub/Sub → reporting-projection → read-model-swap pattern end-to-end on one flow (billing invoices → reporting's invoice metrics), eliminating billing from reporting's cross-schema reads, and produce a go/no-go for rolling out the remaining 12 flows.

**Architecture:** billing writes a `billing.outbox_events` row in the same transaction as each invoice upsert; a `@Scheduled` relay publishes canonical envelopes to Pub/Sub via a `DomainEventPublisher` interface; platform's existing `/reporting-events` inbox pipeline projects `billing.invoice-upserted.v1` into a new `reporting.billing_invoice_read` read model; reporting's invoice metrics read that read model instead of `billing.superadmin_invoices`. Branch/CI-validated with Testcontainers + a stubbed publisher; **prod deploy + Pub/Sub topic/subscription creation are deferred to a controller go/no-go step**.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring `@Scheduled`, Flyway (per-schema), Jackson 3 (`tools.jackson`), Testcontainers, PostgreSQL (`billing` + `reporting` schemas), Google Cloud Pub/Sub (deferred).

**Spec:** `docs/superpowers/specs/2026-07-04-reporting-outbox-spike-design.md` · **Envelope:** `docs/EVENT-ENVELOPE-CONTRACT.md`

## Global Constraints

- **Spike scope = the billing→reporting flow only.** Do NOT touch the other 7 cross-schema sources or generalize into a framework — build billing's concretely.
- **Transactional outbox guarantee:** the `billing.outbox_events` row MUST be written in the **same transaction** as the invoice upsert (atomic — both commit or neither).
- **Idempotent consumer:** the reporting projection is keyed by `eventId` (the inbox already dedups) and the read-model upsert is `ON CONFLICT (id) DO UPDATE` — replaying an event must not duplicate or corrupt.
- **Canonical envelope** per `docs/EVENT-ENVELOPE-CONTRACT.md`: `schemaVersion=ims.event-envelope.v1`, `eventId`, `eventKey`, `eventType`, `eventVersion`, `aggregateType`, `aggregateId`, `occurredAt`, `schoolId`, `payload`. Event type for this flow: **`billing.invoice-upserted.v1`**, `aggregateType=SuperadminInvoice`.
- **Behavior-equivalent read-swap:** reporting's invoice metrics must return the SAME values from `reporting.billing_invoice_read` as from `billing.superadmin_invoices` (given equivalent rows).
- **Relay = a simple `@Scheduled` poller** (at-least-once). No Kafka/Debezium/CDC.
- **No prod deploy / no Pub/Sub infra creation during the spike** — that is the controller's go/no-go step (Task 6), gated on the user's decision.
- **Forward-only Flyway**, in the owning service's schema history: billing outbox = `billing`-schema **V3**; reporting read model = `reporting`-schema **V9**.
- **Branch:** `phase3-reporting-outbox-spike` (created off `main`; spec committed there).
- **Maven (Java 25):** `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"` then `.\mvnw.cmd -f services\<svc>\pom.xml test`. Docker up for Testcontainers.

## File Structure

- billing-service:
  - `src/main/resources/db/migration/V3__outbox_events.sql` — **create** (outbox table).
  - `src/main/java/.../billingservice/outbox/OutboxWriter.java` — **create** (transactional append).
  - `src/main/java/.../billingservice/outbox/DomainEventPublisher.java` — **create** (publish interface).
  - `src/main/java/.../billingservice/outbox/OutboxRelay.java` — **create** (`@Scheduled` poller).
  - `src/main/java/.../billingservice/outbox/LoggingDomainEventPublisher.java` — **create** (default no-Pub/Sub publisher; the real Pub/Sub impl is added at the go/no-go/deploy step).
  - `src/main/java/.../billingservice/application/BillingInvoiceService.java` — **modify** (append outbox in create/update).
  - tests under `src/test/java/.../billingservice/outbox/`.
- platform-service (reporting):
  - `src/main/resources/db/migration/reporting/V9__billing_invoice_read.sql` — **create** (read model).
  - `src/main/java/.../platformservice/application/ReportingEventInboxProcessor.java` — **modify** (add `billing.invoice-upserted.v1` projection).
  - `src/main/java/.../platformservice/persistence/BillingInvoiceReadRepository.java` — **create** (upsert into the read model).
  - `src/main/java/.../platformservice/persistence/ReportingReadRepository.java` — **modify** (read-swap the invoice metrics).
  - `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json` — **modify** (remove `billing` from reporting's allowed list).
  - tests under `src/test/java/.../platformservice/`.
- `docs/REPORTING-OUTBOX-SPIKE-FINDINGS.md` — **create** (Task 5): recipe + effort estimate + GO/NO-GO.

---

### Task 1: Billing outbox table + transactional writer

**Files:**
- Create: `services/billing-service/src/main/resources/db/migration/V3__outbox_events.sql`
- Create: `services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox/OutboxWriter.java`
- Modify: `services/billing-service/src/main/java/com/custoking/ims/billingservice/application/BillingInvoiceService.java`
- Test: `services/billing-service/src/test/java/com/custoking/ims/billingservice/outbox/OutboxWriterIntegrationTest.java`

**Interfaces:**
- Produces: `OutboxWriter.append(String eventType, String eventKey, String aggregateType, String aggregateId, Long schoolId, String payloadJson)` — inserts one `billing.outbox_events` row using the current transaction's connection (so it commits atomically with the caller's transaction). Consumed by `BillingInvoiceService` (Task 1) and the relay reads the table (Task 2).

- [ ] **Step 1: Write the migration**

`services/billing-service/src/main/resources/db/migration/V3__outbox_events.sql`:
```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_key     TEXT,
    event_type    TEXT NOT NULL,
    aggregate_type TEXT,
    aggregate_id  TEXT,
    school_id     BIGINT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload       JSONB NOT NULL,
    published_at  TIMESTAMPTZ,
    attempts      INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Relay poll: only unpublished rows, oldest first.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
```
(This runs in the billing schema — `V3` continues billing's history. `gen_random_uuid()` is available via the built-in `pgcrypto`/`pg_catalog` in PG 13+.)

- [ ] **Step 2: Write the failing integration test**

`OutboxWriterIntegrationTest.java` (Testcontainers Postgres; model container/schema setup on an existing billing integration test if one exists, else on a school-core `*IntegrationTest`):
```java
// Given a BillingInvoiceService-managed transaction that creates an invoice:
// assert that after commit, both the invoice row AND exactly one billing.outbox_events row exist
//   with event_type='billing.invoice-upserted.v1', aggregate_type='SuperadminInvoice', aggregate_id=<invoice id>, published_at IS NULL;
// and that on a forced rollback of the same transaction, NEITHER the invoice NOR the outbox row persist (atomicity).
```
Also a direct `OutboxWriter.append(...)` unit-ish test: after `append` within a `@Transactional` test method, one row exists with the given fields and `published_at IS NULL`.

- [ ] **Step 3: Run it to confirm it fails**

```
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -f services\billing-service\pom.xml "-Dtest=OutboxWriterIntegrationTest" test
```
Expected: FAIL (`OutboxWriter` / outbox table absent).

- [ ] **Step 4: Implement `OutboxWriter`**

`OutboxWriter.java` — a `@Component` using the same `JdbcTemplate`/`DataSource` the billing repos use (read `BillingInvoiceRepository` for the exact JDBC style), inserting a row. It participates in the caller's transaction automatically (Spring-managed `DataSource`/tx):
```java
public void append(String eventType, String eventKey, String aggregateType,
                   String aggregateId, Long schoolId, String payloadJson) {
    jdbc.update(
        "INSERT INTO outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload) " +
        "VALUES (?, ?, ?, ?, ?, ?::jsonb)",
        eventKey, eventType, aggregateType, aggregateId, schoolId, payloadJson);
}
```

- [ ] **Step 5: Hook it into `BillingInvoiceService.create` and `update`**

In `BillingInvoiceService` (both `@Transactional` methods at ~lines 43-49), after the repo returns the `InvoiceRow`, append the outbox event in the same transaction. Read `InvoiceRow` for the exact accessors (id/schoolId/status/total) and build the payload JSON via the service's Jackson 3 `ObjectMapper` (`tools.jackson.databind.ObjectMapper` — inject it):
```java
@Transactional
public InvoiceRow create(Map<String, Object> request) {
    InvoiceRow row = invoices.create(request);
    appendInvoiceOutbox(row);
    return row;
}
// same pattern in update(...)
private void appendInvoiceOutbox(InvoiceRow row) {
    String payload = writeJson(Map.of("id", row.id(), "schoolId", row.schoolId(),
        "status", row.status(), "total", row.total()));  // adapt to InvoiceRow's actual accessors
    outbox.append("billing.invoice-upserted.v1", "InvoiceUpserted:" + row.id(),
        "SuperadminInvoice", String.valueOf(row.id()), row.schoolId(), payload);
}
```
Inject `OutboxWriter` and the `ObjectMapper` via the constructor.

- [ ] **Step 6: Run the test + full billing suite**

```
.\mvnw.cmd -f services\billing-service\pom.xml "-Dtest=OutboxWriterIntegrationTest" test
.\mvnw.cmd -f services\billing-service\pom.xml clean test
```
Expected: the new test PASSES; full suite green at baseline (30) + the new test.

- [ ] **Step 7: Commit**

```bash
git add services/billing-service/src/main/resources/db/migration/V3__outbox_events.sql services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox/OutboxWriter.java services/billing-service/src/main/java/com/custoking/ims/billingservice/application/BillingInvoiceService.java services/billing-service/src/test/java/com/custoking/ims/billingservice/outbox/OutboxWriterIntegrationTest.java
git commit -m "feat(billing): transactional outbox — write invoice-upserted event with each invoice (Phase 3 Task 3.1 spike)"
```

---

### Task 2: Outbox relay (`@Scheduled`) + publisher interface

**Files:**
- Create: `services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox/DomainEventPublisher.java`
- Create: `services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox/LoggingDomainEventPublisher.java`
- Create: `services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox/OutboxRelay.java`
- Test: `services/billing-service/src/test/java/com/custoking/ims/billingservice/outbox/OutboxRelayIntegrationTest.java`

**Interfaces:**
- Consumes: the `billing.outbox_events` rows (Task 1).
- Produces: `DomainEventPublisher.publish(EventEnvelope envelope)` (interface) — the relay builds a canonical envelope from each unpublished row and calls this; the default `LoggingDomainEventPublisher` just logs (the real Pub/Sub impl is added at deploy time). `EventEnvelope` is a record mirroring `docs/EVENT-ENVELOPE-CONTRACT.md` fields.

- [ ] **Step 1: Write the failing test**

`OutboxRelayIntegrationTest.java` (Testcontainers): seed two `billing.outbox_events` rows (`published_at IS NULL`); inject a **capturing stub** `DomainEventPublisher` (records the envelopes it receives); invoke `relay.publishBatch()` (the method `runScheduled` delegates to, so it's callable directly in the test); assert:
- both envelopes were published with `schemaVersion="ims.event-envelope.v1"`, `eventType="billing.invoice-upserted.v1"`, `eventId` = the row id, `aggregateType="SuperadminInvoice"`, and the row's `payload`;
- both rows now have `published_at` set;
- a second `publishBatch()` publishes nothing (already published) — no duplicate.

- [ ] **Step 2: Run it to confirm it fails**

```
.\mvnw.cmd -f services\billing-service\pom.xml "-Dtest=OutboxRelayIntegrationTest" test
```
Expected: FAIL (relay/publisher absent).

- [ ] **Step 3: Implement `DomainEventPublisher` + `EventEnvelope` + `LoggingDomainEventPublisher`**

```java
public interface DomainEventPublisher {
    void publish(EventEnvelope envelope);
}
// EventEnvelope: record(String schemaVersion, String eventId, String eventKey, String eventType,
//   String eventVersion, String aggregateType, String aggregateId, java.time.OffsetDateTime occurredAt,
//   Long schoolId, com.fasterxml.jackson.databind... use tools.jackson JsonNode or String payloadJson)
```
`LoggingDomainEventPublisher implements DomainEventPublisher` (a `@Component` `@ConditionalOnMissingBean(DomainEventPublisher.class)`) that logs the envelope at INFO — this is the default so the app runs without Pub/Sub configured; the real Pub/Sub publisher (added at deploy) will take precedence.

- [ ] **Step 4: Implement `OutboxRelay`**

A `@Component` with a `@Scheduled(fixedDelayString = "${billing.outbox.relay.fixed-delay-ms:10000}")` method `runScheduled()` that calls `publishBatch()`. `publishBatch()`:
```java
@Transactional
public int publishBatch() {
    List<OutboxRow> rows = jdbc.query(
        "SELECT id, event_key, event_type, aggregate_type, aggregate_id, school_id, occurred_at, payload " +
        "FROM outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT ?", rowMapper, batchSize);
    for (OutboxRow r : rows) {
        publisher.publish(toEnvelope(r));                       // eventId = r.id
        jdbc.update("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE id = ?", r.id);
    }
    return rows.size();
}
```
`toEnvelope` sets `schemaVersion="ims.event-envelope.v1"`, `eventId=r.id`, `eventVersion="v1"`, and copies the row fields + payload. (At-least-once: if the process dies after `publish` before the `UPDATE`, the row re-publishes next tick → the consumer dedups by `eventId`.)

- [ ] **Step 5: Run the test + full suite**

```
.\mvnw.cmd -f services\billing-service\pom.xml "-Dtest=OutboxRelayIntegrationTest" test
.\mvnw.cmd -f services\billing-service\pom.xml clean test
```
Expected: PASS; full billing suite green.

- [ ] **Step 6: Commit**

```bash
git add services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox services/billing-service/src/test/java/com/custoking/ims/billingservice/outbox/OutboxRelayIntegrationTest.java
git commit -m "feat(billing): @Scheduled outbox relay publishes canonical envelopes via DomainEventPublisher (Phase 3 Task 3.1 spike)"
```

---

### Task 3: Reporting read model + projection

**Files:**
- Create: `services/platform-service/src/main/resources/db/migration/reporting/V9__billing_invoice_read.sql`
- Create: `services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/BillingInvoiceReadRepository.java`
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/application/ReportingEventInboxProcessor.java`
- Test: `services/platform-service/src/test/java/com/custoking/ims/platformservice/application/BillingInvoiceProjectionIntegrationTest.java`

**Interfaces:**
- Consumes: a `billing.invoice-upserted.v1` envelope arriving via the existing inbox (Task 2 produces these events).
- Produces: rows in `reporting.billing_invoice_read (id, school_id, status, total, occurred_at, updated_at)` — read by `ReportingReadRepository` (Task 4).

- [ ] **Step 1: Write the migration**

`reporting/V9__billing_invoice_read.sql`:
```sql
CREATE TABLE IF NOT EXISTS billing_invoice_read (
    id          TEXT PRIMARY KEY,
    school_id   BIGINT,
    status      TEXT,
    total       NUMERIC,
    occurred_at TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_billing_invoice_read_school_status ON billing_invoice_read (school_id, status);
```
(Runs in the reporting schema. Note whether reporting's other read-model tables are RLS-enabled; match the sibling read-model tables' RLS/tenant-key posture from the V6/V8 `enable_rls` migrations — if reporting read models are RLS-enabled, enable it here too with the same policy shape; if not, leave as-is. Read the existing reporting read-model migrations first.)

- [ ] **Step 2: Write the failing test**

`BillingInvoiceProjectionIntegrationTest.java` (Testcontainers): feed the processor a `billing.invoice-upserted.v1` inbox event (envelope with payload `{id, schoolId, status, total}`) and assert `reporting.billing_invoice_read` has the upserted row; feed the SAME event again (same `eventId`) and a changed-status version → assert idempotent (no dup; status updates on the changed one). Model the harness on the existing reporting inbox/projection tests.

- [ ] **Step 3: Run it to confirm it fails**

```
.\mvnw.cmd -f services\platform-service\pom.xml "-Dtest=BillingInvoiceProjectionIntegrationTest" test
```
Expected: FAIL (read model + projection handler absent).

- [ ] **Step 4: Implement `BillingInvoiceReadRepository` (upsert)**

```java
public void upsert(String id, Long schoolId, String status, java.math.BigDecimal total, OffsetDateTime occurredAt) {
    jdbc.update(
      "INSERT INTO billing_invoice_read (id, school_id, status, total, occurred_at, updated_at) " +
      "VALUES (?, ?, ?, ?, ?, now()) " +
      "ON CONFLICT (id) DO UPDATE SET school_id=EXCLUDED.school_id, status=EXCLUDED.status, " +
      "total=EXCLUDED.total, occurred_at=EXCLUDED.occurred_at, updated_at=now()",
      id, schoolId, status, total, occurredAt);
}
```

- [ ] **Step 5: Add the projection handler in `ReportingEventInboxProcessor`**

Read the processor fully. It dispatches by `eventType`. Add: when `eventType.equals("billing.invoice-upserted.v1")`, parse the envelope's `payload` (`tools.jackson` JsonNode) for `id/schoolId/status/total` and call `billingInvoiceRead.upsert(...)` — IN ADDITION to (or instead of, matching the existing pattern for typed events) the generic command-center-feed record. Keep the existing generic behavior for other event types unchanged. Inject `BillingInvoiceReadRepository`.

- [ ] **Step 6: Run the test + full platform suite**

```
.\mvnw.cmd -f services\platform-service\pom.xml "-Dtest=BillingInvoiceProjectionIntegrationTest" test
.\mvnw.cmd -f services\platform-service\pom.xml clean test
```
Expected: PASS; full platform suite green at baseline (114) + the new test.

- [ ] **Step 7: Commit**

```bash
git add services/platform-service/src/main/resources/db/migration/reporting/V9__billing_invoice_read.sql services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/BillingInvoiceReadRepository.java services/platform-service/src/main/java/com/custoking/ims/platformservice/application/ReportingEventInboxProcessor.java services/platform-service/src/test/java/com/custoking/ims/platformservice/application/BillingInvoiceProjectionIntegrationTest.java
git commit -m "feat(reporting): project billing.invoice-upserted.v1 into reporting.billing_invoice_read (Phase 3 Task 3.1 spike)"
```

---

### Task 4: Read-swap reporting invoice metrics off the cross-schema read

**Files:**
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java`
- Modify: `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json`
- Test: extend `services/platform-service/src/test/java/.../api/ReportingReadControllerTest.java` (or the closest existing reporting-read test)

**Interfaces:**
- Consumes: `reporting.billing_invoice_read` (Task 3).

- [ ] **Step 1: Write/adjust the failing test**

In the reporting-read test, seed `reporting.billing_invoice_read` with a couple of invoices (paid + awaiting payment, with totals) and assert the invoice-metric method returns the same shape/values it used to (`sentThisMonth`/`paid`/`pending`/`totalInvoiced`, global + per-school) — now sourced from the read model. Run it; it fails while the repo still reads `billing.superadmin_invoices` (which the test no longer seeds).

- [ ] **Step 2: Swap the queries**

In `ReportingReadRepository`, change every invoice-metric query `FROM billing.superadmin_invoices` → `FROM reporting.billing_invoice_read` (the `count`/`SUM(total)`/`WHERE LOWER(status)=…`/`WHERE school_id=:schoolId` shapes are identical — only the table changes). Grep to confirm no `billing.superadmin_invoices` reference remains in the file.

- [ ] **Step 3: Remove billing from reporting's allowed cross-schema list**

In `docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json`, remove `"billing"` from `reporting-service`'s `allowedExternalSchemas` array (leave the other 7). This records that reporting no longer reads billing's schema.

- [ ] **Step 4: Run the test + full platform suite + confirm no cross-schema billing read remains**

```
.\mvnw.cmd -f services\platform-service\pom.xml clean test
grep -rn "billing.superadmin_invoices" services/platform-service/src/main
```
Expected: suite green; the grep returns **no matches** in `src/main` (reporting no longer reads billing's schema).

- [ ] **Step 5: Commit**

```bash
git add services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java docs/RUNTIME_SCHEMA_DEPENDENCY_BASELINE.json services/platform-service/src/test
git commit -m "feat(reporting): read invoice metrics from reporting.billing_invoice_read; drop billing cross-schema read (Phase 3 Task 3.1 spike)"
```

---

### Task 5: Backfill helper + go/no-go findings doc

**Files:**
- Create: `scripts/backfill-billing-invoice-outbox.sql`
- Create: `docs/REPORTING-OUTBOX-SPIKE-FINDINGS.md`

**Interfaces:** Consumes the whole pipeline (Tasks 1-4). Produces the seed mechanism + the decision artifact.

- [ ] **Step 1: Write the backfill SQL**

`scripts/backfill-billing-invoice-outbox.sql` — idempotently emit an outbox event for every existing invoice so the read model seeds via the pipeline (not a cross-schema copy). It inserts an `billing.invoice-upserted.v1` outbox row per invoice **only if one isn't already pending/published for that aggregate** (guard on `event_key`):
```sql
INSERT INTO outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'InvoiceUpserted:' || i.id, 'billing.invoice-upserted.v1', 'SuperadminInvoice', i.id::text, i.school_id,
       jsonb_build_object('id', i.id, 'schoolId', i.school_id, 'status', i.status, 'total', i.total)
FROM superadmin_invoices i
WHERE NOT EXISTS (SELECT 1 FROM outbox_events o WHERE o.event_key = 'InvoiceUpserted:' || i.id);
```
(Adapt column names to `superadmin_invoices`' actual columns. Runs in the billing schema. Idempotent via the `NOT EXISTS` guard. Executed by the controller at the deploy step, then the relay publishes them.)

- [ ] **Step 2: Write the go/no-go findings doc**

`docs/REPORTING-OUTBOX-SPIKE-FINDINGS.md`:
- **Outcome:** GO / NO-GO (top line).
- **What the pattern took** (billing flow): the outbox table + transactional writer, the `@Scheduled` relay + `DomainEventPublisher` seam, the reporting read model + projection handler, the read-swap, the backfill — with the reusable recipe.
- **Effort estimate for the remaining 12 flows**, grouped by owning service: school-core (student/attendance/fee/catalog/tenant_school — ~8 tables/entities; note which are single-row-metric reads like invoices vs joins), operations (firefighting), platform in-service (notification — same-service, so a direct projection, no Pub/Sub hop needed). S/M/L each with reasons.
- **Eventual-consistency implications** for dashboards (reporting metrics lag by the relay + projection interval — acceptable for reporting; note the current `fixed-delay` values).
- **Open items for the prod deploy step**: create/confirm the Pub/Sub topic + push subscription → `/reporting-events`; add the real Pub/Sub `DomainEventPublisher` impl in billing; run the backfill; verify an invoice change projects to the read model and the metric reflects it.
- **GO/NO-GO recommendation** + rollout order.

- [ ] **Step 3: Commit**

```bash
git add scripts/backfill-billing-invoice-outbox.sql docs/REPORTING-OUTBOX-SPIKE-FINDINGS.md
git commit -m "docs: reporting-outbox spike backfill + go/no-go findings (Phase 3 Task 3.1 spike)"
```

---

### Task 6: CI validate + go/no-go gate (controller)

**Owner:** controller. The spike is **not merged or deployed** until the user decides on the go/no-go.

- [ ] **Step 1: PR for CI (do NOT merge)**

Push `phase3-reporting-outbox-spike`; open a PR; confirm CI green — `service-test (billing-service)` (outbox writer + relay tests) and `service-test (platform-service)` (projection + read-swap tests), `secret-scan`, `docker-build`, `ci-result`. This validates the pipeline on CI without merging.

- [ ] **Step 2: Present the go/no-go**

Relay `docs/REPORTING-OUTBOX-SPIKE-FINDINGS.md`'s outcome + effort estimate to the user. **STOP** — do not merge, do not deploy, do not create Pub/Sub infra. On a GO, the follow-on (separate step) merges the branch, adds the real Pub/Sub publisher + topic/subscription, deploys billing + platform, runs the backfill, and verifies an invoice projects end-to-end; then plans the rollout of the remaining 12 flows.

- [ ] **Step 3: Record** the spike outcome in the ledger + (on merge later) the prod-state memory.

---

## Self-Review

**Spec coverage:**
- Billing outbox table + transactional writer (spec Component 1) → Task 1. ✓
- `@Scheduled` relay + `DomainEventPublisher` seam (Component 2) → Task 2. ✓
- reporting read model + projection handler, idempotent (Component 3) → Task 3. ✓
- Read-swap + baseline JSON update (Component 4) → Task 4. ✓
- Backfill (Component 5) → Task 5 Step 1. ✓
- Pub/Sub topic/sub + prod deploy DEFERRED to go/no-go (Component 6) → Task 6 (not built during spike). ✓
- Go/no-go findings + gate (spec §Go/no-go) → Task 5 Step 2 + Task 6. ✓
- Constraints: transactional outbox atomicity; idempotent consumer; canonical envelope `ims.event-envelope.v1` + `billing.invoice-upserted.v1`; behavior-equivalent read-swap; simple `@Scheduled` relay; no prod/Pub/Sub during spike; forward-only V3/V9 → Global Constraints + task steps. ✓
- Non-goals respected (billing flow only; no framework; no envelope change). ✓

**Placeholder scan:** the concrete DDL, envelope fields, event type, relay/upsert SQL, and read-swap are fully specified; the few "adapt to `InvoiceRow`'s accessors / `superadmin_invoices` columns / the existing test harness" notes are genuine discovery items (the exact DTO/column names live in files the implementer reads) — not lazy placeholders. No "TODO/handle errors".

**Consistency:** event type `billing.invoice-upserted.v1`, envelope `schemaVersion=ims.event-envelope.v1`, tables `billing.outbox_events` (V3) + `reporting.billing_invoice_read` (V9), the `OutboxWriter.append(...)`/`DomainEventPublisher.publish(...)`/`BillingInvoiceReadRepository.upsert(...)` signatures, and the branch name are consistent across all tasks and match the spec + `EVENT-ENVELOPE-CONTRACT.md`.
