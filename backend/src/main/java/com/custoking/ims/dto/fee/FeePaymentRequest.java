package com.custoking.ims.dto.fee;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.LinkedHashMap;
import java.util.Map;

public record FeePaymentRequest(
        @NotNull @Positive Long studentId,
        @NotNull @Positive Long amount,
        String mode,
        String notes,
        String paidAt
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("studentId", studentId);
        map.put("amount", amount);
        if (mode != null) map.put("mode", mode);
        if (notes != null) map.put("notes", notes);
        if (paidAt != null) map.put("paidAt", paidAt);
        return map;
    }
}
