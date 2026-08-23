package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttemptRepository;
import com.custoking.ims.platformservice.persistence.NotificationDeliveryAttempt;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import com.custoking.ims.platformservice.observability.TraceContextBridge;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        assertThatThrownBy(() -> process(event)).isInstanceOf(IllegalStateException.class);

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

        assertThatThrownBy(() -> process(event)).isInstanceOf(IllegalStateException.class);

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

        process(event);

        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_PROCESSED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void policySuppressionIsTerminalAndAuditedWithoutRetry() {
        NotificationInboxEvent event = event();
        doThrow(new NotificationSuppressedException("POLICY_EVIDENCE_MISSING"))
                .when(delivery).deliver(event);

        process(event);

        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_SUPPRESSED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getProcessedAt()).isNotNull();
        assertThat(event.getNextAttemptAt()).isNull();
        assertThat(event.getDeadLetteredAt()).isNull();
        assertThat(event.getLastError()).isEqualTo("POLICY_EVIDENCE_MISSING");
        verify(inbox).save(event);
        verify(attempts).save(org.mockito.ArgumentMatchers.argThat(attempt ->
                NotificationDeliveryAttempt.STATUS_SUPPRESSED.equals(attempt.getStatus())
                        && "POLICY_EVIDENCE_MISSING".equals(attempt.getError())));
    }

    @Test
    void retryWithExpiredConsentEvidenceIsTerminallySuppressedBeforeProvider() {
        NotificationDeliveryProvider provider = mock(NotificationDeliveryProvider.class);
        NotificationDeliveryService guardedDelivery = new NotificationDeliveryService(new ObjectMapper(), provider);
        NotificationInboxProcessor guardedProcessor = new NotificationInboxProcessor(
                inbox, attempts, guardedDelivery, new ObjectMapper(), TraceContextBridge.noop(),
                "msg91", 3, Duration.ofSeconds(30), Duration.ofMinutes(5));
        NotificationInboxEvent event = event();
        event.setEventType("notification.requested.v1");
        event.setStatus(NotificationInboxEvent.STATUS_FAILED);
        event.setAttemptCount(1);
        event.setNextAttemptAt(OffsetDateTime.now().minusSeconds(1));
        String hash = NotificationPolicyGuard.destinationSha256("SMS", "919999999999");
        event.setPayload("""
                {"sourceEventType":"fees.fee-reminder-requested.v1","sourceEventId":"event-1",
                 "reminderRequestId":"event-1","notificationType":"FEE_REMINDER","template":"fee-reminder.v1",
                 "channel":"SMS","destination":"919999999999","schoolId":10,"studentId":1,
                 "recipientType":"GUARDIAN","recipientId":"guardian-1","policyEvidence":{
                   "decision":"ALLOW","purpose":"SCHOOL_COMMUNICATIONS","lawfulBasis":"CONSENT",
                   "preference":"ENABLED","consentEventId":"consent-1","consentNoticeVersion":"notice-v1",
                   "guardianId":"guardian-1","schoolId":10,"studentId":1,"channel":"SMS",
                   "destinationSha256":"%s","sourceEventId":"event-1",
                   "evaluatedAt":"%s","expiresAt":"%s","policyVersion":"guardian-communications.v2"}}
                """.formatted(hash, OffsetDateTime.now().minusMinutes(3), OffsetDateTime.now().minusMinutes(1)));
        when(inbox.findByIdForUpdate("event-1")).thenReturn(Optional.of(event));

        guardedProcessor.process(event);

        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_SUPPRESSED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getLastError()).isEqualTo("POLICY_EVIDENCE_STALE");
        verify(provider, org.mockito.Mockito.never()).deliver(any());
    }

    private void process(NotificationInboxEvent event) {
        when(inbox.findByIdForUpdate(event.getEventId())).thenReturn(Optional.of(event));
        processor.process(event);
    }

    private NotificationInboxEvent event() {
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("event-1");
        event.setEventType("attendance.absentee.notification.v1");
        event.setPayload("{\"channel\":\"SMS\",\"mobile\":\"919999999999\"}");
        return event;
    }
}
