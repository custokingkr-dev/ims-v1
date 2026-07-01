package com.custoking.ims.catalogservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCatalogOrderRequest(
        @NotBlank String category,
        Long schoolId,
        Long subtotal,
        Long gst,
        Long totalAmount,
        String status,
        String notes,
        Long actorId,
        String requiredByDate) {
}
