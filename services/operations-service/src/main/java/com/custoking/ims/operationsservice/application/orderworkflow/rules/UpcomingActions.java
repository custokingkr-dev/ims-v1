package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource.OpenCallback;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The sidebar's "Upcoming actions" live list (design D39), derived from the order's state and never entered by hand.
 * At most {@link #MAX_ROWS} rows:
 * <ol>
 *   <li>the last completed stage, ticked;</li>
 *   <li>the active stage's deadline;</li>
 *   <li>each unmet gate of the active stage;</li>
 *   <li>each open callback.</li>
 * </ol>
 * The ticked row comes first; the rest are ordered by when they are due (earliest first, rows without a time last; on a
 * tie the stage deadline precedes its gates and gates precede callbacks). When there are more rows than fit, the stage
 * deadline is always kept and the latest gates and callbacks are dropped. Due states are computed in the school's zone.
 */
public final class UpcomingActions {
    public static final int MAX_ROWS = 6;

    public enum Kind { LAST_COMPLETED, STAGE_DEADLINE, UNMET_GATE, CALLBACK }

    /** Overdue, due today, due tomorrow, a later date, or no due time (and the ticked row). */
    public enum DueState { OVERDUE, DUE_TODAY, DUE_TOMORROW, LATER, NONE }

    /**
     * One row of the live list.
     *
     * @param kind      what the row is
     * @param title     the row's text, e.g. "Complete Head Approval" or "Call back School · Mrs Rao"
     * @param stepOrder the stage the row belongs to, or null
     * @param dueAt     when it is due; for the ticked row, when the stage was completed; null when there is no time
     * @param dueState  how the due time reads in the school's zone; {@link DueState#NONE} for the ticked row
     * @param done      true only for the ticked row (the last completed stage)
     */
    public record UpcomingAction(Kind kind, String title, Integer stepOrder, OffsetDateTime dueAt, DueState dueState, boolean done) {
    }

    private static final Map<String, String> ENTITY_LABELS = Map.of(
            "SCHOOL", "School", "DESIGNER", "Designer", "PRINTING_PRESS", "Printing Press",
            "MANUFACTURING_FACTORY", "Manufacturing Factory", "INTERNAL_APPROVER", "Internal Approver");

    private UpcomingActions() { }

    /**
     * @param progress        the order's progress
     * @param lastCompletedAt when the last completed stage was completed, or null
     * @param currentDueAt    the active stage's deadline, or null when it has no timer
     * @param unmet           the active stage's unmet gates ({@link WorkflowProgress#unmet})
     * @param callbacks       the order's open callbacks ({@code CallbackSource})
     * @param now             the server time
     * @param zone            the school's zone
     */
    public static List<UpcomingAction> derive(WorkflowProgress progress, OffsetDateTime lastCompletedAt, OffsetDateTime currentDueAt,
                                              List<GateFailure> unmet, List<OpenCallback> callbacks, Instant now, ZoneId zone) {
        List<UpcomingAction> rows = new ArrayList<>();
        Optional<StepSpec> last = progress.lastCompleted();
        last.ifPresent(step -> rows.add(new UpcomingAction(Kind.LAST_COMPLETED, step.title() + " completed", step.order(),
                lastCompletedAt, DueState.NONE, true)));

        Optional<StepSpec> current = progress.current();
        List<UpcomingAction> pending = new ArrayList<>();
        UpcomingAction deadline = current.map(step -> new UpcomingAction(Kind.STAGE_DEADLINE, "Complete " + step.title(), step.order(),
                currentDueAt, dueState(currentDueAt, now, zone), false)).orElse(null);
        if (current.isPresent() && unmet != null) {
            int order = current.get().order();
            for (GateFailure failure : unmet) {
                pending.add(new UpcomingAction(Kind.UNMET_GATE, failure.message(), order, currentDueAt, dueState(currentDueAt, now, zone), false));
            }
        }
        if (callbacks != null) {
            for (OpenCallback callback : callbacks) {
                pending.add(new UpcomingAction(Kind.CALLBACK, callbackTitle(callback), callback.stepOrder(), callback.nextFollowUpAt(),
                        dueState(callback.nextFollowUpAt(), now, zone), false));
            }
        }

        Comparator<UpcomingAction> byDue = Comparator
                .comparing(UpcomingAction::dueAt, Comparator.nullsLast(Comparator.comparing(OffsetDateTime::toInstant)))
                .thenComparing(UpcomingAction::kind);
        // List.sort is stable, so rows due at the same time keep the order they were added in.
        pending.sort(byDue);
        int room = MAX_ROWS - rows.size() - (deadline == null ? 0 : 1);
        List<UpcomingAction> kept = new ArrayList<>(pending.subList(0, Math.max(0, Math.min(room, pending.size()))));
        if (deadline != null) kept.add(deadline);
        kept.sort(byDue);
        rows.addAll(kept);
        return List.copyOf(rows);
    }

    /** How a due time reads in the school's zone: overdue, today, tomorrow, a later date, or none. */
    public static DueState dueState(OffsetDateTime dueAt, Instant now, ZoneId zone) {
        if (dueAt == null) return DueState.NONE;
        if (now.isAfter(dueAt.toInstant())) return DueState.OVERDUE;
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate due = dueAt.toInstant().atZone(zone).toLocalDate();
        if (due.equals(today)) return DueState.DUE_TODAY;
        if (due.equals(today.plusDays(1))) return DueState.DUE_TOMORROW;
        return DueState.LATER;
    }

    private static String callbackTitle(OpenCallback callback) {
        String entity = ENTITY_LABELS.getOrDefault(callback.contacted(), callback.contacted());
        String who = callback.contactLabel() == null || callback.contactLabel().isBlank() ? "" : " · " + callback.contactLabel().strip();
        return "Call back " + entity + who;
    }
}
