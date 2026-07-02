package com.custoking.ims.feeservice.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO for PUT /bands/{id}.
 * All fields are nullable — only present fields are merged; omitted fields keep their current value.
 *
 * Repo containsKey-gated fields: classFrom, classTo, discount
 *   → controller puts these into the body map only when non-null, preserving containsKey semantics.
 * Non-gated fields: name, schedules
 *   → repo falls back to the current value when the key is absent from the map.
 */
public record UpdateBandRequest(
        @Size(max = 255, message = "Band name must be at most 255 characters") String name,
        @Min(value = 1, message = "classFrom must be at least 1") Integer classFrom,
        @Min(value = 1, message = "classTo must be at least 1") Integer classTo,
        List<String> schedules,
        @PositiveOrZero(message = "discount must be zero or positive") Double discount
) {}
