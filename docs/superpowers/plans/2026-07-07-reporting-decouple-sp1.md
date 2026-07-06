# Reporting Decoupling SP1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give school-core an event-emission (transactional outbox) engine and use it to project a local `tenant_school` **reference dimension** (schools, sections, academic years) into reporting, swapping reporting's two pure-`tenant_school` cross-schema reads.

**Architecture:** Mirror the proven billing→reporting outbox pipeline. school-core gets a `tenant_school.outbox_events` table + an `outbox/` package (copied/adapted from billing) + `OutboxWriter.append(...)` calls inside the school/section/academic-year mutation transactions; an `OutboxRelay` publishes to a Pub/Sub topic; reporting's existing push endpoint + inbox + `ReportingEventInboxProcessor` route three new event types into new `reporting.dim_*` read-models; two reporting reads swap to the dims. A Flyway migration seeds the outbox from existing rows (backfill).

**Tech Stack:** Java 25 / Spring Boot 4 / `JdbcClient` / Flyway / GCP Pub/Sub (google-cloud-pubsub) / Spring `@Scheduled`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-07-reporting-decouple-sp1-design.md` — implement verbatim.
- **Mirror billing exactly** for the engine: reference `services/billing-service/src/main/java/com/custoking/ims/billingservice/outbox/` (`EventEnvelope`, `DomainEventPublisher`, `PubSubDomainEventPublisher`, `LoggingDomainEventPublisher`, `OutboxPublisherConfiguration`, `OutboxPublisherStartupCheck`, `OutboxWriter`, `OutboxRelay`) and `billing.outbox_events` (`V3__outbox_events.sql`). Copy the structure; change package to `…schoolcoreservice.outbox`, schema to `tenant_school`, and the topic property to `school-core.outbox.pubsub.topic-id` (env `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID`).
- **Transactional outbox:** `OutboxWriter.append(...)` MUST be called inside the caller's existing `@Transactional` method (same connection/transaction) so an event is emitted iff the domain change commits.
- **Envelope:** `ims.event-envelope.v1`. Event types: `school.upserted.v1`, `school-section.upserted.v1`, `academic-year.upserted.v1`. Event keys: `SchoolUpserted:{id}`, `SchoolSectionUpserted:{id}`, `AcademicYearUpserted:{id}`. Aggregate types: `School`, `SchoolSection`, `AcademicYear`.
- **Idempotency:** reporting dedupes by `eventId` (inbox) and dim upserts are `ON CONFLICT (id) DO UPDATE` (last-writer-wins; acceptable for reference data).
- **Migrations:** school-core `tenant_school` continues from **V7** (highest existing is V6, from the timetable revamp — verify at implementation time). platform-service `reporting` continues its own history (highest is `V10__billing_invoice_read_detail.sql` — verify).
- **RLS:** the school-core `outbox_events` table has **no** tenant RLS policy (system table, mirror `billing.outbox_events`), but grants runtime-role DML + sequence USAGE via the conditional `app_rt` block. The reporting `dim_*` tables mirror `reporting.billing_invoice_read` (`V9__billing_invoice_read.sql`) for RLS/grants exactly.
- **Tests:** backend only (Mockito + Testcontainers), per SP program. Run school-core: `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.3'; $env:Path="$env:JAVA_HOME\bin;$env:Path"; .\mvnw.cmd -f services\school-core-service\pom.xml -q test`; platform: `.\mvnw.cmd -f services\platform-service\pom.xml -q test`. Testcontainers run RLS-exempt — assert projection/read correctness, not RLS filtering.
- **Config lessons (from the billing rollout — do not repeat):** the topic env var name is `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID` (the `_ID` suffix must match the `@ConditionalOnProperty`); the relay Cloud Run service needs **min-instances=1**; the push subscription needs **OIDC auth** to the private reporting endpoint. These are Task 7 (infra), verified by inspection.
- In dev/local with no topic id set, `LoggingDomainEventPublisher` is used — nothing breaks; the outbox rows still get marked published after logging.

---

## File Structure

**school-core-service (create):** `…/db/migration/tenant_school/V7__outbox_events.sql`, `V8__reference_outbox_backfill.sql`; `…/outbox/{EventEnvelope,DomainEventPublisher,PubSubDomainEventPublisher,LoggingDomainEventPublisher,OutboxPublisherConfiguration,OutboxPublisherStartupCheck,OutboxWriter,OutboxRelay}.java`; tests `…/outbox/OutboxRelayTest.java`, `…/persistence/ReferenceEventEmissionIntegrationTest.java`.
**school-core-service (modify):** `pom.xml` (google-cloud-pubsub dep — copy from billing), `…/persistence/SchoolStructureReadRepository.java` (append outbox writes), `src/main/resources/application*.yml` (topic property).
**platform-service (create):** `…/db/migration/reporting/V11__reference_dimensions.sql`; `…/persistence/DimensionProjectionRepository.java`; tests `…/application/DimensionProjectionTest.java`.
**platform-service (modify):** `…/application/ReportingEventInboxProcessor.java` (route 3 new types), `…/persistence/ReportingReadRepository.java` (swap 2 reads).
**deploy (modify, Task 7):** `cloudbuild.yaml` / Cloud Run env + `deploy/gcp/*` Pub/Sub topic + subscription (mirror billing).

---

## PHASE A — school-core outbox engine + emission + backfill

### Task 1: outbox_events table + backfill migration

**Files:** Create `services/school-core-service/src/main/resources/db/migration/tenant_school/V7__outbox_events.sql` and `V8__reference_outbox_backfill.sql`.

- [ ] **Step 1: Write V7.** Read `services/billing-service/src/main/resources/db/migration/V3__outbox_events.sql` first, then create the tenant_school equivalent:

```sql
CREATE TABLE tenant_school.outbox_events (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    event_key      VARCHAR(255) NOT NULL,
    event_type     VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    school_id      BIGINT,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    payload        JSONB        NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ts_outbox_unpublished ON tenant_school.outbox_events (id) WHERE published_at IS NULL;

DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
  GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_school.outbox_events TO app_rt;
  GRANT USAGE, SELECT ON SEQUENCE tenant_school.outbox_events_id_seq TO app_rt;
END IF; END $$;
```
(No RLS — mirror `billing.outbox_events`, which has none.)

- [ ] **Step 2: Write V8 (backfill).** Seed one event per existing row (payload keys must match the emit payloads in Task 3 and the projector in Task 5):

```sql
INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'SchoolUpserted:'||id, 'school.upserted.v1', 'School', id::text, id,
       jsonb_build_object('id',id,'name',name,'shortCode',short_code,'city',city,'state',state,'active',active)
FROM tenant_school.schools;

INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'SchoolSectionUpserted:'||ss.id, 'school-section.upserted.v1', 'SchoolSection', ss.id, ss.school_id,
       jsonb_build_object('id',ss.id,'name',ss.name,'schoolId',ss.school_id,'classId',ss.school_class_id,
                          'className',sc.name,'active',ss.active,'teacherName',ss.teacher_name)
FROM tenant_school.school_sections ss
LEFT JOIN tenant_school.school_classes sc ON sc.id = ss.school_class_id;

INSERT INTO tenant_school.outbox_events (event_key, event_type, aggregate_type, aggregate_id, school_id, payload)
SELECT 'AcademicYearUpserted:'||id, 'academic-year.upserted.v1', 'AcademicYear', id, NULL,
       jsonb_build_object('id',id,'label',label,'active',active)
FROM tenant_school.academic_years;
```

- [ ] **Step 3: Compile + full suite (migrations apply via Testcontainers).**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 4: Commit.**
```bash
git add services/school-core-service/src/main/resources/db/migration/tenant_school/V7__outbox_events.sql services/school-core-service/src/main/resources/db/migration/tenant_school/V8__reference_outbox_backfill.sql
git commit -m "feat(school-core): outbox_events table + reference backfill (SP1)"
```

---

### Task 2: outbox engine (copy/adapt from billing)

**Files:** Create `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/outbox/` (8 classes) + `OutboxRelayTest.java`. Modify `services/school-core-service/pom.xml`.

**Interfaces — Produces:**
- `OutboxWriter.append(String eventType, String eventKey, String aggregateType, String aggregateId, Long schoolId, Map<String,Object> payload)` — inserts into `tenant_school.outbox_events` on the current transaction/connection (used by Task 3).
- `DomainEventPublisher.publish(EventEnvelope)` (stubbable in tests).

- [ ] **Step 1: Add the Pub/Sub dependency.** Copy the `google-cloud-pubsub` (and any Spring GCP) dependency block from `services/billing-service/pom.xml` into `services/school-core-service/pom.xml`. Confirm versions match billing.

- [ ] **Step 2: Copy the 8 outbox classes** from `services/billing-service/.../outbox/` into `…/schoolcoreservice/outbox/`, changing: package → `com.custoking.ims.schoolcoreservice.outbox`; the SQL schema `billing.outbox_events` → `tenant_school.outbox_events`; the config property `billing.outbox.pubsub.topic-id` → `school-core.outbox.pubsub.topic-id` (env `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID`); any billing-specific logging strings → school-core. Keep `EventEnvelope` identical (schemaVersion `ims.event-envelope.v1`). `OutboxWriter` uses `JdbcClient` (match how billing writes; if billing uses JdbcTemplate, keep that). `OutboxRelay` is `@Scheduled`, polls `WHERE published_at IS NULL ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED`, publishes, marks `published_at`.

- [ ] **Step 3: Add `application.yml` property.** In `services/school-core-service/src/main/resources/application.yml` (and any profile file mirroring billing's), add `school-core.outbox.pubsub.topic-id: ${SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID:}` and the `@Scheduled` fixed-delay property if billing externalizes it. Confirm `@EnableScheduling` is present (billing has it; add to a config class if school-core lacks it).

- [ ] **Step 4: Write `OutboxRelayTest`** (mirror billing's relay test if present): insert 2 unpublished rows, run the relay with a capturing stub `DomainEventPublisher`, assert both are published (envelope fields correct) and marked `published_at`. Use the Testcontainers pattern from `services/school-core-service/src/test/java/.../persistence/SchoolStructureIntegrationTest.java`.

- [ ] **Step 5: Run the test → PASS; full suite green.**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 6: Commit.**
```bash
git add services/school-core-service/pom.xml services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/outbox services/school-core-service/src/main/resources/application*.yml services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/outbox
git commit -m "feat(school-core): transactional outbox engine + Pub/Sub relay (SP1)"
```

---

### Task 3: emit reference events from mutations

**Files:** Modify `services/school-core-service/.../persistence/SchoolStructureReadRepository.java`. Create test `…/persistence/ReferenceEventEmissionIntegrationTest.java`.

**Interfaces — Consumes:** `OutboxWriter` (Task 2). Inject it into `SchoolStructureReadRepository` (constructor).

- [ ] **Step 1: Write the failing integration test.** Using the SchoolStructureIntegrationTest bootstrap, `@Autowired` the repository + a `JdbcClient`:

```java
@Test
void createSchoolEmitsSchoolUpsertedInSameTransaction() {
    Map<String,Object> req = Map.of("name","Test School","shortCode","TS","active",true);
    var created = repo.createSchool(req);
    Long id = ((Number) created.get("id")).longValue();
    var rows = jdbc.sql("SELECT event_type, event_key, payload FROM tenant_school.outbox_events WHERE aggregate_type='School' AND aggregate_id=:id")
        .param("id", id.toString()).query((rs,n)->rs.getString("event_type")).list();
    assertThat(rows).contains("school.upserted.v1");
}

@Test
void failedSchoolCreateEmitsNoEvent() {
    long before = countOutbox();
    assertThatThrownBy(() -> repo.createSchool(Map.of()))   // missing required name → throws
        .isInstanceOf(RuntimeException.class);
    assertThat(countOutbox()).isEqualTo(before);            // rolled back with the transaction
}
```

- [ ] **Step 2: Run → FAIL** (no outbox rows emitted).

- [ ] **Step 3: Implement the emits.** In `SchoolStructureReadRepository`, after the INSERT/UPDATE (still inside the `@Transactional` method), call `outbox.append(...)`:
  - `createSchool` (~172) + `updateSchool` (~210): `outbox.append("school.upserted.v1", "SchoolUpserted:"+id, "School", id.toString(), id, Map.of("id",id,"name",name,"shortCode",shortCode,"city",city,"state",state,"active",active))`.
  - The section create (~295) and the structure-edit section updates (~227/283): `outbox.append("school-section.upserted.v1", "SchoolSectionUpserted:"+sectionId, "SchoolSection", sectionId, schoolId, Map.of("id",sectionId,"name",name,"schoolId",schoolId,"classId",classId,"className",className,"active",active,"teacherName",teacherName))` — include `className` (join or the value already in scope).
  - Academic-year: grep the codebase for where `academic_years` rows are INSERTed/activated (`INSERT INTO tenant_school.academic_years` / an activate/toggle path). If such a mutation exists, emit `academic-year.upserted.v1` there. If academic years are **seed-only** (no runtime mutation path), the V8 backfill covers existing rows and no live emit is needed — note this in the report; do not invent a mutation path.
  - Payload keys MUST match V8 and Task 5's projector exactly (`shortCode`, `classId`, `className`, `teacherName`, etc.).

- [ ] **Step 4: Run → PASS; full suite green.**
Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 5: Commit.**
```bash
git add -A services/school-core-service
git commit -m "feat(school-core): emit school/section/year reference events on mutation (SP1)"
```

---

## PHASE B — reporting dims + projection + read-swaps

### Task 4: reporting dimension tables + projection repository

**Files:** Create `services/platform-service/src/main/resources/db/migration/reporting/V11__reference_dimensions.sql` and `…/persistence/DimensionProjectionRepository.java`.

**Interfaces — Produces:** `DimensionProjectionRepository` with `upsertSchool(long id,String name,String shortCode,String city,String state,boolean active)`, `upsertSection(String id,String name,Long schoolId,String classId,String className,boolean active,String teacherName)`, `upsertAcademicYear(String id,String label,boolean active)` (each `ON CONFLICT (id) DO UPDATE … , updated_at=now()`).

- [ ] **Step 1: Write V11.** Read `…/db/migration/reporting/V9__billing_invoice_read.sql` for the RLS/grant pattern, then create the three dims mirroring it:

```sql
CREATE TABLE reporting.dim_school (
    id         BIGINT PRIMARY KEY,
    name       VARCHAR(255),
    short_code VARCHAR(255),
    city       VARCHAR(255),
    state      VARCHAR(255),
    active     BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE reporting.dim_section (
    id          VARCHAR(255) PRIMARY KEY,
    name        VARCHAR(255),
    school_id   BIGINT,
    class_id    VARCHAR(255),
    class_name  VARCHAR(255),
    active      BOOLEAN,
    teacher_name VARCHAR(255),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE reporting.dim_academic_year (
    id         VARCHAR(255) PRIMARY KEY,
    label      VARCHAR(255),
    active     BOOLEAN,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Mirror V9__billing_invoice_read.sql for RLS enable/policy (if any) + the conditional app_rt GRANTs.
```
Copy the exact RLS/`GRANT`/`app_rt` block shape from V9 (whatever it does — enable RLS + policy, or grants-only). Match it.

- [ ] **Step 2: Implement `DimensionProjectionRepository`** (`@Repository`, `JdbcClient`), three `ON CONFLICT (id) DO UPDATE SET …, updated_at = now()` upserts.

- [ ] **Step 3: Compile + full platform suite (migration applies).**
Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 4: Commit.**
```bash
git add services/platform-service/src/main/resources/db/migration/reporting/V11__reference_dimensions.sql services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/DimensionProjectionRepository.java
git commit -m "feat(reporting): tenant_school reference dimension tables + projection repo (SP1)"
```

---

### Task 5: route reference events into the dims

**Files:** Modify `services/platform-service/.../application/ReportingEventInboxProcessor.java`. Create test `…/application/DimensionProjectionTest.java`.

**Interfaces — Consumes:** `DimensionProjectionRepository` (Task 4). Inject it into `ReportingEventInboxProcessor` (constructor).

- [ ] **Step 1: Write the failing test.** Mirror how the billing projection is tested (find `ReportingEventInboxProcessor`'s existing test). Insert a `RECEIVED` inbox row with `eventType='school.upserted.v1'` and a JSON payload `{"id":7,"name":"S7","shortCode":"S7","active":true}`, run `processBatch()`, assert `DimensionProjectionRepository.upsertSchool` was called with id=7 (Mockito) OR (integration) assert a `reporting.dim_school` row exists. Add an idempotency assertion: processing the same event twice yields one row.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement routing.** In `processBatch()`, alongside the `BILLING_INVOICE_UPSERTED` branch, add:
```java
switch (event.eventType()) {
    case "school.upserted.v1" -> projectSchool(event);
    case "school-section.upserted.v1" -> projectSection(event);
    case "academic-year.upserted.v1" -> projectAcademicYear(event);
    default -> { /* billing handled above / ignored */ }
}
```
(Or add three `if` branches matching the existing style.) Implement `projectSchool/Section/AcademicYear` mirroring `projectBillingInvoice`: `readPayload(event.payload())` then `textOrNull`/`longOrNull` extraction → `dims.upsertSchool(...)` etc. Add the three event-type constants.

- [ ] **Step 4: Run → PASS; full platform suite green.**
Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 5: Commit.**
```bash
git add -A services/platform-service
git commit -m "feat(reporting): project school/section/year events into dimensions (SP1)"
```

---

### Task 6: swap the two pure-tenant_school reads to the dims

**Files:** Modify `services/platform-service/.../persistence/ReportingReadRepository.java`. Add a test asserting the swapped reads use the dims.

- [ ] **Step 1: Write the failing test.** Seed `reporting.dim_school` (2 rows, 1 active) and `reporting.dim_academic_year` (1 active). Assert the method backing the active-schools KPI returns the dim count and the active-year helper returns the dim's active year — WITHOUT any `tenant_school` row present (prove it no longer reads tenant_school). Find the public methods (`commandCenterSummary`/`dashboardCommandCenter` for the schools KPI; the three `academic_years WHERE active` inline reads) and target them.

- [ ] **Step 2: Run → FAIL** (still reads tenant_school).

- [ ] **Step 3: Implement.**
  - Replace `SELECT count(*) FROM tenant_school.schools` (~line 731) with `SELECT count(*) FROM reporting.dim_school WHERE active = true` (the KPI is "active schools").
  - Replace the three inline `SELECT id FROM tenant_school.academic_years WHERE active = true LIMIT 1` (~412/493/581) with a shared private helper `private String activeAcademicYearId()` reading `SELECT id FROM reporting.dim_academic_year WHERE active = true LIMIT 1`, and call it at all three sites.
  - Do NOT touch the fee/student name-join queries (they still cross-read `tenant_school` alongside facts — SP2–SP4).

- [ ] **Step 4: Run → PASS; full platform suite green.**
Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q test` → BUILD SUCCESS.

- [ ] **Step 5: Commit.**
```bash
git add -A services/platform-service
git commit -m "feat(reporting): swap active-schools KPI + active-year to local dims (SP1)"
```

---

## PHASE C — infra wiring (config only; verified by inspection)

### Task 7: Pub/Sub topic + subscription + Cloud Run env

**Files:** Modify `cloudbuild.yaml` (+ any Cloud Run deploy config) and `deploy/gcp/*` — mirror the billing outbox topic/subscription assets.

- [ ] **Step 1: Find billing's outbox infra** in `cloudbuild.yaml` / `deploy/gcp/` (grep `BILLING_OUTBOX_PUBSUB_TOPIC`, `reporting-events` subscription, the topic creation). These are the templates.

- [ ] **Step 2: Add the school-core outbox topic + push subscription.** Create a Pub/Sub topic for school-core's outbox, and a **push subscription** on it delivering to reporting's `POST /api/v1/pubsub/reporting-events` with **OIDC auth** + the `X-Reporting-Pubsub-Token` header — mirror the billing topic's subscription exactly. Set the school-core Cloud Run service env `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID` to the new topic id, and **min-instances=1** on school-core (so the relay always runs), mirroring billing.

- [ ] **Step 3: Verify by inspection** (no local runtime test — dev uses the logging publisher). Confirm: env var name is exactly `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID`; the property `school-core.outbox.pubsub.topic-id` reads it; the subscription targets the correct reporting URL with OIDC; min-instances=1 is set. Cross-check against the billing equivalents so the two are structurally identical.

- [ ] **Step 4: Commit.**
```bash
git add cloudbuild.yaml deploy/gcp
git commit -m "chore(deploy): school-core outbox Pub/Sub topic + reporting push subscription (SP1)"
```

---

## Final verification (whole-branch)

- `.\mvnw.cmd -f services\school-core-service\pom.xml -q test` and `-f services\platform-service\pom.xml -q test` → BUILD SUCCESS.
- Grep confirms reporting's active-schools KPI + active-year now read `reporting.dim_*` and NOT `tenant_school.schools`/`tenant_school.academic_years` (the fee/student name-joins may still reference tenant_school — that's expected, SP2–SP4).
- Manual reasoning (no local Pub/Sub): school-core emits on mutation + backfill seeds existing rows; in dev the logging publisher marks them published; once Task 7's topic/subscription are deployed, events flow to reporting's inbox → dims. Note the min-instances=1 + `…_TOPIC_ID` + OIDC requirements in the PR/rollout notes.
