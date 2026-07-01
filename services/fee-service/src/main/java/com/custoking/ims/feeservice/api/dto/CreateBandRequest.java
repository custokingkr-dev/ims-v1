package com.custoking.ims.feeservice.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for POST /bands.
 * Maps to createBand(Map) repo keys: name, classFrom, classTo, schedules, discount.
 */
public record CreateBandRequest(
        @NotBlank(message = "Band name is required") String name,
        Integer classFrom,
        Integer classTo,
        Object schedules,
        Double discount
) {}
