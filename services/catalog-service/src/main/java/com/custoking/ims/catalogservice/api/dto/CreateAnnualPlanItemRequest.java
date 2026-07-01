package com.custoking.ims.catalogservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAnnualPlanItemRequest(
        @NotBlank String category,
        String id,
        String termName,
        String description,
        String quantity,
        Long estimatedAmount,
        String status) {
}
