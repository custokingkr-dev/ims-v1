package com.custoking.ims.operationsservice.infrastructure;

import java.util.List;

/** What the tracker needs from the catalogue (school-core): the order's design drafts, and to show its progress. */
public interface CatalogOrderGateway {

    record DraftImage(long id, String contentType, String originalFilename) { }

    /** The order's current design drafts. Throws {@link IllegalStateException} when school-core cannot be asked. */
    List<DraftImage> draftImages(String orderId);

    /** Shows the tracker's current step as the order's status. Throws {@link IllegalStateException} on failure. */
    void pushStatus(String orderId, String status);
}
