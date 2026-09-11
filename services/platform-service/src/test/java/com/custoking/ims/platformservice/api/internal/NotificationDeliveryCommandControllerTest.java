package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService;
import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService.DeliverNowCommand;
import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService.DeliveryAnswer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/v1/internal/notifications/deliveries} is a service-to-service route: Cloud Run
 * IAM (OIDC) at the ingress plus the shared {@code X-Notification-Service-Token}, the same secret
 * that gates {@code /api/v1/notifications/logs}.
 */
class NotificationDeliveryCommandControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NotificationDeliveryCommandService service = mock(NotificationDeliveryCommandService.class);
    private final NotificationDeliveryCommandController controller =
            new NotificationDeliveryCommandController(service, mapper, "status-token");

    @Test
    void validTokenDeliversAndReturnsTheAnswer() {
        when(service.deliverNow(any())).thenReturn(new DeliveryAnswer("school-core:absentee:row-1",
                "DELIVERED", true, "logging", 1, null, null));

        Map<String, Object> body = controller.deliver("status-token", envelope());

        ArgumentCaptor<DeliverNowCommand> captor = ArgumentCaptor.forClass(DeliverNowCommand.class);
        verify(service).deliverNow(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("school-core:absentee:row-1");
        assertThat(captor.getValue().eventType()).isEqualTo("notification.requested.v1");
        assertThat(captor.getValue().aggregateType()).isEqualTo("AbsenteeNotification");
        assertThat(captor.getValue().aggregateId()).isEqualTo("row-1");
        assertThat(captor.getValue().payload().path("channel").asText()).isEqualTo("WHATSAPP");
        assertThat(body).containsEntry("eventId", "school-core:absentee:row-1")
                .containsEntry("status", "DELIVERED")
                .containsEntry("dryRun", true)
                .containsEntry("provider", "logging")
                .containsEntry("attempts", 1);
    }

    @Test
    void missingOrWrongTokenIsUnauthorizedBeforeAnythingIsPersisted() {
        assertThatThrownBy(() -> controller.deliver(null, envelope()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> controller.deliver("wrong", envelope()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
        verify(service, never()).deliverNow(any());
    }

    @Test
    void unconfiguredTokenFailsClosed() {
        NotificationDeliveryCommandController unconfigured = new NotificationDeliveryCommandController(service, mapper, "");

        assertThatThrownBy(() -> unconfigured.deliver("", envelope()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
        verify(service, never()).deliverNow(any());
    }

    @Test
    void envelopeWithoutEventIdOrPayloadIsBadRequest() {
        JsonNode noEventId = mapper.readTree("{\"eventType\":\"notification.requested.v1\",\"payload\":{}}");
        JsonNode noPayload = mapper.readTree("{\"eventId\":\"x\",\"eventType\":\"notification.requested.v1\"}");

        assertThatThrownBy(() -> controller.deliver("status-token", noEventId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> controller.deliver("status-token", noPayload))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(400));
        verify(service, never()).deliverNow(any());
    }

    private JsonNode envelope() {
        return mapper.readTree("""
                {"eventId":"school-core:absentee:row-1","eventType":"notification.requested.v1",
                 "eventKey":"AbsenteeNotification:row-1","aggregateType":"AbsenteeNotification","aggregateId":"row-1",
                 "payload":{"channel":"WHATSAPP","destination":"919999999999"}}
                """);
    }
}
