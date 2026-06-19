package com.custoking.ims.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ── 400 Bad Request ──────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.toList());
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST,
                fieldErrors.isEmpty() ? "Validation failed" : fieldErrors.get(0));
        body.put("errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handles constraint violations from {@code @Validated} + method-level
     * annotations ({@code @Min}, {@code @Max}, {@code @NotBlank}, …) on
     * controller parameters and service methods.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> violations = ex.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    // Strip method name prefix (e.g. "listStudents.page" → "page")
                    int dot = path.lastIndexOf('.');
                    String param = dot >= 0 ? path.substring(dot + 1) : path;
                    return param + ": " + v.getMessage();
                })
                .sorted()
                .collect(Collectors.toList());
        Map<String, Object> body = errorBody(HttpStatus.BAD_REQUEST,
                violations.isEmpty() ? "Request parameter validation failed" : violations.get(0));
        body.put("errors", violations);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for parameter: " + ex.getName());
    }

    // ── 403 Forbidden ────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "Access denied");
    }

    // ── 409 Conflict ─────────────────────────────────────────────────────────

    /**
     * Maps known DB constraint names to user-friendly messages.
     * Never exposes raw SQL in the response — constraint details go to the log only.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.debug("data-integrity-violation", ex);
        String message = friendlyConstraintMessage(ex);
        return error(HttpStatus.CONFLICT, message);
    }

    // ── 413 Payload Too Large ────────────────────────────────────────────────

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "Upload exceeds the maximum allowed size");
    }

    // ── Explicit HTTP status exceptions ─────────────────────────────────────

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        return error(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    // ── 500 Internal Server Error (last resort) ──────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        String cid = UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled-exception correlationId={}", cid, ex);
        Map<String, Object> body = errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        body.put("correlationId", cid);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(errorBody(status, message));
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }

    /**
     * Translates known DB constraint names to user-friendly messages.
     * Falls back to a generic conflict message — never leaks raw SQL.
     */
    static String friendlyConstraintMessage(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        if (msg == null) return "A record with conflicting data already exists";
        String lower = msg.toLowerCase();

        // School-scoped admission number uniqueness (V126)
        if (lower.contains("uix_students_school_admission")) {
            return "Admission number already exists in this school";
        }
        // Legacy global admission constraint (pre-V126 environments)
        if (lower.contains("uk_student_admission_no")) {
            return "Admission number already exists";
        }
        if (lower.contains("uk_app_users_email") || lower.contains("app_users_email")) {
            return "An account with this email address already exists";
        }
        if (lower.contains("schools_short_code") || lower.contains("uk_school_short_code")) {
            return "A school with this short code already exists";
        }
        if (lower.contains("fee_assignments") && lower.contains("unique")) {
            return "A fee assignment already exists for this student and academic year";
        }
        return "A record with conflicting data already exists";
    }
}
