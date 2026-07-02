package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /ff/requests/{code}/quotations.
 * Maps to addQuotation(Map) repo keys:
 *   vendorName (required), amount, deliveryTimeline, notes, documentUrl.
 * No containsKey-gated fields — all optional fields are null-gated in controller mapping.
 * schoolId is NOT a caller field here: it is inherited from the parent request row inside
 * the repo (addQuotation reads current.get("schoolId")).
 */
public record CreateQuotationRequest(
        @NotBlank(message = "Vendor name is required") String vendorName,
        Long amount,
        String deliveryTimeline,
        String notes,
        String documentUrl
) {}
