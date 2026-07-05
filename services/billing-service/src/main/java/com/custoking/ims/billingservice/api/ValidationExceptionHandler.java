package com.custoking.ims.billingservice.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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

    /**
     * Spring's persistence-exception translation rewraps an IllegalArgumentException thrown inside a
     * repository bean as InvalidDataAccessApiUsageException before the controller can catch it, so
     * validation failures would otherwise surface as 500. Unwrap it: an IllegalArgumentException
     * cause is a client validation error (400); anything else is a genuine misuse (500).
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<Map<String, Object>> onDataAccessApiUsage(InvalidDataAccessApiUsageException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof IllegalArgumentException) {
            return message(HttpStatus.BAD_REQUEST, cause.getMessage());
        }
        return message(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private ResponseEntity<Map<String, Object>> message(HttpStatus status, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", detail == null || detail.isBlank() ? status.getReasonPhrase() : detail);
        return ResponseEntity.status(status).body(body);
    }
}
