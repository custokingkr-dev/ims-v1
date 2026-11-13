package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.AccessibleOrder;
import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderAccess;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.NoteRules;
import com.custoking.ims.operationsservice.persistence.CallLogRepository;
import com.custoking.ims.operationsservice.persistence.CallLogRepository.CallRow;
import com.custoking.ims.operationsservice.persistence.CallLogRepository.NewCallRow;
import com.custoking.ims.operationsservice.persistence.VendorRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowRequestScope;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An order's call log (design D35, §4.6): who was contacted, about which stage, the outcome, and notes of at most 100
 * words. "Calls, changes and approvals must be logged standardly" [SOP §2].
 * <ul>
 *   <li>Anyone who can work the order may log a call (an in-scope operator holding the act permission, or superadmin),
 *       not only the stage's assignee.</li>
 *   <li>Notes are required for Delay Reported, Changes Requested and Callback Requested; a callback also needs the
 *       follow-up time.</li>
 *   <li>Entries are append-only: nothing changes or removes one.</li>
 * </ul>
 * Also the order workflow's {@link CallbackSource}: the callbacks still owed feed its Upcoming actions (D39).
 */
@Service
public class CallLogService implements CallbackSource {
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 200;
    static final int CONTACT_LABEL_MAX = 200;
    /** A callback may be logged a little after the time agreed (the call happened, the log came later)... */
    static final Duration FOLLOW_UP_GRACE = Duration.ofHours(1);
    /** ...and at most a year ahead. */
    static final Duration FOLLOW_UP_HORIZON = Duration.ofDays(366);

    /** The SOP's call entities [SOP §2]. */
    public enum Contacted {
        SCHOOL("School"), DESIGNER("Designer"), PRINTING_PRESS("Printing Press"), MANUFACTURING_FACTORY("Manufacturing Factory"),
        INTERNAL_APPROVER("Internal Approver");

        private final String label;

        Contacted(String label) { this.label = label; }

        public String label() { return label; }

        /** Calls about a vendor the order selected: its printers, or its binders and makers. */
        boolean namesVendors() { return this == PRINTING_PRESS || this == MANUFACTURING_FACTORY; }
    }

    /** The SOP's call outcomes [SOP §2]. Notes are required for three of them [Ours]; a callback needs its time. */
    public enum Outcome {
        CONNECTED(false), NO_ANSWER(false), BUSY(false), CALLBACK_REQUESTED(true), DELAY_REPORTED(true),
        CHANGES_REQUESTED(true), APPROVAL_RECEIVED(false);

        private final boolean notesRequired;

        Outcome(boolean notesRequired) { this.notesRequired = notesRequired; }

        public boolean notesRequired() { return notesRequired; }

        public boolean followUpRequired() { return this == CALLBACK_REQUESTED; }
    }

    /**
     * One call log entry.
     *
     * @param stepOrder      the stage the call was about, or null when the order had none
     * @param contactLabel   who was called (a contact, vendor or designer name, or typed), or null
     * @param createdByName  the author's name when identity knows it, otherwise null
     */
    public record CallLogEntry(long id, Integer stepOrder, Contacted contacted, Outcome outcome, String contactLabel, Long vendorId,
                               Long designerId, String notes, OffsetDateTime nextFollowUpAt, long createdBy, String createdByName,
                               OffsetDateTime createdAt) {
        static CallLogEntry of(CallRow row, String createdByName) {
            return new CallLogEntry(row.id(), row.stepOrder(), Contacted.valueOf(row.contacted()), Outcome.valueOf(row.outcome()),
                    row.contactLabel(), row.vendorId(), row.designerId(), row.notes(), row.nextFollowUpAt(), row.createdBy(),
                    createdByName, row.createdAt());
        }
    }

    /** A call to log, as the screen sends it; {@code stepOrder} null means the order's current stage. */
    public record NewCall(String contacted, String outcome, String contactLabel, Long vendorId, Long designerId, Integer stepOrder,
                          String notes, OffsetDateTime nextFollowUpAt) { }

    private final CallLogRepository repo;
    private final VendorRepository vendorRepository;
    private final VendorLookup vendors;
    private final OrderAccess access;
    private final WorkflowRequestScope scope;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final StaffNames names;

    /**
     * {@code access} is lazy, so the bean that implements {@link OrderAccess} may itself depend on this service (the order
     * workflow asks it for open callbacks) without a dependency cycle.
     */
    public CallLogService(CallLogRepository repo, VendorRepository vendorRepository, @Lazy OrderAccess access,
                          WorkflowRequestScope scope, TransactionTemplate tx, Clock clock, StaffNames names) {
        this.repo = repo;
        this.vendorRepository = vendorRepository;
        this.vendors = vendorRepository;
        this.access = access;
        this.scope = scope;
        this.tx = tx;
        this.clock = clock;
        this.names = names;
    }

    /** The order's calls, newest first; anyone who may see the order. Authors' names are added after the read commits. */
    public List<CallLogEntry> list(String orderId, Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : limit;
        if (max < 1 || max > MAX_LIMIT) throw bad("limit must be between 1 and " + MAX_LIMIT);
        List<CallRow> rows = tx.execute(status -> {
            scope.applyOperatorScope();
            AccessibleOrder order = access.require(orderId, false);
            return repo.calls(order.instanceId(), max);
        });
        Map<Long, String> authors = names.lookup(rows.stream().map(CallRow::createdBy).collect(Collectors.toSet()));
        return rows.stream().map(row -> CallLogEntry.of(row, authors.get(row.createdBy()))).toList();
    }

    /**
     * Appends a call. Anyone who can work the order may log one.
     *
     * @throws com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation
     *         {@code NOTE_REQUIRED} (400) without notes where the outcome needs them, {@code NOTE_TOO_LONG} (400) over 100
     *         words or 1,000 characters
     */
    public CallLogEntry log(String orderId, NewCall call) {
        if (call == null) throw bad("Send who was contacted and the outcome");
        Contacted contacted = parse(Contacted.class, call.contacted(), "Choose who was contacted: "
                + Arrays.stream(Contacted.values()).map(Contacted::label).collect(Collectors.joining(", ")));
        Outcome outcome = parse(Outcome.class, call.outcome(), "Choose the call's outcome");
        String notes = outcome.notesRequired()
                ? NoteRules.required(call.notes(), NoteRules.CALL_NOTE_MAX_WORDS, NoteRules.CALL_NOTE_MAX_CHARS, "Call notes")
                : NoteRules.optional(call.notes(), NoteRules.CALL_NOTE_MAX_WORDS, NoteRules.CALL_NOTE_MAX_CHARS, "Call notes");
        OffsetDateTime followUp = followUp(outcome, call.nextFollowUpAt());
        String typedLabel = label(call.contactLabel());
        if (call.vendorId() != null && call.designerId() != null) throw bad("Name either a vendor or a designer, not both");
        if (call.vendorId() != null && !contacted.namesVendors()) {
            throw bad("A vendor can be named only on a Printing Press or Manufacturing Factory call");
        }
        if (call.designerId() != null && contacted != Contacted.DESIGNER) throw bad("A designer can be named only on a Designer call");
        Integer requestedStep = call.stepOrder();
        if (requestedStep != null && (requestedStep < 1 || requestedStep > 99)) throw bad("Choose a stage of this order");

        CallRow saved = tx.execute(status -> {
            scope.applyOperatorScope();
            AccessibleOrder order = access.require(orderId, true);
            Integer step = requestedStep != null ? requestedStep : order.activeStep();
            if (requestedStep != null && !repo.hasStep(order.instanceId(), requestedStep)) {
                throw bad("Step " + requestedStep + " is not a stage of this order");
            }
            String contactLabel = typedLabel;
            if (call.vendorId() != null) {
                if (!vendorRepository.selectedVendorIds(order.instanceId()).contains(call.vendorId())) {
                    throw bad("Pick a vendor selected for this order");
                }
                if (contactLabel == null) {
                    contactLabel = vendors.vendors(List.of(call.vendorId())).stream().findFirst()
                            .map(VendorLookup.VendorSummary::name).map(CallLogService::label).orElse(null);
                }
            }
            if (call.designerId() != null) {
                String designer = repo.designerName(call.designerId()).orElseThrow(() -> bad("Unknown designer"));
                if (contactLabel == null) contactLabel = label(designer);
            }
            long id = repo.insert(new NewCallRow(order.instanceId(), order.schoolId(), step, contacted.name(), outcome.name(),
                    contactLabel, call.vendorId(), call.designerId(), notes, followUp, order.userId()));
            return repo.call(id).orElseThrow();
        });
        return CallLogEntry.of(saved, names.lookup(List.of(saved.createdBy())).get(saved.createdBy()));
    }

    // ---- CallbackSource ----------------------------------------------------------------------------------------------

    /**
     * Per contacted entity, the latest Callback Requested with no later call to that entity, earliest follow-up first. The
     * caller has cleared the order: inside its transaction this reads with that transaction's scope; outside one it opens
     * its own and applies the caller's operator scope first, so row-level security never silently hides the callbacks.
     */
    @Override
    public List<OpenCallback> openCallbacks(long instanceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return openCallbacksInScope(instanceId);
        return tx.execute(status -> {
            scope.applyOperatorScope();
            return openCallbacksInScope(instanceId);
        });
    }

    private List<OpenCallback> openCallbacksInScope(long instanceId) {
        return repo.openCallbacks(instanceId).stream()
                .map(row -> new OpenCallback(row.id(), row.contacted(), row.contactLabel(), row.stepOrder(), row.nextFollowUpAt()))
                .toList();
    }

    // ---- validation --------------------------------------------------------------------------------------------------

    private OffsetDateTime followUp(Outcome outcome, OffsetDateTime at) {
        if (at == null) {
            if (outcome.followUpRequired()) throw bad("Choose when to call back");
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (at.isBefore(now.minus(FOLLOW_UP_GRACE))) throw bad("The next follow-up cannot be in the past");
        if (at.isAfter(now.plus(FOLLOW_UP_HORIZON))) throw bad("The next follow-up must be within a year");
        return at;
    }

    /** Who was called, stripped; null when blank. */
    private static String label(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > CONTACT_LABEL_MAX) {
            // A typed name is refused; a vendor or designer name (up to 200 characters already) never reaches this.
            throw bad("Who was called is limited to " + CONTACT_LABEL_MAX + " characters");
        }
        return clean;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        if (value == null || value.isBlank()) throw bad(message);
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException unknown) {
            throw bad(message);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
