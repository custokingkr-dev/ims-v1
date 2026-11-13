package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.CallLogService.CallLogEntry;
import com.custoking.ims.operationsservice.application.CallLogService.Contacted;
import com.custoking.ims.operationsservice.application.CallLogService.NewCall;
import com.custoking.ims.operationsservice.application.CallLogService.Outcome;
import com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.Order;
import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource.OpenCallback;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.persistence.CallLogRepository;
import com.custoking.ims.operationsservice.persistence.CallLogRepository.NewCallRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.OPERATOR_PERMISSIONS;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.READ_ONLY_PERMISSIONS;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.operator;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.schoolAdmin;
import static com.custoking.ims.operationsservice.application.WorkflowExtrasDatabase.superadmin;
import static org.junit.jupiter.api.Assertions.*;

/**
 * An order's call log (design D35, D39) against the real schema through the runtime role and row-level security: who may
 * log, the required-notes and follow-up rules, the 100-word limit, append-only storage, and the open callbacks that feed
 * Upcoming actions.
 */
class CallLogServiceIntegrationTest {
    static final long ASSIGNEE = 31, COLLEAGUE = 32;
    static WorkflowExtrasDatabase db;
    static CallLogService calls;

    @BeforeAll static void start() {
        db = WorkflowExtrasDatabase.get();
        calls = db.calls();
        db.staff.put(ASSIGNEE, "Asha Assignee");
        db.staff.put(COLLEAGUE, "Chetan Colleague");
    }

    @AfterEach void clear() {
        TenantContext.clear();
        db.now = Instant.parse("2026-11-13T06:30:00Z");
    }

    static OffsetDateTime inHours(long hours) { return OffsetDateTime.ofInstant(db.now, ZoneOffset.UTC).plusHours(hours); }

    static NewCall call(Contacted contacted, Outcome outcome, String notes) {
        return new NewCall(contacted.name(), outcome.name(), null, null, null, null, notes, null);
    }

    // ---- who may log ---------------------------------------------------------------------------------------------------

    @Test void anyInScopeOperatorLogsACallNotOnlyTheAssigneeAndItLandsOnTheCurrentStage() {
        Order order = db.notebook(10, 6, ASSIGNEE);
        operator(COLLEAGUE, 10L);

        CallLogEntry logged = calls.log(order.orderId(), new NewCall("SCHOOL", "CONNECTED", "  Mrs Rao (front office)  ", null, null,
                null, " Confirmed the delivery window ", null));

        assertEquals(6, logged.stepOrder(), "the stage defaults to the current one");
        assertEquals(Contacted.SCHOOL, logged.contacted());
        assertEquals(Outcome.CONNECTED, logged.outcome());
        assertEquals("Mrs Rao (front office)", logged.contactLabel());
        assertEquals("Confirmed the delivery window", logged.notes());
        assertNull(logged.nextFollowUpAt());
        assertEquals(COLLEAGUE, logged.createdBy());
        assertEquals("Chetan Colleague", logged.createdByName());
        assertNotNull(logged.createdAt());
        assertEquals(order.schoolId(), db.owner.sql("SELECT school_id FROM workflow.workflow_call_logs WHERE id = :id")
                .param("id", logged.id()).query(Long.class).single(), "the entry belongs to the order's school");
    }

    @Test void theCallLogIsReadByAnyoneWhoMaySeeTheOrderAndWrittenOnlyByThoseWhoMayWorkIt() {
        Order order = db.flier(10, 3, ASSIGNEE);
        superadmin();
        calls.log(order.orderId(), call(Contacted.DESIGNER, Outcome.BUSY, null));

        operator(40, Set.of(10L), Set.of(), READ_ONLY_PERMISSIONS);
        assertEquals(1, calls.list(order.orderId(), null).size(), "the read permission is enough to read");
        assertEquals(403, status(() -> calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, null))),
                "logging needs the act permission");

        schoolAdmin(10);
        assertEquals(403, status(() -> calls.list(order.orderId(), null)), "school administrators keep only the status view");
        assertEquals(403, status(() -> calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, null))));

        operator(41, 20L);
        assertEquals(404, status(() -> calls.list(order.orderId(), null)), "another school's order is not there at all");
        assertEquals(404, status(() -> calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, null))));

        TenantContext.clear();
        assertEquals(403, status(() -> calls.list(order.orderId(), null)));
        superadmin();
        assertEquals(1, calls.list(order.orderId(), null).size(), "nothing refused was stored");
        assertEquals(404, status(() -> calls.list("CK-404404", null)));
    }

    @Test void anItemScopedOperatorWorksOnlyTheirItemsAtTheirSchools() {
        Order flier = db.flier(20, 2, ASSIGNEE);
        Order notebook = db.notebook(20, 2, ASSIGNEE);
        operator(42, Set.of(), Set.of("20:FLIERS"), OPERATOR_PERMISSIONS);

        assertEquals(2, calls.log(flier.orderId(), call(Contacted.SCHOOL, Outcome.NO_ANSWER, null)).stepOrder());
        assertEquals(404, status(() -> calls.log(notebook.orderId(), call(Contacted.SCHOOL, Outcome.NO_ANSWER, null))),
                "a flier scope at the school does not open its notebook orders");
        assertEquals(404, status(() -> calls.list(notebook.orderId(), null)));
    }

    // ---- the rules of an entry -----------------------------------------------------------------------------------------

    @Test void notesAreRequiredForDelaysChangesAndCallbacksOnly() {
        Order order = db.notebook(10, 2, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        for (Outcome outcome : List.of(Outcome.DELAY_REPORTED, Outcome.CHANGES_REQUESTED, Outcome.CALLBACK_REQUESTED)) {
            NewCall withoutNotes = new NewCall("SCHOOL", outcome.name(), null, null, null, null, "   ", inHours(4));
            WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> calls.log(order.orderId(), withoutNotes), outcome.name());
            assertEquals(WorkflowRuleViolation.NOTE_REQUIRED, refused.code());
            assertEquals(400, refused.httpStatus());
            assertEquals("Call notes cannot be empty", refused.getMessage());
        }
        for (Outcome outcome : List.of(Outcome.CONNECTED, Outcome.NO_ANSWER, Outcome.BUSY, Outcome.APPROVAL_RECEIVED)) {
            assertNull(calls.log(order.orderId(), call(Contacted.SCHOOL, outcome, "  ")).notes(), outcome.name());
        }
        assertEquals("Printer is short of board", calls.log(order.orderId(),
                call(Contacted.PRINTING_PRESS, Outcome.DELAY_REPORTED, "Printer is short of board")).notes());
    }

    @Test void aCallbackNeedsItsFollowUpTimeWithinTheComingYear() {
        Order order = db.notebook(10, 2, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        assertBad("Choose when to call back", () -> calls.log(order.orderId(),
                new NewCall("SCHOOL", "CALLBACK_REQUESTED", null, null, null, null, "Principal busy", null)));
        assertBad("The next follow-up cannot be in the past", () -> calls.log(order.orderId(),
                new NewCall("SCHOOL", "CALLBACK_REQUESTED", null, null, null, null, "Principal busy", inHours(-2))));
        assertBad("The next follow-up must be within a year", () -> calls.log(order.orderId(),
                new NewCall("SCHOOL", "CALLBACK_REQUESTED", null, null, null, null, "Principal busy", inHours(24 * 367))));

        OffsetDateTime justAgreed = inHours(0).minusMinutes(30);
        assertEquals(justAgreed.toInstant(), calls.log(order.orderId(), new NewCall("SCHOOL", "CALLBACK_REQUESTED", null, null, null,
                null, "Logged a little late", justAgreed)).nextFollowUpAt().toInstant(), "a callback logged just after its time is kept");
        assertEquals(inHours(26).toInstant(), calls.log(order.orderId(), new NewCall("SCHOOL", "CONNECTED", null, null, null, null,
                null, inHours(26))).nextFollowUpAt().toInstant(), "any call may carry a next follow-up");
    }

    @Test void callNotesAreLimitedToOneHundredWords() {
        Order order = db.notebook(10, 2, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        String hundred = "word ".repeat(100).strip();
        assertEquals(hundred, calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, hundred)).notes());

        WorkflowRuleViolation tooMany = assertThrows(WorkflowRuleViolation.class,
                () -> calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, hundred + " more")));
        assertEquals(WorkflowRuleViolation.NOTE_TOO_LONG, tooMany.code());
        assertEquals("Call notes is limited to 100 words", tooMany.getMessage());
        WorkflowRuleViolation tooLong = assertThrows(WorkflowRuleViolation.class,
                () -> calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, "x".repeat(1001))));
        assertEquals("Call notes is limited to 1000 characters", tooLong.getMessage());
        assertEquals(1, db.owner.sql("SELECT count(*) FROM workflow.workflow_call_logs WHERE instance_id = :id")
                .param("id", order.instanceId()).query(Long.class).single());
    }

    @Test void theEntityAndOutcomeComeFromTheSopsListsAndWhoIsAtMostTwoHundredCharacters() {
        Order order = db.notebook(10, 2, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        assertBad("Choose who was contacted: School, Designer, Printing Press, Manufacturing Factory, Internal Approver",
                () -> calls.log(order.orderId(), new NewCall("VENDOR", "CONNECTED", null, null, null, null, null, null)));
        assertBad("Choose who was contacted: School, Designer, Printing Press, Manufacturing Factory, Internal Approver",
                () -> calls.log(order.orderId(), new NewCall(null, "CONNECTED", null, null, null, null, null, null)));
        assertBad("Choose the call's outcome", () -> calls.log(order.orderId(), new NewCall("SCHOOL", "LATER", null, null, null, null, null, null)));
        assertBad("Who was called is limited to 200 characters", () -> calls.log(order.orderId(),
                new NewCall("SCHOOL", "CONNECTED", "w".repeat(201), null, null, null, null, null)));
        assertBad("Send who was contacted and the outcome", () -> calls.log(order.orderId(), null));
        assertEquals("w".repeat(200), calls.log(order.orderId(),
                new NewCall("INTERNAL_APPROVER", "APPROVAL_RECEIVED", "w".repeat(200), null, null, null, null, null)).contactLabel());
    }

    @Test void aNamedVendorMustBeOneTheOrderSelectedAndNamesTheCallWhenWhoIsBlank() {
        Order order = db.notebook(10, 7, ASSIGNEE);
        long offset = db.vendor("Cityline Offset " + order.orderId(), "9876543210", null, "OFFSET_PRINTERS");
        long binder = db.vendor("Metro Binding " + order.orderId(), "9876543211", null, "BINDERS");
        long other = db.vendor("Unselected Offset " + order.orderId(), "9876543212", null, "OFFSET_PRINTERS");
        db.selectVendors(order, "{\"OFFSET_PRINTERS\": [" + offset + "], \"BINDERS\": [" + binder + "]}");
        operator(ASSIGNEE, 10L);

        CallLogEntry press = calls.log(order.orderId(), new NewCall("PRINTING_PRESS", "DELAY_REPORTED", null, offset, null, null,
                "Plates late by a day", null));
        assertEquals(offset, press.vendorId());
        assertEquals("Cityline Offset " + order.orderId(), press.contactLabel(), "the vendor's name fills a blank who");
        CallLogEntry factory = calls.log(order.orderId(), new NewCall("MANUFACTURING_FACTORY", "CONNECTED", "Binder floor manager", binder,
                null, null, null, null));
        assertEquals("Binder floor manager", factory.contactLabel(), "a typed who is kept");

        assertBad("Pick a vendor selected for this order", () -> calls.log(order.orderId(),
                new NewCall("PRINTING_PRESS", "CONNECTED", null, other, null, null, null, null)));
        assertBad("A vendor can be named only on a Printing Press or Manufacturing Factory call", () -> calls.log(order.orderId(),
                new NewCall("SCHOOL", "CONNECTED", null, offset, null, null, null, null)));
    }

    @Test void aNamedDesignerIsOnlyForADesignerCall() {
        Order order = db.notebook(10, 2, ASSIGNEE);
        long designer = db.designer("Neha Designs " + order.orderId(), "9876500000");
        operator(ASSIGNEE, 10L);

        CallLogEntry entry = calls.log(order.orderId(), new NewCall("DESIGNER", "CHANGES_REQUESTED", null, null, designer, null,
                "Logo too small on the cover", null));
        assertEquals(designer, entry.designerId());
        assertEquals("Neha Designs " + order.orderId(), entry.contactLabel());
        assertBad("A designer can be named only on a Designer call", () -> calls.log(order.orderId(),
                new NewCall("SCHOOL", "CONNECTED", null, null, designer, null, null, null)));
        assertBad("Name either a vendor or a designer, not both", () -> calls.log(order.orderId(),
                new NewCall("DESIGNER", "CONNECTED", null, 1L, designer, null, null, null)));
        assertBad("Unknown designer", () -> calls.log(order.orderId(),
                new NewCall("DESIGNER", "CONNECTED", null, null, 987_654_321L, null, null, null)));
    }

    @Test void aChosenStageMustBeOneOfTheOrdersStages() {
        Order wholesale = db.order(10, "NOTEBOOK_ORDER_TRACKER", "NOTEBOOKS", "NB", "notebook", false, 5, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        assertEquals(1, calls.log(wholesale.orderId(), new NewCall("SCHOOL", "CONNECTED", null, null, null, 1, null, null)).stepOrder());
        assertBad("Step 3 is not a stage of this order", () -> calls.log(wholesale.orderId(),
                new NewCall("SCHOOL", "CONNECTED", null, null, null, 3, null, null)));
        assertBad("Choose a stage of this order", () -> calls.log(wholesale.orderId(), new NewCall("SCHOOL", "CONNECTED", null, null, null, 0, null, null)));
        assertBad("Choose a stage of this order", () -> calls.log(wholesale.orderId(), new NewCall("SCHOOL", "CONNECTED", null, null, null, 100, null, null)));
    }

    @Test void aCallOnAFinishedOrderHasNoStageUnlessOneIsChosen() {
        Order done = db.flier(10, 0, ASSIGNEE);
        superadmin();
        assertNull(calls.log(done.orderId(), call(Contacted.SCHOOL, Outcome.APPROVAL_RECEIVED, null)).stepOrder());
        assertEquals(9, calls.log(done.orderId(), new NewCall("SCHOOL", "CONNECTED", null, null, null, 9, null, null)).stepOrder());
    }

    @Test void entriesAreListedNewestFirstAndCannotBeChangedOrRemoved() {
        Order order = db.notebook(10, 4, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        CallLogEntry first = calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.NO_ANSWER, null));
        CallLogEntry second = calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.BUSY, null));
        CallLogEntry third = calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, "Got through"));

        assertEquals(List.of(third.id(), second.id(), first.id()), calls.list(order.orderId(), null).stream().map(CallLogEntry::id).toList());
        assertEquals(List.of(third.id()), calls.list(order.orderId(), 1).stream().map(CallLogEntry::id).toList());
        assertEquals("Asha Assignee", calls.list(order.orderId(), 1).get(0).createdByName());
        assertBad("limit must be between 1 and 200", () -> calls.list(order.orderId(), 0));
        assertBad("limit must be between 1 and 200", () -> calls.list(order.orderId(), 201));

        // The runtime role itself may only add and read entries.
        for (String change : List.of("UPDATE workflow.workflow_call_logs SET notes = 'rewritten' WHERE id = " + first.id(),
                "DELETE FROM workflow.workflow_call_logs WHERE id = " + first.id())) {
            DataAccessException denied = assertThrows(DataAccessException.class,
                    () -> db.tx.executeWithoutResult(status -> db.runtime.sql(change).update()), change);
            assertTrue(denied.getMostSpecificCause().getMessage().contains("permission denied"), denied.getMostSpecificCause().getMessage());
        }
        assertEquals(3, calls.list(order.orderId(), null).size());
    }

    // ---- open callbacks (Upcoming actions) --------------------------------------------------------------------------------

    @Test void anOpenCallbackIsTheLatestCallToAnEntityWhenItAskedForOne() {
        Order order = db.notebook(10, 5, ASSIGNEE);
        operator(ASSIGNEE, 10L);
        calls.log(order.orderId(), new NewCall("SCHOOL", "CALLBACK_REQUESTED", "Mrs Rao", null, null, null, "Call after assembly", inHours(30)));
        CallLogEntry school = calls.log(order.orderId(), new NewCall("SCHOOL", "CALLBACK_REQUESTED", "Mrs Rao", null, null, null,
                "Moved to tomorrow", inHours(20)));
        CallLogEntry press = calls.log(order.orderId(), new NewCall("PRINTING_PRESS", "CALLBACK_REQUESTED", "Plant desk", null, null, 5,
                "Confirm paper stock", inHours(3)));
        CallLogEntry designer = calls.log(order.orderId(), new NewCall("DESIGNER", "CALLBACK_REQUESTED", null, null, null, null,
                "Wants the logo file", inHours(10)));
        calls.log(order.orderId(), call(Contacted.DESIGNER, Outcome.NO_ANSWER, null));

        List<OpenCallback> open = openCallbacks(order.instanceId());
        assertEquals(List.of(press.id(), school.id()), open.stream().map(OpenCallback::callId).toList(),
                "earliest follow-up first; the school's older callback was superseded and any later call closes the designer's");
        assertEquals(new OpenCallback(press.id(), "PRINTING_PRESS", "Plant desk", 5, press.nextFollowUpAt()), open.get(0));
        assertEquals(inHours(20).toInstant(), open.get(1).nextFollowUpAt().toInstant());
        assertNotEquals(designer.id(), open.get(1).callId());
        assertEquals(open, calls.openCallbacks(order.instanceId()),
                "asked outside a transaction, it applies the operator's own scope rather than seeing nothing");

        calls.log(order.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, null));
        assertEquals(List.of(press.id()), openCallbacks(order.instanceId()).stream().map(OpenCallback::callId).toList(),
                "calling the school back closes its callback");
        assertTrue(openCallbacks(db.flier(10, 1, ASSIGNEE).instanceId()).isEmpty());
    }

    // ---- row-level security --------------------------------------------------------------------------------------------

    @Test void rowLevelSecurityKeepsAnotherSchoolsCallLogOutOfAnOperatorsReach() {
        Order theirs = db.notebook(20, 3, ASSIGNEE);
        superadmin();
        calls.log(theirs.orderId(), call(Contacted.SCHOOL, Outcome.CONNECTED, "Theirs"));
        CallLogRepository repo = new CallLogRepository(db.runtime, WorkflowExtrasDatabase.SCHEMA);

        operator(ASSIGNEE, 10L);
        List<?> seen = db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return repo.calls(theirs.instanceId(), 100);
        });
        assertTrue(seen.isEmpty(), "even read directly, school 20's calls are invisible to a school 10 operator");
        DataAccessException refused = assertThrows(DataAccessException.class, () -> db.tx.executeWithoutResult(status -> {
            db.scope().applyOperatorScope();
            repo.insert(new NewCallRow(theirs.instanceId(), theirs.schoolId(), 3, "SCHOOL", "CONNECTED", null, null, null, null, null, ASSIGNEE));
        }));
        assertTrue(refused.getMostSpecificCause().getMessage().contains("row-level security"), refused.getMostSpecificCause().getMessage());

        operator(ASSIGNEE, 20L);
        assertEquals(1, db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return repo.calls(theirs.instanceId(), 100);
        }).size(), "their own school's operator sees it");
        TenantContext.clear();
        assertTrue(db.tx.execute(status -> repo.calls(theirs.instanceId(), 100)).isEmpty(), "no scope fails closed");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** As the order workflow asks: inside its transaction, with the caller's scope applied and the order cleared. */
    static List<OpenCallback> openCallbacks(long instanceId) {
        return db.tx.execute(status -> {
            db.scope().applyOperatorScope();
            return calls.openCallbacks(instanceId);
        });
    }

    static int status(Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode().value();
    }

    static void assertBad(String message, Executable call) {
        ResponseStatusException refused = assertThrows(ResponseStatusException.class, call);
        assertEquals(400, refused.getStatusCode().value());
        assertEquals(message, refused.getReason());
    }
}
