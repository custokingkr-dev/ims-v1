package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO for POST /payments.
 * Maps to recordPayment(Map) repo keys:
 *   studentId (required), amount (required, must be > 0),
 *   paidAt (optional), mode (optional, defaults "UPI"), notes (optional, defaults ""),
 *   idempotencyKey (required, stable across retries), assignmentId / academicYearId
 *   (optional selectors; without either, only the current-year assignment is used).
 * actorId is accepted for wire compatibility but is always ignored in favor of TenantContext.
 */
public record RecordPaymentRequest(
        @NotNull(message = "Student id is required") @Positive Long studentId,
        @NotNull(message = "Amount must be greater than zero") @Positive Long amount,
        String paidAt,
        String mode,
        String notes,
        Long actorId,
        @NotBlank(message = "A payment idempotency key is required") @Size(max = 128) String idempotencyKey,
        String assignmentId,
        String academicYearId,
        Long schoolId
) {
    public RecordPaymentRequest(Long studentId, Long amount, String paidAt, String mode, String notes, Long actorId, String idempotencyKey, String assignmentId, String academicYearId) {
        this(studentId, amount, paidAt, mode, notes, actorId, idempotencyKey, assignmentId, academicYearId, null);
    }
}
