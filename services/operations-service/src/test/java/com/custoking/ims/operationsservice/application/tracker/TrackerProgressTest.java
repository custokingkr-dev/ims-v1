package com.custoking.ims.operationsservice.application.tracker;

import org.junit.jupiter.api.Test;

import static com.custoking.ims.operationsservice.application.tracker.Role.OPERATOR;
import static com.custoking.ims.operationsservice.application.tracker.Role.SUPERADMIN;
import static com.custoking.ims.operationsservice.application.tracker.StepStatus.ACTIVE;
import static com.custoking.ims.operationsservice.application.tracker.StepStatus.COMPLETED;
import static com.custoking.ims.operationsservice.application.tracker.StepStatus.LOCKED;
import static com.custoking.ims.operationsservice.application.tracker.TrackerStep.*;
import static org.junit.jupiter.api.Assertions.*;

class TrackerProgressTest {
    private static final StepFacts READY = new StepFacts(true, true, true, new Counts(1000, 1000, 1000, 1000), null);

    /** Completes steps in order until {@code last} has been completed. */
    private static TrackerProgress completeThrough(TrackerProgress progress, TrackerStep last) {
        while (progress.current().isPresent()) {
            TrackerStep step = progress.current().get();
            progress = progress.complete(step, SUPERADMIN, READY);
            if (step == last) break;
        }
        return progress;
    }

    @Test
    void customisedOrderHasTwelveStepsAndOnlyTheFirstIsActive() {
        TrackerProgress progress = TrackerProgress.start(true);
        assertEquals(12, progress.total());
        assertEquals(ACTIVE, progress.status(ORDER_DETAILS));
        for (TrackerStep step : progress.path().subList(1, 12)) assertEquals(LOCKED, progress.status(step));
    }

    @Test
    void wholesaleOrderSkipsStepsTwoThreeAndFour() {
        TrackerProgress progress = TrackerProgress.start(false);
        assertEquals(9, progress.total());
        assertFalse(progress.path().contains(DESIGNING_ACTIVITY));
        assertFalse(progress.path().contains(HEAD_APPROVAL));
        assertFalse(progress.path().contains(CDR_CONVERSION));
        TrackerProgress afterOne = progress.complete(ORDER_DETAILS, OPERATOR, StepFacts.none());
        assertEquals(PRODUCTION_PLANNING, afterOne.current().orElseThrow());
        assertThrows(TrackerRuleViolation.class, () -> afterOne.status(DESIGNING_ACTIVITY));
    }

    @Test
    void completingAStepActivatesTheNextOne() {
        TrackerProgress progress = TrackerProgress.start(true).complete(ORDER_DETAILS, OPERATOR, StepFacts.none());
        assertEquals(COMPLETED, progress.status(ORDER_DETAILS));
        assertEquals(ACTIVE, progress.status(DESIGNING_ACTIVITY));
        assertEquals(LOCKED, progress.status(HEAD_APPROVAL));
        assertEquals(1, progress.completedCount());
    }

    @Test
    void aLockedOrAlreadyCompletedStepCannotBeCompleted() {
        TrackerProgress progress = TrackerProgress.start(true);
        TrackerRuleViolation locked = assertThrows(TrackerRuleViolation.class,
                () -> progress.complete(DESIGNING_ACTIVITY, OPERATOR, READY));
        assertEquals("STEP_NOT_ACTIVE", locked.code());
        TrackerProgress next = progress.complete(ORDER_DETAILS, OPERATOR, StepFacts.none());
        assertThrows(TrackerRuleViolation.class, () -> next.complete(ORDER_DETAILS, OPERATOR, StepFacts.none()));
    }

    @Test
    void headApprovalIsSuperadminOnlyAndNeedsAnApprovedDesign() {
        TrackerProgress atThree = completeThrough(TrackerProgress.start(true), DESIGNING_ACTIVITY);
        assertEquals(HEAD_APPROVAL, atThree.current().orElseThrow());
        assertEquals("SUPERADMIN_ONLY", assertThrows(TrackerRuleViolation.class,
                () -> atThree.complete(HEAD_APPROVAL, OPERATOR, READY)).code());
        StepFacts unapproved = new StepFacts(true, false, false, null, null);
        assertEquals("DESIGN_NOT_APPROVED", assertThrows(TrackerRuleViolation.class,
                () -> atThree.complete(HEAD_APPROVAL, SUPERADMIN, unapproved)).code());
        assertEquals(CDR_CONVERSION, atThree.complete(HEAD_APPROVAL, SUPERADMIN, READY).current().orElseThrow());
    }

    @Test
    void designingNeedsTheDesignToHaveBeenSentForApproval() {
        TrackerProgress atTwo = TrackerProgress.start(true).complete(ORDER_DETAILS, OPERATOR, StepFacts.none());
        assertEquals("DESIGN_NOT_SENT", assertThrows(TrackerRuleViolation.class,
                () -> atTwo.complete(DESIGNING_ACTIVITY, OPERATOR, StepFacts.none())).code());
        assertEquals(HEAD_APPROVAL, atTwo.complete(DESIGNING_ACTIVITY, OPERATOR,
                new StepFacts(true, false, false, null, null)).current().orElseThrow());
    }

    @Test
    void qualityGateAndDeliveryNeedSuperadminSignOff() {
        for (TrackerStep gated : new TrackerStep[]{PRE_PRINT_QUALITY_GATE, DELIVERY_INVOICING}) {
            TrackerProgress at = completeThrough(TrackerProgress.start(true), TrackerStep.values()[gated.number() - 2]);
            assertEquals(gated, at.current().orElseThrow());
            assertEquals("SIGN_OFF_REQUIRED", assertThrows(TrackerRuleViolation.class,
                    () -> at.complete(gated, OPERATOR, StepFacts.none())).code());
            assertNotNull(at.complete(gated, OPERATOR, new StepFacts(true, true, true, new Counts(1, 1, 1, 1), null)));
        }
    }

    @Test
    void validationCountNeedsAllCountsAndANoteOnMismatch() {
        TrackerProgress at = completeThrough(TrackerProgress.start(true), PRODUCTION_FOLLOW_UP);
        assertEquals(VALIDATION_COUNT, at.current().orElseThrow());
        assertEquals("COUNTS_INCOMPLETE", assertThrows(TrackerRuleViolation.class,
                () -> at.complete(VALIDATION_COUNT, OPERATOR,
                        new StepFacts(true, true, true, new Counts(1000, null, 1000, 1000), null))).code());
        StepFacts mismatch = new StepFacts(true, true, true, new Counts(1000, 990, 990, 980), null);
        assertEquals("DISCREPANCY_NOTE_REQUIRED", assertThrows(TrackerRuleViolation.class,
                () -> at.complete(VALIDATION_COUNT, OPERATOR, mismatch)).code());
        StepFacts noted = new StepFacts(true, true, true, new Counts(1000, 990, 990, 980), "Ten books were damaged");
        assertEquals(DELIVERY_INVOICING, at.complete(VALIDATION_COUNT, OPERATOR, noted).current().orElseThrow());
    }

    @Test
    void reopeningIsSuperadminOnlyAndRevertsLaterStepsToNotCompleted() {
        TrackerProgress progress = completeThrough(TrackerProgress.start(true), PRINTING_PRESS);
        assertEquals(7, progress.completedCount());
        assertEquals("SUPERADMIN_ONLY", assertThrows(TrackerRuleViolation.class,
                () -> progress.reopen(CDR_CONVERSION, OPERATOR)).code());
        TrackerProgress reopened = progress.reopen(CDR_CONVERSION, SUPERADMIN);
        assertEquals(ACTIVE, reopened.status(CDR_CONVERSION));
        assertEquals(COMPLETED, reopened.status(HEAD_APPROVAL));
        for (TrackerStep later : new TrackerStep[]{PRODUCTION_PLANNING, BOARD_PROCUREMENT, PRINTING_PRESS}) {
            assertEquals(LOCKED, reopened.status(later));
        }
        assertEquals("STEP_NOT_COMPLETED", assertThrows(TrackerRuleViolation.class,
                () -> reopened.reopen(PRODUCTION_PLANNING, SUPERADMIN)).code());
    }

    @Test
    void operatorsAreLockedOutOfCompletedStepsButSuperadminCanEditThem() {
        TrackerProgress progress = TrackerProgress.start(true).complete(ORDER_DETAILS, OPERATOR, StepFacts.none());
        assertFalse(progress.canEditDetails(OPERATOR, ORDER_DETAILS));
        assertTrue(progress.canEditDetails(SUPERADMIN, ORDER_DETAILS));
        assertTrue(progress.canEditDetails(OPERATOR, DESIGNING_ACTIVITY));
        assertFalse(progress.canEditDetails(OPERATOR, HEAD_APPROVAL));
        assertFalse(progress.canEditDetails(SUPERADMIN, HEAD_APPROVAL));
    }

    @Test
    void headApprovalDetailsBelongToSuperadminWhileActive() {
        TrackerProgress atThree = completeThrough(TrackerProgress.start(true), DESIGNING_ACTIVITY);
        assertTrue(atThree.canEditDetails(SUPERADMIN, HEAD_APPROVAL));
        assertFalse(atThree.canEditDetails(OPERATOR, HEAD_APPROVAL));
    }

    @Test
    void stageNotesAreTheOperatorsAndOnlyWhileTheStepIsActive() {
        TrackerProgress progress = TrackerProgress.start(true).complete(ORDER_DETAILS, OPERATOR, StepFacts.none());
        assertFalse(progress.canEditStageNote(SUPERADMIN, DESIGNING_ACTIVITY));
        assertTrue(progress.canEditStageNote(OPERATOR, DESIGNING_ACTIVITY));
        assertFalse(progress.canEditStageNote(OPERATOR, ORDER_DETAILS));
        assertFalse(progress.canEditStageNote(OPERATOR, HEAD_APPROVAL));
        TrackerProgress reopened = progress.reopen(ORDER_DETAILS, SUPERADMIN);
        assertTrue(reopened.canEditStageNote(OPERATOR, ORDER_DETAILS));
        assertFalse(reopened.canEditStageNote(SUPERADMIN, ORDER_DETAILS));
    }

    @Test
    void operatorWritesTheStageNoteOnSuperadminSteps() {
        TrackerProgress atThree = completeThrough(TrackerProgress.start(true), DESIGNING_ACTIVITY);
        assertTrue(atThree.canEditStageNote(OPERATOR, HEAD_APPROVAL));
    }

    @Test
    void draftImagesAfterAChangeRequestCanBeManagedByTheOperatorEvenThoughStepTwoIsCompleted() {
        TrackerProgress atThree = completeThrough(TrackerProgress.start(true), DESIGNING_ACTIVITY);
        assertFalse(atThree.canManageDraftImages(OPERATOR, false));
        assertTrue(atThree.canManageDraftImages(OPERATOR, true));
        assertTrue(atThree.canManageDraftImages(SUPERADMIN, false));
        assertTrue(TrackerProgress.start(true).complete(ORDER_DETAILS, OPERATOR, StepFacts.none())
                .canManageDraftImages(OPERATOR, false));
        assertFalse(TrackerProgress.start(true).canManageDraftImages(OPERATOR, false));
        assertFalse(TrackerProgress.start(false).canManageDraftImages(OPERATOR, true));
    }

    @Test
    void completingTheLastStepFinishesTheOrder() {
        TrackerProgress done = completeThrough(TrackerProgress.start(true), DELIVERY_INVOICING);
        assertTrue(done.finished());
        assertTrue(done.current().isEmpty());
        assertEquals(12, done.completedCount());
    }

    @Test
    void restoreRejectsAnImpossibleCompletedCount() {
        assertThrows(IllegalArgumentException.class, () -> TrackerProgress.restore(false, 10));
        assertEquals(ACTIVE, TrackerProgress.restore(true, 4).status(PRODUCTION_PLANNING));
    }
}
