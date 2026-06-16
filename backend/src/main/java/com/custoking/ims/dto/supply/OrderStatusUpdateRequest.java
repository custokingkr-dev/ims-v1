package com.custoking.ims.dto.supply;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record OrderStatusUpdateRequest(@NotBlank String status) {

    public Map<String, Object> toMap() {
        return Map.of("status", status);
    }
}
