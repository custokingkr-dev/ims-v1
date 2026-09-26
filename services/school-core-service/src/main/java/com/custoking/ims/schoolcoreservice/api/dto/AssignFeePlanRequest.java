package com.custoking.ims.schoolcoreservice.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * DTO for POST /assignments.
 * Maps to assignFeePlan(Map) repo keys:
 *   studentId (required), bandId (required), schedule (required),
 *   bandDiscount (optional — repo uses containsKey to gate partial update),
 *   manualDiscount (optional, defaults 0), surcharge (optional, defaults 0),
 *   actorId (optional — repo uses containsKey to gate).
 */
public record AssignFeePlanRequest(
        @NotNull(message = "Student id is required") @Positive Long studentId,
        @NotBlank(message = "Band id is required") String bandId,
        @NotBlank(message = "Payment schedule is required") String schedule,
        Double bandDiscount,
        Double manualDiscount,
        Double surcharge,
        Long actorId,
        java.util.List<String> optionalItemIds,
        Long discountRuleId,
        String academicYearId
) {
    public AssignFeePlanRequest(Long studentId, String bandId, String schedule, Double bandDiscount, Double manualDiscount, Double surcharge, Long actorId) {
        this(studentId, bandId, schedule, bandDiscount, manualDiscount, surcharge, actorId, null, null, null);
    }
}
