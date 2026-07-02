package com.custoking.ims.notificationservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /notifications/logs.
 * Maps to NotificationLogCommandRepository.createRequestLog(Map) keys:
 *   required: channel, notificationType
 *   optional: id, schoolId, studentId, parentContact, message, status (defaults to "QUEUED"), sentBy
 */
public record CreateLogRequest(
        @NotBlank(message = "channel is required") String channel,
        @NotBlank(message = "notificationType is required") String notificationType,
        String id,
        Long schoolId,
        Long studentId,
        String parentContact,
        String message,
        String status,
        Long sentBy
) {}
