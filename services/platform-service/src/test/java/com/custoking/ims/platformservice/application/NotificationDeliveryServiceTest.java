package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NotificationDeliveryServiceTest {

    private final NotificationDeliveryProvider provider = mock(NotificationDeliveryProvider.class);
    private final NotificationDeliveryService service = new NotificationDeliveryService(new ObjectMapper(), provider);

    @Test
    void guardianDeliveryWithoutPolicyEvidenceIsSuppressedBeforeProviderCall() {
        NotificationInboxEvent event = event("""
                {"sourceEventType":"fees.fee-reminder-requested.v1","sourceEventId":"event-1",
                 "reminderRequestId":"event-1","notificationType":"FEE_REMINDER",
                 "template":"fee-reminder.v1","channel":"SMS","destination":"919999999999",
                 "schoolId":10,"studentId":1,"recipientType":"GUARDIAN","recipientId":"guardian-1"}
                """);

        assertThatThrownBy(() -> service.deliver(event))
                .isInstanceOf(NotificationSuppressedException.class)
                .hasMessageContaining("POLICY_EVIDENCE_MISSING");
        verify(provider, never()).deliver(any());
    }

    @Test
    void guardianDeliveryWithMatchingAuthoritativeEvidenceReachesProvider() {
        NotificationInboxEvent event = event(validPayload("GUARDIAN", 10, 10,
                "919999999999", "919999999999", OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now().plusSeconds(60)));

        service.deliver(event);

        verify(provider).deliver(any());
    }

    @Test
    void relabelingGuardianDestinationAsAnotherRecipientTypeFailsClosed() {
        NotificationInboxEvent event = event(validPayload("STUDENT", 10, 10,
                "919999999999", "919999999999", OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now().plusSeconds(60)));

        assertSuppressed(event, "POLICY_RECIPIENT_TYPE_INVALID");
    }

    @Test
    void tenantOrDestinationEvidenceCannotBeTransplanted() {
        NotificationInboxEvent wrongTenant = event(validPayload("GUARDIAN", 11, 10,
                "919999999999", "919999999999", OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now().plusSeconds(60)));
        NotificationInboxEvent wrongDestination = event(validPayload("GUARDIAN", 10, 10,
                "918888888888", "919999999999", OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now().plusSeconds(60)));

        assertSuppressed(wrongTenant, "POLICY_SCHOOL_MISMATCH");
        assertSuppressed(wrongDestination, "POLICY_DESTINATION_MISMATCH");
    }

    @Test
    void providerDestinationAliasesAndOpaqueBodyCannotBypassEvidenceBinding() {
        String mismatchedAlias = validPayload("GUARDIAN", 10, 10,
                "919999999999", "919999999999", OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now().plusSeconds(60)).replace(
                "\"destination\":\"919999999999\",",
                "\"destination\":\"919999999999\",\"mobile\":\"918888888888\",");
        String opaqueProviderBody = validPayload("GUARDIAN", 10, 10,
                "919999999999", "919999999999", OffsetDateTime.now().minusSeconds(5),
                OffsetDateTime.now().plusSeconds(60)).replace(
                "\"destination\":\"919999999999\",",
                "\"destination\":\"919999999999\",\"msg91Body\":{\"recipients\":[{\"mobiles\":\"918888888888\"}]},");

        assertSuppressed(event(mismatchedAlias), "POLICY_PROVIDER_DESTINATION_MISMATCH");
        assertSuppressed(event(opaqueProviderBody), "POLICY_PROVIDER_BODY_NOT_ALLOWED");
    }

    @Test
    void expiredOrFutureEvidenceFailsClosed() {
        NotificationInboxEvent expired = event(validPayload("GUARDIAN", 10, 10,
                "919999999999", "919999999999", OffsetDateTime.now().minusMinutes(3),
                OffsetDateTime.now().minusMinutes(1)));
        NotificationInboxEvent future = event(validPayload("GUARDIAN", 10, 10,
                "919999999999", "919999999999", OffsetDateTime.now().plusMinutes(1),
                OffsetDateTime.now().plusMinutes(2)));

        assertSuppressed(expired, "POLICY_EVIDENCE_STALE");
        assertSuppressed(future, "POLICY_EVIDENCE_FROM_FUTURE");
    }

    private void assertSuppressed(NotificationInboxEvent event, String reason) {
        assertThatThrownBy(() -> service.deliver(event))
                .isInstanceOf(NotificationSuppressedException.class)
                .hasMessageContaining(reason);
        verify(provider, never()).deliver(any());
    }

    private String validPayload(String recipientType, long payloadSchoolId, long evidenceSchoolId,
                                String destination, String evidenceDestination,
                                OffsetDateTime evaluatedAt, OffsetDateTime expiresAt) {
        String destinationHash = NotificationPolicyGuard.destinationSha256("SMS", evidenceDestination);
        return """
                {
                  "sourceEventType":"fees.fee-reminder-requested.v1",
                  "sourceEventId":"event-1",
                  "reminderRequestId":"event-1",
                  "notificationType":"FEE_REMINDER",
                  "template":"fee-reminder.v1",
                  "channel":"SMS",
                  "destination":"%s",
                  "schoolId":%d,
                  "studentId":1,
                  "recipientType":"%s",
                  "recipientId":"guardian-1",
                  "policyEvidence":{
                    "decision":"ALLOW",
                    "purpose":"SCHOOL_COMMUNICATIONS",
                    "lawfulBasis":"CONSENT",
                    "preference":"ENABLED",
                    "consentEventId":"consent-1",
                    "consentNoticeVersion":"notice-v1",
                    "guardianId":"guardian-1",
                    "schoolId":%d,
                    "studentId":1,
                    "channel":"SMS",
                    "destinationSha256":"%s",
                    "sourceEventId":"event-1",
                    "evaluatedAt":"%s",
                    "expiresAt":"%s",
                    "policyVersion":"guardian-communications.v2"
                  }
                }
                """.formatted(destination, payloadSchoolId, recipientType, evidenceSchoolId,
                destinationHash, evaluatedAt, expiresAt);
    }

    private NotificationInboxEvent event(String payload) {
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId("event-1");
        event.setEventType("notification.requested.v1");
        event.setPayload(payload);
        return event;
    }
}
