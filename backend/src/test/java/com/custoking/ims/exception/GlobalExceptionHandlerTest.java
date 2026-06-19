package com.custoking.ims.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 * No Spring context needed — the handler is a plain class.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ── IllegalArgumentException → 400 ───────────────────────────────────────

    @Test
    @DisplayName("IllegalArgumentException → 400 with exact message")
    void illegalArgument_returns400() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleIllegalArgument(new IllegalArgumentException("Admission Number is mandatory"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("message", "Admission Number is mandatory");
        assertThat(res.getBody()).containsEntry("status", 400);
    }

    // ── ConstraintViolationException → 400 ───────────────────────────────────

    @Test
    @DisplayName("ConstraintViolationException → 400 with parameter name and violations list")
    void constraintViolation_returns400WithViolations() {
        // Build a minimal ConstraintViolation mock
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("listStudents.page");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be greater than or equal to 0");

        ConstraintViolationException ex =
                new ConstraintViolationException(Set.of(violation));

        ResponseEntity<Map<String, Object>> res = handler.handleConstraintViolation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        var errors = (java.util.List<String>) res.getBody().get("errors");
        assertThat(errors).anyMatch(e -> e.contains("page") && e.contains("must be greater than or equal to 0"));
    }

    @Test
    @DisplayName("ConstraintViolationException with no violations → generic 400 message")
    void constraintViolation_empty_returns400() {
        ConstraintViolationException ex = new ConstraintViolationException(Set.of());
        ResponseEntity<Map<String, Object>> res = handler.handleConstraintViolation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("message", "Request parameter validation failed");
    }

    @Test
    @DisplayName("ConstraintViolation path without dot → uses full path as param name")
    void constraintViolation_noDotInPath_usesFullPath() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("size");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must be less than or equal to 200");

        ResponseEntity<Map<String, Object>> res =
                handler.handleConstraintViolation(new ConstraintViolationException(Set.of(violation)));

        @SuppressWarnings("unchecked")
        var errors = (java.util.List<String>) res.getBody().get("errors");
        assertThat(errors).anyMatch(e -> e.startsWith("size:"));
    }

    // ── DataIntegrityViolationException → 409 ────────────────────────────────

    @Test
    @DisplayName("DataIntegrity with admission constraint → school-scoped message")
    void dataIntegrity_admissionConstraint_returns409() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"uix_students_school_admission\"");

        ResponseEntity<Map<String, Object>> res = handler.handleDataIntegrity(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsEntry("message", "Admission number already exists in this school");
    }

    @Test
    @DisplayName("DataIntegrity with email constraint → friendly email message")
    void dataIntegrity_emailConstraint_returns409() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint \"uk_app_users_email\"");

        ResponseEntity<Map<String, Object>> res = handler.handleDataIntegrity(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsEntry("message",
                "An account with this email address already exists");
    }

    @Test
    @DisplayName("DataIntegrity with unknown constraint → generic conflict message, no SQL leaked")
    void dataIntegrity_unknownConstraint_returnsGeneric() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "ERROR: some_internal_constraint violated with raw sql detail");

        ResponseEntity<Map<String, Object>> res = handler.handleDataIntegrity(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String message = (String) res.getBody().get("message");
        assertThat(message).isEqualTo("A record with conflicting data already exists");
        // Must not leak raw SQL
        assertThat(message).doesNotContain("sql").doesNotContain("constraint");
    }

    // ── friendlyConstraintMessage static helper ───────────────────────────────

    @Test
    @DisplayName("friendlyConstraintMessage: null cause message → generic fallback")
    void friendlyConstraintMessage_nullMessage_returnsGeneric() {
        DataIntegrityViolationException ex = mock(DataIntegrityViolationException.class);
        Throwable cause = mock(Throwable.class);
        when(ex.getMostSpecificCause()).thenReturn(cause);
        when(cause.getMessage()).thenReturn(null);

        assertThat(GlobalExceptionHandler.friendlyConstraintMessage(ex))
                .isEqualTo("A record with conflicting data already exists");
    }

    @Test
    @DisplayName("friendlyConstraintMessage: school short code constraint")
    void friendlyConstraintMessage_schoolShortCode() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "duplicate key: uk_school_short_code");

        assertThat(GlobalExceptionHandler.friendlyConstraintMessage(ex))
                .isEqualTo("A school with this short code already exists");
    }

    // ── ResponseStatusException → passthrough ────────────────────────────────

    @Test
    @DisplayName("ResponseStatusException(404, reason) → 404 with reason as message")
    void responseStatus_404_passesThrough() {
        ResponseEntity<Map<String, Object>> res = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).containsEntry("message", "Student not found");
    }

    // ── response envelope shape ───────────────────────────────────────────────

    @Test
    @DisplayName("Error envelope always contains status, error, message, timestamp")
    void errorEnvelope_containsRequiredFields() {
        ResponseEntity<Map<String, Object>> res =
                handler.handleIllegalArgument(new IllegalArgumentException("test"));

        Map<String, Object> body = res.getBody();
        assertThat(body).containsKeys("status", "error", "message", "timestamp");
        assertThat(body.get("timestamp").toString()).isNotBlank();
    }
}
