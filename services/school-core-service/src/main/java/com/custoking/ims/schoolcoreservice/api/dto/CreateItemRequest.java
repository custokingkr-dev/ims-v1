package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /items.
 * Maps to createItem(Map) repo keys: bandId, name (also accepted as itemName), frequency, amount.
 * The repo uses firstPresent(request, "itemName", "name") — we send the value under "name".
 */
public record CreateItemRequest(
        @NotBlank(message = "Band id is required") String bandId,
        @NotBlank(message = "Item name is required") String name,
        String frequency,
        Long amount
) {}
