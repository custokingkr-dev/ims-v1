package com.custoking.ims.platformservice.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * DTO for POST /notifications/broadcasts/{id}/approve and POST /notifications/broadcasts/{id}/send.
 * Shared across both action endpoints.
 *
 * The body is optional (Spring @RequestBody(required=false)) — when absent the controller receives
 * null and passes null actorId to the repo. actorId is optional within the body as well; a null
 * actorId means the action was taken without a recorded actor.
 *
 * FORMAT-ONLY @Positive constraint is skipped automatically when actorId is null.
 * No @NotNull/@NotBlank is used here.
 *
 * actorId is forwarded directly as the Long parameter to:
 *   NotificationBroadcastCommandRepository.approve(id, actorId)
 *   NotificationBroadcastCommandRepository.send(id, actorId)
 */
public record BroadcastActionRequest(
        @Positive Long actorId
) {}
