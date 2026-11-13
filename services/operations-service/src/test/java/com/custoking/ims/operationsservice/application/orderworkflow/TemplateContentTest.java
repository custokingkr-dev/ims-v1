package com.custoking.ims.operationsservice.application.orderworkflow;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The content of the five item-order workflow templates (design v1 §2.7, §3.3, D17, D23, D28, D33, D41), read straight from
 * the JSON resources so it does not depend on the engine's loader: the verbatim checklist wording and order, where each
 * wording comes from, the default SLAs, the introduction messages, the gates each step enforces, the vendor categories each
 * card shows, the notebook fields kept for trackers saved before the templates, and the closed sets of §3.3.
 */
class TemplateContentTest {

    static final String NB = "NOTEBOOK_ORDER_TRACKER";
    static final String FE = "FLIER_ORDER_WORKFLOW";
    static final String FX = "FLEX_ORDER_WORKFLOW";
    static final String BBK = "BILL_BOOK_ORDER_WORKFLOW";
    static final String GENERAL = "GENERAL_ITEM_ORDER_WORKFLOW";
    static final List<String> NINE_STEP = List.of(FE, FX, BBK, GENERAL);
    static final List<String> ALL = List.of(NB, FE, FX, BBK, GENERAL);

    static final Set<String> BLOCK_TYPES = Set.of("ORDER_PDF_LINK", "INTRO_MESSAGE", "SCHOOL_GROUP", "DESIGN_FILES", "FIELDS",
            "CHECKLIST", "CDR_FOR_APPROVAL", "HEAD_APPROVAL", "ORDER_FOLDER", "TITLE_CARD_QUANTITIES", "SAVED_TITLE_CARDS",
            "ITEM_QUANTITIES", "VENDOR_DIRECTORY", "VENDOR_SELECTION", "VENDOR_GROUPS", "INTERNAL_APPROVAL_GROUP", "SIGN_OFF",
            "SEND_BACK", "LEGACY_DETAILS");
    static final Set<String> GATE_TYPES = Set.of("FIELDS_PRESENT", "ALL_CHECKED", "CHOICE_IN", "DESIGN_FILES_PRESENT",
            "VENDORS_SELECTED", "HEAD_APPROVED", "SIGNED_OFF", "COUNTS_CONSISTENT");
    static final Set<String> FIELD_KINDS = Set.of("TEXT", "WORDS", "URL", "DATE", "WHOLE", "NUMBER", "CHOICE");
    static final Set<String> PHASES = Set.of("INTAKE", "PLANNING", "PRODUCTION", "VERIFICATION", "DELIVERY");
    static final Set<String> SOURCES = Set.of("prototype", "sop", "note", "ours");
    static final List<String> ALL_TABS = List.of("WORK", "SCHOOL_VENDOR", "CHECKLIST", "ACTIVITY");
    /** The SOP's 18 vendor categories (D31), as V19 seeds them. */
    static final Set<String> VENDOR_CATEGORIES = Set.of("BOARD_SUPPLIERS", "LAMINATION_VENDORS", "BINDERS", "OFFSET_PRINTERS",
            "DIGITAL_PRINTERS", "FLEX_PRINTERS", "PHOTO_PAPER_PRINTERS", "BILL_BOOK_MAKERS", "BATCH_MAKERS", "FLUTE_BOARD_MAKERS",
            "TROPHIES_MEDALS", "FILE_MAKERS", "SASHES", "DIARIES", "ID_CARDS", "FLAPS", "GIFTING", "NON_WOVEN_BAGS");
    static final List<String> NB_VENDOR_CATEGORIES = List.of("BOARD_SUPPLIERS", "LAMINATION_VENDORS", "BINDERS", "OFFSET_PRINTERS");

    static final List<String> FOLLOW_UP_ITEMS = List.of(
            "Print job confirmed with the vendor and schedule agreed",
            "Printing completed",
            "Finishing completed (cutting, lamination or binding as applicable)",
            "Finished items counted, packed and ready for dispatch");

    static Map<String, Map<String, Object>> templates;

    @BeforeAll
    static void load() throws Exception {
        templates = new LinkedHashMap<>();
        JsonMapper json = JsonMapper.builder().build();
        for (String id : ALL) {
            try (InputStream in = TemplateContentTest.class.getResourceAsStream("/workflow-templates/" + id + ".json")) {
                assertNotNull(in, "missing template " + id);
                templates.put(id, map(json.readValue(in, Map.class)));
            }
        }
    }

    // ---- the template set ------------------------------------------------------------------------------------------

    @Test
    void thereIsOneFilePerDefinitionNamedAfterIt() throws Exception {
        URL folder = TemplateContentTest.class.getResource("/workflow-templates");
        assertNotNull(folder);
        try (Stream<Path> files = Files.list(Path.of(folder.toURI()))) {
            assertEquals(new TreeSet<>(ALL.stream().map(id -> id + ".json").toList()),
                    files.map(path -> path.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new)));
        }
        for (String id : ALL) assertEquals(id, templates.get(id).get("definitionId"));
    }

    @Test
    void titlesNameEachItemsWorkflow() {
        assertEquals("Notebook order workflow", templates.get(NB).get("title"));
        assertEquals("Flier order workflow", templates.get(FE).get("title"));
        assertEquals("Flex order workflow", templates.get(FX).get("title"));
        assertEquals("Bill book order workflow", templates.get(BBK).get("title"));
        assertEquals("General item order workflow", templates.get(GENERAL).get("title"));
    }

    @Test
    void introductionMessagesAreTheSopWording() {
        assertEquals("My name is {Name}, and I will be your point of contact for all notebook-related orders. "
                + "Please feel free to reach out for any queries, updates, or follow-ups.", templates.get(NB).get("introMessage"));
        assertEquals("My name is {Name}, and I will be handling your flier order. "
                + "Please reach out for any queries, updates, or follow-ups.", templates.get(FE).get("introMessage"));
        assertEquals("My name is {Name}, and I will be handling your flex order. "
                + "Please reach out for any queries, updates, or follow-ups.", templates.get(FX).get("introMessage"));
        assertEquals("My name is {Name}, and I will be handling your bill book order. "
                + "Please reach out for any queries, updates, or follow-ups.", templates.get(BBK).get("introMessage"));
        // The general template serves several items; the browser fills {Item} with the category's noun (D8, D28).
        assertEquals("My name is {Name}, and I will be handling your {Item} order. "
                + "Please reach out for any queries, updates, or follow-ups.", templates.get(GENERAL).get("introMessage"));
    }

    /**
     * D28: ticking "Introduction message sent in the school group" records the final text, so the introduction card saves the
     * message as sent (the name filled in, any edits kept) into Step 1's one TEXT field. No gate reads it.
     */
    @Test
    void eachIntroductionCardKeepsTheMessageAsSent() {
        for (String id : ALL) {
            Map<String, Object> card = blocks(step(id, 1), "INTRO_MESSAGE").getFirst();
            assertEquals(List.of("introText"), card.get("fields"), id);
            assertEquals("introChecks", card.get("checklist"), id);
            assertEquals(Map.of("kind", "TEXT", "max", 1000), kindAndMax(field(id, 1, "introText")), id);
            assertEquals(List.of("FIELDS_PRESENT:orderPdfLink", "ALL_CHECKED:introChecks"), gates(id).getFirst(), id);
        }
    }

    /** D25: Step 5 of a nine-step order titles the quantities with the item's own noun ("Certificate quantities"). */
    @Test
    void theQuantitiesCardNamesTheItem() {
        assertEquals("Flier quantities", blocks(step(FE, 5), "ITEM_QUANTITIES").getFirst().get("title"));
        assertEquals("Flex quantities", blocks(step(FX, 5), "ITEM_QUANTITIES").getFirst().get("title"));
        assertEquals("Bill book quantities", blocks(step(BBK, 5), "ITEM_QUANTITIES").getFirst().get("title"));
        // The general template serves several items, so the card takes the order's noun on screen instead of a fixed title.
        assertNull(blocks(step(GENERAL, 5), "ITEM_QUANTITIES").getFirst().get("title"));
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    @Test
    void theNotebookTemplateHasTheTwelveStepsInSopOrder() {
        assertEquals(List.of("ORDER_DETAILS", "DESIGNING_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING",
                "BOARD_PROCUREMENT", "PRE_PRINT_QUALITY_GATE", "PRINTING_PRESS", "FACTORY_HANDOVER", "PRODUCTION_FOLLOW_UP",
                "VALIDATION_COUNT", "DELIVERY_INVOICING"), column(NB, "code"));
        assertEquals(List.of("Order Details", "Designing Activity", "CDR Conversion", "Head Approval", "Production Planning",
                "Board Procurement", "Pre-Print Quality Check", "Printing Press", "Factory Handover", "Production Follow-up",
                "Validation Count", "Delivery & Invoicing"), column(NB, "title"));
        assertEquals(List.of("INTAKE", "INTAKE", "INTAKE", "INTAKE", "PLANNING", "PLANNING", "PRODUCTION", "PRODUCTION",
                "PRODUCTION", "PRODUCTION", "VERIFICATION", "DELIVERY"), column(NB, "phase"));
        // Wholesale notebooks skip Design, CDR and Head Approval (D7).
        assertEquals(List.of(false, true, true, true, false, false, false, false, false, false, false, false),
                column(NB, "customisedOnly"));
    }

    @Test
    void theNineStepTemplatesShareTheSopShape() {
        for (String id : NINE_STEP) {
            assertEquals(List.of("ORDER_DETAILS", "DESIGNING_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING",
                    "PRE_PRINT_QUALITY_CHECK", "PRODUCTION_FOLLOW_UP", "FINAL_APPROVAL", "DELIVERY_INVOICING"), column(id, "code"), id);
            assertEquals(List.of("Order Details", "Designing Activity", "CDR Conversion", "Head Approval", "Production Planning",
                    "Pre-Print Quality Check", "Production Follow-up", "Final Approval", "Delivery & Invoicing"), column(id, "title"), id);
            assertEquals(List.of("INTAKE", "INTAKE", "INTAKE", "INTAKE", "PLANNING", "PRODUCTION", "PRODUCTION", "VERIFICATION",
                    "DELIVERY"), column(id, "phase"), id);
            assertTrue(column(id, "customisedOnly").stream().noneMatch(Boolean.TRUE::equals), id + " skips nothing");
        }
    }

    /** school-core's PR #380 stages map one to one onto the nine-step codes, except two renames (D4). */
    @Test
    void theNineStepCodesMapThePr380StagesForImports() {
        List<String> v36 = List.of("ORDER_DETAILS", "DESIGN_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING",
                "PRE_PRINT_QUALITY_CHECK", "PRODUCTION_FOLLOW_UP", "FINAL_VERIFICATION", "DELIVERY_INVOICING");
        Map<String, String> renamed = Map.of("DESIGN_ACTIVITY", "DESIGNING_ACTIVITY", "FINAL_VERIFICATION", "FINAL_APPROVAL");
        for (String id : NINE_STEP) {
            assertEquals(v36.stream().map(code -> renamed.getOrDefault(code, code)).toList(), column(id, "code"), id);
        }
    }

    @Test
    void stepsAreNumberedFromOneWithCodesThatCanBeOrderStatuses() {
        for (String id : ALL) {
            List<Map<String, Object>> steps = steps(id);
            for (int i = 0; i < steps.size(); i++) {
                assertEquals(i + 1, steps.get(i).get("order"), id);
                String code = (String) steps.get(i).get("code");
                // school-core accepts a workflow status by this pattern (D5), and the code is the status while active.
                assertTrue(code.matches("^[A-Z][A-Z0-9_]{1,63}$"), code);
                assertTrue(PHASES.contains(steps.get(i).get("phase")), id + " " + code);
                assertFalse(((String) steps.get(i).get("description")).isBlank(), id + " " + code);
                assertFalse(((String) steps.get(i).get("icon")).isBlank(), id + " " + code);
            }
            assertEquals(steps.size(), new HashSet<>(column(id, "code")).size(), id + " codes are unique");
        }
    }

    @Test
    void defaultSlasAreTheDecidedHours() {
        assertEquals(List.of(2, 72, 2, 2, 24, 6, 24, 50, 4, 72, 2, 4), slaHours(NB));
        for (String id : NINE_STEP) assertEquals(List.of(2, 72, 2, 2, 24, 24, 72, 4, 4), slaHours(id), id);
    }

    @Test
    void onlyHeadApprovalIsCompletedBySuperadmin() {
        for (String id : ALL) {
            for (Map<String, Object> step : steps(id)) {
                assertEquals("HEAD_APPROVAL".equals(step.get("code")), Boolean.TRUE.equals(step.get("superadminCompletes")),
                        id + " " + step.get("code"));
            }
        }
    }

    @Test
    void stepOneHasNoSchoolAndVendorTab() {
        for (String id : ALL) {
            List<Map<String, Object>> steps = steps(id);
            assertEquals(List.of("WORK", "CHECKLIST", "ACTIVITY"), steps.getFirst().get("tabs"), id);
            for (Map<String, Object> step : steps.subList(1, steps.size())) assertEquals(ALL_TABS, step.get("tabs"), id);
        }
    }

    // ---- checklists ------------------------------------------------------------------------------------------------

    @Test
    void notebookChecklistsAreVerbatimAndKeepTheirSavedPositions() {
        assertEquals(List.of(1, 3, 3, 8, 8, 1, 8), List.of(
                items(NB, 1, "introChecks").size(), items(NB, 3, "cdrChecks").size(), items(NB, 5, "planningChecks").size(),
                items(NB, 7, "checks").size(), items(NB, 8, "pressChecks").size(), items(NB, 9, "handoverChecks").size(),
                items(NB, 10, "productionChecks").size()));
        assertEquals(List.of("Introduction message sent in the school group"), items(NB, 1, "introChecks"));
        assertEquals(List.of("Fonts outlined", "Colours checked", "Artwork matches approved version"), items(NB, 3, "cdrChecks"));
        assertEquals(List.of("NOTEBOOK ORDER SHARED WITH BINDER", "BOARDS AVAILABILITY CHECKED", "DESIGN STATUS CHECKED"),
                items(NB, 5, "planningChecks"));
        assertEquals(List.of(
                "School name, logo and spelling verified",
                "Latest approved artwork version confirmed",
                "Dimensions, bleed and safe margins checked",
                "Board material, GSM and print specifications verified",
                "Physical print sample inspected for defects",
                "School verification recorded where required",
                "Print quantity and title-wise breakup verified",
                "Authorised quality reviewer releases bulk printing"), items(NB, 7, "checks"));
        assertEquals(List.of(
                "VERIFIED THE IMPRESSIONS FOR EACH DESIGN",
                "DESIGN SENT FOR MAKING PLATES",
                "DESIGN PLATES RECEIVED",
                "PRINTING SCHEDULED/ EXPECTED COMPLETION TIME",
                "COMPLETION OF PRINTING",
                "RECEIVED FOR LAMINATION",
                "SCHEDULED FOR LAMINATION",
                "LAMINATION COMPLETED"), items(NB, 8, "pressChecks"));
        assertEquals(List.of("Verified the titles received"), items(NB, 9, "handoverChecks"));
        assertEquals(List.of("CONFIRMED RECEIVING THE TITLE", "FOLDING OF TITLE", "RULED PAPER RECEIVED", "PAPER COUNTING",
                "PAPER FILLING", "PINNING", "CUTTING", "PACKING"), items(NB, 10, "productionChecks"));
    }

    @Test
    void nineStepChecklistsAreVerbatim() {
        assertEquals(List.of("Flier artwork and print specifications checked and approved"), items(FE, 6, "qcChecks"));
        assertEquals(List.of("Checked the size of the flex", "Verified the quantity of the flex", "Verified the design file"),
                items(FX, 6, "qcChecks"));
        assertEquals(List.of("Verified the bill book size", "Verified number of books and copies", "Verified the design file"),
                items(BBK, 6, "qcChecks"));
        assertEquals(List.of("Verified the item size and specifications", "Verified the quantity", "Verified the design file"),
                items(GENERAL, 6, "qcChecks"));
        for (String id : NINE_STEP) {
            assertEquals(List.of("Introduction message sent in the school group"), items(id, 1, "introChecks"), id);
            assertEquals(List.of("Fonts outlined", "Colours checked", "Artwork matches approved version"), items(id, 3, "cdrChecks"), id);
            assertEquals(FOLLOW_UP_ITEMS, items(id, 7, "followUpChecks"), id);
            assertEquals(List.of("Finished items checked against approved specifications"), items(id, 8, "finalChecks"), id);
        }
    }

    /** D17: prototype wording first, then the SOP or the presenter's notes, else ours, which is marked and asked about. */
    @Test
    void everyChecklistNamesWhereItsWordingComesFrom() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(NB + "/1/introChecks", "ours");
        expected.put(NB + "/3/cdrChecks", "note");
        expected.put(NB + "/5/planningChecks", "prototype");
        expected.put(NB + "/7/checks", "prototype");
        expected.put(NB + "/8/pressChecks", "prototype");
        expected.put(NB + "/9/handoverChecks", "prototype");
        expected.put(NB + "/10/productionChecks", "prototype");
        for (String id : NINE_STEP) {
            expected.put(id + "/1/introChecks", "ours");
            expected.put(id + "/3/cdrChecks", "note");
            expected.put(id + "/7/followUpChecks", "ours");
            expected.put(id + "/8/finalChecks", "note");
        }
        expected.put(FE + "/6/qcChecks", "prototype");
        expected.put(FX + "/6/qcChecks", "note");
        expected.put(BBK + "/6/qcChecks", "note");
        expected.put(GENERAL + "/6/qcChecks", "ours");

        Map<String, String> actual = new LinkedHashMap<>();
        for (String id : ALL) {
            for (Map<String, Object> step : steps(id)) {
                map(step.get("checklists")).forEach((key, spec) -> {
                    String source = (String) map(spec).get("source");
                    assertTrue(SOURCES.contains(source), id + " " + key + " " + source);
                    actual.put(id + "/" + step.get("order") + "/" + key, source);
                });
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void onlyTheQcReviewerSignOffTicksAChecklistItem() {
        for (String id : ALL) {
            for (Map<String, Object> step : steps(id)) {
                map(step.get("checklists")).forEach((key, raw) -> {
                    Object signOffItem = map(raw).get("signOffItem");
                    if (id.equals(NB) && step.get("order").equals(7)) {
                        // Item 8, "Authorised quality reviewer releases bulk printing", is set by the sign-off alone (D20).
                        assertEquals(7, signOffItem);
                    } else {
                        assertNull(signOffItem, id + " " + step.get("code") + " " + key);
                    }
                });
            }
        }
    }

    // ---- gates (what Complete requires, §2.7) ----------------------------------------------------------------------

    @Test
    void notebookGatesAreTheDecidedOnes() {
        assertEquals(List.of(
                List.of("FIELDS_PRESENT:orderPdfLink", "ALL_CHECKED:introChecks"),
                List.of("DESIGN_FILES_PRESENT"),
                List.of("FIELDS_PRESENT:cdrLink", "ALL_CHECKED:cdrChecks"),
                List.of("HEAD_APPROVED"),
                List.of("ALL_CHECKED:planningChecks", "VENDORS_SELECTED:BOARD_SUPPLIERS,LAMINATION_VENDORS,BINDERS,OFFSET_PRINTERS"),
                List.of("FIELDS_PRESENT:boardBillLink"),
                List.of("ALL_CHECKED:checks-except-7", "FIELDS_PRESENT:sampleLink",
                        "CHOICE_IN:schoolVerification=VERIFIED,NOT_REQUIRED", "SIGNED_OFF"),
                List.of("ALL_CHECKED:pressChecks"),
                List.of("ALL_CHECKED:handoverChecks"),
                List.of("ALL_CHECKED:productionChecks"),
                List.of("FIELDS_PRESENT:varianceReportLink,deliveryChallanLink,billsLink",
                        "COUNTS_CONSISTENT:producedCount,acceptedCount,dispatchCount->discrepancyNote"),
                List.of("FIELDS_PRESENT:transporter,dispatchDate,proofLink", "SIGNED_OFF")), gates(NB));
    }

    @Test
    void nineStepGatesAreTheDecidedOnes() {
        for (String id : NINE_STEP) {
            assertEquals(List.of(
                    List.of("FIELDS_PRESENT:orderPdfLink", "ALL_CHECKED:introChecks"),
                    List.of("DESIGN_FILES_PRESENT"),
                    List.of("FIELDS_PRESENT:cdrLink", "ALL_CHECKED:cdrChecks"),
                    List.of("HEAD_APPROVED"),
                    List.of("VENDORS_SELECTED:any"),
                    List.of("ALL_CHECKED:qcChecks"),
                    List.of("ALL_CHECKED:followUpChecks"),
                    List.of("ALL_CHECKED:finalChecks", "FIELDS_PRESENT:finalValidationNotes", "SIGNED_OFF"),
                    List.of("FIELDS_PRESENT:transporter,dispatchDate,proofLink", "CHOICE_IN:deliveryStatus=DELIVERED", "SIGNED_OFF")),
                    gates(id), id);
        }
    }

    @Test
    void signOffsAreSuperadminActionsWithTheirLabels() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(NB + "/7", "Record reviewer sign-off");
        expected.put(NB + "/12", "Record consignment sign-off");
        for (String id : NINE_STEP) {
            expected.put(id + "/8", "Record internal approval");
            expected.put(id + "/9", "Record consignment sign-off");
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (String id : ALL) {
            for (Map<String, Object> step : steps(id)) {
                Object signOff = step.get("signOff");
                boolean gated = gateTypes(step).contains("SIGNED_OFF");
                boolean carded = blockTypes(step).contains("SIGN_OFF");
                assertEquals(signOff != null, gated, id + " " + step.get("code") + ": a sign-off spec exactly where a gate needs one");
                assertEquals(signOff != null, carded, id + " " + step.get("code") + ": a sign-off card exactly where a sign-off exists");
                if (signOff != null) {
                    assertEquals("SUPERADMIN", map(signOff).get("role"));
                    actual.put(id + "/" + step.get("order"), (String) map(signOff).get("label"));
                }
            }
        }
        assertEquals(expected, actual);
        assertEquals("Bulk printing blocked until all checks and reviewer sign-off are complete.",
                map(step(NB, 7).get("signOff")).get("banner"));
    }

    // ---- fields ----------------------------------------------------------------------------------------------------

    @Test
    void wordLimitsAreTheSops() {
        assertEquals(Map.of("kind", "WORDS", "max", 200), kindAndMax(field(NB, 11, "discrepancyNote")));
        for (String id : NINE_STEP) assertEquals(Map.of("kind", "WORDS", "max", 100), kindAndMax(field(id, 8, "finalValidationNotes")), id);
    }

    @Test
    void evidenceDocumentsAreLinks() {
        List<String> nbLinks = List.of("1/orderPdfLink", "3/cdrLink", "6/boardBillLink", "7/sampleLink", "11/varianceReportLink",
                "11/deliveryChallanLink", "11/billsLink", "12/proofLink");
        for (String entry : nbLinks) {
            String[] parts = entry.split("/");
            assertEquals("URL", field(NB, Integer.parseInt(parts[0]), parts[1]).get("kind"), entry);
        }
        for (String id : NINE_STEP) {
            for (String entry : List.of("1/orderPdfLink", "3/cdrLink", "9/proofLink")) {
                String[] parts = entry.split("/");
                assertEquals("URL", field(id, Integer.parseInt(parts[0]), parts[1]).get("kind"), id + " " + entry);
            }
        }
    }

    @Test
    void choicesAreTheDecidedLists() {
        assertEquals(List.of(List.of("NOT_REQUIRED", "Not required"), List.of("PENDING", "Pending"), List.of("VERIFIED", "Verified"),
                List.of("CHANGES_REQUIRED", "Not verified – changes needed")), field(NB, 7, "schoolVerification").get("choices"));
        List<List<String>> invoice = List.of(List.of("NOT_INITIATED", "Not initiated"), List.of("INITIATED", "Initiated"),
                List.of("SENT", "Sent"), List.of("PAID", "Paid"));
        assertEquals(invoice, field(NB, 12, "invoiceStatus").get("choices"));
        for (String id : NINE_STEP) {
            assertEquals(invoice, field(id, 9, "invoiceStatus").get("choices"), id);
            assertEquals(List.of(List.of("PENDING", "Pending"), List.of("DELIVERED", "Delivered")),
                    field(id, 9, "deliveryStatus").get("choices"), id);
        }
    }

    /**
     * D41: a notebook tracker saved before the templates keeps working. Every field the old per-step schema accepted is still
     * accepted, so a partial save never rejects or drops it; the ones the screens no longer edit are legacy (read-only).
     */
    @Test
    void notebookStepsStillAcceptEveryFieldSavedBeforeTheTemplates() {
        Map<Integer, Set<String>> before = Map.of(
                3, Set.of("cdrFilename", "cdrLink", "cdrNotes"),
                5, Set.of("paperGsm", "capacityConfirmed", "productionStart", "targetCompletion", "planningNotes", "planningChecks",
                        "workbookFileName", "workbookSheetName", "workbookOrderId", "workbookImportedAt", "workbookTotalQuantity",
                        "workbookKing", "workbookJumboKing", "workbookLong", "workbookJumboLong", "workbookFa", "workbookValidRows"),
                6, Set.of("totalTitleUnits", "piecesPerBoard", "wastagePercent", "availableBoards", "procurementReference"),
                7, Set.of("checks", "sampleLink", "schoolVerification", "qcComments"),
                8, Set.of("jobReference", "quantitySent", "expectedReturnDate", "pressChecks"),
                9, Set.of("factory", "jobReference", "titlesSent", "titlesReceived", "factoryContact"),
                10, Set.of("productionChecks"),
                11, Set.of("producedCount", "acceptedCount", "dispatchCount", "discrepancyNote"),
                12, Set.of("transporter", "dispatchDate", "lrNumber", "invoiceNumber", "proofLink", "invoiceStatus"));
        Map<Integer, Set<String>> legacy = Map.of(
                3, Set.of("cdrFilename"),
                5, Set.of("paperGsm", "capacityConfirmed", "productionStart", "planningNotes", "workbookFileName", "workbookSheetName",
                        "workbookOrderId", "workbookImportedAt", "workbookTotalQuantity", "workbookKing", "workbookJumboKing",
                        "workbookLong", "workbookJumboLong", "workbookFa", "workbookValidRows"),
                6, Set.of("totalTitleUnits", "piecesPerBoard", "wastagePercent", "availableBoards", "procurementReference"),
                8, Set.of("jobReference", "quantitySent"),
                9, Set.of("factory", "jobReference", "titlesSent", "titlesReceived", "factoryContact"));
        for (Map<String, Object> step : steps(NB)) {
            int order = (Integer) step.get("order");
            Set<String> accepted = new HashSet<>(map(step.get("fields")).keySet());
            accepted.addAll(map(step.get("checklists")).keySet());
            assertTrue(accepted.containsAll(before.getOrDefault(order, Set.of())), "step " + order + " accepts " + accepted);
            Set<String> legacyNow = map(step.get("fields")).entrySet().stream()
                    .filter(entry -> Boolean.TRUE.equals(map(entry.getValue()).get("legacy"))).map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            assertEquals(legacy.getOrDefault(order, Set.of()), legacyNow, "legacy fields of step " + order);
            assertEquals(!legacyNow.isEmpty(), blockTypes(step).contains("LEGACY_DETAILS"), "Earlier details card on step " + order);
        }
        // The nine-step templates are new, so nothing on them is legacy.
        for (String id : NINE_STEP) {
            for (Map<String, Object> step : steps(id)) {
                map(step.get("fields")).forEach((name, spec) -> assertNotEquals(Boolean.TRUE, map(spec).get("legacy"), id + " " + name));
            }
        }
    }

    // ---- vendor cards (D33) ----------------------------------------------------------------------------------------

    @Test
    void notebookStepsShowTheirVendorCategories() {
        assertEquals(List.of(NB_VENDOR_CATEGORIES), blockCategories(NB, 5, "VENDOR_DIRECTORY"));
        assertEquals(List.of(NB_VENDOR_CATEGORIES), blockCategories(NB, 5, "VENDOR_SELECTION"));
        assertEquals(List.of(List.of("BOARD_SUPPLIERS"), List.of("OFFSET_PRINTERS")), blockCategories(NB, 6, "VENDOR_GROUPS"));
        assertEquals(List.of(List.of("OFFSET_PRINTERS")), blockCategories(NB, 7, "VENDOR_GROUPS"));
        assertEquals(List.of(List.of("OFFSET_PRINTERS"), List.of("LAMINATION_VENDORS")), blockCategories(NB, 8, "VENDOR_GROUPS"));
        assertEquals(List.of(List.of("LAMINATION_VENDORS", "BINDERS", "OFFSET_PRINTERS")), blockCategories(NB, 9, "VENDOR_GROUPS"));
        assertEquals(List.of(List.of("BINDERS")), blockCategories(NB, 10, "VENDOR_GROUPS"));
        assertEquals(List.of(7), stepsWith(NB, "INTERNAL_APPROVAL_GROUP"));
    }

    @Test
    void nineStepItemsOfferEveryCategoryWithTheirSuggestionsFirst() {
        Map<String, Map<String, List<String>>> suggested = Map.of(
                FE, Map.of("FE", List.of("DIGITAL_PRINTERS", "OFFSET_PRINTERS", "PHOTO_PAPER_PRINTERS")),
                FX, Map.of("FX", List.of("FLEX_PRINTERS")),
                BBK, Map.of("BBK", List.of("BILL_BOOK_MAKERS", "OFFSET_PRINTERS", "DIGITAL_PRINTERS")),
                GENERAL, Map.of(
                        "CF", List.of("DIGITAL_PRINTERS", "OFFSET_PRINTERS", "PHOTO_PAPER_PRINTERS"),
                        "FB", List.of("FLUTE_BOARD_MAKERS"),
                        "RC", List.of("OFFSET_PRINTERS", "DIGITAL_PRINTERS", "FILE_MAKERS"),
                        "BCH", List.of("OFFSET_PRINTERS", "DIGITAL_PRINTERS"),
                        "BLT", List.of(),
                        "STD", List.of()));
        for (String id : NINE_STEP) {
            Map<String, Object> directory = blocks(step(id, 5), "VENDOR_DIRECTORY").getFirst();
            assertNull(directory.get("categories"), id + " the directory shows all 18 categories");
            // §3.3: only the selection card takes suggestions.
            assertNull(directory.get("suggestedByItem"), id);
            Map<String, Object> selection = blocks(step(id, 5), "VENDOR_SELECTION").getFirst();
            assertNull(selection.get("categories"), id + " the selection offers all 18 categories");
            assertEquals(suggested.get(id), selection.get("suggestedByItem"), id);
            // Steps 6-9 show every selected vendor; the internal approval group joins on 6 and 8.
            for (int order = 6; order <= 9; order++) {
                List<Map<String, Object>> groups = blocks(step(id, order), "VENDOR_GROUPS");
                assertEquals(1, groups.size(), id + " step " + order);
                assertNull(groups.getFirst().get("categories"), id + " step " + order);
            }
            assertEquals(List.of(6, 8), stepsWith(id, "INTERNAL_APPROVAL_GROUP"), id);
        }
    }

    // ---- the closed sets and internal references of §3.3 -------------------------------------------------------------

    @Test
    void everyTemplateUsesOnlyTheClosedSetsAndReferencesWhatItDeclares() {
        for (String id : ALL) {
            for (Map<String, Object> step : steps(id)) {
                String where = id + " step " + step.get("order");
                Map<String, Object> fields = map(step.get("fields"));
                Map<String, Object> checklists = map(step.get("checklists"));
                assertSla(map(step.get("sla")), where);

                fields.forEach((name, raw) -> assertField(name, map(raw), where));
                checklists.forEach((name, raw) -> {
                    List<Object> items = list(map(raw).get("items"));
                    assertFalse(items.isEmpty(), where + " " + name);
                    items.forEach(item -> assertFalse(((String) item).isBlank(), where + " " + name));
                    assertEquals(items.size(), new HashSet<>(items).size(), where + " " + name + " items are distinct");
                });

                List<String> fieldsOnCards = new ArrayList<>();
                List<String> checklistsOnCards = new ArrayList<>();
                for (Object raw : list(step.get("blocks"))) {
                    Map<String, Object> card = map(raw);
                    String type = (String) card.get("type");
                    assertTrue(BLOCK_TYPES.contains(type), where + " block " + type);
                    if (card.get("fields") != null) list(card.get("fields")).forEach(name -> fieldsOnCards.add((String) name));
                    if (card.get("checklist") != null) checklistsOnCards.add((String) card.get("checklist"));
                    if (card.get("categories") != null) {
                        list(card.get("categories")).forEach(code -> assertTrue(VENDOR_CATEGORIES.contains(code), where + " " + code));
                    }
                    if (card.get("suggestedByItem") != null) {
                        map(card.get("suggestedByItem")).forEach((item, codes) -> {
                            assertTrue(item.matches("^[A-Z]{2,4}$"), where + " item " + item);
                            list(codes).forEach(code -> assertTrue(VENDOR_CATEGORIES.contains(code), where + " " + code));
                        });
                    }
                    if (List.of("FIELDS", "ORDER_PDF_LINK").contains(type)) {
                        assertNotNull(card.get("fields"), where + " " + type + " edits fields");
                        assertNotNull(card.get("save"), where + " " + type + " has a save button");
                    }
                    if (List.of("CHECKLIST", "INTRO_MESSAGE").contains(type)) assertNotNull(card.get("checklist"), where + " " + type);
                }
                // Each editable field is on exactly one card; legacy fields are on none (they show under Earlier details).
                Set<String> editable = fields.entrySet().stream()
                        .filter(entry -> !Boolean.TRUE.equals(map(entry.getValue()).get("legacy"))).map(Map.Entry::getKey)
                        .collect(Collectors.toSet());
                assertEquals(editable, new HashSet<>(fieldsOnCards), where + " cards edit exactly the editable fields");
                assertEquals(fieldsOnCards.size(), new HashSet<>(fieldsOnCards).size(), where + " no field on two cards");
                assertEquals(checklists.keySet(), new HashSet<>(checklistsOnCards), where + " each checklist has its card");
                assertEquals(checklistsOnCards.size(), new HashSet<>(checklistsOnCards).size(), where + " no checklist on two cards");

                for (Object raw : list(step.get("gates"))) assertGate(map(raw), fields, checklists, where);
                assertTrue(fields.keySet().stream().noneMatch(checklists::containsKey), where + " field and checklist names differ");
                assertFalse(fields.containsKey("signOff") || checklists.containsKey("signOff"), where + " signOff is reserved");
            }
        }
    }

    private static void assertSla(Map<String, Object> sla, String where) {
        assertEquals("HOURS", sla.get("unit"), where + ": defaults are 24/7 calendar hours (D23)");
        assertTrue((Integer) sla.get("value") > 0, where);
    }

    private static void assertField(String name, Map<String, Object> spec, String where) {
        String kind = (String) spec.get("kind");
        assertTrue(FIELD_KINDS.contains(kind), where + " " + name + " kind " + kind);
        assertFalse(((String) spec.get("label")).isBlank(), where + " " + name);
        assertTrue(name.matches("^[a-z][A-Za-z0-9]{1,63}$"), where + " field name " + name);
        switch (kind) {
            case "TEXT", "WORDS" -> assertTrue(spec.get("max") instanceof Integer max && max > 0, where + " " + name + " needs a limit");
            case "WHOLE", "NUMBER" -> {
                assertEquals(0, spec.get("min"), where + " " + name);
                assertTrue(spec.get("max") instanceof Integer max && max > 0, where + " " + name);
            }
            case "CHOICE" -> {
                List<Object> choices = list(spec.get("choices"));
                assertTrue(choices.size() >= 2, where + " " + name);
                Set<Object> values = new HashSet<>();
                for (Object pair : choices) {
                    assertEquals(2, list(pair).size(), where + " " + name + " choices are [value, label]");
                    assertTrue(((String) list(pair).getFirst()).matches("^[A-Z][A-Z_]+$"), where + " " + name);
                    values.add(list(pair).getFirst());
                }
                assertEquals(choices.size(), values.size(), where + " " + name + " values are distinct");
            }
            default -> assertNull(spec.get("max"), where + " " + name + ": a " + kind + " carries its own limit");
        }
    }

    private static void assertGate(Map<String, Object> gate, Map<String, Object> fields, Map<String, Object> checklists, String where) {
        String type = (String) gate.get("type");
        assertTrue(GATE_TYPES.contains(type), where + " gate " + type);
        switch (type) {
            case "FIELDS_PRESENT" -> list(gate.get("fields")).forEach(name -> {
                assertTrue(fields.containsKey(name), where + " gate field " + name);
                assertNotEquals(Boolean.TRUE, map(fields.get(name)).get("legacy"), where + " a gate never needs a legacy field");
            });
            case "ALL_CHECKED" -> {
                String checklist = (String) gate.get("checklist");
                assertTrue(checklists.containsKey(checklist), where + " gate checklist " + checklist);
                Map<String, Object> spec = map(checklists.get(checklist));
                List<Object> except = gate.get("except") == null ? List.of() : list(gate.get("except"));
                except.forEach(index -> assertTrue((Integer) index >= 0 && (Integer) index < list(spec.get("items")).size(), where));
                // The item only a sign-off ticks is exactly the one the checklist gate leaves out.
                assertEquals(spec.get("signOffItem") == null ? List.of() : List.of(spec.get("signOffItem")), except, where);
            }
            case "CHOICE_IN" -> {
                Map<String, Object> field = map(fields.get(gate.get("field")));
                assertEquals("CHOICE", field.get("kind"), where);
                Set<Object> values = list(field.get("choices")).stream().map(pair -> list(pair).getFirst()).collect(Collectors.toSet());
                assertTrue(values.containsAll(list(gate.get("values"))), where + " gate values are choices");
            }
            case "COUNTS_CONSISTENT" -> {
                list(gate.get("fields")).forEach(name -> assertEquals("WHOLE", map(fields.get(name)).get("kind"), where + " " + name));
                assertTrue(fields.containsKey(gate.get("field")), where + " the discrepancy note is a field");
            }
            default -> { }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static List<Map<String, Object>> steps(String id) {
        return list(templates.get(id).get("steps")).stream().map(TemplateContentTest::map).toList();
    }

    private static Map<String, Object> step(String id, int order) {
        return steps(id).stream().filter(step -> step.get("order").equals(order)).findFirst().orElseThrow();
    }

    private static List<Object> column(String id, String key) {
        return steps(id).stream().map(step -> step.get(key)).toList();
    }

    private static List<Integer> slaHours(String id) {
        return steps(id).stream().map(step -> {
            Map<String, Object> sla = map(step.get("sla"));
            assertEquals("HOURS", sla.get("unit"), id);
            return (Integer) sla.get("value");
        }).toList();
    }

    private static List<Object> items(String id, int order, String checklist) {
        Object spec = map(step(id, order).get("checklists")).get(checklist);
        assertNotNull(spec, id + " step " + order + " has no checklist " + checklist);
        return list(map(spec).get("items"));
    }

    private static Map<String, Object> field(String id, int order, String name) {
        Object spec = map(step(id, order).get("fields")).get(name);
        assertNotNull(spec, id + " step " + order + " has no field " + name);
        return map(spec);
    }

    private static Map<String, Object> kindAndMax(Map<String, Object> field) {
        return Map.of("kind", field.get("kind"), "max", field.get("max"));
    }

    private static List<String> blockTypes(Map<String, Object> step) {
        return list(step.get("blocks")).stream().map(raw -> (String) map(raw).get("type")).toList();
    }

    private static List<String> gateTypes(Map<String, Object> step) {
        return list(step.get("gates")).stream().map(raw -> (String) map(raw).get("type")).toList();
    }

    private static List<Map<String, Object>> blocks(Map<String, Object> step, String type) {
        return list(step.get("blocks")).stream().map(TemplateContentTest::map).filter(card -> type.equals(card.get("type"))).toList();
    }

    private static List<Object> blockCategories(String id, int order, String type) {
        return blocks(step(id, order), type).stream().map(card -> card.get("categories")).toList();
    }

    private static List<Object> stepsWith(String id, String blockType) {
        return steps(id).stream().filter(step -> blockTypes(step).contains(blockType)).map(step -> step.get("order")).toList();
    }

    /** Each step's gates as compact strings, e.g. "ALL_CHECKED:checks-except-7" or "CHOICE_IN:field=A,B". */
    private static List<List<String>> gates(String id) {
        return steps(id).stream().map(step -> list(step.get("gates")).stream().map(raw -> {
            Map<String, Object> gate = map(raw);
            String type = (String) gate.get("type");
            return switch (type) {
                case "FIELDS_PRESENT" -> type + ":" + join(gate.get("fields"));
                case "ALL_CHECKED" -> type + ":" + gate.get("checklist")
                        + (gate.get("except") == null ? "" : "-except-" + join(gate.get("except")));
                case "CHOICE_IN" -> type + ":" + gate.get("field") + "=" + join(gate.get("values"));
                case "VENDORS_SELECTED" -> type + ":" + (gate.get("categories") == null ? "any" : join(gate.get("categories")));
                case "COUNTS_CONSISTENT" -> type + ":" + join(gate.get("fields")) + "->" + gate.get("field");
                default -> type;
            };
        }).toList()).toList();
    }

    private static String join(Object values) {
        return list(values).stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertInstanceOf(List.class, value);
        return (List<Object>) value;
    }
}
