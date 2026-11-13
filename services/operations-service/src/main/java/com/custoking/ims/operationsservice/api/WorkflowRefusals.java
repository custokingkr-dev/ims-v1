package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the vendor directory, call log, activity and settings endpoints share: the gateway's workflow service token, and
 * refusals that reach the screen as {@code {"code"?, "message", "unmet"?}} (the contract's {@code OrderWorkflowError}),
 * where the frontend looks for them. Spring would otherwise drop the reason and leave a generic failure.
 */
final class WorkflowRefusals {
    /** The catalogue order id in a path: {@code CK-<n>}, never an order number. */
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    private WorkflowRefusals() { }

    /**
     * Every user call carries the gateway's workflow service token, and each route names its scope (read or write) so the
     * service authorization audit can see it; the caller's own role is checked by the services.
     */
    static void requireToken(String configured, String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        if (configured == null || configured.isEmpty() || !StringUtils.hasText(token)
                || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid workflow service token");
        }
    }

    static String orderId(String orderId) {
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order id");
        }
        return orderId;
    }

    static ResponseEntity<Map<String, Object>> refused(ResponseStatusException refusal) {
        String reason = refusal.getReason();
        if (reason == null || reason.isBlank()) {
            reason = refusal.getStatusCode() instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(refusal.getStatusCode()).headers(refusal.getHeaders()).body(Map.of("message", reason));
    }

    /** A broken workflow rule carries its stable code; {@code GATE_UNMET} also lists every unmet requirement. */
    static ResponseEntity<Map<String, Object>> violation(WorkflowRuleViolation violation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", violation.code());
        body.put("message", violation.getMessage());
        if (!violation.unmet().isEmpty()) body.put("unmet", violation.unmet());
        return ResponseEntity.status(violation.httpStatus()).body(body);
    }

    static ResponseEntity<Map<String, Object>> badParameter(MethodArgumentTypeMismatchException mismatch) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid " + mismatch.getName()));
    }

    static ResponseEntity<Map<String, Object>> unreadable(String expected) {
        return ResponseEntity.badRequest().body(Map.of("message", expected));
    }
}
