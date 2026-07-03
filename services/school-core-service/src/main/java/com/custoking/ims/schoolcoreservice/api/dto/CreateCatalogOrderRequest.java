package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record CreateCatalogOrderRequest(
        @NotBlank String category,
        Long schoolId,
        Long subtotal,
        Long gst,
        Long totalAmount,
        String status,
        String notes,
        Long actorId,
        String requiredByDate,
        // Legacy alias keys that the repo reads via firstPresent(...)
        String id,
        String orderId,
        Long amount,
        String items,
        // Optional nested order-data map forwarded to the repo (absent → repo computes a default)
        Map<String, Object> orderData) {
}
