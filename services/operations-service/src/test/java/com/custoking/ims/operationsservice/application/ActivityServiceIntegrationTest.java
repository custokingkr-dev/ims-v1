package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.ActivityService.ActivityEntry;
import com.custoking.ims.operationsservice.application.ActivityService.ActivityPage;
import com.custoking.ims.operationsservice.application.ActivityService.Kind;
import com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.Order;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.READ_ONLY_PERMISSIONS;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.operator;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.schoolAdmin;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.superadmin;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An order's activity history (design D40) against the real schema: the four sources merged newest first through the
 * runtime role and row-level security, saves grouped, paging by {@code before}, the stage filter, authors' names and who
 * may read it.
 */
class ActivityServiceIntegrationTest {
    static final long WORKER = 61, SUPERVISOR = 62;
    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-10T05:00:00Z");
    static WorkflowExtrasDatabase db;
    static ActivityService activity;

    @BeforeAll static void start() {
        db = WorkflowExtrasDatabase.get();
        activity = db.activity();
        db.staff.put(WORKER, "Wasim Worker");
    }

    @AfterEach void clear() { TenantContext.clear(); }

    static OffsetDateTime t(int minutes) { return T0.plusMinutes(minutes); }

    static long action(Order order, int step, String action, long actor, OffsetDateTime at, String notes) {
        return db.owner.sql("INSERT INTO workflow.workflow_actions (instance_id, school_id, step_order, action, actor_id, notes, acted_at) "
                        + "VALUES (:i, :s, :step, :a, :actor, :notes, :at) RETURNING id")
                .param("i", order.instanceId()).param("s", order.schoolId()).param("step", step).param("a", action)
                .param("actor", actor).param("notes", notes).param("at", at).query(Long.class).single();
    }

    static long note(Order order, Integer step, long author, OffsetDateTime at, String body) {
        return db.owner.sql("INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order) "
                        + "VALUES (:i, :s, :a, :b, :at, :step) RETURNING id")
                .param("i", order.instanceId()).param("s", order.schoolId()).param("a", author).param("b", body).param("at", at)
                .param("step", step).query(Long.class).single();
    }

    static long call(Order order, int step, long author, OffsetDateTime at, OffsetDateTime followUp) {
        return db.owner.sql("INSERT INTO workflow.workflow_call_logs (instance_id, school_id, step_order, contacted, outcome, contact_label, "
                        + "notes, next_follow_up_at, created_by, created_at) VALUES (:i, :s, :step, 'SCHOOL', 'CALLBACK_REQUESTED', "
                        + "'Mrs Rao', 'Call after the assembly', :f, :a, :at) RETURNING id")
                .param("i", order.instanceId()).param("s", order.schoolId()).param("step", step).param("f", followUp)
                .param("a", author).param("at", at).query(Long.class).single();
    }

    static long followUp(Order order, long author, OffsetDateTime at) {
        return db.owner.sql("INSERT INTO workflow.workflow_follow_ups (instance_id, school_id, percent_complete, next_follow_up_on, "
                        + "contact_phone, created_by, created_at) VALUES (:i, :s, 40, DATE '2026-11-20', '+91 98765 43210', :a, :at) RETURNING id")
                .param("i", order.instanceId()).param("s", order.schoolId()).param("a", author).param("at", at).query(Long.class).single();
    }

    /** A notebook order with one of everything, as {@link #expectedIds} lists it newest first. */
    record History(Order order, List<String> expectedIds) { }

    static History history(long school) {
        Order order = db.notebook(school, 11, WORKER);
        long created = action(order, 1, "TRACKER_CREATED", 1, t(0), null);
        long save1 = action(order, 2, "STEP_DATA_SAVED", WORKER, t(60), "{\"fields\":[\"designBrief\"]}");
        long save2 = action(order, 2, "STEP_DATA_SAVED", WORKER, t(63), "{\"fields\":[\"designBrief\",\"pages\"]}");
        long save3 = action(order, 2, "STEP_DATA_SAVED", WORKER, t(67), "{\"checked\":2}");
        long completed = action(order, 2, "STEP_COMPLETED", WORKER, t(68), null);
        long internal = note(order, null, WORKER, t(120), "Principal prefers matte covers");
        long stage = note(order, 5, WORKER, t(180), "Boards booked with Shree");
        long callId = call(order, 6, WORKER, t(240), t(1500));
        long legacy = followUp(order, WORKER, t(300));
        long approved = action(order, 4, "HEAD_APPROVED", SUPERVISOR, t(360), "{\"comment\":\"Approved for print\"}");
        long reopened = action(order, 7, "STEP_REOPENED", SUPERVISOR, t(360), "Sample was smudged");
        return new History(order, List.of("action:" + reopened, "action:" + approved, "follow-up:" + legacy, "call:" + callId,
                "note:" + stage, "note:" + internal, "action:" + completed, "action:" + save3, "action:" + created));
    }

    // ---- the merged timeline ---------------------------------------------------------------------------------------------

    @Test void everySourceIsMergedNewestFirstWithSavesGroupedAndAuthorsNamed() {
        History history = history(10);
        operator(WORKER, 10L);

        ActivityPage page = activity.activity(history.order().orderId(), null, null, null);
        assertEquals(history.expectedIds(), page.entries().stream().map(ActivityEntry::id).toList());
        assertNull(page.nextBefore(), "everything fits on one page");

        Map<String, ActivityEntry> byId = new java.util.HashMap<>();
        page.entries().forEach(entry -> byId.put(entry.id(), entry));
        ActivityEntry reopened = page.entries().get(0);
        assertEquals("STEP_REOPENED", reopened.action());
        assertEquals("Sample was smudged", reopened.text());
        assertEquals(SUPERVISOR, reopened.actorUserId());
        assertNull(reopened.actorName(), "a name identity does not know stays empty");
        ActivityEntry approved = page.entries().get(1);
        assertEquals("Approved for print", approved.text());

        ActivityEntry legacy = page.entries().get(2);
        assertEquals(Kind.FOLLOW_UP, legacy.kind());
        assertEquals(10, legacy.stepOrder(), "legacy follow-ups belong to Production Follow-up");
        assertEquals(Map.of("percentComplete", 40, "nextFollowUpOn", LocalDate.parse("2026-11-20"), "contactPhone", "+91 98765 43210"),
                legacy.details());

        ActivityEntry call = page.entries().get(3);
        assertEquals(Kind.CALL, call.kind());
        assertEquals(6, call.stepOrder());
        assertEquals("Call after the assembly", call.text());
        assertEquals("CALLBACK_REQUESTED", call.details().get("outcome"));
        assertEquals(t(1500).toInstant(), ((OffsetDateTime) call.details().get("nextFollowUpAt")).toInstant());
        assertEquals("Wasim Worker", call.actorName());

        assertEquals(Map.of("type", "STAGE"), page.entries().get(4).details());
        assertEquals(5, page.entries().get(4).stepOrder());
        assertEquals(Map.of("type", "INTERNAL"), page.entries().get(5).details());
        assertNull(page.entries().get(5).stepOrder());

        ActivityEntry saves = page.entries().get(7);
        assertEquals(3, saves.count(), "three saves within ten minutes are one entry");
        assertEquals(t(67).toInstant(), saves.at().toInstant());
        assertEquals(List.of("designBrief", "pages"), saves.details().get("fields"));
        assertEquals(2, saves.details().get("checked"));
        assertEquals(t(60).toInstant(), ((OffsetDateTime) saves.details().get("firstAt")).toInstant());
        assertEquals("Wasim Worker", saves.actorName());
        assertEquals(1, page.entries().get(8).stepOrder(), "the creation is recorded on Step 1");
        assertEquals(1, page.entries().get(8).count());
    }

    @Test void pagingByBeforeVisitsEveryEntryOnceAndNeverSplitsAMoment() {
        History history = history(10);
        superadmin();
        List<String> seen = new ArrayList<>();
        OffsetDateTime before = null;
        int pages = 0;
        do {
            ActivityPage page = activity.activity(history.order().orderId(), before, 1, null);
            page.entries().forEach(entry -> seen.add(entry.id()));
            if (pages == 0) {
                assertEquals(2, page.entries().size(), "both entries at the newest moment are on the first page");
                assertEquals(t(360).toInstant(), page.nextBefore().toInstant());
            }
            before = page.nextBefore();
            pages++;
        } while (before != null && pages < 50);
        assertEquals(history.expectedIds(), seen);
        assertEquals(8, pages);
    }

    @Test void theStageFilterKeepsOneStagesEntries() {
        History history = history(10);
        superadmin();
        assertEquals(List.of(history.expectedIds().get(6), history.expectedIds().get(7)),
                ids(activity.activity(history.order().orderId(), null, null, 2)));
        assertEquals(List.of(history.expectedIds().get(2)), ids(activity.activity(history.order().orderId(), null, null, 10)),
                "the legacy follow-ups are Production Follow-up's");
        assertEquals(List.of(history.expectedIds().get(4)), ids(activity.activity(history.order().orderId(), null, null, 5)),
                "order-level internal notes belong to no stage");
        assertTrue(activity.activity(history.order().orderId(), null, null, 12).entries().isEmpty());
    }

    @Test void anOrderWithoutAProductionFollowUpStageStillShowsItsFollowUpsUnderNoStage() {
        Order flier = db.flier(10, 2, WORKER);
        long legacy = followUp(flier, WORKER, t(10));
        superadmin();
        ActivityEntry entry = activity.activity(flier.orderId(), null, null, null).entries().get(0);
        assertEquals("follow-up:" + legacy, entry.id());
        assertEquals(7, entry.stepOrder(), "the flier template's Production Follow-up is Step 7");
    }

    @Test void theRepositoryReadsBatchesThatEndOnAWholeTimestamp() {
        Order order = db.flier(10, 3, WORKER);
        for (int i = 0; i < 5; i++) action(order, 2, "STEP_DATA_SAVED", WORKER, t(30), null);
        long older = action(order, 1, "STEP_COMPLETED", WORKER, t(20), null);
        long oldest = note(order, 1, WORKER, t(10), "First call made");
        superadmin();
        var repo = new com.custoking.ims.operationsservice.persistence.ActivityRepository(db.runtime, WorkflowExtrasDatabase.SCHEMA);

        var first = db.tx.execute(status -> repo.batch(order.instanceId(), null, null, null, 2));
        assertEquals(5, first.size(), "a batch of two runs on to every row of its oldest moment");
        assertTrue(first.stream().allMatch(row -> row.at().toInstant().equals(t(30).toInstant())));
        var second = db.tx.execute(status -> repo.batch(order.instanceId(), t(30), null, null, 2));
        assertEquals(List.of(older, oldest), second.stream().map(row -> row.id()).toList(), "then strictly older rows, newest first");
        assertTrue(db.tx.execute(status -> repo.batch(order.instanceId(), t(10), null, null, 2)).isEmpty());
        assertEquals(List.of(older), db.tx.execute(status -> repo.batch(order.instanceId(), null, 1, null, 1)).stream()
                .map(row -> row.id()).toList(), "the stage filter applies in the database, and distinct moments are not run on");
    }

    // ---- who may read ------------------------------------------------------------------------------------------------

    @Test void anyoneWhoMaySeeTheOrderReadsItsHistoryAndNobodyElse() {
        History history = history(20);
        String id = history.order().orderId();
        operator(70, Set.of(20L), Set.of(), READ_ONLY_PERMISSIONS);
        assertEquals(history.expectedIds().size(), activity.activity(id, null, null, null).entries().size());
        operator(71, Set.of(), Set.of("20:NOTEBOOKS"), READ_ONLY_PERMISSIONS);
        assertEquals(history.expectedIds().size(), activity.activity(id, null, null, null).entries().size(), "an item scope for the item");
        operator(72, Set.of(), Set.of("20:FLIERS"), READ_ONLY_PERMISSIONS);
        assertEquals(404, status(() -> activity.activity(id, null, null, null)), "another item's scope at the school");
        operator(73, 10L);
        assertEquals(404, status(() -> activity.activity(id, null, null, null)), "another school's operator");
        schoolAdmin(20);
        assertEquals(403, status(() -> activity.activity(id, null, null, null)));
        TenantContext.clear();
        assertEquals(403, status(() -> activity.activity(id, null, null, null)));
    }

    @Test void theLimitAndStageAreChecked() {
        superadmin();
        String id = db.notebook(10, 1, WORKER).orderId();
        assertBad("limit must be between 1 and 200", () -> activity.activity(id, null, 0, null));
        assertBad("limit must be between 1 and 200", () -> activity.activity(id, null, 201, null));
        assertBad("step must be a stage number from 1 to 99", () -> activity.activity(id, null, null, 0));
        assertBad("step must be a stage number from 1 to 99", () -> activity.activity(id, null, null, 100));
        ActivityPage empty = activity.activity(id, null, 200, null);
        assertTrue(empty.entries().isEmpty(), "an order with no history yet has an empty page");
        assertNull(empty.nextBefore());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    static List<String> ids(ActivityPage page) { return page.entries().stream().map(ActivityEntry::id).toList(); }

    static int status(Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode().value();
    }

    static void assertBad(String message, Executable call) {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class, call);
        assertEquals(400, refused.getStatusCode().value());
        assertEquals(message, refused.getReason());
    }
}
