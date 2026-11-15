package com.custoking.ims.schoolcoreservice.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every fee-reminder request answers 409 {@code FEE_REMINDERS_UNAVAILABLE}. The endpoints used to report
 * reminders as "queued" while nothing stored, published or delivered them, and parent messaging is not live
 * anywhere. Callers still pass the token, permission, tenant and fee-module checks first, so a caller who
 * may not ask gets 401/403 rather than this refusal.
 */
public final class FeeRemindersUnavailable {

    public static final String CODE = "FEE_REMINDERS_UNAVAILABLE";
    public static final String MESSAGE = "Fee reminders can't be sent yet: parent messaging isn't switched on.";

    private FeeRemindersUnavailable() {
    }

    public static ResponseEntity<Map<String, Object>> response() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", CODE);
        body.put("message", MESSAGE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
