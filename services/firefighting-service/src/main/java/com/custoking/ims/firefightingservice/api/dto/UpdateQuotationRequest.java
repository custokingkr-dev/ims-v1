package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * DTO for PATCH /ff/requests/{code}/quotations/{quotationId}.
 * All-nullable partial-update record — only non-null fields are forwarded to the repo map so
 * the repository's containsKey-based partial-update semantics are preserved exactly.
 *
 * containsKey fields in updateQuotation: vendorName, amount, deliveryTimeline, notes, documentUrl.
 *
 * Deferred: explicit-null-to-clear for notes/documentUrl is not expressible via a DTO.
 * Sending {"notes": null} is treated as absent (repo keeps current value). Requires
 * JsonNullable / Optional<> wrapper if three-state semantics are ever needed.
 */
public record UpdateQuotationRequest(
        String vendorName,
        @PositiveOrZero(message = "amount must be zero or positive")
        Long amount,
        String deliveryTimeline,
        @Size(max = 2000, message = "notes must be 2000 characters or fewer")
        String notes,
        String documentUrl
) {}
