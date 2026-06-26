package com.custoking.ims.auditservice.api;

import com.custoking.ims.auditservice.persistence.AuditEvent;
import com.custoking.ims.auditservice.persistence.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditIngestControllerTest {

    private final AuditEventRepository repository = mock(AuditEventRepository.class);
    private final AuditIngestController controller = new AuditIngestController(repository, "audit-token");

    @Test
    void ingestRejectsMissingTokenBeforeSaving() {
        assertThatThrownBy(() -> controller.ingest(null, validRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(repository, never()).save(any());
    }

    @Test
    void ingestRejectsBlankActionBeforeSaving() {
        var request = new AuditIngestController.AuditEventRequest(
                " ",
                9L,
                4L,
                "STUDENT",
                "990001",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.parse("2026-01-02T03:04:05Z"));

        assertThatThrownBy(() -> controller.ingest("audit-token", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(repository, never()).save(any());
    }

    @Test
    void ingestMapsRequestAndTrimsOversizedEdgeFields() {
        when(repository.save(any())).thenAnswer(invocation -> {
            AuditEvent event = invocation.getArgument(0);
            ReflectionTestUtils.setField(event, "id", 42L);
            return event;
        });

        var timestamp = OffsetDateTime.parse("2026-01-02T03:04:05Z");
        var response = controller.ingest("audit-token", new AuditIngestController.AuditEventRequest(
                "STUDENT_UPDATED",
                9L,
                4L,
                "STUDENT",
                "990001",
                "1".repeat(80),
                "u".repeat(600),
                "r".repeat(80),
                "admin@example.com",
                "{\"old\":true}",
                "{\"new\":true}",
                "",
                timestamp));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(new AuditIngestController.AuditEventResponse(42L, "ACCEPTED"));

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(eventCaptor.capture());
        AuditEvent saved = eventCaptor.getValue();
        assertThat(saved.getAction()).isEqualTo("STUDENT_UPDATED");
        assertThat(saved.getUserId()).isEqualTo(9L);
        assertThat(saved.getSchoolId()).isEqualTo(4L);
        assertThat(saved.getIpAddress()).hasSize(64);
        assertThat(saved.getUserAgent()).hasSize(512);
        assertThat(saved.getRequestId()).hasSize(64);
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getEventTimestamp()).isEqualTo(timestamp);
    }

    private AuditIngestController.AuditEventRequest validRequest() {
        return new AuditIngestController.AuditEventRequest(
                "LOGIN",
                9L,
                4L,
                "USER",
                "9",
                "127.0.0.1",
                "test-agent",
                "request-1",
                "admin@example.com",
                null,
                null,
                "SUCCESS",
                OffsetDateTime.parse("2026-01-02T03:04:05Z"));
    }
}
