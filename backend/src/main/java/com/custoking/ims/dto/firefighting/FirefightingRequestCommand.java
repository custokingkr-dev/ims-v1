package com.custoking.ims.dto.firefighting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FirefightingRequestCommand(
        Long schoolId,
        String title,
        String category,
        String urgency,
        String requiredByDate,
        @PositiveOrZero Long estimatedBudget,
        String summary,
        String description,
        String referenceFileUrl,
        @Valid List<FirefightingQuotationRequest> quotations
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (schoolId != null) map.put("schoolId", schoolId);
        if (title != null) map.put("title", title);
        if (category != null) map.put("category", category);
        if (urgency != null) map.put("urgency", urgency);
        if (requiredByDate != null) map.put("requiredByDate", requiredByDate);
        if (estimatedBudget != null) map.put("estimatedBudget", estimatedBudget);
        if (summary != null) map.put("summary", summary);
        if (description != null) map.put("description", description);
        if (referenceFileUrl != null) map.put("referenceFileUrl", referenceFileUrl);
        if (quotations != null) {
            map.put("quotations", quotations.stream().map(FirefightingQuotationRequest::toMap).toList());
        }
        return map;
    }
}
