package com.custoking.ims.dto.zone;

public record ZoneUpdateRequest(
        String name,
        String city,
        String state,
        String description,
        Boolean active
) {}
