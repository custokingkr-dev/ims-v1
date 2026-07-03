package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * DTO for POST /payments.
 * Maps to recordPayment(Map) repo keys:
 *   studentId (required), amount (required, must be > 0),
 *   paidAt (optional), mode (optional, defaults "UPI"), notes (optional, defaults ""),
 *   actorId (optional — repo uses containsKey to gate).
 */
public record RecordPaymentRequest(
        @NotNull(message = "Student id is required") @Positive Long studentId,
        @NotNull(message = "Amount must be greater than zero") @Positive Long amount,
        String paidAt,
        String mode,
        String notes,
        Long actorId
) {}
