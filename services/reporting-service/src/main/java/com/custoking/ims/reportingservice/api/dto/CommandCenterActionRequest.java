package com.custoking.ims.reportingservice.api.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * DTO for POST /command-center/actions/{id}/accept and /dismiss.
 * All fields are optional -- body itself may be absent (required=false).
 * Only format constraints are applied; no @NotNull to preserve optional semantics.
 */
public record CommandCenterActionRequest(
        @Positive Long actorId,
        @Positive Long schoolId,
        @Size(max = 500) String reason
) {}