package com.custoking.ims.dto.zone;

import jakarta.validation.constraints.NotBlank;

public record ZoneCreateRequest(
        @NotBlank String name,
        @NotBlank String code,
        String city,
        String state,
        String description
) {}
