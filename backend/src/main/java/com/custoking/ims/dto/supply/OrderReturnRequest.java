package com.custoking.ims.dto.supply;

import java.util.Map;

public record OrderReturnRequest(String reason) {

    public Map<String, Object> toMap() {
        return Map.of("reason", reason == null || reason.isBlank() ? "Returned by Superadmin" : reason);
    }
}
