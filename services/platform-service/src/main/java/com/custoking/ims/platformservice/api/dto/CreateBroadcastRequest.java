package com.custoking.ims.platformservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /notifications/broadcasts.
 * Maps to NotificationBroadcastCommandRepository.create(Map) keys:
 *   required: title, message
 *   optional: schoolId, module, audienceType (defaults to "ALL"),
 *             channels (defaults to "WHATSAPP" where the school has an approved WhatsApp
 *             template, otherwise "SMS" — WhatsApp utility is the cheaper channel but only
 *             sends once the school has completed WhatsApp onboarding),
 *             scheduledAt, createdBy
 */
public record CreateBroadcastRequest(
        @NotBlank(message = "title is required") String title,
        @NotBlank(message = "message is required") String message,
        Long schoolId,
        String module,
        String audienceType,
        Object channels,
        String scheduledAt,
        Long createdBy
) {}
