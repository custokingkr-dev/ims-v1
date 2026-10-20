package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GenericNotificationSubmissionWorkerTest {
    final NotificationInboxRepository inbox=mock(NotificationInboxRepository.class);
    final NotificationDeliveryAttemptRepository attempts=mock(NotificationDeliveryAttemptRepository.class);
    final GenericNotificationSubmissionRepository ledger=mock(GenericNotificationSubmissionRepository.class);
    final CurrentNotificationRecipientPolicy policy=mock(CurrentNotificationRecipientPolicy.class);
    final NotificationDeliveryService delivery=mock(NotificationDeliveryService.class);
    final org.springframework.transaction.PlatformTransactionManager manager=new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition){}
        protected void doCommit(DefaultTransactionStatus status){}
        protected void doRollback(DefaultTransactionStatus status){}
    };
    final GenericNotificationSubmissionWorker worker=new GenericNotificationSubmissionWorker(inbox,attempts,ledger,policy,delivery,new ObjectMapper(),manager);
    NotificationInboxEvent event() {
        var event=new NotificationInboxEvent();event.setEventId("one");event.setEventType("notification.requested.v1");
        event.setPayload("{\"schoolId\":10,\"channel\":\"SMS\"}");when(inbox.findByIdForUpdate("one")).thenReturn(Optional.of(event));
        when(ledger.reserve(eq("one"),eq(10L),anyString())).thenReturn(true);return event;
    }
    @Test void reservationIsCommittedBeforeIoAndTimeoutNeverResends() {
        var event=event();
        doAnswer(call->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();assertThat(event.getStatus()).isEqualTo("SUBMITTING");throw new IllegalStateException("timeout");}).when(delivery).submit(event);
        worker.process("one");worker.process("one");
        assertThat(event.getStatus()).isEqualTo("UNKNOWN");verify(delivery,times(1)).submit(event);
        verify(ledger).finish(eq("one"),eq(10L),anyString(),eq("UNKNOWN"),anyString(),isNull(),anyString());
    }
    @Test void unavailableCurrentOwnerSuppressesBeforeReservationOrIo() {
        var event=event();doThrow(new NotificationSuppressedException("CURRENT_POLICY_UNAVAILABLE")).when(policy).requireCurrent(event);
        worker.process("one");assertThat(event.getStatus()).isEqualTo("SUPPRESSED");verifyNoInteractions(ledger,delivery);
    }
    @Test void revokedBetweenReservationAndIoCannotSendOrRetry() {
        var event=event();doNothing().doThrow(new NotificationSuppressedException("CURRENT_POLICY_DENIED")).when(policy).requireCurrent(event);
        worker.process("one");worker.process("one");assertThat(event.getStatus()).isEqualTo("UNKNOWN");verifyNoInteractions(delivery);
    }
    @Test void existingLedgerReservationCannotSendAfterInboxReset() {
        var event=event();when(ledger.reserve(eq("one"),eq(10L),anyString())).thenReturn(false);
        worker.process("one");verifyNoInteractions(delivery);assertThat(event.getAttemptCount()).isZero();
    }
    @Test void missingTypedReceiptDoesNotInventAcceptanceOrTriggerResend() {
        var event=event();worker.process("one");worker.process("one");
        assertThat(event.getStatus()).isEqualTo("UNKNOWN");verify(delivery,times(1)).submit(event);
        verify(ledger).finish(eq("one"),eq(10L),anyString(),eq("UNKNOWN"),anyString(),isNull(),anyString());
    }
    @Test void boundAcceptedAndRejectedResultsAreTerminalAndNeverClaimDelivery() {
        for(var status:java.util.List.of(NotificationSubmissionResult.Status.ACCEPTED,NotificationSubmissionResult.Status.REJECTED)) {
            var event=event();
            var request=NotificationDeliveryService.request(event,new ObjectMapper());
            when(delivery.submit(event)).thenReturn(NotificationSubmissionResult.of(request,status,
                status==NotificationSubmissionResult.Status.ACCEPTED?"5762846b4f8d285d378b4567":null,"PROVIDER_REQUEST_ACCEPTED"));
            worker.process("one");worker.process("one");
            assertThat(event.getStatus()).isEqualTo(status.name()).isNotEqualTo("PROCESSED");
            verify(delivery,times(1)).submit(event);
        }
    }
    @Test void foreignReceiptOrConcurrentErasureCannotMarkEventAccepted() {
        var event=event();
        var foreign=new NotificationDeliveryRequest("foreign",null,"SMS",null,null,event.getPayload());
        when(delivery.submit(event)).thenReturn(NotificationSubmissionResult.of(foreign,NotificationSubmissionResult.Status.ACCEPTED,"5762846b4f8d285d378b4567","PROVIDER_REQUEST_ACCEPTED"));
        worker.process("one");assertThat(event.getStatus()).isEqualTo("UNKNOWN");
        event.setStatus("RECEIVED");
        when(delivery.submit(event)).thenAnswer(call->{event.setStatus("SUPPRESSED");return NotificationSubmissionResult.of(NotificationDeliveryService.request(event,new ObjectMapper()),NotificationSubmissionResult.Status.ACCEPTED,"5762846b4f8d285d378b4567","PROVIDER_REQUEST_ACCEPTED");});
        worker.process("one");assertThat(event.getStatus()).isEqualTo("SUPPRESSED");
    }
}
