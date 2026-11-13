package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.template.ChecklistSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.FieldSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.GateSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evaluates a stage's completion gates (design D19). Unlike the notebook tracker's first-failure check, it returns
 * <em>every</em> unmet requirement, in the template's gate order, so {@code Complete} can be refused with the whole list
 * and the screen can show it under the disabled button and in Upcoming actions. Gates are evaluated only for the stage
 * being completed: a stage already completed is never re-gated when its template changes.
 */
public final class Gates {
    /** The design state in which the Head Approval has been given. */
    public static final String DESIGN_APPROVED = "APPROVED";

    /** The produced, accepted and dispatched count fields of NB Step 11, used when a counts gate names none. */
    public static final List<String> DEFAULT_COUNT_FIELDS = List.of("producedCount", "acceptedCount", "dispatchCount");
    /** The discrepancy note of NB Step 11, used when a counts gate names none. */
    public static final String DEFAULT_DISCREPANCY_FIELD = "discrepancyNote";

    /** The SOP's vendor category names (D31), used when the facts carry no current name for a code. */
    public static final Map<String, String> SOP_CATEGORY_NAMES = Map.ofEntries(
            Map.entry("BOARD_SUPPLIERS", "Board Suppliers"), Map.entry("LAMINATION_VENDORS", "Lamination Vendors"),
            Map.entry("BINDERS", "Binders"), Map.entry("OFFSET_PRINTERS", "Offset Printers"),
            Map.entry("DIGITAL_PRINTERS", "Digital Printers"), Map.entry("FLEX_PRINTERS", "Flex Printers"),
            Map.entry("PHOTO_PAPER_PRINTERS", "Photo Paper Printers"), Map.entry("BILL_BOOK_MAKERS", "Bill Book Makers"),
            Map.entry("BATCH_MAKERS", "Batch Makers"), Map.entry("FLUTE_BOARD_MAKERS", "Flute Board Makers"),
            Map.entry("TROPHIES_MEDALS", "Trophies & Medals"), Map.entry("FILE_MAKERS", "File Makers"),
            Map.entry("SASHES", "Sashes"), Map.entry("DIARIES", "Diaries"), Map.entry("ID_CARDS", "ID Cards"),
            Map.entry("FLAPS", "Flaps"), Map.entry("GIFTING", "Gifting Item Vendors & Printers"),
            Map.entry("NON_WOVEN_BAGS", "Non-Woven Bag Vendors"));

    private Gates() { }

    /** Every requirement of the stage that the facts do not meet; empty when the stage may be completed. */
    public static List<GateFailure> unmet(StepSpec step, GateFacts facts) {
        List<GateFailure> failures = new ArrayList<>();
        for (GateSpec gate : step.gates()) {
            switch (gate.type()) {
                case FIELDS_PRESENT -> fieldsPresent(step, gate, facts, failures);
                case ALL_CHECKED -> allChecked(step, gate, facts, failures);
                case CHOICE_IN -> choiceIn(step, gate, facts, failures);
                case DESIGN_FILES_PRESENT -> {
                    if (facts.designFileCount() < 1) failures.add(failure(gate, "Upload at least one design file"));
                }
                case VENDORS_SELECTED -> vendorsSelected(gate, facts, failures);
                case HEAD_APPROVED -> headApproved(gate, facts, failures);
                case SIGNED_OFF -> {
                    if (!facts.signedOff()) {
                        failures.add(failure(gate, step.signOff() == null ? "Record the sign-off" : step.signOff().label()));
                    }
                }
                case COUNTS_CONSISTENT -> countsConsistent(step, gate, facts, failures);
            }
        }
        return List.copyOf(failures);
    }

    /** Whether the stage's gates all pass. */
    public static boolean met(StepSpec step, GateFacts facts) {
        return unmet(step, facts).isEmpty();
    }

    /** Whether the Head Approval binds the CDR link saved now: approved, and for this very link. */
    public static boolean headApprovalCurrent(String designState, String approvedCdrLink, String currentCdrLink) {
        return DESIGN_APPROVED.equals(designState) && approvedCdrLink != null && approvedCdrLink.equals(currentCdrLink);
    }

    // ---- gate types -----------------------------------------------------------------------------------------------------

    private static void fieldsPresent(StepSpec step, GateSpec gate, GateFacts facts, List<GateFailure> failures) {
        for (String key : gate.fields() == null ? List.<String>of() : gate.fields()) {
            if (!StepDataRules.present(facts.data(), key)) failures.add(failure(gate, "Save the " + phrase(label(step, key))));
        }
    }

    private static void allChecked(StepSpec step, GateSpec gate, GateFacts facts, List<GateFailure> failures) {
        ChecklistSpec checklist = step.checklists().get(gate.checklist());
        if (checklist == null) {
            // The loader refuses such a template; a stage built in code must not pass a gate it cannot evaluate.
            throw new IllegalStateException(step.title() + " has no checklist " + gate.checklist());
        }
        Set<Integer> excused = gate.except() == null ? Set.of() : new HashSet<>(gate.except());
        Set<Integer> ticked = StepDataRules.ticked(facts.data(), gate.checklist());
        List<Integer> missing = new ArrayList<>();
        for (int index = 0; index < checklist.size(); index++) {
            if (!excused.contains(index) && !ticked.contains(index)) missing.add(index);
        }
        if (missing.isEmpty()) return;
        if (missing.size() == 1) {
            failures.add(failure(gate, "Tick \"" + checklist.items().get(missing.get(0)) + "\""));
            return;
        }
        String title = step.checklistTitle(gate.checklist()).orElse("the checklist");
        failures.add(failure(gate, "Tick the remaining " + missing.size() + " items of " + title));
    }

    private static void choiceIn(StepSpec step, GateSpec gate, GateFacts facts, List<GateFailure> failures) {
        List<String> accepted = gate.values() == null ? List.of() : gate.values();
        Object value = facts.data().get(gate.field());
        if (value instanceof String text && accepted.contains(text)) return;
        FieldSpec field = step.fields().get(gate.field());
        List<String> labels = accepted.stream()
                .map(choice -> field == null ? choice : field.choiceLabel(choice).orElse(choice)).toList();
        failures.add(failure(gate, label(step, gate.field()) + " must be " + either(labels)));
    }

    private static void vendorsSelected(GateSpec gate, GateFacts facts, List<GateFailure> failures) {
        Map<String, List<Long>> selections = facts.vendorSelections();
        if (gate.categories() == null) {
            boolean any = selections.values().stream().anyMatch(ids -> ids != null && !ids.isEmpty());
            if (!any) failures.add(failure(gate, "Select at least one vendor"));
            return;
        }
        for (String category : gate.categories()) {
            List<Long> ids = selections.get(category);
            if (ids == null || ids.isEmpty()) failures.add(failure(gate, "Select a vendor in " + categoryName(facts, category)));
        }
    }

    private static void headApproved(GateSpec gate, GateFacts facts, List<GateFailure> failures) {
        if (headApprovalCurrent(facts.designState(), facts.approvedCdrLink(), facts.currentCdrLink())) return;
        String message;
        if (facts.currentCdrLink() == null || facts.currentCdrLink().isBlank()) {
            message = "Save the CDR link in CDR Conversion, then approve the design";
        } else if (DESIGN_APPROVED.equals(facts.designState())) {
            message = "The CDR link changed after approval; approve the current link";
        } else {
            message = "Approve the design";
        }
        failures.add(failure(gate, message));
    }

    private static void countsConsistent(StepSpec step, GateSpec gate, GateFacts facts, List<GateFailure> failures) {
        List<String> fields = gate.fields() == null || gate.fields().size() != 3 ? DEFAULT_COUNT_FIELDS : gate.fields();
        String noteField = gate.field() == null ? DEFAULT_DISCREPANCY_FIELD : gate.field();
        Counts counts = new Counts(facts.orderedCount(), StepDataRules.whole(facts.data(), fields.get(0)),
                StepDataRules.whole(facts.data(), fields.get(1)), StepDataRules.whole(facts.data(), fields.get(2)));
        if (!counts.complete() || !counts.mismatch()) return;
        if (StepDataRules.present(facts.data(), noteField)) return;
        failures.add(failure(gate, "The counts do not match; save the " + phrase(label(step, noteField))));
    }

    // ---- wording --------------------------------------------------------------------------------------------------------

    private static GateFailure failure(GateSpec gate, String message) {
        return new GateFailure(gate.type().name(), message);
    }

    private static String label(StepSpec step, String key) {
        FieldSpec field = step.fields().get(key);
        return field == null ? key : field.label();
    }

    /**
     * A label inside a sentence: "Controlled storage link" reads "controlled storage link", while labels that start with an
     * acronym ("LR / docket number", "QC reviewer comments") keep their case.
     */
    static String phrase(String label) {
        if (label.length() > 1 && Character.isUpperCase(label.charAt(0)) && Character.isLowerCase(label.charAt(1))) {
            return Character.toLowerCase(label.charAt(0)) + label.substring(1);
        }
        return label;
    }

    private static String either(List<String> labels) {
        if (labels.size() == 1) return labels.get(0);
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " or " + labels.get(labels.size() - 1);
    }

    private static String categoryName(GateFacts facts, String code) {
        String name = facts.categoryNames().get(code);
        if (name != null && !name.isBlank()) return name;
        name = SOP_CATEGORY_NAMES.get(code);
        if (name != null) return name;
        return Arrays.stream(code.split("_")).filter(word -> !word.isEmpty())
                .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(" "));
    }
}
