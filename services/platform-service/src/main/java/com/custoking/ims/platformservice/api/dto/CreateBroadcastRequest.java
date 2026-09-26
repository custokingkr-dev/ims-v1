package com.custoking.ims.platformservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Creates a draft only. Dispatch requires a positive school scope, SCHOOL_NOTICE category,
 * ALL_PARENTS audience and explicitly supported channels, validated by the review workflow.
 * Legacy optional fields remain accepted without implying that the draft can dispatch.
 */
public record CreateBroadcastRequest(
        @NotBlank(message = "title is required") String title,
        @NotBlank(message = "message is required") String message,
        Long schoolId,
        String module,
        String audienceType,
        Object channels,
        String scheduledAt,
        Long createdBy,
        String communicationCategory
) {}
