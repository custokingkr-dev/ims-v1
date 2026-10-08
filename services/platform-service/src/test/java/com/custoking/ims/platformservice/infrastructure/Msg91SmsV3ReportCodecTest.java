package com.custoking.ims.platformservice.infrastructure;
import com.custoking.ims.platformservice.application.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class Msg91SmsV3ReportCodecTest {
    private final Msg91SmsV3ReportCodec codec=new Msg91SmsV3ReportCodec();
    private final Msg91SmsV3ReportCodec.Profile profile=new Msg91SmsV3ReportCodec.Profile("synthetic-user","SYNTH",ZoneOffset.ofHoursMinutes(5,30));
    private final GenericNotificationReport accepted=new GenericNotificationReport(10,"synthetic-event","a".repeat(64),NotificationSubmissionResult.correlationId("synthetic-event"),"synthetic-provider",GenericNotificationReport.Status.ACCEPTED,OffsetDateTime.parse("2026-10-08T12:00:00Z"),"b".repeat(64));
    private Msg91SmsV3ReportCodec.AcceptedBinding binding() throws Exception{return new Msg91SmsV3ReportCodec.AcceptedBinding(accepted,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("919000000001".getBytes(StandardCharsets.UTF_8))));}
    private String body(){return """
        {"date":"2026-10-08 17:30:00","number":"919000000001","senderId":"SYNTH","amount":"0.25","requestId":"synthetic-provider","INMSID":"synthetic-inms","CRQID":"%s","credit":"1","userId":"synthetic-user","campaignName":"synthetic","status":"1","desc":"DELIVERED"}
        """.formatted(accepted.correlationId());}
    private GenericNotificationReport decode(String body)throws Exception{return codec.candidate(body.getBytes(StandardCharsets.UTF_8),profile,binding());}
    @Test void explicitProfileProducesOnlyCandidateForExistingBinding()throws Exception {
        var candidate=decode(body());assertThat(candidate.status()).isEqualTo(GenericNotificationReport.Status.DELIVERED);assertThat(candidate.occurredAt().toInstant()).isEqualTo(Instant.parse("2026-10-08T12:00:00Z"));
        assertThat(candidate.eventId()).isEqualTo(accepted.eventId());assertThat(candidate.providerRequestId()).isEqualTo(accepted.providerRequestId());
        assertThat(decode(body().replace("\"1\",\"desc\":\"DELIVERED\"","\"2\",\"desc\":\"FAILED\"")).status()).isEqualTo(GenericNotificationReport.Status.DELIVERY_FAILED);
        assertThat(candidate.toString()).doesNotContain("919000000001","synthetic-event");assertThat(profile.toString()).doesNotContain("synthetic-user");
    }
    @Test void mismatchedBindingAccountDestinationAndStatusFailWithoutRawInput()throws Exception {
        for(String changed:List.of(body().replace("synthetic-provider","foreign-provider"),body().replace("synthetic-user","foreign-user"),body().replace("919000000001","919000000002"),body().replace("SYNTH","WRONG"),body().replace(accepted.correlationId(),"wrong"),body().replace("DELIVERED","QUEUED"),body().replace("2026-10-08","2026-02-30")))
            assertThatThrownBy(()->decode(changed)).hasMessage("Unrecognized SMS-v3 report; no status authority");
    }
    @Test void duplicateUnknownFieldsTrailingJsonAndTypesAreRejected()throws Exception {
        for(String bad:List.of(body().replace("\"date\":","\"status\":\"2\",\"date\":"),body().replace("\"date\":","\"extra\":\"x\",\"date\":"),body()+"{}",body().replace("\"status\":\"1\"","\"status\":1"),"[]","{\"requestId\":\"email\"}")) assertThatThrownBy(()->decode(bad)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->decode(" ".repeat(8193))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingProfileTimezoneAndUncertainBindingCannotCreateCandidates() {
        assertThatThrownBy(()->new Msg91SmsV3ReportCodec.Profile("synthetic-user","SYNTH",null)).isInstanceOf(IllegalArgumentException.class);
        var uncertain=new GenericNotificationReport(accepted.schoolId(),accepted.eventId(),accepted.requestSha256(),accepted.correlationId(),accepted.providerRequestId(),GenericNotificationReport.Status.DELIVERED,accepted.occurredAt(),accepted.evidenceSha256());
        assertThatThrownBy(()->new Msg91SmsV3ReportCodec.AcceptedBinding(uncertain,"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
}
