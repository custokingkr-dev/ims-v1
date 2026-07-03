package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * DTO for POST /submit-day. The body is OPTIONAL (required=false) — every field is
 * nullable and there are NO presence constraints (@NotNull/@NotBlank). Only FORMAT
 * constraints apply.
 * Maps to submitAttendanceDay(dateText, schoolId, actorId):
 *   date (optional — defaults to "today"),
 *   actorId (optional — sets updated_by; @Positive when supplied),
 *   schoolId (optional — resolved via TenantScope for access scoping; @Positive when supplied).
 */
public record SubmitDayRequest(
        String date,
        @Positive(message = "actorId must be positive") Long actorId,
        @Positive(message = "schoolId must be positive") Long schoolId
) {}
