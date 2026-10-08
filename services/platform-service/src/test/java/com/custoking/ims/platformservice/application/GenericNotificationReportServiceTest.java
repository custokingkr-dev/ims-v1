package com.custoking.ims.platformservice.application;
import com.custoking.ims.platformservice.persistence.GenericNotificationReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.OffsetDateTime;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GenericNotificationReportServiceTest {
    private GenericNotificationReport report(GenericNotificationReport.Status status){return new GenericNotificationReport(10,"synthetic-event","a".repeat(64),NotificationSubmissionResult.correlationId("synthetic-event"),"claimed-provider",status,OffsetDateTime.parse("2026-10-08T12:00:00Z"),"b".repeat(64));}
    private NotificationReportAuthority.VerifiedReporter reporter(){return new NotificationReportAuthority(t->Optional.of("reports@synthetic.iam.gserviceaccount.com"),true,"r".repeat(40),"s".repeat(40),"p".repeat(40),"reports@synthetic.iam.gserviceaccount.com","gateway@synthetic.iam.gserviceaccount.com").verify("Bearer synthetic-signed","r".repeat(40));}
    @Test void onlyVerifiedOperatorAcceptedAssertionCanRecordUnverifiedEvidence() {
        var repository=mock(GenericNotificationReportRepository.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service=new GenericNotificationReportService(repository,manager);var accepted=report(GenericNotificationReport.Status.ACCEPTED);
        service.reconcile(accepted,reporter());
        var captured=org.mockito.ArgumentCaptor.forClass(GenericNotificationUnknownEvidence.class);verify(repository).recordUnknownAssertion(captured.capture());
        assertThat(captured.getValue().reporterServiceAccount()).isEqualTo("reports@synthetic.iam.gserviceaccount.com");assertThat(captured.getValue().toString()).contains("UNVERIFIED").doesNotContain("claimed-provider","reports@");
        reset(repository);service.reconcile(accepted);verify(repository,never()).recordUnknownAssertion(any());
        reset(repository);service.reconcile(report(GenericNotificationReport.Status.DELIVERED),reporter());verify(repository,never()).recordUnknownAssertion(any());
        reset(repository);when(repository.reconcile(accepted)).thenReturn(true);service.reconcile(accepted,reporter());verify(repository,never()).recordUnknownAssertion(any());
        assertThatThrownBy(()->service.reconcile(accepted,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reporterCannotBeAssertedWithSharedAuthorityOrInvalidPurposeCaller() {
        var authority=new NotificationReportAuthority(t->Optional.of("gateway@synthetic.iam.gserviceaccount.com"),true,"r".repeat(40),"s".repeat(40),"p".repeat(40),"reports@synthetic.iam.gserviceaccount.com","gateway@synthetic.iam.gserviceaccount.com");
        assertThatThrownBy(()->authority.verify("Bearer signed","r".repeat(40))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->authority.verify("Bearer signed","s".repeat(40))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
