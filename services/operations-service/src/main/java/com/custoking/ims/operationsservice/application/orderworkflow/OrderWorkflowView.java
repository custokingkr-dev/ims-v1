package com.custoking.ims.operationsservice.application.orderworkflow;

import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.GateFailure;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions.UpcomingAction;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.DesignerRow;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the order workflow screen needs for one order (design §3.5 {@code GET /item-orders/{orderId}}, §4.3-4.4),
 * filtered to what the caller may see and do. The component order of every record here is the contract's property order
 * ({@code contracts/openapi/orderWorkflowClient.v1.openapi.json}, {@code x-ims-java-record}); the generator refuses a
 * drift. Staff names are filled in after the read has committed ({@code StaffNames}), so they are null until then and
 * stay null when identity cannot be asked.
 */
public record OrderWorkflowView(String orderId, String orderNumber, String definitionId, String categoryCode, String itemCode,
                                String itemNoun, long schoolId, String schoolName, boolean customised, int orderedCount,
                                OffsetDateTime placedAt, String timeZone, String instanceStatus, String status,
                                WorkflowProgressView progress, Long assigneeUserId, String assigneeName, DesignDecision design,
                                Map<String, List<Long>> vendorSelections, ResolvedGroups groups, DesignerRow designer,
                                OrderContacts contacts, List<NoteEntry> internalNotes, List<UpcomingAction> upcomingActions,
                                OrderCapabilities can, OffsetDateTime now, long version, List<StepView> steps) {

    public OrderWorkflowView {
        vendorSelections = vendorSelections == null ? Map.of() : vendorSelections;
        internalNotes = internalNotes == null ? List.of() : List.copyOf(internalNotes);
        upcomingActions = upcomingActions == null ? List.of() : List.copyOf(upcomingActions);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    // ---- list and board -------------------------------------------------------------------------------------------------

    /** One row of the order list and one card of the board (design §4.2). */
    public record OrderWorkflowListItem(String orderId, String orderNumber, String definitionId, String categoryCode, String itemCode,
                                        long schoolId, String schoolName, boolean customised, int orderedCount, OffsetDateTime placedAt,
                                        String timeZone, String status, String phase, Integer currentStep, String currentTitle,
                                        Long assigneeUserId, String assigneeName, OffsetDateTime dueAt, boolean overdue, String slaState,
                                        int completed, int total) {

        public OrderWorkflowListItem withAssigneeName(String name) {
            return new OrderWorkflowListItem(orderId, orderNumber, definitionId, categoryCode, itemCode, schoolId, schoolName, customised,
                    orderedCount, placedAt, timeZone, status, phase, currentStep, currentTitle, assigneeUserId, name, dueAt, overdue,
                    slaState, completed, total);
        }
    }

    /** The summary chips: every unfinished order in the caller's scope that matches the item and school filters. */
    public record OrderWorkflowCounts(long open, long overdue, long dueToday, long unassigned, long mine) { }

    /** A page of the list: the rows, how many match every filter, the chips and the server time. */
    public record OrderWorkflowPage(List<OrderWorkflowListItem> items, long total, OrderWorkflowCounts counts, OffsetDateTime now) {
        public OrderWorkflowPage {
            items = items == null ? List.of() : List.copyOf(items);
        }

        /** Every assignee on the page. */
        public Set<Long> staffIds() {
            Set<Long> ids = new LinkedHashSet<>();
            for (OrderWorkflowListItem item : items) if (item.assigneeUserId() != null) ids.add(item.assigneeUserId());
            return ids;
        }

        public OrderWorkflowPage withStaffNames(Map<Long, String> names) {
            if (names == null || names.isEmpty()) return this;
            return new OrderWorkflowPage(items.stream()
                    .map(item -> item.assigneeUserId() == null ? item : item.withAssigneeName(names.get(item.assigneeUserId()))).toList(),
                    total, counts, now);
        }
    }

    // ---- parts of the order view ----------------------------------------------------------------------------------------

    /** A step's time slot as this school's orders use it: the school override ({@code SCHOOL}) or the default. */
    public record EffectiveSla(long value, String unit, String source) { }

    /** What the caller may do on one step now (design D18, D20, D38). */
    public record StepCapabilities(boolean canEdit, boolean canComplete, boolean canReopen, boolean canSignOff, boolean canAddNote) { }

    /** A recorded sign-off: who and when. */
    public record SignOffRecord(long by, String byName, OffsetDateTime at) {
        SignOffRecord withName(Map<Long, String> names) { return new SignOffRecord(by, names.get(by), at); }
    }

    /** A stage note ({@code stepOrder} set) or an order-level internal note. */
    public record NoteEntry(long id, Integer stepOrder, long authorUserId, String authorName, String body, OffsetDateTime createdAt) {
        public NoteEntry withName(Map<Long, String> names) {
            return new NoteEntry(id, stepOrder, authorUserId, names.get(authorUserId), body, createdAt);
        }
    }

    /** One step of the order's path, as the stage panel, stepper and stage list show it. */
    public record StepView(int step, String code, String title, String phase, boolean customisedOnly, String status,
                           Long assigneeUserId, String assigneeName, OffsetDateTime startedAt, OffsetDateTime dueAt, boolean overdue,
                           EffectiveSla sla, OffsetDateTime completedAt, Long completedBy, String completedByName,
                           Map<String, Object> data, OffsetDateTime dataSavedAt, Long dataSavedBy, String dataSavedByName,
                           SignOffRecord signOff, List<NoteEntry> notes, List<GateFailure> unmet, long version, StepCapabilities can) {

        public StepView {
            data = data == null ? Map.of() : data;
            notes = notes == null ? List.of() : List.copyOf(notes);
            unmet = unmet == null ? List.of() : List.copyOf(unmet);
        }

        public StepView withUnmet(List<GateFailure> failures) {
            return new StepView(step, code, title, phase, customisedOnly, status, assigneeUserId, assigneeName, startedAt, dueAt, overdue,
                    sla, completedAt, completedBy, completedByName, data, dataSavedAt, dataSavedBy, dataSavedByName, signOff, notes,
                    failures, version, can);
        }

        StepView withNames(Map<Long, String> names) {
            return new StepView(step, code, title, phase, customisedOnly, status, assigneeUserId, name(names, assigneeUserId), startedAt,
                    dueAt, overdue, sla, completedAt, completedBy, name(names, completedBy), data, dataSavedAt, dataSavedBy,
                    name(names, dataSavedBy), signOff == null ? null : signOff.withName(names),
                    notes.stream().map(note -> note.withName(names)).toList(), unmet, version, can);
        }
    }

    /** Where the order stands: steps done of the path, and the active step. */
    public record WorkflowProgressView(int completed, int total, Integer currentStep, String currentCode, String currentTitle,
                                       boolean finished) { }

    /** What the caller may do on the order as a whole (design D42). */
    public record OrderCapabilities(boolean canAssign, boolean canClaim, boolean canSelectVendors, boolean canSetDesigner,
                                    boolean canSetContacts, boolean canSetSchoolGroup, boolean canLogCall, boolean canAddNote,
                                    boolean canManageDesignFiles, boolean canApproveDesign) { }

    /** The Head Approval decision (design D21): the state, its comment and who decided, and the CDR link approved. */
    public record DesignDecision(String state, String comment, OffsetDateTime decidedAt, Long decidedBy, String decidedByName,
                                 String approvedCdrLink) {
        DesignDecision withName(Map<Long, String> names) {
            return new DesignDecision(state, comment, decidedAt, decidedBy, name(names, decidedBy), approvedCdrLink);
        }
    }

    /** The order's contacts (design D36); the factory contact is the tracker's {@code vendor_name}/{@code vendor_phone}. */
    public record OrderContacts(String schoolContactName, String schoolContactPhone, String factoryContactName,
                                String factoryContactPhone) { }

    // ---- staff names ---------------------------------------------------------------------------------------------------

    /** Every staff id the view shows a name for: assignees, completers, savers, signers, note authors and the decider. */
    public Set<Long> staffIds() {
        Set<Long> ids = new LinkedHashSet<>();
        if (assigneeUserId != null) ids.add(assigneeUserId);
        if (design != null && design.decidedBy() != null) ids.add(design.decidedBy());
        for (NoteEntry note : internalNotes) ids.add(note.authorUserId());
        for (StepView step : steps) {
            addIfPresent(ids, step.assigneeUserId(), step.completedBy(), step.dataSavedBy());
            if (step.signOff() != null) ids.add(step.signOff().by());
            for (NoteEntry note : step.notes()) ids.add(note.authorUserId());
        }
        return ids;
    }

    /** The view with every name that is known; the rest stay null. */
    public OrderWorkflowView withStaffNames(Map<Long, String> names) {
        if (names == null || names.isEmpty()) return this;
        return new OrderWorkflowView(orderId, orderNumber, definitionId, categoryCode, itemCode, itemNoun, schoolId, schoolName,
                customised, orderedCount, placedAt, timeZone, instanceStatus, status, progress, assigneeUserId,
                name(names, assigneeUserId), design == null ? null : design.withName(names), vendorSelections, groups, designer,
                contacts, internalNotes.stream().map(note -> note.withName(names)).toList(), upcomingActions, can, now, version,
                steps.stream().map(step -> step.withNames(names)).toList());
    }

    /** The view with the active step's unmet requirements and the live list replaced (once the design files are counted). */
    public OrderWorkflowView withActiveStep(int stepOrder, List<GateFailure> unmet, List<UpcomingAction> actions) {
        List<StepView> updated = new ArrayList<>(steps.size());
        for (StepView step : steps) updated.add(step.step() == stepOrder ? step.withUnmet(unmet) : step);
        return new OrderWorkflowView(orderId, orderNumber, definitionId, categoryCode, itemCode, itemNoun, schoolId, schoolName,
                customised, orderedCount, placedAt, timeZone, instanceStatus, status, progress, assigneeUserId, assigneeName, design,
                vendorSelections, groups, designer, contacts, internalNotes, actions, can, now, version, updated);
    }

    private static void addIfPresent(Collection<Long> ids, Long... values) {
        for (Long value : values) if (value != null) ids.add(value);
    }

    private static String name(Map<Long, String> names, Long id) {
        return id == null ? null : names.get(id);
    }
}
