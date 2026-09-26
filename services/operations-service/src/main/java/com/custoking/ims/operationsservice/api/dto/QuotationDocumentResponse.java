package com.custoking.ims.operationsservice.api.dto;

import java.time.OffsetDateTime;

/** Only authorized review metadata crosses the API; bucket names, object keys and credentials stay server-side. */
public record QuotationDocumentResponse(String id, String filename, String contentType, long sizeBytes, OffsetDateTime uploadedAt) {}
