package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService.DeliverNowCommand;
import com.custoking.ims.platformservice.application.NotificationDeliveryCommandService.DeliveryAnswer;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import com.custoking.ims.platformservice.persistence.NotificationInboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Synchronous delivery for a peer that owns the retry loop (school-core's absentee drainer). The
 * command reuses the persisted inbox — row lock, policy guard, provider, delivery-attempt audit —
 * and answers from the inbox row's state, so a replayed eventId is idempotent at this boundary.
 */
class NotificationDeliveryCommandServiceTest {

    private final NotificationInboxRepository inbox = mock(NotificationInboxRepository.class);
    private final NotificationInboxProcessor processor = mock(NotificationInboxProcessor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void newEventIsPersistedProcessedAndAnsweredFromItsFinalState() {
        NotificationDeliveryCommandService service = service("logging", true);
        when(inbox.findById("school-core:absentee:row-1")).thenReturn(Optional.empty());
        ArgumentCaptor<NotificationInboxEvent> saved = ArgumentCaptor.forClass(NotificationInboxEvent.class);
        when(inbox.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            NotificationInboxEvent event = inv.getArgument(0);
            event.setStatus(NotificationInboxEvent.STATUS_PROCESSED);
            event.setAttemptCount(1);
            return null;
        }).when(processor).process(any());

        DeliveryAnswer answer = service.deliverNow(command());

        NotificationInboxEvent event = saved.getValue();
        assertThat(event.getEventId()).isEqualTo("school-core:absentee:row-1");
        assertThat(event.getEventType()).isEqualTo("notification.requested.v1");
        assertThat(event.getAggregateType()).isEqualTo("AbsenteeNotification");
        assertThat(event.getPayload()).contains("\"channel\":\"WHATSAPP\"");
        assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_PROCESSED);
        verify(processor).process(event);
        assertThat(answer.status()).isEqualTo("DELIVERED");
        assertThat(answer.dryRun()).isTrue();
        assertThat(answer.provider()).isEqualTo("logging");
        assertThat(answer.attempts()).isEqualTo(1);
    }

    @Test
    void terminalEventIsAnsweredWithoutAnotherProviderAttempt() {
        NotificationDeliveryCommandService service = service("msg91", false);
        NotificationInboxEvent processed = existing(NotificationInboxEvent.STATUS_PROCESSED);
        processed.setAttemptCount(2);
        when(inbox.findById("school-core:absentee:row-1")).thenReturn(Optional.of(processed));

        DeliveryAnswer answer = service.deliverNow(command());

        verify(processor, never()).process(any());
        verify(inbox, never()).save(any());
        assertThat(answer.status()).isEqualTo("DELIVERED");
        assertThat(answer.dryRun()).isFalse();
        assertThat(answer.provider()).isEqualTo("msg91");
        assertThat(answer.attempts()).isEqualTo(2);
    }

    @Test
    void retryRefreshesThePayloadAndClearsTheInboxBackoffBecauseTheCallerPacesRetries() {
        NotificationDeliveryCommandService service = service("logging", true);
        NotificationInboxEvent failed = existing(NotificationInboxEvent.STATUS_FAILED);
        failed.setPayload("{\"stale\":true}");
        failed.setAttemptCount(1);
        failed.setNextAttemptAt(OffsetDateTime.now().plusMinutes(30));
        when(inbox.findById("school-core:absentee:row-1")).thenReturn(Optional.of(failed));
        when(inbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            NotificationInboxEvent event = inv.getArgument(0);
            assertThat(event.getNextAttemptAt()).isNull();
            assertThat(event.getStatus()).isEqualTo(NotificationInboxEvent.STATUS_RECEIVED);
            assertThat(event.getPayload()).contains("\"channel\":\"WHATSAPP\"").doesNotContain("stale");
            event.setStatus(NotificationInboxEvent.STATUS_PROCESSED);
            event.setAttemptCount(2);
            return null;
        }).when(processor).process(failed);

        DeliveryAnswer answer = service.deliverNow(command());

        verify(processor).process(failed);
        assertThat(answer.status()).isEqualTo("DELIVERED");
        assertThat(answer.attempts()).isEqualTo(2);
    }

    @Test
    void providerFailureIsAnsweredAsFailedWithTheRecordedError() {
        NotificationDeliveryCommandService service = service("msg91", true);
        when(inbox.findById("school-core:absentee:row-1")).thenReturn(Optional.empty());
        when(inbox.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(inv -> {
            NotificationInboxEvent event = inv.getArgument(0);
            event.setStatus(NotificationInboxEvent.STATUS_FAILED);
            event.setAttemptCount(1);
            event.setLastError("MSG91 delivery failed for event school-core:absentee:row-1");
            event.setNextAttemptAt(OffsetDateTime.now().plusSeconds(30));
            throw new NotificationDeliveryFailedException(event.getEventId(), new IllegalStateException("boom"));
        }).when(processor).process(any());

        DeliveryAnswer answer = service.deliverNow(command());

        assertThat(answer.status()).isEqualTo("FAILED");
        assertThat(answer.error()).isEqualTo("MSG91 delivery failed for event school-core:absentee:row-1");
        assertThat(answer.dryRun()).isTrue();
    }

    @Test
    void policySuppressionAndDeadLetterAreAnsweredAsSuch() {
        NotificationDeliveryCommandService service = service("logging", true);
        NotificationInboxEvent suppressed = existing(NotificationInboxEvent.STATUS_SUPPRESSED);
        suppressed.setLastError("POLICY_EVIDENCE_STALE");
        NotificationInboxEvent dead = existing(NotificationInboxEvent.STATUS_DEAD_LETTER);
        dead.setLastError("provider unavailable");
        when(inbox.findById("school-core:absentee:row-1")).thenReturn(Optional.of(suppressed), Optional.of(dead));

        DeliveryAnswer suppressedAnswer = service.deliverNow(command());
        DeliveryAnswer deadAnswer = service.deliverNow(command());

        assertThat(suppressedAnswer.status()).isEqualTo("SUPPRESSED");
        assertThat(suppressedAnswer.error()).isEqualTo("POLICY_EVIDENCE_STALE");
        assertThat(deadAnswer.status()).isEqualTo("DEAD_LETTER");
        assertThat(deadAnswer.error()).isEqualTo("provider unavailable");
        verify(processor, never()).process(any());
    }

    @Test
    void dryRunIsOnlyFalseForMsg91WithDryRunDisabled() {
        assertThat(service("logging", false).dryRun()).isTrue();
        assertThat(service("msg91", true).dryRun()).isTrue();
        assertThat(service("msg91", false).dryRun()).isFalse();
    }

    private NotificationDeliveryCommandService service(String provider, boolean msg91DryRun) {
        return new NotificationDeliveryCommandService(inbox, processor, objectMapper, provider, msg91DryRun);
    }

    private static NotificationInboxEvent existing(String status) {
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("school-core:absentee:row-1");
        event.setEventType("notification.requested.v1");
        event.setPayload("{}");
        event.setStatus(status);
        return event;
    }

    private DeliverNowCommand command() {
        return new DeliverNowCommand("school-core:absentee:row-1", "notification.requested.v1",
                "AbsenteeNotification:row-1", "AbsenteeNotification", "row-1",
                objectMapper.readTree("{\"channel\":\"WHATSAPP\",\"destination\":\"919999999999\"}"));
    }
}
