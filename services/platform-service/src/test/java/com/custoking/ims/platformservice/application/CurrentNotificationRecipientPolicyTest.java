package com.custoking.ims.platformservice.application;
import com.custoking.ims.platformservice.infrastructure.SchoolCoreBroadcastRecipientPolicy;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentNotificationRecipientPolicyTest {
    final ObjectMapper mapper=new ObjectMapper();
    final SchoolCoreBroadcastRecipientPolicy owner=mock(SchoolCoreBroadcastRecipientPolicy.class);
    final CurrentNotificationRecipientPolicy policy=new CurrentNotificationRecipientPolicy(owner,mapper);
    final String hash=NotificationPolicyGuard.destinationSha256("SMS","919999999999");
    Map<String,Object> evidence() {
        var e=new LinkedHashMap<String,Object>();
        e.put("decision","ALLOW");e.put("purpose","SCHOOL_COMMUNICATIONS");e.put("lawfulBasis","CONSENT");e.put("preference","ENABLED");
        e.put("consentEventId","consent-one");e.put("consentNoticeVersion","v1");e.put("guardianId","guardian-one");
        e.put("schoolId",10);e.put("studentId",1);e.put("channel","SMS");e.put("destinationSha256",hash);e.put("sourceEventId","one");
        e.put("evaluatedAt",OffsetDateTime.now().toString());e.put("expiresAt",OffsetDateTime.now().plusMinutes(1).toString());e.put("policyVersion","guardian-communications.v2");return e;
    }
    NotificationInboxEvent event() {
        var payload=new LinkedHashMap<String,Object>();payload.put("sourceEventType","fees.fee-reminder-requested.v1");payload.put("sourceEventId","one");
        payload.put("reminderRequestId","one");payload.put("notificationType","FEE_REMINDER");payload.put("template","fee-reminder.v1");
        payload.put("recipientType","GUARDIAN");payload.put("recipientId","guardian-one");payload.put("schoolId",10);payload.put("studentId",1);
        payload.put("channel","SMS");payload.put("destination","919999999999");payload.put("policyEvidence",evidence());
        var event=new NotificationInboxEvent();event.setEventId("one");event.setEventType("notification.requested.v1");event.setPayload(mapper.writeValueAsString(payload));return event;
    }
    BroadcastRecipientPolicy.Recipient recipient(boolean allowed,String guardian,Map<String,Object> evidence) {
        return new BroadcastRecipientPolicy.Recipient(10,1,"SMS","owner-correlation",allowed,"ALLOW",guardian,"919999999999",hash,evidence);
    }
    @Test void ownerFreshConsentIsRequiredAndReboundOnlyToOriginalEvent() {
        var evidence=evidence();evidence.put("sourceEventId","owner-correlation");
        when(owner.resolveFeeReminders(10,"one",List.of(1L))).thenReturn(List.of(recipient(true,"guardian-one",evidence)));
        policy.requireCurrent(event());
    }
    @Test void ownerUnavailableDeniedOrChangedGuardianCannotAdmitDestination() {
        when(owner.resolveFeeReminders(10,"one",List.of(1L))).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(()->policy.requireCurrent(event())).hasMessageContaining("CURRENT_POLICY_UNAVAILABLE");
        doReturn(List.of(recipient(false,"guardian-one",evidence()))).when(owner).resolveFeeReminders(10,"one",List.of(1L));
        assertThatThrownBy(()->policy.requireCurrent(event())).hasMessageContaining("CURRENT_POLICY_DENIED");
        when(owner.resolveFeeReminders(10,"one",List.of(1L))).thenReturn(List.of(recipient(true,"new-guardian",evidence())));
        assertThatThrownBy(()->policy.requireCurrent(event())).hasMessageContaining("CURRENT_POLICY_RECIPIENT_CHANGED");
    }
    @Test void expiredOwnerEvidenceCannotOverrideValidProducerEvidence() {
        var evidence=evidence();evidence.put("evaluatedAt",OffsetDateTime.now().minusMinutes(4).toString());evidence.put("expiresAt",OffsetDateTime.now().minusMinutes(3).toString());
        when(owner.resolveFeeReminders(10,"one",List.of(1L))).thenReturn(List.of(recipient(true,"guardian-one",evidence)));
        assertThatThrownBy(()->policy.requireCurrent(event())).hasMessageContaining("POLICY_EVIDENCE_STALE");
    }
    @Test void destinationHashMismatchCannotAdmitRecipient() {
        when(owner.resolveFeeReminders(10,"one",List.of(1L))).thenReturn(List.of(new BroadcastRecipientPolicy.Recipient(10,1,"SMS","owner",true,"ALLOW","guardian-one","918888888888",hash,evidence())));
        assertThatThrownBy(()->policy.requireCurrent(event())).hasMessageContaining("CURRENT_POLICY_RECIPIENT_CHANGED");
    }
    @Test void absenteeRechecksOriginalRequestDateAndExactMessage() {
        var event=event();String id="school-core:absentee:notice-one";event.setEventId(id);
        var payload=(tools.jackson.databind.node.ObjectNode)mapper.readTree(event.getPayload());
        payload.put("sourceEventType","attendance.absentee-notification-requested.v1");payload.put("sourceEventId",id);
        payload.put("absenteeRequestId",id);payload.put("absenteeNotificationId","notice-one");payload.put("notificationType","ABSENTEE_ALERT");payload.put("template","absentee-alert.v1");
        payload.putObject("variables").put("attendanceDate","2026-10-07").put("message","Synthetic absence");
        ((tools.jackson.databind.node.ObjectNode)payload.get("policyEvidence")).put("sourceEventId",id);
        event.setPayload(mapper.writeValueAsString(payload));
        String messageHash;
        try { messageHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("Synthetic absence".getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var evidence=evidence();evidence.put("sourceEventId",id);evidence.put("notificationCategory","ABSENTEE_ALERT");
        evidence.put("absenteeNotificationId","notice-one");evidence.put("attendanceDate","2026-10-07");evidence.put("messageSha256",messageHash);
        when(owner.resolveAbsentee(10,1,"SMS",id,"notice-one","2026-10-07",messageHash)).thenReturn(List.of(recipient(true,"guardian-one",evidence)));
        policy.requireCurrent(event);
        evidence.put("attendanceDate","2026-10-06");
        assertThatThrownBy(()->policy.requireCurrent(event)).hasMessageContaining("CURRENT_POLICY_ABSENCE_MISMATCH");
        evidence.put("attendanceDate","2026-10-07");evidence.put("messageSha256","a".repeat(64));
        assertThatThrownBy(()->policy.requireCurrent(event)).hasMessageContaining("CURRENT_POLICY_ABSENCE_MISMATCH");
    }
}
