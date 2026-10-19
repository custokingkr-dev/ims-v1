package com.custoking.ims.schoolcoreservice.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Carries the reason of a refused order-form request to the screen as {@code {"message": ...}}, which is where the
 * frontend looks for it. Without this the message is dropped and the user sees a generic failure instead of, for
 * example, "No more images allowed. Contact IT.". Limited to the order-form controller so no other endpoint changes.
 */
@RestControllerAdvice(assignableTypes = CatalogOrderFormController.class)
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CatalogOrderFormExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException exception) {
        HttpStatusCode status = exception.getStatusCode();
        String reason = exception.getReason();
        if (reason == null || reason.isBlank()) {
            reason = status instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(status).headers(exception.getHeaders()).body(Map.of("message", reason));
    }
}
