package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BroadcastDeliveryWorkerTest {
    final UUID broadcast = UUID.randomUUID();
    final String event = "broadcast:" + broadcast + ":1:SMS";
    final String hash = NotificationPolicyGuard.destinationSha256("SMS", "9999999999");
    final BroadcastDispatchRepository repository = mock(BroadcastDispatchRepository.class);
    final BroadcastRecipientPolicy policy = mock(BroadcastRecipientPolicy.class);
    final NotificationDeliveryCommandService delivery = mock(NotificationDeliveryCommandService.class);
    final BroadcastDispatchService workflows = mock(BroadcastDispatchService.class);
    final BroadcastDeliveryWorker worker = new BroadcastDeliveryWorker(repository, policy, delivery, workflows, new ObjectMapper(), mock(PlatformTransactionManager.class));
    QueuedRecipient row(int attempts) { return new QueuedRecipient(UUID.randomUUID(), broadcast, 10, 1, "SMS", event, "guardian", hash, attempts, "Notice", "Message", "DRY_RUN"); }
    Recipient decision(boolean allowed, String destinationHash) {
        var now = OffsetDateTime.now();
        Map<String,Object> evidence = new HashMap<>(Map.of("decision", "ALLOW", "purpose", "SCHOOL_COMMUNICATIONS", "lawfulBasis", "CONSENT", "preference", "ENABLED", "consentEventId", "fresh-consent", "consentNoticeVersion", "v1", "guardianId", "guardian", "schoolId", 10, "studentId", 1));
        evidence.putAll(Map.of("channel", "SMS", "destinationSha256", destinationHash, "sourceEventId", event, "evaluatedAt", now.toString(), "expiresAt", now.plusMinutes(2).toString(), "policyVersion", "guardian-communications.v2"));
        return new Recipient(10, 1, "SMS", event, allowed, allowed ? "ALLOWED" : "SCHOOL_COMMUNICATIONS_NOT_GRANTED", "guardian", "9999999999", destinationHash, evidence);
    }
    @BeforeEach void setup() { when(delivery.dryRun()).thenReturn(true); }
    @Test void revokedConsentSuppressesWithoutCallingProvider() {
        var row = row(0);
        when(policy.resolve(10, broadcast, List.of("SMS"), List.of(1L))).thenReturn(List.of(decision(false, hash)));
        worker.attempt(row);
        verify(repository).outcome(row, "SUPPRESSED", "SCHOOL_COMMUNICATIONS_NOT_GRANTED", null, null);
        verify(delivery, never()).deliverNow(any());
    }
    @Test void changedContactCannotReceivePreviouslyApprovedNotice() {
        var row = row(0);
        when(policy.resolve(10, broadcast, List.of("SMS"), List.of(1L))).thenReturn(List.of(decision(true, "new-contact-hash")));
        worker.attempt(row);
        verify(repository).outcome(row, "SUPPRESSED", "POLICY_BINDING_CHANGED", null, null);
        verify(delivery, never()).deliverNow(any());
    }
    @Test void retryRefreshesEvidenceAndUsesSameInboxKeyWithoutClaimingDelivery() {
        var row = row(2);
        when(policy.resolve(10, broadcast, List.of("SMS"), List.of(1L))).thenReturn(List.of(decision(true, hash)));
        when(delivery.deliverNow(any())).thenReturn(new NotificationDeliveryCommandService.DeliveryAnswer(event, "DELIVERED", true, "logging", 1, null, null));
        worker.attempt(row);
        var request = ArgumentCaptor.forClass(NotificationDeliveryCommandService.DeliverNowCommand.class);
        verify(delivery).deliverNow(request.capture());
        assertThat(request.getValue().eventId()).isEqualTo(event);
        var inbox = new NotificationInboxEvent(); inbox.setEventId(event); inbox.setEventType("notification.requested.v1");
        assertThatCode(() -> new NotificationPolicyGuard().requireAllowed(inbox, request.getValue().payload())).doesNotThrowAnyException();
        verify(repository).outcome(row, "DRY_RUN", null, "logging", null);
    }
    @Test void unavailablePolicyRetriesWithBoundedBackoffThenDeadLettersWithoutLeakingErrors() {
        when(policy.resolve(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("private-contact@example.com"));
        var first = row(0); worker.attempt(first);
        verify(repository).outcome(eq(first), eq("FAILED"), eq("POLICY_OR_DELIVERY_UNAVAILABLE"), isNull(), argThat(time -> time.isAfter(OffsetDateTime.now().plusSeconds(20))));
        var last = row(7); worker.attempt(last);
        verify(repository).outcome(last, "DEAD_LETTER", "POLICY_OR_DELIVERY_UNAVAILABLE", null, null);
        verify(delivery, never()).deliverNow(any());
    }
    @Test void switchingProviderToLiveCannotExecuteAnExistingDryRunQueue() {
        when(delivery.dryRun()).thenReturn(false);
        worker.attempt(row(0));
        verifyNoInteractions(policy);
        verify(delivery, never()).deliverNow(any());
        when(workflows.queueEnabled()).thenReturn(false);
        assertThat(worker.drainBatch()).isZero();
    }
}
