package com.custoking.ims.schoolcoreservice.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Carries the reason of a refused order request to the screen as {@code {"message": ...}}, which is where the frontend
 * looks for it, with the refusal's own status. Without this the message is dropped (Spring Boot leaves it out of its error
 * body) and the user sees a generic failure instead of, for example, "No more images allowed. Contact IT.", "Workflow
 * changed; refresh and try again" or "This action is unavailable while the order is PROCESSING". Limited to the order
 * controllers: the order form, the production workflow, and the order lifecycle in {@link CatalogReadController} (create,
 * place, design approval, final approval, return, delivery and status changes), so no other endpoint changes. A workflow
 * request that reaches no controller (wrong method, unknown path) gets its message from
 * {@link ProductOrderWorkflowRouteExceptionHandler} instead.
 */
@RestControllerAdvice(assignableTypes = {CatalogOrderFormController.class, ProductOrderWorkflowController.class,
        CatalogReadController.class})
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

    /** A body that is not JSON of the expected shape, for example a version sent as text. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "The request could not be read. Refresh and try again"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupported(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("message", "This request's content type is not supported"));
    }
}
