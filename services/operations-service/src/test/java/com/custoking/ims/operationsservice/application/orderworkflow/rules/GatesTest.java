package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.nb;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.nine;
import static org.junit.jupiter.api.Assertions.*;

/** Every gate type of design D19, ported from the tracker's CompletionGates rules and returning every failure. */
class GatesTest {
    private static final String CDR = "https://drive.example.com/cdr-v2";

    private static GateFacts data(Map<String, Object> data) {
        return GateFacts.ofData(data);
    }

    private static GateFacts design(String state, String approvedLink, String currentLink, int files) {
        return new GateFacts(Map.of(), false, state, approvedLink, currentLink, files, Map.of(), 0);
    }

    private static GateFacts vendors(Map<String, List<Long>> selections, Map<String, String> names) {
        return new GateFacts(Map.of(), false, null, null, null, 0, selections, 0, names);
    }

    private static GateFacts counts(long ordered, Map<String, Object> data) {
        return new GateFacts(data, false, null, null, null, 0, Map.of(), ordered);
    }

    private static List<String> messages(List<GateFailure> failures) {
        return failures.stream().map(GateFailure::message).toList();
    }

    @Test
    void fieldsPresentListsEachMissingFieldByItsLabel() {
        assertEquals(List.of("Save the variance report link", "Save the delivery challan link", "Save the bills link"),
                messages(Gates.unmet(nb(11), data(Map.of()))));
        List<GateFailure> partly = Gates.unmet(nb(11), data(Map.of("varianceReportLink", "https://x.example.com/v", "billsLink", "  ")));
        assertEquals(List.of("Save the delivery challan link", "Save the bills link"), messages(partly), "blank text is not saved");
        assertTrue(partly.stream().allMatch(failure -> failure.code().equals("FIELDS_PRESENT")));
        assertTrue(Gates.unmet(nb(11), data(Map.of("varianceReportLink", "https://x.example.com/v",
                "deliveryChallanLink", "https://x.example.com/c", "billsLink", "https://x.example.com/b"))).isEmpty());
    }

    @Test
    void labelsThatStartWithAnAcronymKeepTheirCase() {
        assertEquals("controlled storage link", Gates.phrase("Controlled storage link"));
        assertEquals("LR / docket number", Gates.phrase("LR / docket number"));
        assertEquals("QC reviewer comments", Gates.phrase("QC reviewer comments"));
    }

    @Test
    void allCheckedNamesTheLastItemOrCountsTheRemainingOnes() {
        assertEquals(List.of("Tick the remaining 3 items of Conversion checks"), messages(Gates.unmet(nb(3),
                data(Map.of("cdrLink", CDR)))));
        assertEquals(List.of("Tick \"Artwork matches approved version\""), messages(Gates.unmet(nb(3),
                data(Map.of("cdrLink", CDR, "cdrChecks", List.of(0, 1))))));
        assertTrue(Gates.unmet(nb(3), data(Map.of("cdrLink", CDR, "cdrChecks", List.of(2L, 1L, 0L)))).isEmpty(),
                "indexes read back from JSON as longs count too");
        assertEquals(List.of("Save the school order PDF link", "Tick \"Introduction message sent in the school group\""),
                messages(Gates.unmet(nb(1), data(Map.of()))));
    }

    @Test
    void allCheckedExcusesTheSignOffItem() {
        Map<String, Object> qc = new HashMap<>(Map.of("checks", List.of(0, 1, 2, 3, 4, 5, 6), "sampleLink", "https://x.example.com/s",
                "schoolVerification", "NOT_REQUIRED"));
        GateFacts signed = new GateFacts(qc, true, null, null, null, 0, Map.of(), 0);
        assertTrue(Gates.unmet(nb(7), signed).isEmpty(), "item 8 is ticked by the sign-off, never required of the data");
        GateFacts unsigned = new GateFacts(qc, false, null, null, null, 0, Map.of(), 0);
        assertEquals(List.of("Record reviewer sign-off"), messages(Gates.unmet(nb(7), unsigned)));
        assertEquals("SIGNED_OFF", Gates.unmet(nb(7), unsigned).get(0).code());
    }

    @Test
    void choiceInNamesTheAcceptedChoicesByTheirLabels() {
        Map<String, Object> qc = new HashMap<>(Map.of("checks", List.of(0, 1, 2, 3, 4, 5, 6), "sampleLink", "https://x.example.com/s"));
        GateFacts missing = new GateFacts(qc, true, null, null, null, 0, Map.of(), 0);
        assertEquals(List.of("School verification must be Verified or Not required"), messages(Gates.unmet(nb(7), missing)));
        for (String refused : List.of("PENDING", "CHANGES_REQUIRED")) {
            qc.put("schoolVerification", refused);
            assertEquals("CHOICE_IN", Gates.unmet(nb(7), new GateFacts(qc, true, null, null, null, 0, Map.of(), 0)).get(0).code());
        }
        for (String accepted : List.of("VERIFIED", "NOT_REQUIRED")) {
            qc.put("schoolVerification", accepted);
            assertTrue(Gates.unmet(nb(7), new GateFacts(qc, true, null, null, null, 0, Map.of(), 0)).isEmpty());
        }
    }

    @Test
    void designFilesPresentNeedsAtLeastOneFile() {
        assertEquals(List.of("Upload at least one design file"), messages(Gates.unmet(nb(2), design("NOT_SENT", null, null, 0))));
        assertEquals("DESIGN_FILES_PRESENT", Gates.unmet(nb(2), design("NOT_SENT", null, null, 0)).get(0).code());
        assertTrue(Gates.unmet(nb(2), design("NOT_SENT", null, null, 1)).isEmpty());
    }

    @Test
    void vendorsSelectedNeedsEachListedCategory() {
        Map<String, List<Long>> two = Map.of("BOARD_SUPPLIERS", List.of(1L), "OFFSET_PRINTERS", List.of(4L, 5L), "BINDERS", List.of());
        List<GateFailure> failures = Gates.unmet(nb(5), new GateFacts(Map.of("planningChecks", List.of(0, 1, 2)), false, null, null, null, 0, two, 0));
        assertEquals(List.of("Select a vendor in Lamination Vendors", "Select a vendor in Binders"), messages(failures),
                "an empty selection does not count");
        assertEquals(List.of("Select a vendor in Lamination Vendors", "Select a vendor in Binding Houses"),
                messages(Gates.unmet(nb(5), new GateFacts(Map.of("planningChecks", List.of(0, 1, 2)), false, null, null, null, 0, two, 0,
                        Map.of("BINDERS", "Binding Houses")))), "a renamed category reads by its current name");
        Map<String, List<Long>> all = Map.of("BOARD_SUPPLIERS", List.of(1L), "LAMINATION_VENDORS", List.of(2L), "BINDERS", List.of(3L),
                "OFFSET_PRINTERS", List.of(4L));
        assertTrue(Gates.unmet(nb(5), new GateFacts(Map.of("planningChecks", List.of(0, 1, 2)), false, null, null, null, 0, all, 0)).isEmpty());
    }

    @Test
    void vendorsSelectedWithoutCategoriesNeedsAnyOneVendor() {
        assertEquals(List.of("Select at least one vendor"), messages(Gates.unmet(nine(5), vendors(Map.of(), Map.of()))));
        assertEquals(List.of("Select at least one vendor"), messages(Gates.unmet(nine(5), vendors(Map.of("FLEX_PRINTERS", List.of()), Map.of()))));
        assertTrue(Gates.unmet(nine(5), vendors(Map.of("FLEX_PRINTERS", List.of(9L)), Map.of())).isEmpty());
    }

    @Test
    void headApprovedNeedsAnApprovalBoundToTheLinkSavedNow() {
        assertEquals(List.of("Save the CDR link in CDR Conversion, then approve the design"),
                messages(Gates.unmet(nb(4), design("SENT", null, null, 1))));
        assertEquals(List.of("Approve the design"), messages(Gates.unmet(nb(4), design("SENT", null, CDR, 1))));
        assertEquals(List.of("Approve the design"), messages(Gates.unmet(nb(4), design("CHANGES_REQUESTED", null, CDR, 1))));
        assertEquals(List.of("The CDR link changed after approval; approve the current link"),
                messages(Gates.unmet(nb(4), design("APPROVED", "https://drive.example.com/cdr-v1", CDR, 1))));
        assertTrue(Gates.unmet(nb(4), design("APPROVED", CDR, CDR, 1)).isEmpty());
        assertTrue(Gates.headApprovalCurrent("APPROVED", CDR, CDR));
        assertFalse(Gates.headApprovalCurrent("APPROVED", null, null));
        assertFalse(Gates.headApprovalCurrent("SENT", CDR, CDR));
    }

    @Test
    void countsAreOptionalButAMismatchNeedsADiscrepancyNote() {
        Map<String, Object> links = Map.of("varianceReportLink", "https://x.example.com/v", "deliveryChallanLink", "https://x.example.com/c",
                "billsLink", "https://x.example.com/b");
        Map<String, Object> partial = new HashMap<>(links);
        partial.put("producedCount", 990);
        assertTrue(Gates.unmet(nb(11), counts(1000, partial)).isEmpty(), "counts are optional (C9)");

        Map<String, Object> matching = new HashMap<>(links);
        matching.putAll(Map.of("producedCount", 990, "acceptedCount", 980, "dispatchCount", 980));
        assertTrue(Gates.unmet(nb(11), counts(1000, matching)).isEmpty());

        Map<String, Object> mismatch = new HashMap<>(links);
        mismatch.putAll(Map.of("producedCount", 990, "acceptedCount", 980, "dispatchCount", 970));
        List<GateFailure> failures = Gates.unmet(nb(11), counts(1000, mismatch));
        assertEquals(List.of("The counts do not match; save the discrepancy / approval notes"), messages(failures));
        assertEquals("COUNTS_CONSISTENT", failures.get(0).code());

        mismatch.put("discrepancyNote", "Ten damaged in transit");
        assertTrue(Gates.unmet(nb(11), counts(1000, mismatch)).isEmpty());

        Map<String, Object> overProduced = new HashMap<>(links);
        overProduced.putAll(Map.of("producedCount", 1010, "acceptedCount", 1010, "dispatchCount", 1010));
        assertEquals(1, Gates.unmet(nb(11), counts(1000, overProduced)).size(), "more produced than ordered is a mismatch");
    }

    @Test
    void everyUnmetRequirementIsListedInGateOrder() {
        List<GateFailure> failures = Gates.unmet(nine(9), data(Map.of("deliveryStatus", "PENDING")));
        assertEquals(List.of("FIELDS_PRESENT", "FIELDS_PRESENT", "FIELDS_PRESENT", "CHOICE_IN", "SIGNED_OFF"),
                failures.stream().map(GateFailure::code).toList());
        assertEquals(List.of("Save the transporter / vehicle", "Save the dispatch date", "Save the proof of delivery / invoice link",
                "Delivery status must be Delivered", "Record consignment sign-off"), messages(failures));
        assertFalse(Gates.met(nine(9), data(Map.of())));
    }

    @Test
    void finalApprovalNeedsTheCheckTheNotesAndTheInternalApproval() {
        assertEquals(List.of("Tick \"Finished items checked against approved specifications\"", "Save the final validation notes",
                "Record internal approval"), messages(Gates.unmet(nine(8), data(Map.of()))));
        GateFacts ready = new GateFacts(Map.of("finalChecks", List.of(0), "finalValidationNotes", "Counted and checked"), true,
                null, null, null, 0, Map.of(), 0);
        assertTrue(Gates.met(nine(8), ready));
    }

    @Test
    void aStageWithoutGatesIsAlwaysMet() {
        assertTrue(Gates.met(TemplateFixtures.load("minimal/MINIMAL_WORKFLOW.json").step(2).orElseThrow(), data(Map.of())));
    }

    @Test
    void countsMatchOnlyWhenAcceptedEqualsDispatchWithinProducedWithinOrdered() {
        assertFalse(new Counts(1000L, 1000L, 1000L, 1000L).mismatch());
        assertFalse(new Counts(1000L, 990L, 980L, 980L).mismatch());
        assertTrue(new Counts(1000L, 990L, 980L, 970L).mismatch());
        assertTrue(new Counts(1000L, 990L, 995L, 995L).mismatch());
        assertTrue(new Counts(1000L, 1010L, 1010L, 1010L).mismatch());
        assertFalse(new Counts(1000L, null, 1L, 1L).complete());
        assertFalse(new Counts(1000L, -1L, 1L, 1L).complete());
        assertThrows(IllegalStateException.class, () -> new Counts(1000L, null, 1L, 1L).mismatch());
    }
}
