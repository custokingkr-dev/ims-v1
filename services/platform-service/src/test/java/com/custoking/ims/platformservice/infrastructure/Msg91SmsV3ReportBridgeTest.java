package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.*;
import com.custoking.ims.platformservice.persistence.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class Msg91SmsV3ReportBridgeTest {
    private final GenericAcceptedReportBindingRepository bindings=mock(GenericAcceptedReportBindingRepository.class);
    private final GenericNotificationReportRepository reports=mock(GenericNotificationReportRepository.class);
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final String event="synthetic-bridge",token="r".repeat(40);
    private final OffsetDateTime at=OffsetDateTime.parse("2026-10-08T12:00:00Z");
    private String payload(){return "{\"schoolId\":10,\"template\":\"synthetic\",\"channel\":\"SMS\",\"recipientType\":\"PARENT\",\"recipientId\":\"301\",\"destination\":\"919000000001\"}";}
    private String hash(){return NotificationSubmissionResult.requestSha256(new NotificationDeliveryRequest(event,"synthetic","SMS","PARENT","301",payload()));}
    private GenericAcceptedReportBindingRepository.Binding binding(String payload,String hash){return new GenericAcceptedReportBindingRepository.Binding(hash,NotificationSubmissionResult.correlationId(event),"synthetic-provider",at,payload);}
    private byte[] raw(){return ("{\"date\":\"2026-10-08 12:00:00\",\"number\":\"919000000001\",\"senderId\":\"SYNTH\",\"amount\":\"0.25\",\"requestId\":\"synthetic-provider\",\"INMSID\":\"synthetic\",\"CRQID\":\""+NotificationSubmissionResult.correlationId(event)+"\",\"credit\":\"1\",\"userId\":\"synthetic-user\",\"campaignName\":\"synthetic\",\"status\":\"1\",\"desc\":\"DELIVERED\"}").getBytes(StandardCharsets.UTF_8);}
    private Msg91SmsV3ReportBridge bridge(boolean enabled,String caller){
        var authority=new NotificationReportAuthority(t->Optional.of(caller),enabled,token,"s".repeat(40),"p".repeat(40),"reports@synthetic.iam.gserviceaccount.com","gateway@synthetic.iam.gserviceaccount.com");
        return new Msg91SmsV3ReportBridge(authority,bindings,reports,manager,new Msg91SmsV3ReportCodec.Profile("synthetic-user","SYNTH",ZoneOffset.UTC));
    }
    @BeforeEach void setup(){when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());}
    @Test void authenticatedRawReportUsesOnlyServerAcceptedBinding() {
        when(bindings.find(10,event)).thenReturn(Optional.of(binding(payload(),hash())));when(reports.reconcile(any())).thenReturn(true);
        assertThat(bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).isTrue();
        var captured=org.mockito.ArgumentCaptor.forClass(GenericNotificationReport.class);verify(reports).reconcile(captured.capture());
        assertThat(captured.getValue().requestSha256()).isEqualTo(hash());assertThat(captured.getValue().status()).isEqualTo(GenericNotificationReport.Status.DELIVERED);
        verify(reports,never()).recordUnknownAssertion(any());verify(manager).commit(any());
    }
    @Test void disabledWrongTokenAndForbiddenCallerFailBeforeBodyAndDatabase() {
        for(var b:new Msg91SmsV3ReportBridge[]{bridge(false,"reports@synthetic.iam.gserviceaccount.com"),bridge(true,"gateway@synthetic.iam.gserviceaccount.com")})
            assertThatThrownBy(()->b.reconcile("Bearer signed",token,10,event,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed","wrong",10,event,raw())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(bindings,reports);verify(manager,never()).getTransaction(any());
    }
    @Test void absentUnknownForeignOrSuppressedBindingCannotSelfAuthorize() {
        when(bindings.find(10,event)).thenReturn(Optional.empty());
        assertThat(bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).isFalse();
        verifyNoInteractions(reports);
    }
    @Test void changedStoredRequestForeignSchoolAndChannelAreRejectedBeforeReconciliation() {
        for(var b:new GenericAcceptedReportBindingRepository.Binding[]{binding(payload(),"a".repeat(64)),binding(payload().replace("10","20"),hash()),binding(payload().replace("SMS","EMAIL"),hash()),binding(payload().replace("10","18446744073709551626"),hash())}){
            when(bindings.find(10,event)).thenReturn(Optional.of(b));
            assertThatThrownBy(()->bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).hasMessage("Unrecognized SMS-v3 report binding");
        }
        verifyNoInteractions(reports);
    }
    @Test void malformedOversizedAndWrongProviderReportsCannotWrite() {
        when(bindings.find(10,event)).thenReturn(Optional.of(binding(payload(),hash())));
        for(byte[] bytes:new byte[][]{new byte[8193],"{}{}".getBytes(StandardCharsets.UTF_8),new String(raw(),StandardCharsets.UTF_8).replace("synthetic-provider","foreign-provider").getBytes(StandardCharsets.UTF_8)})
            assertThatThrownBy(()->bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,bytes)).hasMessage("Unrecognized SMS-v3 report binding");
        verifyNoInteractions(reports);
    }
    @Test void erasureRejectionAndDatabaseFailureRemainAuthoritative() {
        when(bindings.find(10,event)).thenReturn(Optional.of(binding(payload(),hash())));
        assertThat(bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).isFalse();
        when(reports.reconcile(any())).thenThrow(new IllegalStateException("bounded database failure"));
        assertThatThrownBy(()->bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).hasMessage("bounded database failure");
        verify(manager).rollback(any());
    }
    @Test void routingAliasesAndPassthroughCannotClaimCanonicalRecipientBinding() {
        for(String addition:new String[]{"\"mobile\":\"919000000002\"", "\"phone\":\"919000000002\"", "\"to\":\"919000000002\"", "\"recipientMobile\":\"919000000002\"", "\"mobile\":\"919000000001\"", "\"mobile\":null", "\"msg91Body\":{\"recipients\":[{\"mobiles\":\"919000000002\"}]}"}) {
            String changed=payload().replaceFirst("\\{", "{"+addition+",");
            String hash=NotificationSubmissionResult.requestSha256(new NotificationDeliveryRequest(event,"synthetic","SMS","PARENT","301",changed));
            when(bindings.find(10,event)).thenReturn(Optional.of(binding(changed,hash)));
            assertThatThrownBy(()->bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).hasMessage("Unrecognized SMS-v3 report binding");
        }
        verifyNoInteractions(reports);
    }
    @Test void noncanonicalDestinationCannotBeNormalizedIntoAReceiptBinding() {
        for(String destination:new String[]{"+91 9000000001","919000000001x","919000000001 ","????????????"}) {
            String changed=payload().replace("919000000001",destination);
            String hash=NotificationSubmissionResult.requestSha256(new NotificationDeliveryRequest(event,"synthetic","SMS","PARENT","301",changed));
            when(bindings.find(10,event)).thenReturn(Optional.of(binding(changed,hash)));
            assertThatThrownBy(()->bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).hasMessage("Unrecognized SMS-v3 report binding");
        }
        verifyNoInteractions(reports);
    }
    @Test void nullPassthroughUsesOnlyCanonicalDestination() {
        String changed=payload().replaceFirst("\\{","{\"msg91Body\":null,");
        String hash=NotificationSubmissionResult.requestSha256(new NotificationDeliveryRequest(event,"synthetic","SMS","PARENT","301",changed));
        when(bindings.find(10,event)).thenReturn(Optional.of(binding(changed,hash)));when(reports.reconcile(any())).thenReturn(true);
        assertThat(bridge(true,"reports@synthetic.iam.gserviceaccount.com").reconcile("Bearer signed",token,10,event,raw())).isTrue();
    }
}
