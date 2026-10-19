package com.custoking.ims.operationsservice.application.tracker;

import java.util.Optional;

/** Rules a step must satisfy before it can be marked completed (spec v4, "Sign-offs and gates"). */
final class CompletionGates {
    private CompletionGates() { }

    static Optional<TrackerRuleViolation> blocking(TrackerStep step, StepFacts facts) {
        return switch (step) {
            case DESIGNING_ACTIVITY -> facts.designSentForApproval() ? Optional.empty()
                    : Optional.of(new TrackerRuleViolation("DESIGN_NOT_SENT", "Send the design for approval before completing this step"));
            case HEAD_APPROVAL -> facts.designApproved() ? Optional.empty()
                    : Optional.of(new TrackerRuleViolation("DESIGN_NOT_APPROVED", "The design must be approved before this step can be completed"));
            case PRE_PRINT_QUALITY_GATE, DELIVERY_INVOICING -> facts.superadminSignedOff() ? Optional.empty()
                    : Optional.of(new TrackerRuleViolation("SIGN_OFF_REQUIRED", "Superadmin sign-off is required"));
            case VALIDATION_COUNT -> validationCount(facts);
            default -> Optional.empty();
        };
    }

    private static Optional<TrackerRuleViolation> validationCount(StepFacts facts) {
        Counts counts = facts.counts();
        if (counts == null || !counts.complete()) {
            return Optional.of(new TrackerRuleViolation("COUNTS_INCOMPLETE", "Enter every count before completing this step"));
        }
        boolean hasNote = facts.discrepancyNote() != null && !facts.discrepancyNote().isBlank();
        if (counts.mismatch() && !hasNote) {
            return Optional.of(new TrackerRuleViolation("DISCREPANCY_NOTE_REQUIRED", "The counts do not match; add a discrepancy note"));
        }
        return Optional.empty();
    }
}
