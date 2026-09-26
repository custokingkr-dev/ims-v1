package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO for POST /bands.
 * Maps to createBand(Map) repo keys: name, classFrom, classTo, schedules, discount.
 */
public record CreateBandRequest(
        @NotBlank(message = "Band name is required") String name,
        Integer classFrom,
        Integer classTo,
        @NotNull(message = "At least one schedule is required") Object schedules,
        Double discount,
        Long schoolId,
        Integer gracePeriodDays,
        String lateFeeType,
        Double lateFeeAmount,
        Integer lateFeeIntervalDays
) {
    public CreateBandRequest(String name, Integer classFrom, Integer classTo, Object schedules, Double discount, Long schoolId) {
        this(name, classFrom, classTo, schedules, discount, schoolId, null, null, null, null);
    }
}
