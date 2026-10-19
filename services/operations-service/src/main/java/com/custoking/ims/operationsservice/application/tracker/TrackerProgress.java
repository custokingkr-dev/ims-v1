package com.custoking.ims.operationsservice.application.tracker;

import java.util.List;
import java.util.Optional;

/**
 * Where an order stands on its tracker path. Steps before {@code completed} are completed, the next one is active
 * and the rest are locked, so reopening a step reverts every later step to not-completed (their saved data is
 * kept by the caller). Instances are immutable; each change returns a new one.
 */
public final class TrackerProgress {
    private final List<TrackerStep> path;
    private final int completed;

    private TrackerProgress(List<TrackerStep> path, int completed) {
        this.path = path;
        this.completed = completed;
    }

    /** A new order: only the first step is active. */
    public static TrackerProgress start(boolean customised) {
        return new TrackerProgress(TrackerStep.pathFor(customised), 0);
    }

    /** Rebuilds saved progress; {@code completedCount} is the number of steps completed in path order. */
    public static TrackerProgress restore(boolean customised, int completedCount) {
        List<TrackerStep> path = TrackerStep.pathFor(customised);
        if (completedCount < 0 || completedCount > path.size()) {
            throw new IllegalArgumentException("Completed count is outside the tracker path");
        }
        return new TrackerProgress(path, completedCount);
    }

    public List<TrackerStep> path() { return path; }
    public int total() { return path.size(); }
    public int completedCount() { return completed; }
    public boolean finished() { return completed == path.size(); }

    public Optional<TrackerStep> current() {
        return finished() ? Optional.empty() : Optional.of(path.get(completed));
    }

    public StepStatus status(TrackerStep step) {
        int index = path.indexOf(step);
        if (index < 0) throw new TrackerRuleViolation("STEP_NOT_ON_PATH", step.title() + " does not apply to this order");
        if (index < completed) return StepStatus.COMPLETED;
        return index == completed ? StepStatus.ACTIVE : StepStatus.LOCKED;
    }

    /** Marks the active step completed. Only the active step can be completed, in path order. */
    public TrackerProgress complete(TrackerStep step, Role actor, StepFacts facts) {
        if (status(step) != StepStatus.ACTIVE) {
            throw new TrackerRuleViolation("STEP_NOT_ACTIVE", step.title() + " is not the active step");
        }
        if (step == TrackerStep.HEAD_APPROVAL && actor != Role.SUPERADMIN) {
            throw new TrackerRuleViolation("SUPERADMIN_ONLY", "Only superadmin can complete Head Approval");
        }
        CompletionGates.blocking(step, facts).ifPresent(violation -> { throw violation; });
        return new TrackerProgress(path, completed + 1);
    }

    /** Superadmin reopens a completed step; every later step reverts to not-completed. */
    public TrackerProgress reopen(TrackerStep step, Role actor) {
        if (actor != Role.SUPERADMIN) {
            throw new TrackerRuleViolation("SUPERADMIN_ONLY", "Only superadmin can reopen a completed step");
        }
        if (status(step) != StepStatus.COMPLETED) {
            throw new TrackerRuleViolation("STEP_NOT_COMPLETED", step.title() + " is not completed");
        }
        return new TrackerProgress(path, path.indexOf(step));
    }

    /** Step details (everything except the stage note). Operators are locked out of completed steps. */
    public boolean canEditDetails(Role actor, TrackerStep step) {
        return switch (status(step)) {
            case LOCKED -> false;
            case COMPLETED -> actor == Role.SUPERADMIN;
            case ACTIVE -> step == TrackerStep.HEAD_APPROVAL ? actor == Role.SUPERADMIN : true;
        };
    }

    /**
     * Stage notes belong to the assigned operator on every step, including the ones superadmin acts on, and only
     * while the step is active (a completed step is locked; superadmin reopening it makes it active again).
     * Superadmin never edits a stage note.
     */
    public boolean canEditStageNote(Role actor, TrackerStep step) {
        return actor == Role.OPERATOR && status(step) == StepStatus.ACTIVE;
    }

    /**
     * Uploading or deleting draft images. Step 2 only exists on customised orders. After superadmin requests
     * changes the operator may manage images although step 2 stays completed.
     */
    public boolean canManageDraftImages(Role actor, boolean changesRequested) {
        if (!path.contains(TrackerStep.DESIGNING_ACTIVITY)) return false;
        return switch (status(TrackerStep.DESIGNING_ACTIVITY)) {
            case LOCKED -> false;
            case ACTIVE -> true;
            case COMPLETED -> actor == Role.SUPERADMIN || changesRequested;
        };
    }
}
