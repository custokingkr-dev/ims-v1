package com.custoking.ims.dto.school;

public record SchoolUpdateRequest(
        String name,
        String city,
        Boolean active
) {}
