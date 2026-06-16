package com.custoking.ims.dto.firefighting;

import jakarta.validation.constraints.PositiveOrZero;

import java.util.LinkedHashMap;
import java.util.Map;

public record FirefightingQuotationRequest(
        String vendorName,
        @PositiveOrZero Long amount,
        String deliveryTimeline,
        String notes,
        String documentUrl
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (vendorName != null) map.put("vendorName", vendorName);
        if (amount != null) map.put("amount", amount);
        if (deliveryTimeline != null) map.put("deliveryTimeline", deliveryTimeline);
        if (notes != null) map.put("notes", notes);
        if (documentUrl != null) map.put("documentUrl", documentUrl);
        return map;
    }
}
