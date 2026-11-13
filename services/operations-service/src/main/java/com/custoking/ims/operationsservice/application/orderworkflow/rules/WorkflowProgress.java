package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.template.BlockType;
import com.custoking.ims.operationsservice.application.orderworkflow.template.Phase;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Where an order stands on its workflow path (design D18). The path is the template's stages that apply to the order
 * (wholesale notebook orders skip the customised-only stages). Stages before {@code completed} are completed, the next is
 * active and the rest are locked: stages complete strictly in order, and reopening a stage reverts every later stage to
 * locked (their saved data is kept by the caller). Instances are immutable; each change returns a new one.
 *
 * <p>Stages are addressed by their template number ({@link StepSpec#order()}), which is also the stored step order.
 * Whether a caller is the assignee of a stage is the service's check; the rules here are the role and status rules.
 */
public final class WorkflowProgress {
    /** The order status once every stage is completed. */
    public static final String COMPLETED = "COMPLETED";
    /** The design state an order returns to when Head Approval requests changes. */
    public static final String CHANGES_REQUESTED = "CHANGES_REQUESTED";

    private final List<StepSpec> path;
    private final int completed;

    private WorkflowProgress(List<StepSpec> path, int completed) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("A workflow path needs at least one stage");
        if (completed < 0 || completed > path.size()) throw new IllegalArgumentException("Completed count is outside the workflow path");
        for (int index = 1; index < path.size(); index++) {
            if (path.get(index).order() <= path.get(index - 1).order()) {
                throw new IllegalArgumentException("A workflow path must be in stage order");
            }
        }
        this.path = List.copyOf(path);
        this.completed = completed;
    }

    /** A new order: only the first stage of its path is active. */
    public static WorkflowProgress start(WorkflowTemplate template, boolean customised) {
        return new WorkflowProgress(template.pathFor(customised), 0);
    }

    /** Rebuilds saved progress; {@code completedCount} is the number of stages completed in path order. */
    public static WorkflowProgress restore(WorkflowTemplate template, boolean customised, int completedCount) {
        return new WorkflowProgress(template.pathFor(customised), completedCount);
    }

    /** Progress over an explicit path (for example the stages an existing order was created with). */
    public static WorkflowProgress of(List<StepSpec> path, int completedCount) {
        return new WorkflowProgress(path, completedCount);
    }

    public List<StepSpec> path() { return path; }
    public int total() { return path.size(); }
    public int completedCount() { return completed; }
    public boolean finished() { return completed == path.size(); }

    /** The active stage, or empty once every stage is completed. */
    public Optional<StepSpec> current() {
        return finished() ? Optional.empty() : Optional.of(path.get(completed));
    }

    /** The most recently completed stage on the path, or empty when none is. */
    public Optional<StepSpec> lastCompleted() {
        return completed == 0 ? Optional.empty() : Optional.of(path.get(completed - 1));
    }

    /** The order's status: the active stage's code, or {@link #COMPLETED}. */
    public String statusCode() {
        return current().map(StepSpec::code).orElse(COMPLETED);
    }

    /** The phase of the active stage; the last stage's phase once finished. */
    public Phase phase() {
        return current().orElse(path.get(path.size() - 1)).phase();
    }

    public boolean onPath(int order) {
        return indexOf(order) >= 0;
    }

    /** The stage with this number on this order's path. */
    public StepSpec step(int order) {
        int index = indexOf(order);
        if (index < 0) throw notOnPath(order);
        return path.get(index);
    }

    public StepStatus status(int order) {
        int index = indexOf(order);
        if (index < 0) throw notOnPath(order);
        if (index < completed) return StepStatus.COMPLETED;
        return index == completed ? StepStatus.ACTIVE : StepStatus.LOCKED;
    }

    /** The stage before this one on the path, for "Opens for editing when <previous stage> is completed". */
    public Optional<StepSpec> previous(int order) {
        int index = indexOf(order);
        if (index < 0) throw notOnPath(order);
        return index == 0 ? Optional.empty() : Optional.of(path.get(index - 1));
    }

    /** The stage after this one on the path. */
    public Optional<StepSpec> next(int order) {
        int index = indexOf(order);
        if (index < 0) throw notOnPath(order);
        return index + 1 < path.size() ? Optional.of(path.get(index + 1)) : Optional.empty();
    }

    /**
     * Every requirement {@code Complete} still needs on this stage: the unmet gates while it is active, and nothing for a
     * completed or locked stage.
     */
    public List<GateFailure> unmet(int order, GateFacts facts) {
        return status(order) == StepStatus.ACTIVE ? Gates.unmet(step(order), facts) : List.of();
    }

    /**
     * Completes the active stage. Refused with {@code STEP_NOT_ACTIVE} for any other stage, {@code SUPERADMIN_ONLY} when
     * the stage is superadmin's to complete (Head Approval), and {@code GATE_UNMET} listing every unmet requirement.
     */
    public WorkflowProgress complete(int order, Role actor, GateFacts facts) {
        StepSpec step = step(order);
        if (status(order) != StepStatus.ACTIVE) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_ACTIVE, step.title() + " is not the active stage");
        }
        if (step.superadminCompletes() && actor != Role.SUPERADMIN) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, "Only a superadmin can complete " + step.title());
        }
        List<GateFailure> unmet = Gates.unmet(step, facts);
        if (!unmet.isEmpty()) throw WorkflowRuleViolation.gateUnmet(step.title(), unmet);
        return new WorkflowProgress(path, completed + 1);
    }

    /**
     * Superadmin reopens a completed stage: it becomes active again and every later stage is locked. This is also how
     * Head Approval's "Request changes" and Pre-Print's "Send back to Designing Activity" return an order to Designing
     * Activity (D21, D22).
     */
    public WorkflowProgress reopen(int order, Role actor) {
        StepSpec step = step(order);
        if (actor != Role.SUPERADMIN) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.SUPERADMIN_ONLY, "Only a superadmin can reopen a completed stage");
        }
        if (status(order) != StepStatus.COMPLETED) {
            throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_COMPLETED, step.title() + " is not completed");
        }
        return new WorkflowProgress(path, indexOf(order));
    }

    /** The stages a reopen of {@code order} locks again: every stage after it up to and including the active one. */
    public List<StepSpec> revertedBy(int order) {
        int index = indexOf(order);
        if (index < 0) throw notOnPath(order);
        return index >= completed ? List.of() : path.subList(index + 1, Math.min(completed + 1, path.size()));
    }

    /**
     * Saving data and ticking checklists (D18): nobody edits a locked stage; the active stage is editable (by its
     * assignee, which the service checks, or by superadmin), except that only superadmin edits a stage superadmin
     * completes; only superadmin edits a completed stage. This is what keeps Printing Press read-only until Pre-Print
     * Quality Check is completed.
     */
    public boolean canEdit(Role actor, int order) {
        StepSpec step = step(order);
        return switch (status(order)) {
            case LOCKED -> false;
            case COMPLETED -> actor == Role.SUPERADMIN;
            case ACTIVE -> !step.superadminCompletes() || actor == Role.SUPERADMIN;
        };
    }

    /** Refuses an edit {@link #canEdit} does not allow, with {@code STEP_NOT_EDITABLE} and the reason. */
    public void requireEditable(Role actor, int order) {
        if (canEdit(actor, order)) return;
        StepSpec step = step(order);
        String reason = switch (status(order)) {
            case LOCKED -> "Opens for editing when " + previous(order).map(StepSpec::title).orElse("the previous stage") + " is completed";
            case COMPLETED -> step.title() + " is completed; only a superadmin can change it";
            case ACTIVE -> "Only a superadmin can change " + step.title();
        };
        throw new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_EDITABLE, reason);
    }

    /** Whether the role may complete the stage now (gates aside): it is active, and superadmin's when it says so. */
    public boolean canComplete(Role actor, int order) {
        return status(order) == StepStatus.ACTIVE && (!step(order).superadminCompletes() || actor == Role.SUPERADMIN);
    }

    public boolean canReopen(Role actor, int order) {
        return actor == Role.SUPERADMIN && status(order) == StepStatus.COMPLETED;
    }

    /** Superadmin records a stage's sign-off while the stage is open to them: active, or completed (D18, D20). */
    public boolean canSignOff(Role actor, int order) {
        return actor == Role.SUPERADMIN && step(order).hasSignOff() && status(order) != StepStatus.LOCKED;
    }

    /** Stage notes may be added to any stage that has been reached; a locked stage is read-only. */
    public boolean canAddNote(int order) {
        return status(order) != StepStatus.LOCKED;
    }

    /** The order's design stage: the first stage on the path with the Upload Design Files card, if any. */
    public Optional<StepSpec> designStep() {
        return path.stream().filter(step -> step.hasBlock(BlockType.DESIGN_FILES)).findFirst();
    }

    /**
     * Uploading or deleting design files (D26, D42): while the design stage is active (by its assignee, which the service
     * checks, or by superadmin), and after it is completed only by superadmin or while Head Approval's requested changes
     * are open. Orders without a design stage (wholesale notebooks) have none.
     */
    public boolean canManageDesignFiles(Role actor, String designState) {
        Optional<StepSpec> design = designStep();
        if (design.isEmpty()) return false;
        return switch (status(design.get().order())) {
            case LOCKED -> false;
            case ACTIVE -> true;
            case COMPLETED -> actor == Role.SUPERADMIN || CHANGES_REQUESTED.equals(designState);
        };
    }

    private int indexOf(int order) {
        for (int index = 0; index < path.size(); index++) if (path.get(index).order() == order) return index;
        return -1;
    }

    private static WorkflowRuleViolation notOnPath(int order) {
        return new WorkflowRuleViolation(WorkflowRuleViolation.STEP_NOT_ON_PATH, "Stage " + order + " does not apply to this order");
    }
}
