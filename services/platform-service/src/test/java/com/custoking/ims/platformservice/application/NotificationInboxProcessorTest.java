package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttemptRepository;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import com.custoking.ims.platformservice.observability.TraceContextBridge;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationInboxProcessorTest {

    private final NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
    private final NotificationDeliveryAttemptRepository attempts = mock(NotificationDeliveryAttemptRepository.class);
    private final NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
    private final NotificationInboxProcessor processor = new NotificationInboxProcessor(
            inbox, attempts, delivery, new ObjectMapper(), TraceContextBridge.noop(),
            "msg91", 3, Duration.ofSeconds(30), Duration.ofMinutes(5));

    @Test
    void failureSchedulesExponentialRetry() {
        NotificationInboxEvent event = event();
        doThrow(new IllegalStateException("provider unavailable")).when(delivery).deliver(event);

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class);

        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(event.getLastAttemptAt().plusSeconds(29));
        assertThat(event.getDeadLetteredAt()).isNull();
        verify(inbox).save(event);
        verify(attempts).save(any());
    }

    @Test
    void finalFailureMovesEventToDeadLetter() {
        NotificationInboxEvent event = event();
        event.setAttemptCount(2);
        doThrow(new IllegalStateException("provider unavailable")).when(delivery).deliver(event);

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class);

        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_DEAD_LETTER);
        assertThat(event.getAttemptCount()).isEqualTo(3);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getDeadLetteredAt()).isNotNull();
    }

    @Test
    void successClearsRetrySchedule() {
        NotificationInboxEvent event = event();
        event.setAttemptCount(1);
        event.setStatus(NotificationInboxEvent.STATUS_FAILED);
        event.setNextAttemptAt(java.time.OffsetDateTime.now().minusMinutes(1));

        processor.process(event);

        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_PROCESSED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getProcessedAt()).isNotNull();
    }

    private NotificationInboxEvent event() {
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("event-1");
        event.setEventType("attendance.absentee.notification.v1");
        event.setPayload("{\"channel\":\"SMS\",\"mobile\":\"919999999999\"}");
        return event;
    }
}
