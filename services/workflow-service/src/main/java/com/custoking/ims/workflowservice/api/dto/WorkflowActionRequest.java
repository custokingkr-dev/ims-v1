package com.custoking.ims.workflowservice.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Optional request body for workflow action endpoints:
 * submit, approve, reject, cancel, complete.
 *
 * All fields are nullable — the entire body is optional (required=false).
 * Format constraints fire only when the field is present and non-null.
 * No @NotNull / @NotBlank — callers may omit any or all fields.
 */
public record WorkflowActionRequest(
        /** Actor user ID — must be positive when provided. */
        @Positive Long actorId,

        /** Actor e-mail address — must be a well-formed address when provided. */
        @Email String actorEmail,

        /** Free-text notes or rejection reason — capped at 2 000 characters when provided. */
        @Size(max = 2000) String notes
) {}
