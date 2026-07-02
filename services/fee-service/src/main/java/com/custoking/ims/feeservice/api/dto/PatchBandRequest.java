package com.custoking.ims.feeservice.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * DTO for PATCH /bands/{id}.
 * All fields are nullable — only present fields trigger updates.
 *
 * Repo containsKey-gated fields: discount, bandDiscount (repo checks OR of the two)
 *   → controller puts either key into the body map only when non-null.
 * Non-gated: schedules — repo updates schedules only when schedulesCsv result is non-blank,
 *   so null/absent and empty list both mean "skip".
 */
public record PatchBandRequest(
        @PositiveOrZero(message = "discount must be zero or positive") Double discount,
        @PositiveOrZero(message = "bandDiscount must be zero or positive") Double bandDiscount,
        List<String> schedules
) {}
