package com.custoking.ims.feeservice.api;

import jakarta.validation.ConstraintViolationException;
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
}
