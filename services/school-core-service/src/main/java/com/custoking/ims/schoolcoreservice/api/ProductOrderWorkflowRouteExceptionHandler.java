package com.custoking.ims.schoolcoreservice.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gives a request under {@code /api/v1/catalog/orders/{id}/production-workflow} that reaches no handler the same
 * {@code {"message": ...}} body as every other refusal there: a wrong HTTP method (405, with its {@code Allow} header) or
 * an unknown path (404), such as the removed WhatsApp links endpoint. {@link CatalogOrderFormExceptionHandler} cannot do
 * this, because it is limited to its controllers and these requests fail before any controller is chosen.
 *
 * <p>Every other path is rethrown. Spring then resolves the exception as if this handler had not matched, so the rest of
 * the service keeps its default responses.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ProductOrderWorkflowRouteExceptionHandler {
    private static final Pattern WORKFLOW_PATH = Pattern.compile("^/api/v1/catalog/orders/[^/]+/production-workflow(?:/.*)?$");

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> methodNotAllowed(HttpRequestMethodNotSupportedException exception,
                                                                HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        if (!workflowPath(request)) throw exception;
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(exception.getHeaders())
                .body(Map.of("message", "This production workflow request does not accept that method"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, String>> notFound(Exception exception, HttpServletRequest request) throws Exception {
        if (!workflowPath(request)) throw exception;
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "This production workflow request does not exist"));
    }

    static boolean workflowPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri == null) return false;
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        return WORKFLOW_PATH.matcher(path).matches();
    }
}
