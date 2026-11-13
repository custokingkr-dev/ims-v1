package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.VendorDirectoryService.CategoryChange;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorCategoryView;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorInput;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorView;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.operator;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.schoolAdmin;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.superadmin;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The vendor directory (design D31, D32, §4.5) against the real schema, through the runtime role: superadmin maintains
 * vendors and categories, operators read active vendors, and the order workflow's {@link VendorLookup} sees inactive
 * vendors too (an order keeps what it chose).
 */
class VendorDirectoryServiceIntegrationTest {
    static final String INVITE = "https://chat.whatsapp.com/FlexHouseGroup01";
    static WorkflowExtrasDatabase db;
    static VendorDirectoryService directory;
    static VendorLookup lookup;

    @BeforeAll static void start() {
        db = WorkflowExtrasDatabase.get();
        directory = db.vendorDirectory();
        lookup = db.vendorRepository();
    }

    @AfterEach void clear() { TenantContext.clear(); }

    /** A name no other test uses, so listings can be filtered to this test's vendors. */
    static String unique(String name) { return name + " " + UUID.randomUUID().toString().substring(0, 8); }

    static VendorInput input(String name, List<String> categories, String phone) {
        return new VendorInput(name, categories, null, phone, null, null, null, null, null, null);
    }

    // ---- superadmin maintains vendors ------------------------------------------------------------------------------

    @Test void superadminAddsAVendorWithEveryFieldCleaned() {
        superadmin();
        String name = unique("Sharma Flex House");
        VendorView created = directory.create(new VendorInput("  " + name + "  ", List.of("OFFSET_PRINTERS", "FLEX_PRINTERS", "FLEX_PRINTERS"),
                " Ravi Sharma ", " +91 98765 43210 ", "011-2345678", "ravi@sharmaflex.in", " Gurugram ",
                " HTTPS://Chat.WhatsApp.com/FlexHouseGroup01 ", " Prints overnight ", null));

        assertTrue(created.id() > 0);
        assertEquals(name, created.name(), "surrounding spaces are dropped");
        assertEquals(List.of("OFFSET_PRINTERS", "FLEX_PRINTERS"), created.categories(), "duplicates collapse, in category order");
        assertEquals("Ravi Sharma", created.contactPerson());
        assertEquals("+91 98765 43210", created.phone());
        assertEquals("011-2345678", created.altPhone());
        assertEquals("ravi@sharmaflex.in", created.email());
        assertEquals("Gurugram", created.city());
        assertEquals(INVITE, created.whatsappGroupUrl(), "the invite is stored in its one spelling, the code kept exactly");
        assertEquals("Prints overnight", created.notes());
        assertFalse(created.sample());
        assertTrue(created.active(), "a new vendor is active unless sent otherwise");
        assertEquals(WorkflowExtrasDatabase.SUPER, created.updatedBy());
        assertNotNull(created.updatedAt());
        assertEquals(WorkflowExtrasDatabase.SUPER, db.owner.sql("SELECT created_by FROM workflow.workflow_vendors WHERE id = :id")
                .param("id", created.id()).query(Long.class).single(), "who added it is kept");
    }

    @Test void onlySuperadminMaintainsTheDirectory() {
        superadmin();
        VendorView vendor = directory.create(input(unique("Guarded Binders"), List.of("BINDERS"), "9876543210"));

        for (Runnable caller : List.<Runnable>of(() -> operator(10, 10L), () -> schoolAdmin(10))) {
            caller.run();
            WorkflowRuleViolation create = assertThrows(WorkflowRuleViolation.class,
                    () -> directory.create(input(unique("Sneaky"), List.of("BINDERS"), "9876543210")));
            assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, create.code());
            assertEquals(403, create.httpStatus());
            assertEquals(403, assertThrows(WorkflowRuleViolation.class,
                    () -> directory.update(vendor.id(), input("Renamed", List.of("BINDERS"), "9876543210"))).httpStatus());
            assertEquals(403, assertThrows(WorkflowRuleViolation.class,
                    () -> directory.updateCategory("BINDERS", new CategoryChange("Bookbinders", null, null))).httpStatus());
        }
        TenantContext.clear();
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> directory.create(input(unique("Anonymous"), List.of("BINDERS"), "9876543210"))).getStatusCode().value());
        superadmin();
        assertEquals(vendor.name(), directory.vendors(null, vendor.name(), false).get(0).name(), "nothing was changed");
    }

    @Test void updateReplacesEveryFieldAndKeepsTheActiveStateWhenNotSent() {
        superadmin();
        VendorView vendor = directory.create(new VendorInput(unique("Metro Laminates"), List.of("LAMINATION_VENDORS", "BINDERS"),
                "Asha", "9876543210", null, "asha@metro.in", "Delhi", INVITE, "old notes", null));
        directory.update(vendor.id(), new VendorInput(vendor.name(), vendor.categories(), null, vendor.phone(), null, null, null,
                null, null, false));

        VendorView edited = directory.update(vendor.id(), new VendorInput(vendor.name() + " Ltd", List.of("BINDERS"), null,
                "9811111111", null, null, null, null, null, null));

        assertEquals(vendor.name() + " Ltd", edited.name());
        assertEquals(List.of("BINDERS"), edited.categories(), "a category left out is removed");
        assertNull(edited.contactPerson(), "a field left out is cleared: the form sends every field");
        assertNull(edited.email());
        assertNull(edited.city());
        assertNull(edited.whatsappGroupUrl());
        assertNull(edited.notes());
        assertFalse(edited.active(), "no active state sent keeps it inactive");
        assertFalse(lookup.inCategory(vendor.id(), "LAMINATION_VENDORS"));
        assertTrue(lookup.inCategory(vendor.id(), "BINDERS"));
    }

    @Test void anUnknownVendorIsNotFound() {
        superadmin();
        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> directory.update(987_654_321L, input("Nobody", List.of("BINDERS"), "9876543210")));
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test void theSameNameAndPhoneTwiceIsRefusedButBranchesWithTheirOwnPhonesAreNot() {
        superadmin();
        String name = unique("Precision Print");
        VendorView first = directory.create(input(name, List.of("OFFSET_PRINTERS"), "9876500001"));

        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> directory.create(input(" " + name.toUpperCase() + " ", List.of("DIGITAL_PRINTERS"), "9876500001")));
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals("A vendor with this name and phone already exists", duplicate.getReason());

        VendorView branch = directory.create(input(name, List.of("OFFSET_PRINTERS"), "9876500002"));
        assertNotEquals(first.id(), branch.id());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> directory.update(branch.id(), input(name, List.of("OFFSET_PRINTERS"), "9876500001"))).getStatusCode().value());
        assertEquals("9876500001", directory.update(first.id(), input(name, List.of("OFFSET_PRINTERS"), "9876500001")).phone(),
                "saving a vendor unchanged is not a duplicate of itself");
    }

    @Test void twoSuperadminsAddingTheSameVendorAtOnceGetOneVendor() throws Exception {
        String name = unique("Racing Boards");
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<String>> results = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
                superadmin();
                try {
                    together.await(10, TimeUnit.SECONDS);
                    directory.create(input(name, List.of("BOARD_SUPPLIERS"), "9876512345"));
                    return "created";
                } catch (ResponseStatusException refused) {
                    return "refused " + refused.getStatusCode().value();
                } catch (Exception failure) {
                    return "failed " + failure;
                } finally {
                    TenantContext.clear();
                }
            }, pool)).toList();
            List<String> outcomes = results.stream().map(CompletableFuture::join).sorted().toList();
            assertEquals(List.of("created", "refused 409"), outcomes);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, db.owner.sql("SELECT count(*) FROM workflow.workflow_vendors WHERE name = :n").param("n", name)
                .query(Long.class).single());
    }

    // ---- validation ------------------------------------------------------------------------------------------------

    @Test void everyVendorFieldIsValidatedWithAMessageForTheForm() {
        superadmin();
        String name = unique("Valid");
        assertBad("Enter the vendor's name", () -> directory.create(input("   ", List.of("BINDERS"), "9876543210")));
        assertBad("Enter the vendor's name (at most 200 characters)", () -> directory.create(input("x".repeat(201), List.of("BINDERS"), "9876543210")));
        assertBad("Choose at least one vendor category", () -> directory.create(input(name, List.of(), "9876543210")));
        assertBad("Choose at least one vendor category", () -> directory.create(input(name, null, "9876543210")));
        assertBad("Unknown vendor category flex", () -> directory.create(input(name, List.of("flex"), "9876543210")));
        assertBad("Unknown vendor category MAGIC_PRINTERS", () -> directory.create(input(name, List.of("MAGIC_PRINTERS"), "9876543210")));
        assertBad("Enter the vendor's phone number", () -> directory.create(input(name, List.of("BINDERS"), "  ")));
        assertBad("The phone number must be a phone number, such as +91 98765 43210",
                () -> directory.create(input(name, List.of("BINDERS"), "call me")));
        assertBad("The phone number must be a phone number, such as +91 98765 43210",
                () -> directory.create(input(name, List.of("BINDERS"), "12-34")));
        assertBad("The phone number is limited to 32 characters", () -> directory.create(input(name, List.of("BINDERS"), "9".repeat(33))));
        assertBad("The alternate phone number must be a phone number, such as +91 98765 43210", () -> directory.create(
                new VendorInput(name, List.of("BINDERS"), null, "9876543210", "n/a", null, null, null, null, null)));
        assertBad("Enter a valid email address", () -> directory.create(
                new VendorInput(name, List.of("BINDERS"), null, "9876543210", null, "ravi-at-example", null, null, null, null)));
        assertBad("Notes is limited to 500 characters", () -> directory.create(
                new VendorInput(name, List.of("BINDERS"), null, "9876543210", null, null, null, null, "n".repeat(501), null)));
        assertBad("The contact person is limited to 200 characters", () -> directory.create(
                new VendorInput(name, List.of("BINDERS"), "c".repeat(201), "9876543210", null, null, null, null, null, null)));
        assertBad("The city is limited to 100 characters", () -> directory.create(
                new VendorInput(name, List.of("BINDERS"), null, "9876543210", null, null, "c".repeat(101), null, null, null)));
        assertTrue(directory.vendors(null, name, false).isEmpty(), "nothing invalid was stored");
    }

    @Test void onlyAWhatsAppGroupInviteIsAcceptedAsTheVendorsGroup() {
        superadmin();
        for (String invite : List.of("https://example.com/AbCdEf1234567890", "http://chat.whatsapp.com/AbCdEf1234567890",
                "https://chat.whatsapp.com/short", "https://chat.whatsapp.com/AbCdEf1234567890?ref=x",
                "https://chat.whatsapp.com/AbCdEf1234567890#x", "https://chat.whatsapp.com:443/AbCdEf1234567890",
                "https://user@chat.whatsapp.com/AbCdEf1234567890", "https://wa.me/919876543210", "chat.whatsapp.com/AbCdEf1234567890")) {
            WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> directory.create(new VendorInput(
                    unique("Invite"), List.of("BINDERS"), null, "9876543210", null, null, null, invite, null, null)), invite);
            assertEquals(WorkflowRuleViolation.INVALID_INVITE, refused.code(), invite);
            assertEquals(400, refused.httpStatus());
            assertEquals("Paste a WhatsApp group invite link (https://chat.whatsapp.com/…)", refused.getMessage());
        }
    }

    // ---- sample vendors -------------------------------------------------------------------------------------------------

    @Test void aSampleVendorStaysSampleWithoutAPhoneAndBecomesRealWithOne() {
        superadmin();
        long sampleId = db.owner.sql("INSERT INTO workflow.workflow_vendors (name, sample, legacy_key) VALUES (:n, true, :k) RETURNING id")
                .param("n", unique("Sample Board Co")).param("k", "test_" + UUID.randomUUID().toString().substring(0, 8))
                .query(Long.class).single();
        db.owner.sql("INSERT INTO workflow.workflow_vendor_category_members VALUES (:id, 'BOARD_SUPPLIERS')").param("id", sampleId).update();

        VendorView stillSample = directory.update(sampleId, new VendorInput("Shree Board Traders (sample)", List.of("BOARD_SUPPLIERS"),
                null, null, null, null, "Jaipur", null, null, null));
        assertTrue(stillSample.sample(), "edited without a phone it is still a sample vendor");
        assertNull(stillSample.phone());
        assertEquals("Jaipur", stillSample.city());

        VendorView real = directory.update(sampleId, input("Shree Board Traders", List.of("BOARD_SUPPLIERS"), "9876543210"));
        assertFalse(real.sample(), "a phone makes it a real vendor");
        assertBad("Enter the vendor's phone number", () -> directory.update(sampleId, input("Shree Board Traders", List.of("BOARD_SUPPLIERS"), null)));
        assertEquals("9876543210", lookup.vendors(List.of(sampleId)).get(0).phone());
    }

    @Test void theSeededSampleVendorsAreListedAsSample() {
        operator(10, 10L);
        List<VendorView> boards = directory.vendors("BOARD_SUPPLIERS", "Shree Board", false);
        assertTrue(boards.stream().anyMatch(v -> v.sample() && v.phone() == null && v.name().equals("Shree Board Traders")), boards.toString());
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    @Test void operatorsReadActiveVendorsOnlyAndSuperadminMayAskForInactiveOnes() {
        superadmin();
        String tag = unique("Readers");
        VendorView active = directory.create(input(tag + " Active Diaries", List.of("DIARIES"), "9876543210"));
        VendorView inactive = directory.create(new VendorInput(tag + " Closed Diaries", List.of("DIARIES"), null, "9876543211",
                null, null, null, null, null, false));

        assertEquals(List.of(active.id(), inactive.id()), ids(directory.vendors("DIARIES", tag, true)));
        assertEquals(List.of(active.id()), ids(directory.vendors("DIARIES", tag, false)));

        operator(10, 10L);
        assertEquals(List.of(active.id()), ids(directory.vendors("DIARIES", tag, true)), "operators never see inactive vendors");
        operator(11, Set.of(), Set.of("20:FLIERS"), WorkflowExtrasDatabase.READ_ONLY_PERMISSIONS);
        assertEquals(List.of(active.id()), ids(directory.vendors(null, tag, false)), "an item-scoped operator reads them too");
        assertFalse(directory.categories().isEmpty());
    }

    @Test void searchMatchesNameContactPhoneAndCityWithoutWildcards() {
        superadmin();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        VendorView vendor = directory.create(new VendorInput("Flaps " + tag + " 100% Cotton_Works", List.of("FLAPS"), "Meera " + tag,
                "98111" + "22334", null, null, "Kota" + tag, null, null, null));
        directory.create(input("Flaps " + tag + " 1000 Cotton Works", List.of("FLAPS"), "9811122335"));

        assertEquals(List.of(vendor.id()), ids(directory.vendors(null, "100% cotton_", false)), "% and _ are matched literally");
        assertEquals(List.of(vendor.id()), ids(directory.vendors(null, "meera " + tag.toUpperCase(), false)));
        assertEquals(List.of(vendor.id()), ids(directory.vendors(null, "kota" + tag, false)));
        assertEquals(2, directory.vendors("FLAPS", "flaps " + tag, false).size());
        assertTrue(directory.vendors("SASHES", "flaps " + tag, false).isEmpty(), "the category filter applies");
        assertBad("Unknown vendor category flaps", () -> directory.vendors("flaps", null, false));
        assertBad("A search is limited to 100 characters", () -> directory.vendors(null, "q".repeat(101), false));
    }

    @Test void readingIsForSuperadminAndOperatorsHoldingTheReadPermission() {
        schoolAdmin(10);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> directory.vendors(null, null, false)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> directory.categories()).getStatusCode().value());
        operator(12, Set.of(10L), Set.of(), Set.of("workspace:access", "order:read"));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> directory.vendors(null, null, false)).getStatusCode().value());
        TenantContext.clear();
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> directory.categories()).getStatusCode().value());
    }

    // ---- categories ----------------------------------------------------------------------------------------------------

    @Test void theEighteenSopCategoriesAreListedInOrderWithTheirActiveVendorCounts() {
        superadmin();
        String tag = unique("Sashes");
        directory.create(input(tag + " A", List.of("SASHES"), "9876543210"));
        directory.create(new VendorInput(tag + " B", List.of("SASHES"), null, "9876543211", null, null, null, null, null, false));
        long activeSashVendors = db.owner.sql("SELECT count(*) FROM workflow.workflow_vendor_category_members m "
                + "JOIN workflow.workflow_vendors v ON v.id = m.vendor_id WHERE m.category_code = 'SASHES' AND v.active").query(Long.class).single();

        List<VendorCategoryView> categories = directory.categories();
        assertEquals(18, categories.size());
        assertEquals("BOARD_SUPPLIERS", categories.get(0).code());
        assertEquals("NON_WOVEN_BAGS", categories.get(17).code());
        assertEquals("Gifting Item Vendors & Printers", categories.stream().filter(c -> c.code().equals("GIFTING")).findFirst().orElseThrow().name());
        assertEquals(activeSashVendors, categories.stream().filter(c -> c.code().equals("SASHES")).findFirst().orElseThrow().vendorCount(),
                "only active vendors are counted");
        assertEquals(18, lookup.categoryCodes().size());
        assertTrue(lookup.categoryCodes().contains("NON_WOVEN_BAGS"));
        assertEquals(lookup.categoryCodes(), lookup.categoryNames().keySet(), "every category has its name");
        assertEquals("Gifting Item Vendors & Printers", lookup.categoryNames().get("GIFTING"));
    }

    @Test void superadminRenamesReordersAndDeactivatesACategoryWhoseCodeStaysTheSame() {
        superadmin();
        VendorCategoryView before = directory.categories().stream().filter(c -> c.code().equals("BATCH_MAKERS")).findFirst().orElseThrow();
        try {
            VendorCategoryView renamed = directory.updateCategory("BATCH_MAKERS", new CategoryChange("  Badge Makers ", null, null));
            assertEquals("Badge Makers", renamed.name());
            assertEquals(before.sortOrder(), renamed.sortOrder(), "what was not sent is kept");
            assertTrue(renamed.active());
            assertEquals(WorkflowExtrasDatabase.SUPER, renamed.updatedBy());

            VendorCategoryView off = directory.updateCategory("BATCH_MAKERS", new CategoryChange(null, false, 40));
            assertEquals("Badge Makers", off.name());
            assertFalse(off.active());
            assertEquals(40, off.sortOrder());
            assertEquals("BATCH_MAKERS", directory.categories().get(17).code(), "the new position orders the list");
            assertTrue(lookup.categoryCodes().contains("BATCH_MAKERS"), "an inactive category is still a category");
            assertEquals("Badge Makers", lookup.categoryNames().get("BATCH_MAKERS"), "the order workflow's gates name it as renamed");

            assertBad("A category name needs 1 to 100 characters", () -> directory.updateCategory("BATCH_MAKERS", new CategoryChange(" ", null, null)));
            assertBad("A category name needs 1 to 100 characters", () -> directory.updateCategory("BATCH_MAKERS", new CategoryChange("n".repeat(101), null, null)));
            assertBad("A category position is a whole number from 1 to 9999", () -> directory.updateCategory("BATCH_MAKERS", new CategoryChange(null, null, 0)));
            assertBad("Send the category's name, active state or position", () -> directory.updateCategory("BATCH_MAKERS", null));
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> directory.updateCategory("NO_SUCH_CATEGORY", new CategoryChange("X", null, null))).getStatusCode().value());
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> directory.updateCategory("batch makers", new CategoryChange("X", null, null))).getStatusCode().value());
        } finally {
            directory.updateCategory("BATCH_MAKERS", new CategoryChange(before.name(), before.active(), before.sortOrder()));
        }
    }

    // ---- the lookup the order workflow uses ------------------------------------------------------------------------------

    @Test void theLookupSeesInactiveVendorsAndLeavesUnknownIdsOut() {
        superadmin();
        String tag = unique("Lookup");
        VendorView offset = directory.create(new VendorInput(tag + " Offset", List.of("OFFSET_PRINTERS", "DIGITAL_PRINTERS"), "Kiran",
                "9876543210", null, null, "Pune", INVITE, null, false));
        VendorView binder = directory.create(input(tag + " Binder", List.of("BINDERS"), "9876543211"));

        List<VendorSummary> found = lookup.vendors(List.of(binder.id(), offset.id(), 987_654_321L, offset.id()));
        assertEquals(List.of(binder.id(), offset.id()), found.stream().map(VendorSummary::id).toList(), "by name; unknown ids left out");
        VendorSummary summary = found.get(1);
        assertEquals(new VendorSummary(offset.id(), offset.name(), List.of("OFFSET_PRINTERS", "DIGITAL_PRINTERS"), "Kiran", "9876543210",
                "Pune", INVITE, false, false), summary);
        assertTrue(lookup.inCategory(offset.id(), "DIGITAL_PRINTERS"), "an inactive vendor still belongs to its categories");
        assertFalse(lookup.inCategory(offset.id(), "BINDERS"));
        assertFalse(lookup.inCategory(987_654_321L, "BINDERS"));
        assertFalse(lookup.inCategory(offset.id(), null));
        assertTrue(lookup.vendors(List.of()).isEmpty());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    static List<Long> ids(List<VendorView> vendors) { return vendors.stream().map(VendorView::id).toList(); }

    static void assertBad(String message, Executable call) {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class, call);
        assertEquals(400, refused.getStatusCode().value());
        assertEquals(message, refused.getReason());
    }
}
