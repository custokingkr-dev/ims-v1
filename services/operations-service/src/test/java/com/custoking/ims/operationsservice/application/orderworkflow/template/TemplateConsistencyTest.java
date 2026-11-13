package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures;
import com.custoking.ims.operationsservice.application.orderworkflow.template.TemplateConsistency.StepRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The startup check that a template matches its definition's workflow_steps rows (design D2, risk table of §5.5). */
class TemplateConsistencyTest {
    private static final Set<String> CATEGORIES = Set.of("BOARD_SUPPLIERS", "LAMINATION_VENDORS", "BINDERS", "OFFSET_PRINTERS",
            "DIGITAL_PRINTERS", "FLEX_PRINTERS", "PHOTO_PAPER_PRINTERS");

    private static List<StepRow> rowsOf(WorkflowTemplate template) {
        return new ArrayList<>(template.steps().stream().map(step -> new StepRow(template.definitionId(), step.order(), step.code())).toList());
    }

    @Test
    void matchingRowsHaveNoProblems() {
        List<StepRow> rows = rowsOf(TemplateFixtures.NOTEBOOK);
        rows.addAll(rowsOf(TemplateFixtures.NINE_STEP));
        rows.add(new StepRow("FIREFIGHTING_DEFAULT", 1, null));
        assertEquals(List.of(), TemplateConsistency.problems(List.of(TemplateFixtures.NOTEBOOK, TemplateFixtures.NINE_STEP), rows, CATEGORIES),
                "other definitions' rows are not the templates' business");
    }

    @Test
    void aDifferentCodeAMissingRowAndAnExtraRowAreEachReported() {
        List<StepRow> rows = rowsOf(TemplateFixtures.NOTEBOOK);
        rows.set(6, new StepRow("NOTEBOOK_ORDER_TRACKER", 7, "PRE_PRINT_QUALITY_CHECK"));
        rows.remove(11);
        rows.add(new StepRow("NOTEBOOK_ORDER_TRACKER", 13, "EXTRA_STEP"));
        assertEquals(List.of(
                "NOTEBOOK_ORDER_TRACKER: stage 7 is PRE_PRINT_QUALITY_GATE in the template but PRE_PRINT_QUALITY_CHECK in workflow_steps",
                "NOTEBOOK_ORDER_TRACKER: stage 12 (DELIVERY_INVOICING) has no workflow_steps row",
                "NOTEBOOK_ORDER_TRACKER: workflow_steps row 13 is not a stage of the template"),
                TemplateConsistency.problems(List.of(TemplateFixtures.NOTEBOOK), rows, CATEGORIES));
    }

    @Test
    void aRowWithoutACodeDoesNotMatch() {
        List<StepRow> rows = rowsOf(TemplateFixtures.NINE_STEP);
        rows.set(0, new StepRow("FLIER_ORDER_WORKFLOW", 1, null));
        assertEquals(List.of("FLIER_ORDER_WORKFLOW: stage 1 is ORDER_DETAILS in the template but null in workflow_steps"),
                TemplateConsistency.problems(List.of(TemplateFixtures.NINE_STEP), rows, CATEGORIES));
    }

    @Test
    void aDefinitionWithoutRowsIsReported() {
        assertEquals(List.of("FLIER_ORDER_WORKFLOW: the definition has no workflow_steps rows"),
                TemplateConsistency.problems(List.of(TemplateFixtures.NINE_STEP), List.of(), CATEGORIES));
    }

    @Test
    void everyVendorCategoryATemplateNamesMustExist() {
        assertEquals(List.of("BINDERS", "BOARD_SUPPLIERS", "DIGITAL_PRINTERS", "FLEX_PRINTERS", "LAMINATION_VENDORS", "OFFSET_PRINTERS",
                        "PHOTO_PAPER_PRINTERS"),
                List.copyOf(TemplateConsistency.referencedCategories(List.of(TemplateFixtures.NOTEBOOK, TemplateFixtures.NINE_STEP))));
        assertEquals(List.of("vendor category FLEX_PRINTERS is named by a template but does not exist"),
                TemplateConsistency.problems(List.of(TemplateFixtures.NINE_STEP), rowsOf(TemplateFixtures.NINE_STEP),
                        Set.of("DIGITAL_PRINTERS", "OFFSET_PRINTERS", "PHOTO_PAPER_PRINTERS")));
    }
}
