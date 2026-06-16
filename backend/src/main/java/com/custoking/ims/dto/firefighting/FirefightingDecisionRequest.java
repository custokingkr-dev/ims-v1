package com.custoking.ims.dto.firefighting;

import java.util.LinkedHashMap;
import java.util.Map;

public record FirefightingDecisionRequest(
        String note,
        String selectedQuotationId,
        String reason,
        String rejectedReason,
        String rejectedBy
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (note != null) map.put("note", note);
        if (selectedQuotationId != null) map.put("selectedQuotationId", selectedQuotationId);
        if (reason != null) map.put("reason", reason);
        if (rejectedReason != null) map.put("rejectedReason", rejectedReason);
        if (rejectedBy != null) map.put("rejectedBy", rejectedBy);
        return map;
    }
}
