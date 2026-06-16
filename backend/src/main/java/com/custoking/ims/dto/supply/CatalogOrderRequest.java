package com.custoking.ims.dto.supply;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.LinkedHashMap;
import java.util.Map;

public record CatalogOrderRequest(
        Long schoolId,
        @NotBlank String category,
        Object orderData,
        String items,
        @PositiveOrZero Long subtotal,
        @PositiveOrZero Long gst,
        @PositiveOrZero Long totalAmount,
        @PositiveOrZero Long amount,
        String requiredByDate,
        String status,
        String notes
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (schoolId != null) map.put("schoolId", schoolId);
        map.put("category", category);
        if (orderData != null) map.put("orderData", orderData);
        if (items != null) map.put("items", items);
        if (subtotal != null) map.put("subtotal", subtotal);
        if (gst != null) map.put("gst", gst);
        if (totalAmount != null) map.put("totalAmount", totalAmount);
        if (amount != null) map.put("amount", amount);
        if (requiredByDate != null) map.put("requiredByDate", requiredByDate);
        if (status != null) map.put("status", status);
        if (notes != null) map.put("notes", notes);
        return map;
    }
}
