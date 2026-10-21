package com.custoking.ims.operationsservice.infrastructure;

import java.util.List;

/** What the tracker needs from the catalogue (school-core): the order's design drafts, and to show its progress. */
public interface CatalogOrderGateway {

    record DraftImage(long id, String contentType, String originalFilename) { }

    /** The order's current design drafts. Throws {@link IllegalStateException} when school-core cannot be asked. */
    List<DraftImage> draftImages(String orderId);

    /**
     * Shows the tracker's current step as the order's status. {@code trackerVersion} orders the pushes: the catalogue
     * ignores one no newer than the last it applied. Throws {@link OrderClosedException} when the order can show no
     * tracker status, and {@link IllegalStateException} on any other failure.
     */
    void pushStatus(String orderId, String status, long trackerVersion);

    /**
     * The catalogue will never show a tracker status for this order: school-core answers 409 for a cancelled (or draft)
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
