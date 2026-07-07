# Review-Campaign Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a school admin complete a review campaign once every item is resolved — freezing it as a read-only archive, unlocking a fresh campaign, and making the dashboard "pending review" KPI count only active campaigns.

**Architecture:** A new `completeCampaign` write in school-core (ACTIVE→COMPLETED, guarded "block until all resolved") emits a `student-review-campaign.completed.v1` outbox event; platform-service projects it onto a new `campaign_status` column of `reporting.fact_student_review_item` and restores the `campaign_status = 'ACTIVE'` filter on `pendingReviewCount`. Item edits are frozen on completed campaigns via a repository-level guard mapped to HTTP 409. The frontend gets a "Complete campaign" button.

**Tech Stack:** Java 25 / Spring Boot 4.0.7 (`JdbcClient`, `@Transactional`, Flyway per-schema), transactional outbox → Pub/Sub → reporting inbox projector, React 18 + Vite + TS. Build: `.\mvnw.cmd -f services\<svc>\pom.xml -q test`; frontend `cd frontend; npm run build`; gateway `node --test server.test.js`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-07-review-campaign-completion-design.md`.
- Decisions (verbatim): **Manual** Complete button; **block until all resolved** (Complete allowed only when every item is `COMPLETED`); **freeze items** on completion; **same permission as initiate** (`student:write`), **one-way** (no reopen).
- New event type string is exactly `student-review-campaign.completed.v1`.
- Event payload keys are camelCase: `campaignId`, `schoolId`, `status` (must match the projector's `PayloadJson.textOrNull(payload, "…")` reads).
- Migrations are forward-only; each service has its own Flyway history. Next free versions: school-core `student/V8`, platform `reporting/V21`.
- `CampaignCompletedException` must NOT extend `IllegalArgumentException` (else the existing 400 mapping swallows it before the 409 mapping).
- Complete endpoint path is `POST /api/v1/students/review-campaigns/{campaignId}/complete` (routed by the existing `/api/v1/students/` gateway prefix — do NOT invent an `/api/v1/reviews/…` path).
- Testcontainers run as the `owner` role (RLS-exempt): tests prove domain logic, not RLS scoping.

## Prerequisite — ALREADY DONE

Spec §3.5 (repoint the drawer's status/initiate FE calls from the dead `/dashboard/student-lifecycle/…` to the working school-core `/students/reviews/…` endpoints) was completed and deployed this session (commit `4c77ce6`, verified on dev). **Do not repeat it.** This plan builds on the now-working drawer.

---

### Task 1: Backend completion domain (migration + `completeCampaign` + freeze guard)

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/student/V8__review_campaign_completion.sql`
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CampaignCompletedException.java`
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java` (add `completeCampaign`, add `requireCampaignEditable`, call the guard at the top of `updateReviewItem` and `verifyFullName`)
- Test: `services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/StudentReviewCompletionIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxWriter.append(String eventType, String eventKey, String aggregateType, String aggregateId, Long schoolId, Map<String,Object> payload)`; existing private helpers `row(Object...)`, `idCardStatus(String campaignId)`, `fullNameStatus(String campaignId)`.
- Produces: `StudentReadRepository.completeCampaign(String campaignId, Long actorId) : Map<String,Object>` (returns the campaign status DTO); `CampaignCompletedException extends RuntimeException` thrown by `updateReviewItem`/`verifyFullName` when the owning campaign is `COMPLETED`.

- [ ] **Step 1: Write the failing test harness + first tests**

Create `StudentReviewCompletionIntegrationTest.java`. It migrates only the `student` schema, creates a local `student.outbox_events` table (so `OutboxWriter` has a target without pulling in the tenant_school schema), and constructs the repository with a real `OutboxWriter` pointed at the `student` schema.

```java
package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentReviewCompletionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static StudentReadRepository repo;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("student").defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load().migrate();
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        // OutboxWriter writes to <schema>.outbox_events; point it at the student schema and
        // create a minimal matching table so completeCampaign's append has a target.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS student.outbox_events (
                        id BIGSERIAL PRIMARY KEY,
                        event_key TEXT, event_type TEXT, aggregate_type TEXT, aggregate_id TEXT,
                        school_id BIGINT, payload JSONB, status TEXT DEFAULT 'PENDING',
                        created_at TIMESTAMPTZ DEFAULT now())
                    """);
        } catch (Exception e) { throw new RuntimeException(e); }
        OutboxWriter outbox = new OutboxWriter(jdbc, new ObjectMapper(), "student");
        repo = new StudentReadRepository(jdbc, null, outbox);
    }

    @AfterAll
    static void tearDown() { if (PG != null) PG.stop(); }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM student.student_review_items");
            st.execute("DELETE FROM student.student_review_campaigns");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM student.outbox_events");
        }
    }

    /** Seeds a student, a campaign, and N items with the given per-item statuses; returns campaignId. */
    private String seedCampaign(long schoolId, String status, String reviewType, String... itemStatuses) throws Exception {
        String campaignId = "camp-" + java.util.UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO student.students (admission_no, full_name, school_id) VALUES ('A1','Test'," + schoolId + ")");
            long studentId = jdbc.sql("SELECT id FROM student.students WHERE admission_no='A1'").query(Long.class).single();
            st.execute("INSERT INTO student.student_review_campaigns (id, school_id, review_type, title, status, initiated_at, created_at, updated_at) VALUES ('"
                    + campaignId + "'," + schoolId + ",'" + reviewType + "','T','" + status + "', now(), now(), now())");
            int n = 0;
            for (String s : itemStatuses) {
                st.execute("INSERT INTO student.student_review_items (id, campaign_id, student_id, school_id, status) VALUES ('item-"
                        + campaignId + "-" + (n++) + "','" + campaignId + "'," + studentId + "," + schoolId + ",'" + s + "')");
            }
        }
        return campaignId;
    }

    @Test
    void completeSucceedsWhenAllItemsCompleted() throws Exception {
        String campaignId = seedCampaign(7L, "ACTIVE", "ID_CARD_DETAILS", "COMPLETED", "COMPLETED");
        Map<String, Object> dto = repo.completeCampaign(campaignId, 99L);
        assertThat(dto.get("campaignId")).isEqualTo(campaignId);
        String status = jdbc.sql("SELECT status FROM student.student_review_campaigns WHERE id = :id")
                .param("id", campaignId).query(String.class).single();
        assertThat(status).isEqualTo("COMPLETED");
        Long completedBy = jdbc.sql("SELECT completed_by FROM student.student_review_campaigns WHERE id = :id")
                .param("id", campaignId).query(Long.class).single();
        assertThat(completedBy).isEqualTo(99L);
        Long outboxRows = jdbc.sql("SELECT count(*) FROM student.outbox_events WHERE event_type = 'student-review-campaign.completed.v1' AND aggregate_id = :id")
                .param("id", campaignId).query(Long.class).single();
        assertThat(outboxRows).isEqualTo(1L);
    }

    @Test
    void completeBlockedWhenItemsUnresolved() throws Exception {
        String campaignId = seedCampaign(7L, "ACTIVE", "ID_CARD_DETAILS", "COMPLETED", "PENDING");
        assertThatThrownBy(() -> repo.completeCampaign(campaignId, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("still need review");
        String status = jdbc.sql("SELECT status FROM student.student_review_campaigns WHERE id = :id")
                .param("id", campaignId).query(String.class).single();
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    void completeBlockedWhenNotActive() throws Exception {
        String campaignId = seedCampaign(7L, "COMPLETED", "ID_CARD_DETAILS", "COMPLETED");
        assertThatThrownBy(() -> repo.completeCampaign(campaignId, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void itemEditRejectedOnCompletedCampaign() throws Exception {
        String campaignId = seedCampaign(7L, "COMPLETED", "ID_CARD_DETAILS", "COMPLETED");
        String itemId = jdbc.sql("SELECT id FROM student.student_review_items WHERE campaign_id = :id LIMIT 1")
                .param("id", campaignId).query(String.class).single();
        Map<String, Object> patch = new HashMap<>();
        patch.put("schoolId", 7L);
        patch.put("verifiedPhoto", true);
        assertThatThrownBy(() -> repo.updateReviewItem(itemId, patch))
                .isInstanceOf(CampaignCompletedException.class);
    }
}
```

- [ ] **Step 2: Run the test — verify it fails to compile**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -Dtest=StudentReviewCompletionIntegrationTest test`
Expected: FAIL — `completeCampaign` / `CampaignCompletedException` / `completed_by` column do not exist yet.

- [ ] **Step 3: Add the migration**

Create `services/school-core-service/src/main/resources/db/migration/student/V8__review_campaign_completion.sql`:

```sql
-- Adds campaign-completion columns. status already exists (V1, default 'DRAFT'; the app writes
-- 'ACTIVE'); completion sets it to 'COMPLETED'. Forward-only, no backfill.
ALTER TABLE student.student_review_campaigns
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_by BIGINT;
```

- [ ] **Step 4: Add the exception class**

Create `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CampaignCompletedException.java`:

```java
package com.custoking.ims.schoolcoreservice.persistence;

/**
 * Thrown when a review item is edited while its owning campaign is COMPLETED (frozen).
 * Deliberately extends RuntimeException, NOT IllegalArgumentException, so controllers can map it
 * to HTTP 409 (Conflict) instead of the 400 the IllegalArgumentException handler produces.
 */
public class CampaignCompletedException extends RuntimeException {
    public CampaignCompletedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Add `completeCampaign` and the freeze guard to `StudentReadRepository`**

Add these methods (place `completeCampaign` near the other review methods, e.g. after `verifyFullName` around line 799):

```java
    @Transactional
    public Map<String, Object> completeCampaign(String campaignId, Long actorId) {
        Map<String, Object> campaign = jdbc.sql("""
                        SELECT status, school_id, review_type
                        FROM student.student_review_campaigns
                        WHERE id = :campaignId
                        """)
                .param("campaignId", campaignId)
                .query((rs, rowNum) -> row(
                        "status", rs.getString("status"),
                        "schoolId", rs.getLong("school_id"),
                        "reviewType", rs.getString("review_type")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Review campaign not found"));
        if (!"ACTIVE".equals(campaign.get("status"))) {
            throw new IllegalArgumentException("This campaign is not active");
        }
        long unresolved = jdbc.sql("""
                        SELECT count(*) FROM student.student_review_items
                        WHERE campaign_id = :campaignId AND status <> 'COMPLETED'
                        """)
                .param("campaignId", campaignId)
                .query(Long.class)
                .single();
        if (unresolved > 0) {
            throw new IllegalArgumentException(unresolved
                    + " item(s) still need review — resolve them before completing the campaign.");
        }
        jdbc.sql("""
                        UPDATE student.student_review_campaigns
                        SET status = 'COMPLETED', completed_at = now(), completed_by = :actorId, updated_at = now()
                        WHERE id = :campaignId
                        """)
                .param("actorId", actorId)
                .param("campaignId", campaignId)
                .update();
        Long schoolId = ((Number) campaign.get("schoolId")).longValue();
        outbox.append("student-review-campaign.completed.v1", "StudentReviewCampaignCompleted:" + campaignId,
                "StudentReviewCampaign", campaignId, schoolId,
                row("campaignId", campaignId, "schoolId", schoolId, "status", "COMPLETED"));
        return "ID_CARD_DETAILS".equals(campaign.get("reviewType"))
                ? idCardStatus(campaignId)
                : fullNameStatus(campaignId);
    }

    /** Rejects a mutation when the item's owning campaign is COMPLETED (frozen archive). */
    private void requireCampaignEditable(String itemId) {
        String campaignStatus = jdbc.sql("""
                        SELECT c.status
                        FROM student.student_review_items i
                        JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                        WHERE i.id = :itemId
                        """)
                .param("itemId", itemId)
                .query(String.class)
                .optional()
                .orElse(null);
        if ("COMPLETED".equals(campaignStatus)) {
            throw new CampaignCompletedException("This campaign is completed and read-only.");
        }
    }
```

Then add the guard call as the FIRST statement inside `updateReviewItem` (line ~680) and `verifyFullName` (line ~741), immediately after the method opening brace:

```java
        requireCampaignEditable(itemId);
```

- [ ] **Step 6: Run the tests — verify they pass**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -Dtest=StudentReviewCompletionIntegrationTest test`
Expected: PASS — all four tests green.

- [ ] **Step 7: Commit**

```bash
git add services/school-core-service/src/main/resources/db/migration/student/V8__review_campaign_completion.sql \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CampaignCompletedException.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/StudentReadRepository.java \
        services/school-core-service/src/test/java/com/custoking/ims/schoolcoreservice/persistence/StudentReviewCompletionIntegrationTest.java
git commit -m "feat(school-core): completeCampaign + freeze item edits on completed review campaigns"
```

---

### Task 2: Controller wiring (complete endpoint + 409 mapping)

**Files:**
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/StudentReadController.java` (add the complete endpoint; add `CampaignCompletedException`→409 to `execute()`)
- Modify: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/StudentWorkspaceCompatibilityController.java` (add `CampaignCompletedException`→409 to `updateReviewItem`)

**Interfaces:**
- Consumes: `StudentReadRepository.completeCampaign(String, Long)`, `CampaignCompletedException`, existing `execute(Command)`, `requireToken(String, String)`, `TenantContext.get().userId()`.
- Produces: `POST /api/v1/students/review-campaigns/{campaignId}/complete` returning the status DTO; item-edit endpoints returning HTTP 409 when the campaign is completed.

- [ ] **Step 1: Confirm the gateway already routes the path**

Run: `grep -n "api/v1/students'" services/api-gateway/server.js` (expect `route('student', '/api/v1/students/')` and `route('student', '/api/v1/students')`). The complete path `/api/v1/students/review-campaigns/{id}/complete` is a subpath of `/api/v1/students/` → already routed. No gateway change.

- [ ] **Step 2: Add the complete endpoint to `StudentReadController`**

Add after the `verifyFullName` endpoint (around line 309). `StudentReadController` is `@RequestMapping("/api/v1/students")`, so the method path is `/review-campaigns/{campaignId}/complete`:

```java
    @PostMapping("/review-campaigns/{campaignId}/complete")
    public Map<String, Object> completeCampaign(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable String campaignId) {
        requireToken(token, "student:write");
        Long actorId = TenantContext.get().userId();
        return execute(() -> students.completeCampaign(campaignId, actorId));
    }
```

- [ ] **Step 3: Map `CampaignCompletedException`→409 in `execute()`**

Modify `execute(Command command)` (around line 393). Add the `CampaignCompletedException` catch BEFORE the `IllegalArgumentException` catch, and add the import `import com.custoking.ims.schoolcoreservice.persistence.CampaignCompletedException;`:

```java
    private Map<String, Object> execute(Command command) {
        try {
            return command.run();
        } catch (CampaignCompletedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
```

- [ ] **Step 4: Map `CampaignCompletedException`→409 in the compat controller**

In `StudentWorkspaceCompatibilityController.updateReviewItem` (line ~87), add the catch before the existing `IllegalArgumentException` catch, plus the import `import com.custoking.ims.schoolcoreservice.persistence.CampaignCompletedException;`:

```java
        try {
            return students.updateReviewItem(itemId, mutableRequest);
        } catch (CampaignCompletedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
```

Then run `grep -rn "student-review-items" services/school-core-service/src/main/java` — if any OTHER controller maps a `/student-review-items/…/full-name-verification` path, add the same 409 catch there. (If none exists, the FE's full-name compat call already lacks a handler — out of scope here; note it and move on.)

- [ ] **Step 5: Compile**

Run: `.\mvnw.cmd -f services\school-core-service\pom.xml -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/StudentReadController.java \
        services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/compat/StudentWorkspaceCompatibilityController.java
git commit -m "feat(school-core): complete-campaign endpoint + map CampaignCompletedException to 409"
```

---

### Task 3: Reporting projection of campaign completion

**Files:**
- Create: `services/platform-service/src/main/resources/db/migration/reporting/V21__review_campaign_status.sql`
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/StudentReviewFactReadRepository.java` (add `updateCampaignStatus`)
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/application/projection/StudentReviewProjector.java` (handle the completed event)
- Test: `services/platform-service/src/test/java/com/custoking/ims/platformservice/application/StudentReviewFactProjectionIntegrationTest.java` (add a completion-projection test)

**Interfaces:**
- Consumes: `ReportingEventInboxRepository.ReportingEventInboxProjectionRow` (has `eventType()` and `payload()`), `PayloadJson.textOrNull`, existing `StudentReviewFactReadRepository.upsert(String,Long,String,String)`.
- Produces: `StudentReviewFactReadRepository.updateCampaignStatus(String campaignId, String status)`; `StudentReviewProjector` now also handles `student-review-campaign.completed.v1`.

- [ ] **Step 1: Write the failing test**

Add to `StudentReviewFactProjectionIntegrationTest.java`. First add a helper mirroring `feedReviewItemEvent` but for the completion event:

```java
    private void feedCampaignCompletedEvent(String eventId, long schoolId, String campaignId) {
        String payload = "{\"campaignId\":\"" + campaignId + "\",\"schoolId\":" + schoolId + ",\"status\":\"COMPLETED\"}";
        String envelope = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"student-review-campaign.completed.v1\","
                + "\"payload\":" + payload + "}";
        inbox.record(new ReportingEventInboxRecord(
                eventId, null, "student-review-campaign.completed.v1", "v1", "StudentReviewCampaign", campaignId, schoolId,
                null, Optional.of(OffsetDateTime.now()), OffsetDateTime.now(), envelope, payload));
    }
```

Then the test:

```java
    @Test
    void campaignCompletedEvent_flipsCampaignStatusForAllItemsOfThatCampaign() throws Exception {
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-a", 7L, "camp-x", "PENDING");
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-b", 7L, "camp-x", "COMPLETED");
        feedReviewItemEvent(UUID.randomUUID().toString(), "item-c", 7L, "camp-y", "PENDING");
        processor.processBatch();

        feedCampaignCompletedEvent(UUID.randomUUID().toString(), 7L, "camp-x");
        int processed = processor.processBatch();
        assertEquals(1, processed);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT campaign_id, campaign_status FROM reporting.fact_student_review_item ORDER BY id")) {
            try (ResultSet rs = ps.executeQuery()) {
                int completedCamp = 0, activeCamp = 0;
                while (rs.next()) {
                    if ("camp-x".equals(rs.getString("campaign_id"))) {
                        assertEquals("COMPLETED", rs.getString("campaign_status"));
                        completedCamp++;
                    } else {
                        assertEquals("ACTIVE", rs.getString("campaign_status"));
                        activeCamp++;
                    }
                }
                assertEquals(2, completedCamp, "both camp-x items flip to COMPLETED");
                assertEquals(1, activeCamp, "camp-y stays ACTIVE");
            }
        }
    }
```

- [ ] **Step 2: Run the test — verify it fails**

Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q -Dtest=StudentReviewFactProjectionIntegrationTest test`
Expected: FAIL — `campaign_status` column missing and the projector ignores the new event type.

- [ ] **Step 3: Add the migration**

Create `services/platform-service/src/main/resources/db/migration/reporting/V21__review_campaign_status.sql`:

```sql
-- Adds campaign_status to the review-item fact so pendingReviewCount can exclude non-active
-- campaigns (restoring the filter dropped in SP7 phase 3). Existing rows default to 'ACTIVE',
-- correct because every pre-existing campaign is ACTIVE. Set by the
-- student-review-campaign.completed.v1 projection; the item upsert never touches this column
-- (ON CONFLICT DO UPDATE lists only school_id/campaign_id/status), so completion is order-safe.
ALTER TABLE fact_student_review_item
    ADD COLUMN IF NOT EXISTS campaign_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_fact_student_review_item_campaign
    ON fact_student_review_item (campaign_id);
```

- [ ] **Step 4: Add `updateCampaignStatus` to the fact repository**

Add to `StudentReviewFactReadRepository`:

```java
    @Transactional
    public void updateCampaignStatus(String campaignId, String status) {
        jdbc.sql("""
                        UPDATE reporting.fact_student_review_item
                        SET campaign_status = :status, updated_at = now()
                        WHERE campaign_id = :campaignId
                        """)
                .param("status", status)
                .param("campaignId", campaignId)
                .update();
    }
```

- [ ] **Step 5: Handle the completed event in `StudentReviewProjector`**

Replace the class body's constant + `handledEventTypes()` + `project(...)` so it branches on `event.eventType()`:

```java
    private static final String STUDENT_REVIEW_ITEM_UPSERTED = "student-review-item.upserted.v1";
    private static final String STUDENT_REVIEW_CAMPAIGN_COMPLETED = "student-review-campaign.completed.v1";

    @Override
    public Set<String> handledEventTypes() {
        return Set.of(STUDENT_REVIEW_ITEM_UPSERTED, STUDENT_REVIEW_CAMPAIGN_COMPLETED);
    }

    @Override
    public boolean feedWorthy() {
        return false;
    }

    @Override
    public void project(ReportingEventInboxRepository.ReportingEventInboxProjectionRow event) {
        JsonNode payload = PayloadJson.readPayload(objectMapper, event.payload());
        if (STUDENT_REVIEW_CAMPAIGN_COMPLETED.equals(event.eventType())) {
            String campaignId = PayloadJson.textOrNull(payload, "campaignId");
            if (campaignId != null && !campaignId.isBlank()) {
                String status = PayloadJson.textOrNull(payload, "status");
                facts.updateCampaignStatus(campaignId, status == null ? "COMPLETED" : status);
            }
            return;
        }
        String id = PayloadJson.textOrNull(payload, "id");
        if (id == null || id.isBlank()) {
            return;
        }
        Long schoolId = PayloadJson.longOrNull(payload, "schoolId");
        String campaignId = PayloadJson.textOrNull(payload, "campaignId");
        String status = PayloadJson.textOrNull(payload, "status");
        facts.upsert(id, schoolId, campaignId, status);
    }
```

- [ ] **Step 6: Run the test — verify it passes**

Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q -Dtest=StudentReviewFactProjectionIntegrationTest test`
Expected: PASS — new completion test green, existing item-upsert tests still green.

- [ ] **Step 7: Commit**

```bash
git add services/platform-service/src/main/resources/db/migration/reporting/V21__review_campaign_status.sql \
        services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/StudentReviewFactReadRepository.java \
        services/platform-service/src/main/java/com/custoking/ims/platformservice/application/projection/StudentReviewProjector.java \
        services/platform-service/src/test/java/com/custoking/ims/platformservice/application/StudentReviewFactProjectionIntegrationTest.java
git commit -m "feat(reporting): project student-review-campaign.completed onto fact campaign_status"
```

---

### Task 4: Restore the `pendingReviewCount` KPI filter

**Files:**
- Modify: `services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java` (`dashboardCommandCenter` → `pendingReviewCount` query)
- Test: `services/platform-service/src/test/java/com/custoking/ims/platformservice/persistence/ReportingFactReadIntegrationTest.java` (add a KPI-exclusion test)

**Interfaces:**
- Consumes: `reporting.fact_student_review_item` now has `campaign_status` (Task 3).
- Produces: `pendingReviewCount` counts only `campaign_status = 'ACTIVE'` PENDING items.

- [ ] **Step 1: Write the failing test**

Add to `ReportingFactReadIntegrationTest.java` (this test migrates the reporting schema and exercises `ReportingReadRepository`; follow its existing setup for seeding rows and calling `dashboardCommandCenter`). Add:

```java
    @Test
    void pendingReviewCount_excludesCompletedCampaigns() throws Exception {
        // Two PENDING items: one in an ACTIVE campaign, one in a COMPLETED campaign.
        jdbcClient.sql("INSERT INTO reporting.fact_student_review_item (id, school_id, campaign_id, status, campaign_status) VALUES "
                + "('r1', :s, 'c-active', 'PENDING', 'ACTIVE'), ('r2', :s, 'c-done', 'PENDING', 'COMPLETED')")
                .param("s", TEST_SCHOOL_ID).update();

        Map<String, Object> dash = repo.dashboardCommandCenter(TEST_SCHOOL_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> lifecycle = (Map<String, Object>) dash.get("lifecycle");
        assertThat(((Number) lifecycle.get("pendingReviewCount")).intValue()).isEqualTo(1);
    }
```

> Note: reuse the file's existing constant for the seeded school id and its `repo` field; if the existing tests use a different accessor for the dashboard sub-map, match it. The `lifecycle.pendingReviewCount` path is set by `dashboardCommandCenterRow(...)` in `ReportingReadRepository`.

- [ ] **Step 2: Run the test — verify it fails**

Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q -Dtest=ReportingFactReadIntegrationTest test`
Expected: FAIL — count is 2 (both PENDING items counted), expected 1.

- [ ] **Step 3: Add the filter**

In `ReportingReadRepository.dashboardCommandCenter`, replace the `pendingReviewCount` query (and its phase-3 invariant-note comment) with the filtered version:

```java
        // pendingReviewCount counts only ACTIVE campaigns' pending items. campaign_status is
        // projected from student-review-campaign.completed.v1 (reporting V21), so completed
        // campaigns' still-PENDING items no longer inflate this KPI.
        long pendingReviewCount = count("""
                SELECT count(*)
                FROM reporting.fact_student_review_item
                WHERE school_id = :schoolId
                  AND status = 'PENDING'
                  AND campaign_status = 'ACTIVE'
                """, schoolId);
```

- [ ] **Step 4: Run the test — verify it passes**

Run: `.\mvnw.cmd -f services\platform-service\pom.xml -q -Dtest=ReportingFactReadIntegrationTest test`
Expected: PASS — count is 1.

- [ ] **Step 5: Commit**

```bash
git add services/platform-service/src/main/java/com/custoking/ims/platformservice/persistence/ReportingReadRepository.java \
        services/platform-service/src/test/java/com/custoking/ims/platformservice/persistence/ReportingFactReadIntegrationTest.java
git commit -m "feat(reporting): pendingReviewCount excludes completed campaigns via campaign_status"
```

---

### Task 5: Frontend "Complete campaign" button

**Files:**
- Modify: `frontend/src/api/dashboardCommandCenterApi.ts` (add `completeReviewCampaign`)
- Modify: `frontend/src/pages/workspace/dashboard/drawers/StudentReviewDrawer.tsx` (Complete button + handler in both tabs)

**Interfaces:**
- Consumes: `POST /students/review-campaigns/{campaignId}/complete` (Task 2). Status endpoints already repointed (prerequisite, done).
- Produces: a Complete button, enabled only when `status.completed === status.totalStudents`, that completes the campaign and reloads status.

No frontend tests (repo convention for these drawers).

- [ ] **Step 1: Add the API function**

In `frontend/src/api/dashboardCommandCenterApi.ts`, after `verifyFullName` (around line 152):

```ts
export async function completeReviewCampaign(campaignId: string): Promise<void> {
  await api.post(`/students/review-campaigns/${campaignId}/complete`, {});
}
```

- [ ] **Step 2: Wire the button into `IdCardTab`**

In `StudentReviewDrawer.tsx`, import `completeReviewCampaign` alongside the other imports from `dashboardCommandCenterApi`. In `IdCardTab`, add a handler and a button. Add the handler near `handleInitiate`:

```tsx
  const [completing, setCompleting] = useState(false);
  const handleComplete = async () => {
    if (!status?.campaignId) return;
    if (!window.confirm('Complete this campaign? It will be locked and can’t be edited.')) return;
    setCompleting(true);
    setError(null);
    try {
      await completeReviewCampaign(status.campaignId);
      await loadStatus();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(msg ?? 'Failed to complete campaign.');
    } finally {
      setCompleting(false);
    }
  };
```

Then, in the active-campaign render (inside the `return (` that shows the summary, e.g. just after the progress `<div>` block ends near line 191), add:

```tsx
      {canWrite && (
        <div style={{ marginBottom: 16, textAlign: 'right' }}>
          <button
            onClick={handleComplete}
            disabled={completing || status.completed !== status.totalStudents}
            title={status.completed !== status.totalStudents
              ? `${status.totalStudents - status.completed} student(s) still to review`
              : undefined}
            style={{ padding: '8px 16px', borderRadius: 6, border: 'none', fontWeight: 600,
                     cursor: (completing || status.completed !== status.totalStudents) ? 'default' : 'pointer',
                     background: (completing || status.completed !== status.totalStudents) ? '#c5cae9' : '#1a6840',
                     color: '#fff' }}>
            {completing ? 'Completing…' : 'Complete campaign'}
          </button>
          {status.completed !== status.totalStudents && (
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
              {status.totalStudents - status.completed} student(s) still to review
            </div>
          )}
        </div>
      )}
```

- [ ] **Step 3: Wire the button into `FullNameTab`**

Repeat Step 2's handler and button inside `FullNameTab`. It has the same `status.campaignId`, `status.totalStudents`, `status.completed` fields available (the full-name status DTO includes `confirmed`/`correctionRequested`; completion still gates on `completed === totalStudents` — the status DTO's `completed` equals `confirmed` count of fully-completed items). Use the identical handler and button code from Step 2 (paste it in full; do not reference Step 2).

Handler:

```tsx
  const [completing, setCompleting] = useState(false);
  const handleComplete = async () => {
    if (!status?.campaignId) return;
    if (!window.confirm('Complete this campaign? It will be locked and can’t be edited.')) return;
    setCompleting(true);
    setError(null);
    try {
      await completeReviewCampaign(status.campaignId);
      await loadStatus();
    } catch (e: unknown) {
      const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(msg ?? 'Failed to complete campaign.');
    } finally {
      setCompleting(false);
    }
  };
```

Button (place after the progress block):

```tsx
      {canWrite && (
        <div style={{ marginBottom: 16, textAlign: 'right' }}>
          <button
            onClick={handleComplete}
            disabled={completing || status.completed !== status.totalStudents}
            title={status.completed !== status.totalStudents
              ? `${status.totalStudents - status.completed} student(s) still to review`
              : undefined}
            style={{ padding: '8px 16px', borderRadius: 6, border: 'none', fontWeight: 600,
                     cursor: (completing || status.completed !== status.totalStudents) ? 'default' : 'pointer',
                     background: (completing || status.completed !== status.totalStudents) ? '#c5cae9' : '#1a6840',
                     color: '#fff' }}>
            {completing ? 'Completing…' : 'Complete campaign'}
          </button>
          {status.completed !== status.totalStudents && (
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
              {status.totalStudents - status.completed} student(s) still to review
            </div>
          )}
        </div>
      )}
```

> Check the `FullNameVerificationStatusResponse` type: it must expose `completed` and `totalStudents`. The full-name status DTO returns `confirmed`/`correctionRequested`, not `completed`. If the TS type lacks `completed`, gate instead on `status.confirmed === status.totalStudents` (a full-name item is COMPLETED only when confirmed). Use whichever field the type actually declares; the intent is "enabled only when every item is resolved."

- [ ] **Step 4: Build the frontend**

Run: `cd frontend; npm run build`
Expected: build succeeds (no TS errors).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/dashboardCommandCenterApi.ts frontend/src/pages/workspace/dashboard/drawers/StudentReviewDrawer.tsx
git commit -m "feat(fe): Complete-campaign button in the student review drawer"
```

---

## Self-Review

**Spec coverage:**
- §3.5 prerequisite (drawer repoint) → done pre-plan (`4c77ce6`), noted.
- §5.1 migration → Task 1 Step 3. §5.2 completeCampaign + endpoint → Task 1 (repo) + Task 2 (endpoint). §5.3 freeze → Task 1 (guard) + Task 2 (409 both controllers). §5.4 outbox event → Task 1 Step 5.
- §6.1 V21 → Task 3 Step 3. §6.2 projection → Task 3 Steps 4-5. §6.3 KPI filter → Task 4.
- §7 FE → Task 5. §8 error table → covered by Task 1 messages + Task 2 mappings. §9 tests → Tasks 1, 3, 4.

**Placeholder scan:** no TBD/TODO; every code step has complete code. The two `>` notes (Task 2 Step 4 grep, Task 5 Step 3 field name) are conditional-verification instructions with the exact fallback given, not placeholders.

**Type consistency:** `completeCampaign(String,Long):Map` used identically in Task 1 (def) and Task 2 (call). `updateCampaignStatus(String,String)` def (Task 3 Step 4) matches call (Task 3 Step 5). Event type string `student-review-campaign.completed.v1` identical in Task 1 emit, Task 3 test + projector. Payload keys `campaignId`/`schoolId`/`status` identical across emit (Task 1), test (Task 3 Step 1), projector reads (Task 3 Step 5). `campaign_status` column identical across V21, updateCampaignStatus, pendingReviewCount, tests.
