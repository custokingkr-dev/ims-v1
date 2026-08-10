package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationInboxProcessor;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PubSubPushControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void receiveNotificationRequest_recordsTraceContextAttributes() throws Exception {
        NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
        NotificationInboxProcessor processor = mock(NotificationInboxProcessor.class);
        PubSubPushController controller = new PubSubPushController(inbox, processor, mapper, "push-token");
        when(inbox.findById("event-1")).thenReturn(Optional.empty());

        controller.receiveNotificationRequest("push-token", null, envelopeWithTrace());

        ArgumentCaptor<NotificationInboxEvent> captor = ArgumentCaptor.forClass(NotificationInboxEvent.class);
        verify(inbox).save(captor.capture());
        assertThat(captor.getValue().getTraceParent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(captor.getValue().getTraceState()).isEqualTo("vendor=value");
        verify(processor).process(captor.getValue());
    }

    @Test
    void oidcOnlyMode_acceptsRequestWithoutLegacySharedToken() throws Exception {
        NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
        NotificationInboxProcessor processor = mock(NotificationInboxProcessor.class);
        PubSubPushController controller = new PubSubPushController(inbox, processor, mapper, "", false);
        when(inbox.findById("event-1")).thenReturn(Optional.empty());

        controller.receiveNotificationRequest(null, null, envelopeWithTrace());

        ArgumentCaptor<NotificationInboxEvent> captor = ArgumentCaptor.forClass(NotificationInboxEvent.class);
        verify(inbox).save(captor.capture());
        verify(processor).process(captor.getValue());
    }

    @Test
    void legacyMode_rejectsMissingSharedToken() throws Exception {
        NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
        NotificationInboxProcessor processor = mock(NotificationInboxProcessor.class);
        PubSubPushController controller = new PubSubPushController(inbox, processor, mapper, "push-token");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.receiveNotificationRequest(null, null, envelopeWithTrace()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(401));

        verify(inbox, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void terminalDeadLetterAcknowledgesWithoutAnotherProviderAttempt() throws Exception {
        NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
        NotificationInboxProcessor processor = mock(NotificationInboxProcessor.class);
        PubSubPushController controller = new PubSubPushController(inbox, processor, mapper, "push-token");
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("event-1");
        event.setStatus(NotificationInboxEvent.STATUS_DEAD_LETTER);
        when(inbox.findById("event-1")).thenReturn(Optional.of(event));

        controller.receiveNotificationRequest("push-token", null, envelopeWithTrace());

        verify(processor, never()).process(event);
    }

    @Test
    void redeliveryBeforeScheduledRetryDoesNotCallProvider() throws Exception {
        NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
        NotificationInboxProcessor processor = mock(NotificationInboxProcessor.class);
        PubSubPushController controller = new PubSubPushController(inbox, processor, mapper, "push-token");
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("event-1");
        event.setStatus(NotificationInboxEvent.STATUS_FAILED);
        event.setNextAttemptAt(OffsetDateTime.now().plusMinutes(5));
        when(inbox.findById("event-1")).thenReturn(Optional.of(event));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                controller.receiveNotificationRequest("push-token", null, envelopeWithTrace()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error)
                        .getStatusCode().value()).isEqualTo(503));

        verify(processor, never()).process(event);
    }

    private JsonNode envelopeWithTrace() throws Exception {
        String payload = """
                {
                  "schemaVersion": "ims.event-envelope.v1",
                  "eventType": "notification.requested.v1",
                  "payload": { "channel": "email" }
                }
                """;
        String data = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return mapper.readTree("""
                {
                  "message": {
                    "messageId": "event-1",
                    "data": "%s",
                    "attributes": {
                      "eventType": "notification.requested.v1",
                      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                      "tracestate": "vendor=value"
                    }
                  }
                }
                """.formatted(data));
    }
}
