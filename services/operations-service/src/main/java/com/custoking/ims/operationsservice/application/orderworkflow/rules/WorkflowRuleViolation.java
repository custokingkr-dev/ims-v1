package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import java.util.List;
import java.util.Set;

/**
 * An order-workflow business rule was broken. The code is stable for callers and the screen; the message is a sentence
 * for people. A {@code GATE_UNMET} violation also carries every unmet requirement ({@link #unmet()}), so the response can
 * list them all (design D19).
 *
 * <p>{@link #httpStatus()} is the status the codes map to, so every controller that handles this exception answers the
 * same way: 403 for who-may-act refusals, 400 for malformed input, 409 for everything that is refused because of the
 * order's current state.
 */
public class WorkflowRuleViolation extends RuntimeException {
    /** Complete was refused; {@link #unmet()} lists every requirement still missing. */
    public static final String GATE_UNMET = "GATE_UNMET";
    /** The stage is locked, or completed and the caller is not superadmin (D18). */
    public static final String STEP_NOT_EDITABLE = "STEP_NOT_EDITABLE";
    public static final String STEP_NOT_ACTIVE = "STEP_NOT_ACTIVE";
    public static final String STEP_NOT_COMPLETED = "STEP_NOT_COMPLETED";
    public static final String STEP_NOT_ON_PATH = "STEP_NOT_ON_PATH";
    public static final String HEAD_APPROVAL_STALE = "HEAD_APPROVAL_STALE";
    public static final String SUPERADMIN_ONLY = "SUPERADMIN_ONLY";
    public static final String NOT_ASSIGNEE = "NOT_ASSIGNEE";
    public static final String INVALID_STEP_DATA = "INVALID_STEP_DATA";
    public static final String NOTE_TOO_LONG = "NOTE_TOO_LONG";
    /** A note, comment or other required text was empty. */
    public static final String NOTE_REQUIRED = "NOTE_REQUIRED";
    public static final String INVALID_INVITE = "INVALID_INVITE";

    private static final Set<String> FORBIDDEN = Set.of(SUPERADMIN_ONLY, NOT_ASSIGNEE);
    private static final Set<String> BAD_REQUEST = Set.of(INVALID_STEP_DATA, NOTE_TOO_LONG, NOTE_REQUIRED, INVALID_INVITE);

    private final String code;
    private final List<GateFailure> unmet;

    public WorkflowRuleViolation(String code, String message) {
        this(code, message, List.of());
    }

    public WorkflowRuleViolation(String code, String message, List<GateFailure> unmet) {
        super(message);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("A rule violation needs a code");
        this.code = code;
        this.unmet = unmet == null ? List.of() : List.copyOf(unmet);
    }

    /** Complete refused with every requirement the stage still needs, in the template's gate order. */
    public static WorkflowRuleViolation gateUnmet(String stepTitle, List<GateFailure> unmet) {
        if (unmet == null || unmet.isEmpty()) throw new IllegalArgumentException("GATE_UNMET needs at least one failure");
        String missing = String.join("; ", unmet.stream().map(GateFailure::message).toList());
        return new WorkflowRuleViolation(GATE_UNMET, stepTitle + " cannot be completed yet: " + missing, unmet);
    }

    public String code() { return code; }

    /** Every unmet requirement for {@code GATE_UNMET}; empty for every other code. */
    public List<GateFailure> unmet() { return unmet; }

    /** 403, 400 or 409 (see the class comment). */
    public int httpStatus() {
        if (FORBIDDEN.contains(code)) return 403;
        if (BAD_REQUEST.contains(code)) return 400;
        return 409;
    }
}
