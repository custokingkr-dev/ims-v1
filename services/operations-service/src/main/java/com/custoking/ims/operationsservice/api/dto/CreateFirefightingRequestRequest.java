package com.custoking.ims.operationsservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /ff/requests.
 * Maps to createRequest(Map) repo keys:
 *   title (required), category, urgency, requiredByDate, estimatedBudget,
 *   schoolId (resolved via TenantScope; optional here for superadmin cross-school),
 *   description / summary (alias pair via firstPresent — both optional),
 *   referenceFileUrl, actorId, actorEmail.
 * No containsKey-gated fields in createRequest — all optional fields are null-gated in
 * controller mapping.
 */
public record CreateFirefightingRequestRequest(
        // @NotBlank: intentional tightening — repo previously defaulted to placeholder "Request"; a real value is required.
        @NotBlank(message = "Title is required") String title,
        String category,
        String urgency,
        String requiredByDate,
        Long estimatedBudget,
        Long schoolId,
        String description,
        String summary,
        String referenceFileUrl,
        Long actorId,
        String actorEmail
) {}
