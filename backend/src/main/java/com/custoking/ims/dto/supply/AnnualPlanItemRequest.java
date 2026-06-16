package com.custoking.ims.dto.supply;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.LinkedHashMap;
import java.util.Map;

public record AnnualPlanItemRequest(
        String id,
        Long schoolId,
        String term,
        String termName,
        @NotBlank String category,
        String description,
        String quantity,
        @PositiveOrZero Long estimatedAmount,
        @PositiveOrZero Long amount,
        String status
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (id != null) map.put("id", id);
        if (schoolId != null) map.put("schoolId", schoolId);
        if (term != null) map.put("term", term);
        if (termName != null) map.put("termName", termName);
        map.put("category", category);
        if (description != null) map.put("description", description);
        if (quantity != null) map.put("quantity", quantity);
        if (estimatedAmount != null) map.put("estimatedAmount", estimatedAmount);
        if (amount != null) map.put("amount", amount);
        if (status != null) map.put("status", status);
        return map;
    }
}
