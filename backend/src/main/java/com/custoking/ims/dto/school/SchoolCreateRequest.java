package com.custoking.ims.dto.school;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SchoolCreateRequest(
        @NotBlank String name,
        @NotBlank String shortCode,
        String city,
        String state,
        String contactEmail,
        String contactPhone,
        @Min(1) @Max(12) Integer classCount,
        @Min(1) @Max(26) Integer sectionCount
) {}
