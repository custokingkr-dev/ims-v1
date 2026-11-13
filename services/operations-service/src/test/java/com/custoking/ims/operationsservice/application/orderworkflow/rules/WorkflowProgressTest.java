package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.template.Phase;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.custoking.ims.operationsservice.application.orderworkflow.rules.Role.OPERATOR;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.Role.SUPERADMIN;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.StepStatus.ACTIVE;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.StepStatus.COMPLETED;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.StepStatus.LOCKED;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.NINE_STEP;
import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.NOTEBOOK;
import static org.junit.jupiter.api.Assertions.*;

/** Ported from the notebook tracker's TrackerProgressTest, over template stages instead of the retired enum. */
class WorkflowProgressTest {
    /** Facts that satisfy every gate of either fixture. */
    private static final GateFacts READY = new GateFacts(Map.ofEntries(
            Map.entry("orderPdfLink", "https://drive.example.com/order.pdf"), Map.entry("introChecks", List.of(0)),
            Map.entry("cdrLink", "https://drive.example.com/cdr"), Map.entry("cdrChecks", List.of(0, 1, 2)),
            Map.entry("planningChecks", List.of(0, 1, 2)), Map.entry("boardBillLink", "https://drive.example.com/bills"),
            Map.entry("checks", List.of(0, 1, 2, 3, 4, 5, 6, 7)), Map.entry("sampleLink", "https://drive.example.com/sample"),
            Map.entry("schoolVerification", "VERIFIED"), Map.entry("pressChecks", List.of(0, 1, 2, 3, 4, 5, 6, 7)),
            Map.entry("handoverChecks", List.of(0)), Map.entry("productionChecks", List.of(0, 1, 2, 3, 4, 5, 6, 7)),
            Map.entry("varianceReportLink", "https://drive.example.com/v"), Map.entry("deliveryChallanLink", "https://drive.example.com/c"),
            Map.entry("billsLink", "https://drive.example.com/b"), Map.entry("transporter", "VRL"), Map.entry("dispatchDate", "2026-11-20"),
            Map.entry("proofLink", "https://drive.example.com/pod"), Map.entry("qcChecks", List.of(0)),
            Map.entry("followUpChecks", List.of(0, 1, 2, 3)), Map.entry("finalChecks", List.of(0)),
            Map.entry("finalValidationNotes", "All good"), Map.entry("deliveryStatus", "DELIVERED")),
            true, "APPROVED", "https://drive.example.com/cdr", "https://drive.example.com/cdr", 2,
            Map.of("BOARD_SUPPLIERS", List.of(1L), "LAMINATION_VENDORS", List.of(2L), "BINDERS", List.of(3L), "OFFSET_PRINTERS", List.of(4L)),
            1000);

    /** Completes stages in order as superadmin until {@code last} has been completed. */
    private static WorkflowProgress completeThrough(WorkflowProgress progress, int last) {
        while (progress.current().isPresent()) {
            int order = progress.current().get().order();
            progress = progress.complete(order, SUPERADMIN, READY);
            if (order == last) break;
        }
        return progress;
    }

    private static List<Integer> orders(List<StepSpec> path) {
        return path.stream().map(StepSpec::order).toList();
    }

    @Test
    void aCustomisedNotebookOrderRunsTwelveStagesAndOnlyTheFirstIsActive() {
        WorkflowProgress progress = WorkflowProgress.start(NOTEBOOK, true);
        assertEquals(12, progress.total());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), orders(progress.path()));
        assertEquals(ACTIVE, progress.status(1));
        for (int order = 2; order <= 12; order++) assertEquals(LOCKED, progress.status(order));
        assertEquals("ORDER_DETAILS", progress.statusCode());
        assertEquals(Phase.INTAKE, progress.phase());
    }

    @Test
    void aWholesaleNotebookOrderSkipsStagesTwoToFourAndRunsNine() {
        WorkflowProgress progress = WorkflowProgress.start(NOTEBOOK, false);
        assertEquals(9, progress.total());
        assertEquals(List.of(1, 5, 6, 7, 8, 9, 10, 11, 12), orders(progress.path()));
        WorkflowProgress afterOne = progress.complete(1, OPERATOR, READY);
        assertEquals("PRODUCTION_PLANNING", afterOne.current().orElseThrow().code());
        WorkflowRuleViolation notOnPath = assertThrows(WorkflowRuleViolation.class, () -> afterOne.status(2));
        assertEquals("STEP_NOT_ON_PATH", notOnPath.code());
        assertFalse(afterOne.onPath(3));
        assertTrue(afterOne.designStep().isEmpty(), "a wholesale order has no design stage");
    }

    @Test
    void nineStepTemplatesRunNineStagesWhetherOrNotCustomised() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), orders(WorkflowProgress.start(NINE_STEP, true).path()));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), orders(WorkflowProgress.start(NINE_STEP, false).path()));
        WorkflowProgress done = completeThrough(WorkflowProgress.start(NINE_STEP, true), 9);
        assertTrue(done.finished());
        assertEquals("COMPLETED", done.statusCode());
        assertEquals(Phase.DELIVERY, done.phase());
    }

    @Test
    void completingAStageActivatesTheNextOne() {
        WorkflowProgress progress = WorkflowProgress.start(NOTEBOOK, true).complete(1, OPERATOR, READY);
        assertEquals(COMPLETED, progress.status(1));
        assertEquals(ACTIVE, progress.status(2));
        assertEquals(LOCKED, progress.status(3));
        assertEquals(1, progress.completedCount());
        assertEquals("Order Details", progress.lastCompleted().orElseThrow().title());
        assertEquals("Designing Activity", progress.next(1).orElseThrow().title());
        assertEquals("Order Details", progress.previous(2).orElseThrow().title());
        assertTrue(progress.previous(1).isEmpty());
    }

    @Test
    void aLockedOrAlreadyCompletedStageCannotBeCompleted() {
        WorkflowProgress progress = WorkflowProgress.start(NOTEBOOK, true);
        assertEquals("STEP_NOT_ACTIVE", assertThrows(WorkflowRuleViolation.class, () -> progress.complete(2, OPERATOR, READY)).code());
        WorkflowProgress next = progress.complete(1, OPERATOR, READY);
        assertEquals("STEP_NOT_ACTIVE", assertThrows(WorkflowRuleViolation.class, () -> next.complete(1, OPERATOR, READY)).code());
    }

    @Test
    void completeIsRefusedWithEveryUnmetRequirement() {
        WorkflowProgress atQuality = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 6);
        assertEquals(7, atQuality.current().orElseThrow().order());
        WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class,
                () -> atQuality.complete(7, OPERATOR, GateFacts.ofData(Map.of())));
        assertEquals("GATE_UNMET", refused.code());
        assertEquals(409, refused.httpStatus());
        assertEquals(List.of("ALL_CHECKED", "FIELDS_PRESENT", "CHOICE_IN", "SIGNED_OFF"),
                refused.unmet().stream().map(GateFailure::code).toList());
        assertTrue(refused.getMessage().startsWith("Pre-Print Quality Check cannot be completed yet: "), refused.getMessage());
        assertEquals(refused.unmet(), atQuality.unmet(7, GateFacts.ofData(Map.of())));
        assertEquals(List.of(), atQuality.unmet(8, GateFacts.ofData(Map.of())), "only the active stage has unmet requirements");
        assertEquals(List.of(), atQuality.unmet(6, GateFacts.ofData(Map.of())), "a completed stage is never re-gated");
    }

    @Test
    void headApprovalIsSuperadminOnlyAndNeedsTheCurrentLinkApproved() {
        WorkflowProgress atFour = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 3);
        assertEquals("HEAD_APPROVAL", atFour.statusCode());
        assertEquals("SUPERADMIN_ONLY", assertThrows(WorkflowRuleViolation.class, () -> atFour.complete(4, OPERATOR, READY)).code());
        assertFalse(atFour.canComplete(OPERATOR, 4));
        assertTrue(atFour.canComplete(SUPERADMIN, 4));
        GateFacts stale = new GateFacts(Map.of(), false, "APPROVED", "https://drive.example.com/old", "https://drive.example.com/new", 1, Map.of(), 0);
        WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> atFour.complete(4, SUPERADMIN, stale));
        assertEquals("GATE_UNMET", refused.code());
        assertEquals("HEAD_APPROVED", refused.unmet().get(0).code());
        assertEquals(5, atFour.complete(4, SUPERADMIN, READY).current().orElseThrow().order());
    }

    @Test
    void reopeningIsSuperadminOnlyAndLocksEveryLaterStage() {
        WorkflowProgress progress = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 8);
        assertEquals(8, progress.completedCount());
        assertEquals("SUPERADMIN_ONLY", assertThrows(WorkflowRuleViolation.class, () -> progress.reopen(3, OPERATOR)).code());
        assertEquals(List.of(4, 5, 6, 7, 8, 9), orders(progress.revertedBy(3)));
        WorkflowProgress reopened = progress.reopen(3, SUPERADMIN);
        assertEquals(ACTIVE, reopened.status(3));
        for (int later = 4; later <= 12; later++) assertEquals(LOCKED, reopened.status(later));
        assertEquals("STEP_NOT_COMPLETED", assertThrows(WorkflowRuleViolation.class, () -> reopened.reopen(5, SUPERADMIN)).code());
        assertEquals("STEP_NOT_COMPLETED", assertThrows(WorkflowRuleViolation.class, () -> reopened.reopen(3, SUPERADMIN)).code());
    }

    @Test
    void requestChangesReturnsTheOrderToDesigningActivity() {
        WorkflowProgress atHeadApproval = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 3);
        WorkflowProgress backToDesign = atHeadApproval.reopen(2, SUPERADMIN);
        assertEquals(ACTIVE, backToDesign.status(2));
        assertEquals(LOCKED, backToDesign.status(3));
        assertEquals(LOCKED, backToDesign.status(4));
        assertEquals(List.of(3, 4), orders(atHeadApproval.revertedBy(2)));
    }

    @Test
    void aFinishedOrderCanBeReopenedAndRevertsOnlyTheStagesAfter() {
        WorkflowProgress done = completeThrough(WorkflowProgress.start(NOTEBOOK, false), 12);
        assertTrue(done.finished());
        assertTrue(done.current().isEmpty());
        assertEquals(List.of(), done.revertedBy(12));
        WorkflowProgress reopened = done.reopen(12, SUPERADMIN);
        assertEquals(ACTIVE, reopened.status(12));
        assertEquals(List.of(12), orders(done.revertedBy(11)));
    }

    /** Bulk printing is blocked: Printing Press stays read-only until Pre-Print Quality Check is completed (D18). */
    @Test
    void aLockedStageIsReadOnlyForEveryoneSoPrintingWaitsForTheQualityGate() {
        WorkflowProgress atQuality = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 6);
        assertEquals(ACTIVE, atQuality.status(7));
        assertEquals(LOCKED, atQuality.status(8));
        assertFalse(atQuality.canEdit(OPERATOR, 8));
        assertFalse(atQuality.canEdit(SUPERADMIN, 8));
        WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> atQuality.requireEditable(SUPERADMIN, 8));
        assertEquals("STEP_NOT_EDITABLE", refused.code());
        assertEquals("Opens for editing when Pre-Print Quality Check is completed", refused.getMessage());
        WorkflowProgress printing = atQuality.complete(7, SUPERADMIN, READY);
        assertTrue(printing.canEdit(OPERATOR, 8));
    }

    @Test
    void operatorsEditOnlyTheActiveStageAndSuperadminAlsoCompletedOnes() {
        WorkflowProgress progress = WorkflowProgress.start(NOTEBOOK, true).complete(1, OPERATOR, READY);
        assertFalse(progress.canEdit(OPERATOR, 1));
        assertTrue(progress.canEdit(SUPERADMIN, 1));
        assertEquals("Order Details is completed; only a superadmin can change it",
                assertThrows(WorkflowRuleViolation.class, () -> progress.requireEditable(OPERATOR, 1)).getMessage());
        assertTrue(progress.canEdit(OPERATOR, 2));
        assertTrue(progress.canEdit(SUPERADMIN, 2));
        progress.requireEditable(OPERATOR, 2);
    }

    @Test
    void theStageSuperadminCompletesIsEditedOnlyBySuperadmin() {
        WorkflowProgress atFour = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 3);
        assertTrue(atFour.canEdit(SUPERADMIN, 4));
        assertFalse(atFour.canEdit(OPERATOR, 4));
        assertEquals("STEP_NOT_EDITABLE", assertThrows(WorkflowRuleViolation.class, () -> atFour.requireEditable(OPERATOR, 4)).code());
    }

    @Test
    void reopenSignOffAndNotesFollowRoleAndStatus() {
        WorkflowProgress atQuality = completeThrough(WorkflowProgress.start(NOTEBOOK, true), 6);
        assertTrue(atQuality.canSignOff(SUPERADMIN, 7));
        assertFalse(atQuality.canSignOff(OPERATOR, 7));
        assertFalse(atQuality.canSignOff(SUPERADMIN, 6), "Board Procurement has no sign-off");
        assertFalse(atQuality.canSignOff(SUPERADMIN, 12), "a locked stage cannot be signed off");
        assertTrue(atQuality.canReopen(SUPERADMIN, 6));
        assertFalse(atQuality.canReopen(OPERATOR, 6));
        assertFalse(atQuality.canReopen(SUPERADMIN, 7));
        assertTrue(atQuality.canAddNote(6));
        assertTrue(atQuality.canAddNote(7));
        assertFalse(atQuality.canAddNote(8));
        WorkflowProgress done = completeThrough(atQuality, 12);
        assertTrue(done.canSignOff(SUPERADMIN, 12), "superadmin may still sign off a completed stage it edits");
    }

    @Test
    void designFilesAreManagedWhileDesigningAndAfterChangesAreRequested() {
        WorkflowProgress fresh = WorkflowProgress.start(NOTEBOOK, true);
        assertEquals(2, fresh.designStep().orElseThrow().order());
        assertFalse(fresh.canManageDesignFiles(OPERATOR, "NOT_SENT"), "Designing Activity has not started");
        WorkflowProgress designing = fresh.complete(1, OPERATOR, READY);
        assertTrue(designing.canManageDesignFiles(OPERATOR, "NOT_SENT"));
        WorkflowProgress atThree = completeThrough(fresh, 2);
        assertFalse(atThree.canManageDesignFiles(OPERATOR, "SENT"));
        assertTrue(atThree.canManageDesignFiles(OPERATOR, "CHANGES_REQUESTED"));
        assertTrue(atThree.canManageDesignFiles(SUPERADMIN, "SENT"));
        assertFalse(WorkflowProgress.start(NOTEBOOK, false).complete(1, OPERATOR, READY).canManageDesignFiles(SUPERADMIN, "NOT_SENT"));
        assertEquals(2, WorkflowProgress.start(NINE_STEP, true).designStep().orElseThrow().order());
    }

    @Test
    void progressMovesThroughThePhases() {
        WorkflowProgress progress = WorkflowProgress.start(NINE_STEP, true);
        assertEquals(Phase.INTAKE, progress.phase());
        assertEquals(Phase.PLANNING, completeThrough(progress, 4).phase());
        assertEquals(Phase.PRODUCTION, completeThrough(progress, 5).phase());
        assertEquals(Phase.VERIFICATION, completeThrough(progress, 7).phase());
        assertEquals("FINAL_APPROVAL", completeThrough(progress, 7).statusCode());
    }

    @Test
    void restoreRejectsAnImpossibleCompletedCount() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowProgress.restore(NOTEBOOK, false, 10));
        assertThrows(IllegalArgumentException.class, () -> WorkflowProgress.restore(NOTEBOOK, true, -1));
        assertEquals(ACTIVE, WorkflowProgress.restore(NOTEBOOK, true, 4).status(5));
        assertEquals(ACTIVE, WorkflowProgress.restore(NOTEBOOK, false, 1).status(5));
        assertThrows(IllegalArgumentException.class, () -> WorkflowProgress.of(List.of(TemplateFixtures.nb(5), TemplateFixtures.nb(1)), 0),
                "a path must be in stage order");
    }
}
