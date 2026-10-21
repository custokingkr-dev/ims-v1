package com.custoking.ims.operationsservice.application.tracker;

/** The recorded facts the completion gates look at; the caller loads them from the saved step data. */
public record StepFacts(boolean designSentForApproval, boolean designApproved, boolean superadminSignedOff,
                        Counts counts, String discrepancyNote) {

    public static StepFacts none() { return new StepFacts(false, false, false, null, null); }
}
