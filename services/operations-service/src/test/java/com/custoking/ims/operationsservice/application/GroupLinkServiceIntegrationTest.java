package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.Order;
import com.custoking.ims.operationsservice.application.WorkflowSettingsService.InternalApprovalGroupView;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.GroupLink;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.Source;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.OPERATOR_PERMISSIONS;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.READ_ONLY_PERMISSIONS;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.operator;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.schoolAdmin;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.superadmin;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WhatsApp group links (design D34) against the real schema: which link an order shows (school group: item, else school,
 * else none; internal approval group: the school's own, else the global one), who may save an item-level school group,
 * the global internal approval group setting, and row-level security on item group links.
 */
class GroupLinkServiceIntegrationTest {
    static final long ASSIGNEE = 51, OTHER = 52;
    static final String ITEM_INVITE = "https://chat.whatsapp.com/ItemFlierGroup0001";
    static final String SCHOOL_INVITE = "https://chat.whatsapp.com/SchoolWideGroup0001";
    static final String SCHOOL_APPROVAL_INVITE = "https://chat.whatsapp.com/SchoolApproval0001";
    static final String GLOBAL_APPROVAL_INVITE = "https://chat.whatsapp.com/GlobalApproval0001";
    /** A school id of its own per test, so school-wide links never leak between tests. */
    static final AtomicLong SCHOOLS = new AtomicLong(300);
    static WorkflowExtrasDatabase db;
    static GroupLinkService groups;
    static WorkflowSettingsService settings;

    @BeforeAll static void start() {
        db = WorkflowExtrasDatabase.get();
        groups = db.groups();
        settings = db.settings();
    }

    @AfterEach void clear() {
        TenantContext.clear();
        db.owner.sql("DELETE FROM workflow.workflow_settings").update();
    }

    static void schoolLink(long school, String target, String invite) {
        db.owner.sql("INSERT INTO workflow.workflow_school_whatsapp_group_links (school_id, target_key, invite_url, updated_by) "
                + "VALUES (:school, :target, :url, 1)").param("school", school).param("target", target).param("url", invite).update();
    }

    static void globalApprovalGroup(String invite) {
        db.owner.sql("INSERT INTO workflow.workflow_settings (key, value, updated_by) VALUES ('INTERNAL_APPROVAL_GROUP_URL', :v, 1)")
                .param("v", invite).update();
    }

    // ---- resolution ----------------------------------------------------------------------------------------------------

    @Test void theSchoolGroupIsTheItemsGroupElseTheSchoolsElseNone() {
        long school = SCHOOLS.incrementAndGet();
        Order flier = db.flier(school, 2, ASSIGNEE);
        Order notebook = db.notebook(school, 2, ASSIGNEE);
        operator(ASSIGNEE, school);

        assertEquals(GroupLink.none(), groups.orderGroups(flier.orderId()).school(), "no group saved anywhere");
        schoolLink(school, "SCHOOL_GROUP", SCHOOL_INVITE);
        assertEquals(new GroupLink(SCHOOL_INVITE, Source.SCHOOL), groups.orderGroups(flier.orderId()).school());

        assertEquals(new GroupLink(ITEM_INVITE, Source.ITEM), groups.setOrderSchoolGroup(flier.orderId(), ITEM_INVITE).school());
        assertEquals(new GroupLink(ITEM_INVITE, Source.ITEM), groups.orderGroups(flier.orderId()).school(), "the item's group wins");
        assertEquals(new GroupLink(SCHOOL_INVITE, Source.SCHOOL), groups.orderGroups(notebook.orderId()).school(),
                "the flier group is the school's group for fliers only");
        Order secondFlier = db.flier(school, 1, ASSIGNEE);
        assertEquals(new GroupLink(ITEM_INVITE, Source.ITEM), groups.orderGroups(secondFlier.orderId()).school(),
                "every flier order at the school shares the item's group");

        assertEquals(new GroupLink(SCHOOL_INVITE, Source.SCHOOL), groups.setOrderSchoolGroup(flier.orderId(), "  ").school(),
                "removing the item's group shows the school's again");
    }

    @Test void theInternalApprovalGroupIsTheSchoolsOwnElseTheGlobalOneElseNone() {
        long school = SCHOOLS.incrementAndGet();
        Order order = db.notebook(school, 7, ASSIGNEE);
        superadmin();

        assertEquals(GroupLink.none(), groups.orderGroups(order.orderId()).internalApproval());
        globalApprovalGroup(GLOBAL_APPROVAL_INVITE);
        assertEquals(new GroupLink(GLOBAL_APPROVAL_INVITE, Source.GLOBAL), groups.orderGroups(order.orderId()).internalApproval());
        schoolLink(school, "INTERNAL_APPROVAL", SCHOOL_APPROVAL_INVITE);
        assertEquals(new GroupLink(SCHOOL_APPROVAL_INVITE, Source.SCHOOL), groups.orderGroups(order.orderId()).internalApproval(),
                "a school's own approval group overrides the global one");
    }

    @Test void theOrdersVendorsAreItsSelectedOnesInactiveIncludedEachWithItsOwnGroup() {
        long school = SCHOOLS.incrementAndGet();
        Order order = db.notebook(school, 8, ASSIGNEE);
        long offset = db.vendor("A Offset " + order.orderId(), "9876543210", "https://chat.whatsapp.com/OffsetGroup00001", "OFFSET_PRINTERS");
        long laminator = db.vendor("B Lamination " + order.orderId(), "9876543211", null, "LAMINATION_VENDORS");
        db.vendor("C Unselected " + order.orderId(), "9876543212", "https://chat.whatsapp.com/Unselected000001", "OFFSET_PRINTERS");
        db.owner.sql("UPDATE workflow.workflow_vendors SET active = false WHERE id = :id").param("id", laminator).update();
        db.selectVendors(order, "{\"OFFSET_PRINTERS\": [" + offset + "], \"LAMINATION_VENDORS\": [" + laminator + ", " + offset + "], "
                + "\"BINDERS\": []}");
        operator(ASSIGNEE, school);

        List<VendorSummary> vendors = groups.orderGroups(order.orderId()).vendors();
        assertEquals(List.of(offset, laminator), vendors.stream().map(VendorSummary::id).toList(), "each selected vendor once, by name");
        assertEquals("https://chat.whatsapp.com/OffsetGroup00001", vendors.get(0).whatsappGroupUrl());
        assertNull(vendors.get(1).whatsappGroupUrl());
        assertFalse(vendors.get(1).active(), "a deactivated vendor stays on the order, shown as Inactive");
    }

    @Test void resolvingOutsideATransactionAppliesTheOperatorsScopeAndInsideOneUsesTheCallers() {
        long school = SCHOOLS.incrementAndGet();
        schoolLink(school, "SCHOOL_GROUP", SCHOOL_INVITE);
        db.owner.sql("INSERT INTO workflow.workflow_school_item_group_links (school_id, category_code, invite_url, updated_by) "
                + "VALUES (:s, 'FLIERS', :u, 1)").param("s", school).param("u", ITEM_INVITE).update();

        operator(ASSIGNEE, school);
        assertEquals(new GroupLink(ITEM_INVITE, Source.ITEM), groups.resolve(school, "FLIERS", List.of()).school());
        assertEquals(new GroupLink(SCHOOL_INVITE, Source.SCHOOL), groups.resolve(school, "NOTEBOOKS", null).school());
        operator(ASSIGNEE, school + 1000);
        assertEquals(GroupLink.none(), groups.resolve(school, "FLIERS", List.of()).school(),
                "an operator elsewhere cannot read the school's links, even through the resolver");

        superadmin();
        ResolvedGroups inside = db.tx.execute(status -> groups.resolve(school, "FLIERS", List.of()));
        assertEquals(new GroupLink(ITEM_INVITE, Source.ITEM), inside.school());
    }

    // ---- who may save the item's school group ---------------------------------------------------------------------------

    @Test void superadminOrTheCurrentStagesAssigneeSavesTheItemsGroupAndTheChangeIsRecorded() {
        long school = SCHOOLS.incrementAndGet();
        Order order = db.flier(school, 1, ASSIGNEE);

        operator(OTHER, school);
        WorkflowRuleViolation notAssignee = assertThrows(WorkflowRuleViolation.class, () -> groups.setOrderSchoolGroup(order.orderId(), ITEM_INVITE));
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, notAssignee.code());
        assertEquals(403, notAssignee.httpStatus());

        operator(ASSIGNEE, Set.of(school), Set.of(), READ_ONLY_PERMISSIONS);
        assertEquals(403, status(() -> groups.setOrderSchoolGroup(order.orderId(), ITEM_INVITE)), "acting needs the act permission");
        schoolAdmin(school);
        assertEquals(403, status(() -> groups.setOrderSchoolGroup(order.orderId(), ITEM_INVITE)));
        assertEquals(403, status(() -> groups.orderGroups(order.orderId())));
        operator(ASSIGNEE, school + 1000);
        assertEquals(404, status(() -> groups.setOrderSchoolGroup(order.orderId(), ITEM_INVITE)));
        assertTrue(itemLink(school, "FLIERS").isEmpty(), "nothing refused was saved");

        operator(ASSIGNEE, Set.of(), Set.of(school + ":FLIERS"), OPERATOR_PERMISSIONS);
        groups.setOrderSchoolGroup(order.orderId(), ITEM_INVITE);
        groups.setOrderSchoolGroup(order.orderId(), " " + ITEM_INVITE + " ");
        assertEquals(Optional.of(ITEM_INVITE), itemLink(school, "FLIERS"));
        assertEquals(ASSIGNEE, db.owner.sql("SELECT updated_by FROM workflow.workflow_school_item_group_links WHERE school_id = :s")
                .param("s", school).query(Long.class).single());
        List<Map<String, Object>> recorded = db.owner.sql("SELECT step_order, action, actor_id, notes FROM workflow.workflow_actions "
                + "WHERE instance_id = :id ORDER BY id").param("id", order.instanceId()).query().listOfRows();
        assertEquals(1, recorded.size(), "saving the same link again records nothing: " + recorded);
        assertEquals("GROUP_LINK_SET", recorded.get(0).get("action"));
        assertEquals(1, recorded.get(0).get("step_order"));
        assertEquals(ASSIGNEE, ((Number) recorded.get(0).get("actor_id")).longValue());
        assertEquals("{\"group\":\"SCHOOL\",\"level\":\"ITEM\",\"removed\":false}", recorded.get(0).get("notes"));

        superadmin();
        groups.setOrderSchoolGroup(order.orderId(), null);
        assertTrue(itemLink(school, "FLIERS").isEmpty());
        assertEquals("{\"group\":\"SCHOOL\",\"level\":\"ITEM\",\"removed\":true}", db.owner.sql("SELECT notes FROM workflow.workflow_actions "
                + "WHERE instance_id = :id ORDER BY id DESC LIMIT 1").param("id", order.instanceId()).query(String.class).single());
    }

    @Test void onAFinishedOrderOnlySuperadminSavesTheItemsGroup() {
        long school = SCHOOLS.incrementAndGet();
        Order done = db.flier(school, 0, ASSIGNEE);
        operator(ASSIGNEE, school);
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, assertThrows(WorkflowRuleViolation.class,
                () -> groups.setOrderSchoolGroup(done.orderId(), ITEM_INVITE)).code());
        superadmin();
        assertEquals(Source.ITEM, groups.setOrderSchoolGroup(done.orderId(), ITEM_INVITE).school().source());
        assertEquals(9, db.owner.sql("SELECT step_order FROM workflow.workflow_actions WHERE instance_id = :id")
                .param("id", done.instanceId()).query(Integer.class).single(), "recorded on the last stage");
    }

    @Test void anythingButAWhatsAppGroupInviteIsRefusedBeforeTheOrderIsTouched() {
        long school = SCHOOLS.incrementAndGet();
        Order order = db.flier(school, 1, ASSIGNEE);
        superadmin();
        for (String invite : List.of("https://chat.whatsapp.com/short", "https://evil.example/AbCdEf1234567890",
                "https://chat.whatsapp.com:443/AbCdEf1234567890", "javascript:alert(1)")) {
            WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> groups.setOrderSchoolGroup(order.orderId(), invite));
            assertEquals(WorkflowRuleViolation.INVALID_INVITE, refused.code(), invite);
            assertEquals(GroupLinkService.INVALID_INVITE_MESSAGE, refused.getMessage());
        }
        assertTrue(itemLink(school, "FLIERS").isEmpty());
    }

    @Test void whatsAppsCopyLinkTailIsDroppedAndTheInviteKept() {
        assertEquals("https://chat.whatsapp.com/AbCdEf1234567890",
                GroupLinkService.canonicalInvite("https://chat.whatsapp.com/AbCdEf1234567890?mode=ems_copy_t"));
        assertEquals("https://chat.whatsapp.com/AbCdEf1234567890",
                GroupLinkService.canonicalInvite(" https://chat.whatsapp.com/AbCdEf1234567890?mode=r_c#join "));
        // Everything from the first ? or # is dropped unread, as the browser's invite.ts does, so no tail can make the
        // two disagree.
        for (String tail : List.of("?x=[1]", "?ref=café", "#é", "?a b", "?a%zz", "?a|b", "#a#b", "?")) {
            assertEquals("https://chat.whatsapp.com/AbCdEf1234567890",
                    GroupLinkService.canonicalInvite("https://chat.whatsapp.com/AbCdEf1234567890" + tail), tail);
        }
        assertEquals(WorkflowRuleViolation.INVALID_INVITE, assertThrows(WorkflowRuleViolation.class,
                () -> GroupLinkService.canonicalInvite("https://chat.whatsapp.com/short?mode=ems_copy_t")).code(),
                "the invite code itself is still checked");
        assertEquals(WorkflowRuleViolation.INVALID_INVITE, assertThrows(WorkflowRuleViolation.class,
                () -> GroupLinkService.canonicalInvite("https://chat.whatsapp.com?x=/AbCdEf1234567890")).code(),
                "the invite code must come before the tail");
    }

    // ---- a school's item groups in Workflow set-up --------------------------------------------------------------------

    @Test void superadminKeepsASchoolsItemGroupsAndSchoolWideOperatorsReadThem() {
        long school = SCHOOLS.incrementAndGet();
        Order flier = db.flier(school, 2, ASSIGNEE);
        superadmin();
        assertEquals(List.of(), groups.schoolItemGroupLinks(school));

        List<GroupLinkService.ItemGroupLinkView> saved = groups.setSchoolItemGroupLink(school, " fliers ", " " + ITEM_INVITE + "?mode=ems_copy_t");
        assertEquals(1, saved.size());
        assertEquals("FLIERS", saved.get(0).categoryCode(), "the item code is stored as the catalogue spells it");
        assertEquals(ITEM_INVITE, saved.get(0).inviteUrl());
        assertEquals(WorkflowExtrasDatabase.SUPER, saved.get(0).updatedBy());
        assertNotNull(saved.get(0).updatedAt());
        assertEquals(new GroupLink(ITEM_INVITE, Source.ITEM), groups.orderGroups(flier.orderId()).school(), "orders of the item use it");
        assertEquals(0, db.owner.sql("SELECT count(*) FROM workflow.workflow_actions WHERE instance_id = :id")
                .param("id", flier.instanceId()).query(Integer.class).single(), "a set-up change belongs to no order's history");

        operator(ASSIGNEE, school);
        assertEquals(saved, groups.schoolItemGroupLinks(school), "an operator assigned to the whole school reads them");
        WorkflowRuleViolation notSuper = assertThrows(WorkflowRuleViolation.class,
                () -> groups.setSchoolItemGroupLink(school, "FLIERS", null));
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, notSuper.code());
        operator(ASSIGNEE, Set.of(), Set.of(school + ":FLIERS"), OPERATOR_PERMISSIONS);
        assertEquals(403, status(() -> groups.schoolItemGroupLinks(school)), "an item scope is not the whole school");
        schoolAdmin(school);
        assertEquals(403, status(() -> groups.schoolItemGroupLinks(school)));

        superadmin();
        assertEquals(400, status(() -> groups.setSchoolItemGroupLink(school, "fliers & flex", ITEM_INVITE)));
        assertEquals(400, status(() -> groups.setSchoolItemGroupLink(0, "FLIERS", ITEM_INVITE)));
        assertEquals(WorkflowRuleViolation.INVALID_INVITE, assertThrows(WorkflowRuleViolation.class,
                () -> groups.setSchoolItemGroupLink(school, "FLIERS", "https://example.com/AbCdEf1234567890")).code());
        assertEquals(Optional.of(ITEM_INVITE), itemLink(school, "FLIERS"), "nothing refused changed it");

        assertEquals(List.of(), groups.setSchoolItemGroupLink(school, "FLIERS", " "), "a blank link removes it");
        assertEquals(GroupLink.none(), groups.orderGroups(flier.orderId()).school());
    }

    // ---- the spreadsheet import ----------------------------------------------------------------------------------------

    static GroupLinkService.ImportRow row(int line, String kind, Long school, String category, Long vendor, String invite) {
        return new GroupLinkService.ImportRow(line, kind, school, category, vendor, invite);
    }

    static Map<GroupLinkService.ImportOutcome, Long> outcomes(GroupLinkService.ImportResult result) {
        return result.changes().stream().collect(java.util.stream.Collectors.groupingBy(GroupLinkService.ImportChange::outcome,
                java.util.stream.Collectors.counting()));
    }

    @Test void anImportIsADryRunUntilAskedAndThenAppliesEveryRow() {
        long school = SCHOOLS.incrementAndGet();
        schoolLink(school, "SCHOOL_GROUP", SCHOOL_INVITE);
        schoolLink(school, "INTERNAL_APPROVAL", SCHOOL_APPROVAL_INVITE);
        long vendor = db.vendor("Import Offset " + school, "9876500001", null, "OFFSET_PRINTERS");
        long kept = db.vendor("Import Binder " + school, "9876500002", "https://chat.whatsapp.com/BinderGroup00001", "BINDERS");
        superadmin();
        List<GroupLinkService.ImportRow> rows = List.of(
                row(2, "SCHOOL_GROUP", school, null, null, "https://chat.whatsapp.com/NewSchoolGroup001"),
                row(3, "internal_approval", school, null, null, null),
                row(4, "ITEM", school, "fliers", null, ITEM_INVITE),
                row(5, "VENDOR", null, null, vendor, "https://chat.whatsapp.com/OffsetGroup00002?mode=ems_copy_t"),
                row(6, "VENDOR", null, null, kept, "https://chat.whatsapp.com/BinderGroup00001"));

        GroupLinkService.ImportResult preview = groups.importGroupLinks(new GroupLinkService.ImportRequest(null, rows));
        assertTrue(preview.dryRun(), "no dryRun flag is a dry run");
        assertFalse(preview.applied());
        assertEquals(List.of(), preview.errors());
        assertEquals(Map.of(GroupLinkService.ImportOutcome.CHANGED, 1L, GroupLinkService.ImportOutcome.REMOVED, 1L,
                GroupLinkService.ImportOutcome.ADDED, 2L, GroupLinkService.ImportOutcome.UNCHANGED, 1L), outcomes(preview));
        GroupLinkService.ImportChange first = preview.changes().get(0);
        assertEquals(SCHOOL_INVITE, first.before());
        assertEquals("https://chat.whatsapp.com/NewSchoolGroup001", first.after());
        assertEquals("FLIERS", preview.changes().get(2).categoryCode());
        assertEquals("https://chat.whatsapp.com/OffsetGroup00002", preview.changes().get(3).after());
        assertEquals(SCHOOL_INVITE, db.owner.sql("SELECT invite_url FROM workflow.workflow_school_whatsapp_group_links "
                + "WHERE school_id = :s AND target_key = 'SCHOOL_GROUP'").param("s", school).query(String.class).single(),
                "a dry run changes nothing");

        GroupLinkService.ImportResult applied = groups.importGroupLinks(new GroupLinkService.ImportRequest(false, rows));
        assertTrue(applied.applied());
        assertFalse(applied.dryRun());
        assertEquals(Map.of("SCHOOL_GROUP", "https://chat.whatsapp.com/NewSchoolGroup001"), db.owner.sql(
                "SELECT target_key, invite_url FROM workflow.workflow_school_whatsapp_group_links WHERE school_id = :s")
                .param("s", school).query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                "the school group changed and its approval group was removed");
        assertEquals(Optional.of(ITEM_INVITE), itemLink(school, "FLIERS"));
        Map<String, Object> offset = db.owner.sql("SELECT name, phone, whatsapp_group_url, updated_by FROM workflow.workflow_vendors "
                + "WHERE id = :id").param("id", vendor).query().singleRow();
        assertEquals("https://chat.whatsapp.com/OffsetGroup00002", offset.get("whatsapp_group_url"));
        assertEquals("Import Offset " + school, offset.get("name"), "only the vendor's group changes");
        assertEquals("9876500001", offset.get("phone"));
        assertEquals(WorkflowExtrasDatabase.SUPER, ((Number) offset.get("updated_by")).longValue());

        GroupLinkService.ImportResult again = groups.importGroupLinks(new GroupLinkService.ImportRequest(false, rows));
        assertEquals(Map.of(GroupLinkService.ImportOutcome.UNCHANGED, 5L), outcomes(again), "importing the same file twice changes nothing more");
    }

    @Test void oneRowInErrorAppliesNothingAndEveryErrorNamesItsLine() {
        long school = SCHOOLS.incrementAndGet();
        superadmin();
        List<GroupLinkService.ImportRow> rows = List.of(
                row(2, "SCHOOL_GROUP", school, null, null, SCHOOL_INVITE),
                row(3, "SCHOOL_GROUP", school, null, null, "https://chat.whatsapp.com/Duplicate000001"),
                row(4, "ITEM", school, "Fliers and flex", null, ITEM_INVITE),
                row(5, "VENDOR", null, null, 999_999L, ITEM_INVITE),
                row(6, "BROADCAST", school, null, null, ITEM_INVITE),
                row(7, "INTERNAL_APPROVAL", school, null, null, "https://example.com/AbCdEf1234567890"),
                row(8, "INTERNAL_APPROVAL", null, null, null, ITEM_INVITE));

        GroupLinkService.ImportResult result = groups.importGroupLinks(new GroupLinkService.ImportRequest(false, rows));
        assertFalse(result.applied());
        assertEquals(List.of(
                new GroupLinkService.ImportError(3, "This sets the same group as line 2"),
                new GroupLinkService.ImportError(4, "Give the item's code, such as FLIERS"),
                new GroupLinkService.ImportError(5, "There is no vendor 999999"),
                new GroupLinkService.ImportError(6, "Unknown group type BROADCAST"),
                new GroupLinkService.ImportError(7, GroupLinkService.INVALID_INVITE_MESSAGE),
                new GroupLinkService.ImportError(8, "Give the school's id")), result.errors());
        assertEquals(1, result.changes().size(), "the rows that were understood are still listed");
        assertEquals(0, db.owner.sql("SELECT count(*) FROM workflow.workflow_school_whatsapp_group_links WHERE school_id = :s")
                .param("s", school).query(Integer.class).single(), "nothing was applied");
    }

    @Test void onlySuperadminImportsAndAnEmptyOrOversizedFileIsRefused() {
        long school = SCHOOLS.incrementAndGet();
        List<GroupLinkService.ImportRow> rows = List.of(row(2, "SCHOOL_GROUP", school, null, null, SCHOOL_INVITE));
        operator(ASSIGNEE, school);
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, assertThrows(WorkflowRuleViolation.class,
                () -> groups.importGroupLinks(new GroupLinkService.ImportRequest(false, rows))).code());
        schoolAdmin(school);
        assertEquals(403, status(() -> groups.importGroupLinks(new GroupLinkService.ImportRequest(false, rows))));

        superadmin();
        assertEquals(400, status(() -> groups.importGroupLinks(null)));
        assertEquals(400, status(() -> groups.importGroupLinks(new GroupLinkService.ImportRequest(true, List.of()))));
        List<GroupLinkService.ImportRow> tooMany = java.util.Collections.nCopies(GroupLinkService.MAX_IMPORT_ROWS + 1, rows.get(0));
        assertEquals(400, status(() -> groups.importGroupLinks(new GroupLinkService.ImportRequest(true, tooMany))));
        assertEquals(0, db.owner.sql("SELECT count(*) FROM workflow.workflow_school_whatsapp_group_links WHERE school_id = :s")
                .param("s", school).query(Integer.class).single());
    }

    // ---- the global internal approval group ----------------------------------------------------------------------------

    @Test void superadminSetsTheGlobalInternalApprovalGroupThatOperatorsRead() {
        superadmin();
        assertEquals(new InternalApprovalGroupView(null, null, null), settings.internalApprovalGroup());
        InternalApprovalGroupView saved = settings.setInternalApprovalGroup(" https://CHAT.whatsapp.com/GlobalApproval0001 ");
        assertEquals(GLOBAL_APPROVAL_INVITE, saved.inviteUrl());
        assertEquals(WorkflowExtrasDatabase.SUPER, saved.updatedBy());
        assertNotNull(saved.updatedAt());

        operator(ASSIGNEE, Set.of(), Set.of("20:FLIERS"), READ_ONLY_PERMISSIONS);
        assertEquals(GLOBAL_APPROVAL_INVITE, settings.internalApprovalGroup().inviteUrl());
        WorkflowRuleViolation notSuper = assertThrows(WorkflowRuleViolation.class,
                () -> settings.setInternalApprovalGroup("https://chat.whatsapp.com/OperatorGroup0001"));
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, notSuper.code());
        assertEquals(403, notSuper.httpStatus());

        schoolAdmin(10);
        assertEquals(403, status(() -> settings.internalApprovalGroup()));
        operator(ASSIGNEE, Set.of(10L), Set.of(), Set.of("workspace:access"));
        assertEquals(403, status(() -> settings.internalApprovalGroup()), "reading needs the order workflow read permission");

        superadmin();
        assertEquals(WorkflowRuleViolation.INVALID_INVITE, assertThrows(WorkflowRuleViolation.class,
                () -> settings.setInternalApprovalGroup("https://example.com/group")).code());
        assertEquals(GLOBAL_APPROVAL_INVITE, settings.internalApprovalGroup().inviteUrl(), "a refused link changes nothing");
        assertEquals(new InternalApprovalGroupView(null, null, null), settings.setInternalApprovalGroup(""), "blank removes it");
    }

    // ---- row-level security ----------------------------------------------------------------------------------------------

    @Test void rowLevelSecurityKeepsAnotherSchoolsItemGroupsOutOfAnOperatorsReach() {
        long school = SCHOOLS.incrementAndGet();
        long elsewhere = SCHOOLS.incrementAndGet();
        db.owner.sql("INSERT INTO workflow.workflow_school_item_group_links (school_id, category_code, invite_url, updated_by) "
                + "VALUES (:s, 'FLIERS', :u, 1)").param("s", elsewhere).param("u", ITEM_INVITE).update();
        WorkflowSettingsRepository repo = db.settingsRepository();

        operator(ASSIGNEE, school);
        assertEquals(Optional.empty(), db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return repo.itemGroupLink(elsewhere, "FLIERS");
        }), "even read directly, another school's item group is invisible");
        DataAccessException refused = assertThrows(DataAccessException.class, () -> db.tx.executeWithoutResult(status -> {
            db.scope().applyOperatorScope();
            repo.upsertItemGroupLink(elsewhere, "FLEX", ITEM_INVITE, ASSIGNEE);
        }));
        assertTrue(refused.getMostSpecificCause().getMessage().contains("row-level security"), refused.getMostSpecificCause().getMessage());
        Integer removed = db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return repo.deleteItemGroupLink(elsewhere, "FLIERS");
        });
        assertEquals(0, removed, "nor can it be removed");

        operator(ASSIGNEE, elsewhere);
        assertEquals(Optional.of(ITEM_INVITE), db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return repo.itemGroupLink(elsewhere, "FLIERS");
        }));
        TenantContext.clear();
        assertEquals(Optional.empty(), db.tx.execute(status -> repo.itemGroupLink(elsewhere, "FLIERS")), "no scope fails closed");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    static Optional<String> itemLink(long school, String category) {
        return db.owner.sql("SELECT invite_url FROM workflow.workflow_school_item_group_links WHERE school_id = :s AND category_code = :c")
                .param("s", school).param("c", category).query(String.class).optional();
    }

    static int status(Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode().value();
    }
}
