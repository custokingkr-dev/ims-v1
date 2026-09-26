package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Prepared;
import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Result;
import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BroadcastLiveWorkerTest {
    private static final String HASH = "a".repeat(64);
    private static final UUID BROADCAST = UUID.fromString("847cd80f-c6fb-4d78-a13a-e667b0b316d3");
    private static final String EVENT = "broadcast:" + BROADCAST + ":1:EMAIL";
    private final QueuedRecipient row = new QueuedRecipient(UUID.randomUUID(), BROADCAST, 1, 1, "EMAIL", EVENT,
            "g1", HASH, 0, "Synthetic", "Private body must not escape", "LIVE");
    private final Prepared prepared = new Prepared("EMAIL", "ims" + "b".repeat(48), HASH, "c".repeat(64), "d".repeat(64), "private body");

    @Test void reservationCommitPrecedesProviderIoAndOutcomeRunsInSeparateTransaction() {
        var f = fixture();
        when(f.ledger.reserve(row, prepared)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.transactions.commits).isZero();
            return true;
        });
        when(f.provider.submit(prepared)).thenAnswer(invocation -> {
            assertThat(f.transactions.commits).isEqualTo(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new Result("ACCEPTED", "provider-id", "PROVIDER_QUEUED");
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(f.transactions.commits).isEqualTo(1);
            return null;
        }).when(f.ledger).finish(eq(row), eq(prepared), any());
        assertThat(f.worker.drainBatch()).isEqualTo(1);
        var order = inOrder(f.ledger, f.provider);
        order.verify(f.provider).prepare(row, allowed());
        order.verify(f.ledger).reserve(row, prepared);
        order.verify(f.provider).submit(prepared);
        order.verify(f.ledger).finish(row, prepared, new Result("ACCEPTED", "provider-id", "PROVIDER_QUEUED"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void failedReservationCommitNeverReachesProvider() {
        var f = fixture(); f.transactions.failCommit = true;
        assertThatThrownBy(f.worker::drainBatch).hasMessage("Synthetic commit failure");
        verify(f.ledger).reserve(row, prepared);
        verify(f.provider, never()).submit(any());
        verify(f.ledger, never()).finish(any(), any(), any());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void uncertainProviderExceptionBecomesUnknownAndExistingReservationPreventsResend() {
        var f = fixture();
        // Re-offer the row to prove the durable reservation guard also blocks an accidental re-claim.
        when(f.queue.claim(any(), eq("LIVE"))).thenReturn(Optional.of(row), Optional.empty(), Optional.of(row), Optional.empty());
        AtomicBoolean reserved = new AtomicBoolean();
        when(f.ledger.reserve(row, prepared)).thenAnswer(invocation -> !reserved.getAndSet(true));
        when(f.provider.submit(prepared)).thenThrow(new IllegalStateException("guardian@synthetic.invalid private message secret"));
        assertThat(f.worker.drainBatch()).isEqualTo(1);
        assertThat(f.worker.drainBatch()).isEqualTo(1);
        verify(f.provider, times(1)).submit(prepared);
        ArgumentCaptor<Result> captured = ArgumentCaptor.forClass(Result.class);
        verify(f.ledger, times(1)).finish(eq(row), eq(prepared), captured.capture());
        assertThat(captured.getValue()).isEqualTo(new Result("UNKNOWN", null, "PROVIDER_RESULT_UNCONFIRMED"));
        assertThat(captured.getValue().toString()).doesNotContain("guardian@", "secret", "private message");
    }

    @Test void failureToPersistAcceptedOutcomeDoesNotPermitAnotherSubmission() {
        var f = fixture();
        when(f.queue.claim(any(), eq("LIVE"))).thenReturn(Optional.of(row), Optional.of(row), Optional.empty());
        when(f.ledger.reserve(row, prepared)).thenReturn(true, false);
        doThrow(new IllegalStateException("Synthetic persistence failure")).when(f.ledger).finish(any(), any(), any());
        assertThatThrownBy(f.worker::drainBatch).hasMessage("Synthetic persistence failure");
        assertThat(f.worker.drainBatch()).isEqualTo(1);
        verify(f.provider, times(1)).submit(prepared);
    }

    @Test void withdrawnCurrentConsentIsSuppressedBeforePreparationAndReservation() {
        var f = fixture();
        when(f.policy.resolve(anyLong(), any(), any(), any())).thenReturn(List.of(new Recipient(1, 1, "EMAIL", EVENT,
                false, "SCHOOL_COMMUNICATIONS_NOT_GRANTED", "g1", null, null, Map.of())));
        assertThat(f.worker.drainBatch()).isEqualTo(1);
        verify(f.ledger).preparationOutcome(row, "SUPPRESSED", "SCHOOL_COMMUNICATIONS_NOT_GRANTED");
        verify(f.ledger, never()).reserve(any(), any()); verifyNoInteractions(f.provider);
    }

    @Test void policyReasonContainingContactDataIsReplacedWithSafeConstant() {
        var f = fixture();
        when(f.policy.resolve(anyLong(), any(), any(), any())).thenReturn(List.of(new Recipient(1, 1, "EMAIL", EVENT,
                false, "guardian@synthetic.invalid is denied", "g1", null, null, Map.of())));
        f.worker.drainBatch();
        verify(f.ledger).preparationOutcome(row, "SUPPRESSED", "CURRENT_POLICY_DENIED");
        verifyNoInteractions(f.provider);
    }

    @Test void changedGuardianOrDestinationBindingIsSuppressed() {
        for (Recipient changed : List.of(new Recipient(1, 1, "EMAIL", EVENT, true, "ALLOWED", "g2", "other@synthetic.invalid", HASH, Map.of()),
                new Recipient(1, 1, "EMAIL", EVENT, true, "ALLOWED", "g1", "other@synthetic.invalid", "f".repeat(64), Map.of()),
                new Recipient(2, 1, "EMAIL", EVENT, true, "ALLOWED", "g1", "other@synthetic.invalid", HASH, Map.of()))) {
            var f = fixture();
            when(f.policy.resolve(anyLong(), any(), any(), any())).thenReturn(List.of(changed));
            f.worker.drainBatch();
            verify(f.ledger).preparationOutcome(row, "SUPPRESSED", "POLICY_BINDING_CHANGED");
            verifyNoInteractions(f.provider); verify(f.ledger, never()).reserve(any(), any());
        }
    }

    @Test void allowlistAndChannelChangesSuppressWithoutReadingContacts() {
        for (QueuedRecipient changed : List.of(
                new QueuedRecipient(row.id(), BROADCAST, 2, 1, "EMAIL", EVENT, "g1", HASH, 0, "Title", "Message", "LIVE"),
                new QueuedRecipient(row.id(), BROADCAST, 1, 1, "EMAIL", EVENT, "g1", "f".repeat(64), 0, "Title", "Message", "LIVE"),
                new QueuedRecipient(row.id(), BROADCAST, 1, 1, "SMS", EVENT, "g1", HASH, 0, "Title", "Message", "LIVE"))) {
            var f = fixture(); when(f.queue.claim(any(), eq("LIVE"))).thenReturn(Optional.of(changed), Optional.empty());
            f.worker.drainBatch();
            verify(f.ledger).preparationOutcome(changed, "SUPPRESSED", "LIVE_RECIPIENT_NOT_ADMITTED");
            verifyNoInteractions(f.policy, f.provider); verify(f.ledger, never()).reserve(any(), any());
        }
    }

    @Test void unavailableOrIncompletePolicyRetriesOnlyPreparationWithoutReservingOrSending() {
        var unavailable = fixture();
        when(unavailable.policy.resolve(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("private upstream failure"));
        unavailable.worker.drainBatch();
        verify(unavailable.ledger).preparationOutcome(row, "FAILED", "LIVE_PREPARATION_UNAVAILABLE");
        verify(unavailable.ledger, never()).reserve(any(), any()); verifyNoInteractions(unavailable.provider);
        var incomplete = fixture(); when(incomplete.policy.resolve(anyLong(), any(), any(), any())).thenReturn(List.of());
        incomplete.worker.drainBatch();
        verify(incomplete.ledger).preparationOutcome(row, "FAILED", "LIVE_PREPARATION_UNAVAILABLE");
        verify(incomplete.ledger, never()).reserve(any(), any()); verifyNoInteractions(incomplete.provider);
    }

    @Test void disabledPilotDoesNoWorkAndBatchIsCappedAtThree() {
        var disabled = fixture(); when(disabled.workflows.liveQueueEnabled()).thenReturn(false);
        assertThat(disabled.worker.drainBatch()).isZero();
        verifyNoInteractions(disabled.queue, disabled.policy, disabled.provider, disabled.ledger);
        var bounded = fixture(); when(bounded.queue.claim(any(), eq("LIVE"))).thenReturn(Optional.of(row));
        when(bounded.ledger.reserve(row, prepared)).thenReturn(false);
        assertThat(bounded.worker.drainBatch()).isEqualTo(3);
        verify(bounded.queue, times(3)).claim(any(), eq("LIVE")); verify(bounded.provider, never()).submit(any());
    }

    private Recipient allowed() { return new Recipient(1, 1, "EMAIL", EVENT, true, "ALLOWED", "g1", "guardian@synthetic.invalid", HASH, Map.of()); }
    private Fixture fixture() {
        var queue = mock(BroadcastDispatchRepository.class); var ledger = mock(BroadcastLiveRepository.class);
        var policy = mock(BroadcastRecipientPolicy.class); var provider = mock(BroadcastLiveProvider.class);
        var workflows = mock(BroadcastDispatchService.class); var manager = new TrackingTransactions();
        when(workflows.liveQueueEnabled()).thenReturn(true);
        when(queue.claim(any(), eq("LIVE"))).thenReturn(Optional.of(row), Optional.empty());
        when(policy.resolve(1, BROADCAST, List.of("EMAIL"), List.of(1L))).thenReturn(List.of(allowed()));
        when(provider.prepare(row, allowed())).thenReturn(prepared);
        when(provider.submit(prepared)).thenReturn(new Result("ACCEPTED", "provider-id", "PROVIDER_QUEUED"));
        when(ledger.reserve(row, prepared)).thenReturn(true);
        var configuration = new LiveBroadcastConfiguration(true, "EMAIL", true, "1", HASH, "w".repeat(40));
        return new Fixture(new BroadcastLiveWorker(queue, ledger, policy, provider, workflows, configuration, manager), queue, ledger, policy, provider, workflows, manager);
    }
    private record Fixture(BroadcastLiveWorker worker, BroadcastDispatchRepository queue, BroadcastLiveRepository ledger,
                           BroadcastRecipientPolicy policy, BroadcastLiveProvider provider, BroadcastDispatchService workflows, TrackingTransactions transactions) {}
    /** Real Spring transaction lifecycle, with no database/network needed for worker orchestration. */
    private static final class TrackingTransactions extends AbstractPlatformTransactionManager {
        int commits; boolean failCommit;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new IllegalStateException("Synthetic commit failure");
            commits++;
        }
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
