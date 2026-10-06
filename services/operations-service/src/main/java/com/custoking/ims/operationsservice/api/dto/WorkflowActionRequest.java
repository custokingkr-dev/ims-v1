package com.custoking.ims.operationsservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Optional request body for workflow action endpoints:
 * submit, approve, reject, cancel, complete.
 *
 * Decisions require expectedVersion in repository validation; other actions retain
 * optional bodies. Actor fields are ignored in favor of authenticated context.
 */
public record WorkflowActionRequest(
        /** Actor user ID — must be positive when provided. */
        @Positive Long actorId,

        /** Actor e-mail address — must be a well-formed address when provided. */
        @Email String actorEmail,

        /** Free-text notes or rejection reason, capped at 1 000 characters. */
        @Size(max = 1000) String notes,
        @jakarta.validation.constraints.PositiveOrZero Long expectedVersion
) {
    public WorkflowActionRequest(Long actorId, String actorEmail, String notes) {
        this(actorId, actorEmail, notes, null);
    }
}
