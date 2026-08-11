package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ImportAdmissionException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Turns bean-validation failures into a consistent SPA-friendly 400. Copied per service. */
@RestControllerAdvice
public class ValidationExceptionHandler {

    private static final String IMPORT_ADMISSION_REJECTIONS =
            "ims.student.import.admission.rejections";
    private final MeterRegistry meterRegistry;

    public ValidationExceptionHandler() {
        this(null);
    }

    @Autowired
    public ValidationExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return badRequest(fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> onParamValidation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> {
            String path = v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.putIfAbsent(field, v.getMessage());
        });
        return badRequest(fieldErrors);
    }

    private ResponseEntity<Map<String, Object>> badRequest(Map<String, String> fieldErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Validation failed");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** Business validation throws IllegalArgumentException; return a clean 400 with its message. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onIllegalArgument(IllegalArgumentException ex) {
        return message(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ImportAdmissionException.class)
    public ResponseEntity<Map<String, Object>> onImportAdmission(ImportAdmissionException ex) {
        if (meterRegistry != null) {
            // The reason tag is a closed application-controlled set, never a school id,
            // token or exception message, so metric cardinality and PII remain bounded.
            String reason = switch (ex.code()) {
                case "school_import_active", "import_capacity_busy" -> ex.code();
                default -> "unknown";
            };
            meterRegistry.counter(IMPORT_ADMISSION_REJECTIONS, "reason", reason).increment();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        body.put("code", ex.code());
        body.put("retryAfterSeconds", ex.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(body);
    }

    /**
     * Spring's persistence-exception translation rewraps an IllegalArgumentException thrown inside a
     * repository bean as InvalidDataAccessApiUsageException before the controller can catch it, so
     * validation failures would otherwise surface as 500. Unwrap it: an IllegalArgumentException
     * cause is a client validation error (400); anything else is a genuine misuse (500).
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<Map<String, Object>> onDataAccessApiUsage(InvalidDataAccessApiUsageException ex) {
        // Admission has priority over generic validation even if an intermediate
        // IllegalArgumentException wraps it. Keep the passes separate so persistence
        // translation cannot downgrade a deliberate 429 to a 400.
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ImportAdmissionException admission) {
                return onImportAdmission(admission);
            }
        }
        // Walk the chain (not getMostSpecificCause, which can dig past the wrapped
        // IllegalArgumentException to a deeper root like an IOException) so a validation error
        // that itself wraps another exception still surfaces as a 400.
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) {
                return message(HttpStatus.BAD_REQUEST, cause.getMessage());
            }
        }
        return message(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private ResponseEntity<Map<String, Object>> message(HttpStatus status, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        return ResponseEntity.status(status).body(body);
    }
}
