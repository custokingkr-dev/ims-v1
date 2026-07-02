package com.custoking.ims.firefightingservice.api.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * DTO for PATCH /ff/requests/{code}.
 * All-nullable partial-update record — only non-null fields are forwarded to the repo map so
 * the repository's containsKey-based partial-update semantics are preserved exactly.
 *
 * containsKey fields in updateRequest: title, category, urgency, requiredByDate,
 * estimatedBudget, description.
 * Non-containsKey: actorEmail (used only for updatedBy — always read via map.get).
 *
 * Deferred: explicit-null-to-clear for description is not expressible via a DTO.
 * Sending {"description": null} is treated as absent (repo keeps current value).
 * Requires JsonNullable / Optional<> wrapper if three-state semantics are ever needed.
 */
public record UpdateFirefightingRequestRequest(
        @Size(max = 500, message = "title must be 500 characters or fewer")
        String title,
        String category,
        String urgency,
        String requiredByDate,
        @PositiveOrZero(message = "estimatedBudget must be zero or positive")
        Long estimatedBudget,
        @Size(max = 2000, message = "description must be 2000 characters or fewer")
        String description,
        String actorEmail
) {}
