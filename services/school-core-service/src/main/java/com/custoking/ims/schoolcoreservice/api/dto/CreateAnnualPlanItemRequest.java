package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateAnnualPlanItemRequest(
        @NotBlank String category,
        String id,
        String termName,
        String description,
        String quantity,
        Long estimatedAmount,
        String status,
        // Legacy alias keys that the repo reads via firstPresent(...)
        String term,
        Long amount) {
}
