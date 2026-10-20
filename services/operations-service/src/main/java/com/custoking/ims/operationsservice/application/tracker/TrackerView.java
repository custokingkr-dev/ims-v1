package com.custoking.ims.operationsservice.application.tracker;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Everything the tracker screen needs for one order, already filtered to what the caller may see and do. */
public record TrackerView(
        String orderId,
        long schoolId,
        boolean customised,
        int orderedCount,
        OffsetDateTime placedAt,
        String timeZone,
        String schoolName,
        String instanceStatus,
        Progress progress,
        Contact vendor,
        Designer designer,
        Design design,
        List<Step> steps,
        List<Note> internalNotes,
        List<FollowUp> followUps,
        long version) {

    public record Progress(int completed, int total, Integer currentStep, String currentTitle, boolean finished) { }

    /**
     * One order in the worklist. {@code status} is the order's status code: the current step, or COMPLETED. {@code timeZone}
     * is the school's IANA zone stored with the tracker, in which deadlines are shown.
     */
    public record ListItem(String orderId, long schoolId, String schoolName, boolean customised, int orderedCount,
                           OffsetDateTime placedAt, String timeZone, String status, Integer currentStep, String currentTitle,
                           Long assigneeUserId, OffsetDateTime dueAt, boolean overdue, int completed, int total) { }

    public record Contact(String name, String phone) { }

    public record Designer(long id, String name, String phone) { }

    /** Design-approval state (spec: send, approve, request changes). */
    public record Design(String state, String comment, OffsetDateTime at, Long by, Long approvedDraftRef,
                         boolean canManageImages) { }

    public record Sla(long value, String unit, String source) { }

    public record Capabilities(boolean canEditDetails, boolean canEditStageNote, boolean canComplete, boolean canReopen) { }

    public record Step(int step, String title, boolean customisedOnly, String status, Long assigneeUserId,
                       OffsetDateTime startedAt, OffsetDateTime dueAt, boolean overdue, Sla sla,
                       OffsetDateTime completedAt, Long completedBy, Map<String, Object> data, String stageNote,
                       long version, Capabilities can) { }

    public record Note(long id, long authorUserId, String body, OffsetDateTime createdAt) { }

    public record FollowUp(long id, int percentComplete, LocalDate nextFollowUpOn, String contactPhone,
                           long createdBy, OffsetDateTime createdAt) { }
}
