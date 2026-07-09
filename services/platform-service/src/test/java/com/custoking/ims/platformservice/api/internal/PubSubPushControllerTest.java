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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
