package com.custoking.ims.schoolcoreservice.infrastructure;

import java.time.OffsetDateTime;
import java.util.List;

/** What school-core needs from the order workflow engine, which lives in operations-service. */
public interface TrackerGateway {

    String NOTEBOOK_DEFINITION = "NOTEBOOK_ORDER_TRACKER";

    /**
     * A placed order's workflow. {@code definitionId} is the template its category ran at placement; {@code customised} is
     * whether the design steps apply (a wholesale notebook skips them; every other item takes them). {@code orderNumber}
     * may be null (a category without an order code); a create for a workflow that already exists fills a missing number.
     * {@code importedProgress} carries a retired PR #380 product workflow's stages, in stage order, empty otherwise.
     */
    record NewTracker(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                      String timeZone, Long placedBy, Long assigneeUserId, String schoolName, String definitionId,
                      String categoryCode, String itemCode, String itemNoun, String orderNumber,
                      List<ImportedStep> importedProgress) {
        public NewTracker {
            importedProgress = importedProgress == null ? List.of() : List.copyOf(importedProgress);
        }

        /** A notebook order's workflow, as before order numbers and other items (kept for callers that build only that). */
        public NewTracker(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                          String timeZone, Long placedBy, Long assigneeUserId, String schoolName) {
            this(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assigneeUserId, schoolName,
                    NOTEBOOK_DEFINITION, "NOTEBOOKS", "NB", "notebook", null, List.of());
        }

        public NewTracker(String orderId, long schoolId, boolean customised, int orderedCount, OffsetDateTime placedAt,
                          String timeZone, Long placedBy, Long assigneeUserId) {
            this(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assigneeUserId, null);
        }

        /** The same workflow with this operator as its assignee. */
        public NewTracker assignedTo(Long assignee) {
            return new NewTracker(orderId, schoolId, customised, orderedCount, placedAt, timeZone, placedBy, assignee, schoolName,
                    definitionId, categoryCode, itemCode, itemNoun, orderNumber, importedProgress);
        }
    }

    /**
     * One stage of a retired product workflow (catalog V36) as the engine imports it (design D4). {@code stepCode} is the
     * engine's code; {@code sourceStageCode} the V36 one, which differs only for DESIGN_ACTIVITY (DESIGNING_ACTIVITY) and
     * FINAL_VERIFICATION (FINAL_APPROVAL). {@code status} is COMPLETED, or ACTIVE for the stage in progress, whose
     * {@code details} ("Production update") and {@code note} are still worth keeping.
     */
    record ImportedStep(String stepCode, String sourceStageCode, int stepOrder, String status, OffsetDateTime completedAt,
                        Long completedBy, String details, String note) { }

    /** The engine's answer to "may this user upload or delete design files for this order right now". */
    record ImageAccess(boolean allowed, String reason) { }

    /** Creates the order's workflow; idempotent. Throws {@link IllegalStateException} when it cannot be reached. */
    void create(NewTracker tracker);

    /** Throws {@link IllegalStateException} when the engine cannot be reached, so callers refuse instead of guessing. */
    ImageAccess imageAccess(String orderId, long userId, String role);
}
