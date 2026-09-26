package com.custoking.ims.platformservice.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * Approval binds the reviewed recipient fingerprint. The optional legacy actorId is validated
 * for wire compatibility but never trusted; controllers use the authenticated actor instead.
 * Queueing accepts the same optional body and never interprets approval as a provider send.
 */
public record BroadcastActionRequest(
        @Positive Long actorId,
        String previewFingerprint,
        String mode
) {
    public BroadcastActionRequest(Long actorId, String previewFingerprint) { this(actorId, previewFingerprint, null); }
}
