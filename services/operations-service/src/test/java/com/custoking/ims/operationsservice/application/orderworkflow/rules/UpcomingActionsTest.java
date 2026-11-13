package com.custoking.ims.operationsservice.application.orderworkflow.rules;

import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource.OpenCallback;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions.DueState;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions.Kind;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions.UpcomingAction;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures.NOTEBOOK;
import static org.junit.jupiter.api.Assertions.*;

/** The derived "Live list" of D39: what is in it, in which order, how many rows, and how each due time reads. */
class UpcomingActionsTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ZoneOffset IST_OFFSET = ZoneOffset.ofHoursMinutes(5, 30);
    /** 2026-11-13 12:00 IST. */
    private static final Instant NOW = OffsetDateTime.of(2026, 11, 13, 12, 0, 0, 0, IST_OFFSET).toInstant();

    private static OffsetDateTime ist(int day, int hour, int minute) {
        return OffsetDateTime.of(2026, 11, day, hour, minute, 0, 0, IST_OFFSET);
    }

    private static List<String> titles(List<UpcomingAction> rows) {
        return rows.stream().map(UpcomingAction::title).toList();
    }

    @Test
    void aNewOrderListsItsDeadlineThenWhatTheFirstStageStillNeeds() {
        WorkflowProgress progress = WorkflowProgress.start(NOTEBOOK, true);
        List<GateFailure> unmet = progress.unmet(1, GateFacts.ofData(Map.of()));
        List<UpcomingAction> rows = UpcomingActions.derive(progress, null, ist(13, 14, 0), unmet, List.of(), NOW, IST);
        assertEquals(List.of("Complete Order Details", "Save the school order PDF link",
                "Tick \"Introduction message sent in the school group\""), titles(rows));
        assertEquals(List.of(Kind.STAGE_DEADLINE, Kind.UNMET_GATE, Kind.UNMET_GATE), rows.stream().map(UpcomingAction::kind).toList());
        assertTrue(rows.stream().allMatch(row -> row.stepOrder() == 1 && !row.done() && row.dueState() == DueState.DUE_TODAY));
    }

    @Test
    void theLastCompletedStageComesFirstTicked() {
        WorkflowProgress progress = WorkflowProgress.restore(NOTEBOOK, true, 3);
        List<UpcomingAction> rows = UpcomingActions.derive(progress, ist(13, 10, 0), ist(13, 14, 0),
                List.of(new GateFailure("HEAD_APPROVED", "Approve the design")), List.of(), NOW, IST);
        UpcomingAction done = rows.get(0);
        assertEquals(new UpcomingAction(Kind.LAST_COMPLETED, "CDR Conversion completed", 3, ist(13, 10, 0), DueState.NONE, true), done);
        assertEquals(List.of("CDR Conversion completed", "Complete Head Approval", "Approve the design"), titles(rows));
    }

    @Test
    void openCallbacksAreSortedIntoTheListByWhenTheyAreDue() {
        WorkflowProgress progress = WorkflowProgress.restore(NOTEBOOK, true, 4);
        List<OpenCallback> callbacks = List.of(
                new OpenCallback(1, "PRINTING_PRESS", "Metro Offset", 5, ist(15, 11, 0)),
                new OpenCallback(2, "SCHOOL", "Mrs Rao", 5, ist(13, 15, 0)),
                new OpenCallback(3, "DESIGNER", null, null, null));
        List<UpcomingAction> rows = UpcomingActions.derive(progress, ist(13, 9, 0), ist(14, 12, 0),
                List.of(new GateFailure("ALL_CHECKED", "Tick the remaining 3 items of Production planning checklist")), callbacks, NOW, IST);
        assertEquals(List.of("Head Approval completed", "Call back School · Mrs Rao", "Complete Production Planning",
                "Tick the remaining 3 items of Production planning checklist", "Call back Printing Press · Metro Offset",
                "Call back Designer"), titles(rows));
        assertEquals(List.of(DueState.NONE, DueState.DUE_TODAY, DueState.DUE_TOMORROW, DueState.DUE_TOMORROW, DueState.LATER, DueState.NONE),
                rows.stream().map(UpcomingAction::dueState).toList());
        assertEquals(Kind.CALLBACK, rows.get(1).kind());
        assertEquals(5, rows.get(1).stepOrder());
    }

    @Test
    void atMostSixRowsAndTheStageDeadlineIsAlwaysKept() {
        WorkflowProgress progress = WorkflowProgress.restore(NOTEBOOK, true, 6);
        List<GateFailure> unmet = List.of(new GateFailure("ALL_CHECKED", "Tick the checks"), new GateFailure("FIELDS_PRESENT", "Save the sample"),
                new GateFailure("CHOICE_IN", "Verify the school"), new GateFailure("SIGNED_OFF", "Record reviewer sign-off"));
        List<OpenCallback> callbacks = List.of(
                new OpenCallback(1, "SCHOOL", "Mr Iyer", 7, ist(13, 11, 0)),
                new OpenCallback(2, "PRINTING_PRESS", null, 7, ist(13, 12, 30)),
                new OpenCallback(3, "INTERNAL_APPROVER", null, 7, ist(20, 9, 0)));
        List<UpcomingAction> rows = UpcomingActions.derive(progress, ist(12, 18, 0), ist(16, 12, 0), unmet, callbacks, NOW, IST);
        assertEquals(UpcomingActions.MAX_ROWS, rows.size());
        assertEquals(List.of("Board Procurement completed", "Call back School · Mr Iyer", "Call back Printing Press",
                "Complete Pre-Print Quality Check", "Tick the checks", "Save the sample"), titles(rows),
                "the latest gates and callbacks give way; the deadline stays");
        assertEquals(DueState.OVERDUE, rows.get(1).dueState());
        assertEquals(DueState.DUE_TODAY, rows.get(2).dueState());
        assertEquals(DueState.LATER, rows.get(3).dueState());
    }

    @Test
    void aStageWithoutATimerStillListsItsDeadlineRowWithoutADate() {
        WorkflowProgress progress = WorkflowProgress.restore(NOTEBOOK, true, 1);
        List<UpcomingAction> rows = UpcomingActions.derive(progress, ist(13, 9, 0), null, List.of(), List.of(), NOW, IST);
        assertEquals(List.of("Order Details completed", "Complete Designing Activity"), titles(rows));
        assertNull(rows.get(1).dueAt());
        assertEquals(DueState.NONE, rows.get(1).dueState());
    }

    @Test
    void aFinishedOrderShowsOnlyItsLastStageTicked() {
        WorkflowProgress done = WorkflowProgress.restore(NOTEBOOK, false, 9);
        List<UpcomingAction> rows = UpcomingActions.derive(done, ist(13, 11, 0), null, List.of(), List.of(), NOW, IST);
        assertEquals(List.of("Delivery & Invoicing completed"), titles(rows));
        assertTrue(rows.get(0).done());
    }

    /** Dates read in the school's zone: 00:30 IST on the 14th is tomorrow even though it is still the 13th in UTC. */
    @Test
    void dueStatesAreReadInTheSchoolsZone() {
        Instant lateEvening = OffsetDateTime.of(2026, 11, 13, 23, 30, 0, 0, IST_OFFSET).toInstant();
        assertEquals(DueState.DUE_TOMORROW, UpcomingActions.dueState(ist(14, 0, 30), lateEvening, IST));
        assertEquals(DueState.DUE_TODAY, UpcomingActions.dueState(ist(13, 23, 59), lateEvening, IST));
        assertEquals(DueState.OVERDUE, UpcomingActions.dueState(ist(13, 23, 0), lateEvening, IST));
        assertEquals(DueState.LATER, UpcomingActions.dueState(ist(15, 0, 0), lateEvening, IST));
        assertEquals(DueState.NONE, UpcomingActions.dueState(null, lateEvening, IST));
        assertEquals(DueState.DUE_TODAY, UpcomingActions.dueState(OffsetDateTime.of(2026, 11, 13, 18, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 11, 13, 18, 0, 0, 0, ZoneOffset.UTC).toInstant().minusSeconds(60), IST),
                "23:30 IST due at 23:30 IST, seen a minute earlier");
    }
}
