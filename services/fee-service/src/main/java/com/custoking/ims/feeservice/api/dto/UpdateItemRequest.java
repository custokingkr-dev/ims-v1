package com.custoking.ims.feeservice.api.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * DTO for PUT /items/{id}.
 * All fields are nullable — only present fields are merged; omitted fields keep their current value.
 *
 * All fields are containsKey-gated in the repo:
 *   - itemName / name   → repo checks containsKey("itemName") || containsKey("name"); firstPresent prefers itemName
 *   - frequency         → repo checks containsKey("frequency")
 *   - amount            → repo checks containsKey("amount")
 * Controller puts each key into the body map only when non-null, preserving containsKey semantics.
 */
public record UpdateItemRequest(
        @Size(max = 255, message = "Item name must be at most 255 characters") String name,
        @Size(max = 255, message = "Item name must be at most 255 characters") String itemName,
        @Size(max = 100, message = "Frequency must be at most 100 characters") String frequency,
        @PositiveOrZero(message = "amount must be zero or positive") Long amount
) {}
