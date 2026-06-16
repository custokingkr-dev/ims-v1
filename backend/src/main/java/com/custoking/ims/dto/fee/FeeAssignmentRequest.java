package com.custoking.ims.dto.fee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.LinkedHashMap;
import java.util.Map;

public record FeeAssignmentRequest(
        @NotNull @Positive Long studentId,
        @NotBlank String bandId,
        @NotBlank String schedule,
        @PositiveOrZero Double bandDiscount,
        @PositiveOrZero Double manualDiscount,
        @PositiveOrZero Double surcharge,
        @PositiveOrZero Long netPayable,
        String academicYearId
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("studentId", studentId);
        map.put("bandId", bandId);
        map.put("schedule", schedule);
        if (bandDiscount != null) map.put("bandDiscount", bandDiscount);
        if (manualDiscount != null) map.put("manualDiscount", manualDiscount);
        if (surcharge != null) map.put("surcharge", surcharge);
        if (netPayable != null) map.put("netPayable", netPayable);
        if (academicYearId != null) map.put("academicYearId", academicYearId);
        return map;
    }
}
