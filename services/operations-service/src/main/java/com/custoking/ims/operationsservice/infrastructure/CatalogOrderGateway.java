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
     * The catalogue refuses every tracker status for this order because it is closed: school-core answers 409 for a
     * cancelled (or draft) order. Retrying cannot change that, so the status is dropped rather than retried.
     */
    class OrderClosedException extends IllegalStateException {
        public OrderClosedException(String message, Throwable cause) { super(message, cause); }
    }
}
