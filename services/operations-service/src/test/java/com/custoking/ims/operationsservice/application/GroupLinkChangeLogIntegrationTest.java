package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.GroupLinkHistoryService.GroupLinkChangePage;
import com.custoking.ims.operationsservice.application.GroupLinkHistoryService.GroupLinkChangeView;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorInput;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorView;
import com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.Order;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository.ChangeRow;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository.Source;
import com.custoking.ims.operationsservice.persistence.GroupLinkChangeRepository.Target;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.OPERATOR_PERMISSIONS;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.SUPER;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.operator;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.schoolAdmin;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.superadmin;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The WhatsApp group link change log (workflow V23) against the real schema, through the runtime role: every save path
 * logs who changed which group, from where, and the link before and after; saving the saved link or a refused one logs
 * nothing; superadmin reads it back a page at a time with names; and the log is append-only and kept per school.
 */
class GroupLinkChangeLogIntegrationTest {
    /** User ids of this class's own: no other test gives them a name. */
    static final long ASSIGNEE = 761;
    static final String A = "https://chat.whatsapp.com/ChangeLogGroupA001";
    static final String B = "https://chat.whatsapp.com/ChangeLogGroupB001";
    static final String C = "https://chat.whatsapp.com/ChangeLogGroupC001";
    /** School ids of this class's own, apart from the other group link tests' (300 up). */
    static final AtomicLong SCHOOLS = new AtomicLong(700);
    static WorkflowExtrasDatabase db;
    static GroupLinkService groups;
    static WorkflowSettingsService settings;
    static VendorDirectoryService directory;

    @BeforeAll static void start() {
        db = WorkflowExtrasDatabase.get();
        groups = db.groups();
        settings = db.settings();
        directory = db.vendorDirectory();
    }

    @AfterEach void clear() {
        TenantContext.clear();
        db.staff.remove(SUPER);
        db.owner.sql("DELETE FROM workflow.workflow_settings").update();
    }

    /** A phone number no other vendor has. */
    static String phone() {
        return "97" + String.format("%08d", SCHOOLS.incrementAndGet());
    }

    /** The newest change id so far, to read only what a test logged. */
    static long mark() {
        return db.owner.sql("SELECT COALESCE(max(id), 0) FROM workflow.workflow_group_link_changes").query(Long.class).single();
    }

    /** What was logged after {@code mark}, oldest first, as the owner sees it. */
    static List<Map<String, Object>> loggedSince(long mark) {
        return db.owner.sql("SELECT kind, school_id, category_code, vendor_id, before_url, after_url, source, order_id, changed_by, "
                        + "changed_by_email, request_correlation_id FROM workflow.workflow_group_link_changes WHERE id > :mark ORDER BY id")
                .param("mark", mark).query().listOfRows();
    }

    static Map<String, Object> expected(String kind, Long school, String category, Long vendor, String before, String after,
                                        String source, String order, long by) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("kind", kind);
        row.put("school_id", school);
        row.put("category_code", category);
        row.put("vendor_id", vendor);
        row.put("before_url", before);
        row.put("after_url", after);
        row.put("source", source);
        row.put("order_id", order);
        row.put("changed_by", by);
        return row;
    }

    /** The logged rows without the columns a test cannot predict (email, correlation id), numbers as longs. */
    static List<Map<String, Object>> comparable(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> kept = new java.util.HashMap<>();
            row.forEach((key, value) -> {
                if (key.equals("changed_by_email") || key.equals("request_correlation_id")) return;
                kept.put(key, value instanceof Number number ? (Object) number.longValue() : value);
            });
            return kept;
        }).toList();
    }

    // ---- every save path logs its real changes ---------------------------------------------------------------------------

    @Test void aSchoolsOwnGroupsAndItemGroupsLogEachRealChangeFromSetUp() {
        long school = SCHOOLS.incrementAndGet();
        long mark = mark();
        superadmin();
        assertEquals(Map.of("SCHOOL_GROUP", A), groups.setSchoolGroupLink(school, " SCHOOL_GROUP ", A + "?mode=ems_copy_t"));
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", " " + A + " ");
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", B);
        groups.setSchoolGroupLink(school, "INTERNAL_APPROVAL", C);
        assertEquals(Map.of("INTERNAL_APPROVAL", C), groups.setSchoolGroupLink(school, "SCHOOL_GROUP", ""));
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", null);
        groups.setSchoolItemGroupLink(school, "fliers", A);
        groups.setSchoolItemGroupLink(school, "FLIERS", A);
        groups.setSchoolItemGroupLink(school, "FLIERS", null);

        assertEquals(List.of(
                expected("SCHOOL_GROUP", school, null, null, null, A, "SETUP", null, SUPER),
                expected("SCHOOL_GROUP", school, null, null, A, B, "SETUP", null, SUPER),
                expected("INTERNAL_APPROVAL", school, null, null, null, C, "SETUP", null, SUPER),
                expected("SCHOOL_GROUP", school, null, null, B, null, "SETUP", null, SUPER),
                expected("ITEM", school, "FLIERS", null, null, A, "SETUP", null, SUPER),
                expected("ITEM", school, "FLIERS", null, A, null, "SETUP", null, SUPER)), comparable(loggedSince(mark)),
                "saving the saved link, or removing a group that is not there, logs nothing");
        Map<String, Object> first = loggedSince(mark).get(0);
        assertEquals("super@custoking.test", first.get("changed_by_email"));
        assertNotNull(first.get("request_correlation_id"));
    }

    @Test void aSaveOnAnOrderLogsTheOrderAndTheOperatorWhoMadeIt() {
        long school = SCHOOLS.incrementAndGet();
        Order order = db.flier(school, 1, ASSIGNEE);
        long mark = mark();
        operator(ASSIGNEE, Set.of(), Set.of(school + ":FLIERS"), OPERATOR_PERMISSIONS);
        groups.setOrderSchoolGroup(order.orderId(), A);
        groups.setOrderSchoolGroup(order.orderId(), A);
        superadmin();
        groups.setOrderSchoolGroup(order.orderId(), "");

        List<Map<String, Object>> logged = loggedSince(mark);
        assertEquals(List.of(
                expected("ITEM", school, "FLIERS", null, null, A, "ORDER", order.orderId(), ASSIGNEE),
                expected("ITEM", school, "FLIERS", null, A, null, "ORDER", order.orderId(), SUPER)), comparable(logged));
        assertEquals("op" + ASSIGNEE + "@custoking.test", logged.get(0).get("changed_by_email"));
    }

    @Test void theGlobalInternalApprovalGroupLogsEachRealChange() {
        long mark = mark();
        superadmin();
        settings.setInternalApprovalGroup(A);
        settings.setInternalApprovalGroup(A);
        settings.setInternalApprovalGroup(B);
        settings.setInternalApprovalGroup(" ");
        settings.setInternalApprovalGroup(null);
        assertEquals(List.of(
                expected("GLOBAL_INTERNAL_APPROVAL", null, null, null, null, A, "SETUP", null, SUPER),
                expected("GLOBAL_INTERNAL_APPROVAL", null, null, null, A, B, "SETUP", null, SUPER),
                expected("GLOBAL_INTERNAL_APPROVAL", null, null, null, B, null, "SETUP", null, SUPER)), comparable(loggedSince(mark)));
    }

    @Test void theVendorDirectoryLogsAVendorsGroupOnlyWhenItChanges() {
        String unique = "Change Log Offset " + SCHOOLS.incrementAndGet();
        String plainPhone = phone(), groupedPhone = phone();
        long mark = mark();
        superadmin();
        VendorView plain = directory.create(new VendorInput(unique + " plain", List.of("OFFSET_PRINTERS"), null, plainPhone, null,
                null, null, null, null, null));
        VendorView grouped = directory.create(new VendorInput(unique, List.of("OFFSET_PRINTERS"), null, groupedPhone, null, null,
                null, A, null, null));
        directory.update(grouped.id(), new VendorInput(unique, List.of("OFFSET_PRINTERS"), "Ravi", groupedPhone, null, null,
                null, A, null, null));
        directory.update(grouped.id(), new VendorInput(unique, List.of("OFFSET_PRINTERS"), "Ravi", groupedPhone, null, null,
                null, B + "?mode=ems_copy_t", null, null));
        directory.update(grouped.id(), new VendorInput(unique, List.of("OFFSET_PRINTERS"), "Ravi", groupedPhone, null, null,
                null, "", null, false));
        directory.update(plain.id(), new VendorInput(unique + " plain", List.of("OFFSET_PRINTERS"), null, plainPhone, null,
                null, "Pune", null, null, null));

        assertEquals(List.of(
                expected("VENDOR", null, null, grouped.id(), null, A, "VENDOR_DIRECTORY", null, SUPER),
                expected("VENDOR", null, null, grouped.id(), A, B, "VENDOR_DIRECTORY", null, SUPER),
                expected("VENDOR", null, null, grouped.id(), B, null, "VENDOR_DIRECTORY", null, SUPER)), comparable(loggedSince(mark)),
                "a vendor without a group, and edits that keep the group, log nothing");
    }

    @Test void anAppliedImportLogsEachRowItChangesAndADryRunOrARefusedFileNothing() {
        long school = SCHOOLS.incrementAndGet();
        long vendor = db.vendor("Change Log Binder " + school, phone(), B, "BINDERS");
        superadmin();
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", A);
        List<GroupLinkService.ImportRow> rows = List.of(
                new GroupLinkService.ImportRow(2, "SCHOOL_GROUP", school, null, null, A),
                new GroupLinkService.ImportRow(3, "ITEM", school, "FLEX", null, C),
                new GroupLinkService.ImportRow(4, "VENDOR", null, null, vendor, ""));
        long mark = mark();
        groups.importGroupLinks(new GroupLinkService.ImportRequest(true, rows));
        List<GroupLinkService.ImportRow> broken = new java.util.ArrayList<>(rows);
        broken.add(new GroupLinkService.ImportRow(5, "BROADCAST", school, null, null, A));
        assertFalse(groups.importGroupLinks(new GroupLinkService.ImportRequest(false, broken)).applied());
        assertEquals(List.of(), loggedSince(mark), "a dry run and a file with an error log nothing");

        assertTrue(groups.importGroupLinks(new GroupLinkService.ImportRequest(false, rows)).applied());
        assertEquals(List.of(
                expected("ITEM", school, "FLEX", null, null, C, "IMPORT", null, SUPER),
                expected("VENDOR", null, null, vendor, B, null, "IMPORT", null, SUPER)), comparable(loggedSince(mark)),
                "the unchanged school group logs nothing");
    }

    @Test void aRefusedSaveLogsNothing() {
        long school = SCHOOLS.incrementAndGet();
        Order order = db.flier(school, 1, ASSIGNEE);
        long mark = mark();
        operator(ASSIGNEE, school);
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, assertThrows(WorkflowRuleViolation.class,
                () -> groups.setSchoolGroupLink(school, "SCHOOL_GROUP", A)).code());
        operator(ASSIGNEE + 1, school);
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, assertThrows(WorkflowRuleViolation.class,
                () -> groups.setOrderSchoolGroup(order.orderId(), A)).code());
        superadmin();
        assertEquals(WorkflowRuleViolation.INVALID_INVITE, assertThrows(WorkflowRuleViolation.class,
                () -> groups.setSchoolGroupLink(school, "SCHOOL_GROUP", "https://example.com/AbCdEf1234567890")).code());
        assertEquals(400, status(() -> groups.setSchoolGroupLink(school, "PRODUCT_VENDOR", A)));
        assertEquals(400, status(() -> groups.setSchoolGroupLink(0, "SCHOOL_GROUP", A)));
        assertEquals(List.of(), loggedSince(mark));
    }

    // ---- reading it back -------------------------------------------------------------------------------------------------

    @Test void superadminReadsTheLogNewestFirstAPageAtATimeWithNames() {
        long school = SCHOOLS.incrementAndGet();
        long other = SCHOOLS.incrementAndGet();
        Order order = db.flier(school, 1, ASSIGNEE);
        db.staff.put(SUPER, "Asha Superadmin");
        superadmin();
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", A);
        groups.setSchoolGroupLink(other, "SCHOOL_GROUP", A);
        groups.setSchoolItemGroupLink(school, "FLIERS", B);
        operator(ASSIGNEE, Set.of(), Set.of(school + ":FLIERS"), OPERATOR_PERMISSIONS);
        groups.setOrderSchoolGroup(order.orderId(), C);

        superadmin();
        GroupLinkHistoryService history = db.history();
        GroupLinkChangePage first = history.changes(school, null, 2);
        assertEquals(2, first.changes().size());
        GroupLinkChangeView newest = first.changes().get(0);
        assertEquals("ITEM", newest.kind());
        assertEquals("ORDER", newest.source());
        assertEquals(order.orderId(), newest.orderId());
        assertEquals(B, newest.before());
        assertEquals(C, newest.after());
        assertEquals("CHANGED", newest.outcome());
        assertEquals(ASSIGNEE, newest.changedBy());
        assertEquals("op" + ASSIGNEE + "@custoking.test", newest.changedByName(), "identity does not know them: their email");
        assertEquals("ADDED", first.changes().get(1).outcome());
        assertEquals("Asha Superadmin", first.changes().get(1).changedByName());
        assertNotNull(first.nextBefore());

        GroupLinkChangePage second = history.changes(school, first.nextBefore(), 2);
        assertEquals(List.of("SCHOOL_GROUP"), second.changes().stream().map(GroupLinkChangeView::kind).toList());
        assertEquals(school, second.changes().get(0).schoolId());
        assertNull(second.nextBefore(), "nothing older");

        List<GroupLinkChangeView> everything = history.changes(null, null, 200).changes();
        assertTrue(everything.stream().anyMatch(change -> Long.valueOf(other).equals(change.schoolId())), "every school without a filter");
        for (int i = 1; i < everything.size(); i++) assertTrue(everything.get(i - 1).id() > everything.get(i).id(), "newest first");
    }

    @Test void onlySuperadminReadsTheLogAndBadParametersAreRefused() {
        long school = SCHOOLS.incrementAndGet();
        GroupLinkHistoryService history = db.history();
        operator(ASSIGNEE, school);
        WorkflowRuleViolation notSuper = assertThrows(WorkflowRuleViolation.class, () -> history.changes(school, null, null));
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, notSuper.code());
        assertEquals(403, notSuper.httpStatus());
        schoolAdmin(school);
        assertEquals(403, status(() -> history.changes(school, null, null)));
        TenantContext.clear();
        assertEquals(403, status(() -> history.changes(null, null, null)));

        superadmin();
        assertEquals(400, status(() -> history.changes(0L, null, null)));
        assertEquals(400, status(() -> history.changes(null, 0L, null)));
        assertEquals(400, status(() -> history.changes(null, null, 0)));
        assertEquals(400, status(() -> history.changes(null, null, GroupLinkHistoryService.MAX_LIMIT + 1)));
        assertTrue(history.changes(null, null, null).changes().size() <= GroupLinkHistoryService.DEFAULT_LIMIT);
    }

    // ---- the log itself ----------------------------------------------------------------------------------------------------

    @Test void theRuntimeRoleCanAddAndReadTheLogButNeverChangeOrRemoveIt() {
        long school = SCHOOLS.incrementAndGet();
        superadmin();
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", A);
        for (String sql : List.of("UPDATE workflow.workflow_group_link_changes SET after_url = NULL WHERE school_id = " + school,
                "DELETE FROM workflow.workflow_group_link_changes WHERE school_id = " + school,
                "TRUNCATE workflow.workflow_group_link_changes")) {
            DataAccessException refused = assertThrows(DataAccessException.class,
                    () -> db.tx.executeWithoutResult(status -> db.runtime.sql(sql).update()), sql);
            assertTrue(refused.getMostSpecificCause().getMessage().contains("permission denied"), refused.getMostSpecificCause().getMessage());
        }
        assertEquals(1, db.owner.sql("SELECT count(*) FROM workflow.workflow_group_link_changes WHERE school_id = :s")
                .param("s", school).query(Integer.class).single());
    }

    @Test void rowLevelSecurityKeepsOtherSchoolsAndTheGlobalRowsOutOfAnOperatorsReach() {
        long school = SCHOOLS.incrementAndGet();
        long elsewhere = SCHOOLS.incrementAndGet();
        long vendor = db.vendor("Change Log Flex " + school, phone(), null, "FLEX_PRINTERS");
        superadmin();
        groups.setSchoolGroupLink(school, "SCHOOL_GROUP", A);
        groups.setSchoolGroupLink(elsewhere, "SCHOOL_GROUP", A);
        settings.setInternalApprovalGroup(B);
        groups.importGroupLinks(new GroupLinkService.ImportRequest(false,
                List.of(new GroupLinkService.ImportRow(2, "VENDOR", null, null, vendor, C))));
        GroupLinkChangeRepository repo = db.changeRepository();

        operator(ASSIGNEE, school);
        List<ChangeRow> seen = db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return repo.changes(null, null, 1000);
        });
        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().allMatch(row -> Long.valueOf(school).equals(row.schoolId())),
                "an operator sees only their schools' rows, never another school's, the global group's or a vendor's: " + seen);
        DataAccessException refused = assertThrows(DataAccessException.class, () -> db.tx.executeWithoutResult(status -> {
            db.scope().applyOperatorScope();
            repo.record(Target.schoolGroup(elsewhere, "SCHOOL_GROUP"), A, B, Source.SETUP, null);
        }));
        assertTrue(refused.getMostSpecificCause().getMessage().contains("row-level security"), refused.getMostSpecificCause().getMessage());
        assertThrows(DataAccessException.class, () -> db.tx.executeWithoutResult(status -> {
            db.scope().applyOperatorScope();
            repo.record(Target.globalInternalApproval(), A, B, Source.SETUP, null);
        }), "a row for no school is superadmin's alone");
        TenantContext.clear();
        assertEquals(List.of(), db.tx.execute(status -> repo.changes(null, null, 1000)), "no scope fails closed");
    }

    @Test void theSchemaRefusesAChangeThatIsNoChangeOrNamesTheWrongGroup() {
        String insert = "INSERT INTO workflow.workflow_group_link_changes (kind, school_id, category_code, vendor_id, before_url, "
                + "after_url, source, order_id, changed_by) VALUES ";
        for (String values : List.of(
                "('SCHOOL_GROUP', 1, NULL, NULL, '" + A + "', '" + A + "', 'SETUP', NULL, 1)",
                "('SCHOOL_GROUP', 1, NULL, NULL, NULL, NULL, 'SETUP', NULL, 1)",
                "('SCHOOL_GROUP', NULL, NULL, NULL, NULL, '" + A + "', 'SETUP', NULL, 1)",
                "('ITEM', 1, NULL, NULL, NULL, '" + A + "', 'SETUP', NULL, 1)",
                "('VENDOR', 1, NULL, 5, NULL, '" + A + "', 'SETUP', NULL, 1)",
                "('GLOBAL_INTERNAL_APPROVAL', NULL, NULL, NULL, NULL, '" + A + "', 'ORDER', 'CK-1', 1)",
                "('ITEM', 1, 'FLIERS', NULL, NULL, '" + A + "', 'ORDER', NULL, 1)",
                "('ITEM', 1, 'FLIERS', NULL, NULL, 'https://example.com/AbCdEf1234567890', 'SETUP', NULL, 1)",
                "('ITEM', 1, 'fliers', NULL, NULL, '" + A + "', 'SETUP', NULL, 1)",
                "('ITEM', 1, 'FLIERS', NULL, NULL, '" + A + "', 'EMAIL', NULL, 1)")) {
            assertThrows(DataAccessException.class, () -> db.owner.sql(insert + values).update(), values);
        }
        assertThrows(IllegalArgumentException.class, () -> new Target(GroupLinkChangeRepository.Kind.ITEM, 1L, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Target(GroupLinkChangeRepository.Kind.VENDOR, 1L, null, 5L));
    }

    static int status(Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode().value();
    }
}
