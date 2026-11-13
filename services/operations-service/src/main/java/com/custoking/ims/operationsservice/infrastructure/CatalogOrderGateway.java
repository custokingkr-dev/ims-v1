package com.custoking.ims.operationsservice.infrastructure;

import java.util.List;

/**
 * What the order workflow engine needs from the catalogue (school-core): the order's current design files, and to show
 * its progress as the order's status (design §3.5, §3.6).
 */
public interface CatalogOrderGateway {

    record DraftImage(long id, String contentType, String originalFilename) { }

    /**
     * The step an order's status names, for school-core's order lists ({@code attributes.workflowStep}, design D5): the
     * template, the step's number and title on the order's path, the path's length and how many steps are done. For a
     * finished order it is the last step, with every step done.
     */
    record WorkflowStep(String definitionId, int stepOrder, String stepTitle, int totalSteps, int completedSteps) { }

    /**
     * The order's current design files (DESIGN_DRAFT assets). Throws {@link IllegalStateException} when school-core cannot
     * be asked.
     */
    List<DraftImage> draftImages(String orderId);

    /**
     * Shows the workflow's current step as the order's status. {@code trackerVersion} orders the pushes: the catalogue
     * ignores one no newer than the last it applied. {@code step} describes that step and may be null when it is unknown.
     * Throws {@link OrderClosedException} when the order can show no workflow status, and {@link IllegalStateException}
     * on any other failure.
     */
    void pushStatus(String orderId, String status, long trackerVersion, WorkflowStep step);

    /**
     * The catalogue will never show a workflow status for this order: school-core answers 409 for a cancelled (or draft)
     * order and 404 for one that no longer exists (a data cleanup deleted it, say). Retrying cannot change either, so the
     * status is dropped rather than retried into a dead letter. {@link #httpStatus()} tells the two apart in the log.
     */
    class OrderClosedException extends IllegalStateException {
        private final int httpStatus;
        public OrderClosedException(String message, Throwable cause) { this(message, 409, cause); }
        public OrderClosedException(String message, int httpStatus, Throwable cause) { super(message, cause); this.httpStatus = httpStatus; }
        public int httpStatus() { return httpStatus; }
    }
}
