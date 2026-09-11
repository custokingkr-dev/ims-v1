package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard admits exactly two producer contracts. The absentee alert contract binds the same v2
 * evidence as the fee reminder; only the source event type, category, template and request id
 * field differ. Everything else must keep failing closed.
 */
class NotificationPolicyGuardAbsenteeContractTest {

    private static final String EVENT_ID = "school-core:absentee:row-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationPolicyGuard guard = new NotificationPolicyGuard();

    @Test
    void absenteeAlertContractWithMatchingEvidenceIsAllowed() {
        JsonNode payload = absenteePayload("ABSENTEE_ALERT", "absentee-alert.v1", EVENT_ID);

        assertThatCode(() -> guard.requireAllowed(event(EVENT_ID), payload)).doesNotThrowAnyException();
    }

    @Test
    void absenteeContractRejectsForeignCategoryOrTemplate() {
        assertThatThrownBy(() -> guard.requireAllowed(event(EVENT_ID),
                absenteePayload("FEE_REMINDER", "absentee-alert.v1", EVENT_ID)))
                .isInstanceOf(NotificationSuppressedException.class)
                .hasMessageContaining("POLICY_CATEGORY_INVALID");
        assertThatThrownBy(() -> guard.requireAllowed(event(EVENT_ID),
                absenteePayload("ABSENTEE_ALERT", "fee-reminder.v1", EVENT_ID)))
                .isInstanceOf(NotificationSuppressedException.class)
                .hasMessageContaining("POLICY_TEMPLATE_INVALID");
    }

    @Test
    void absenteeRequestIdMustBindToTheInboxEventId() {
        assertThatThrownBy(() -> guard.requireAllowed(event(EVENT_ID),
                absenteePayload("ABSENTEE_ALERT", "absentee-alert.v1", "school-core:absentee:other")))
                .isInstanceOf(NotificationSuppressedException.class)
                .hasMessageContaining("POLICY_REQUEST_ID_MISMATCH");
    }

    @Test
    void unknownSourceEventTypesStillFailClosed() {
        JsonNode payload = absenteePayload("ABSENTEE_ALERT", "absentee-alert.v1", EVENT_ID);
        ((tools.jackson.databind.node.ObjectNode) payload).put("sourceEventType", "attendance.something-else.v1");

        assertThatThrownBy(() -> guard.requireAllowed(event(EVENT_ID), payload))
                .isInstanceOf(NotificationSuppressedException.class)
                .hasMessageContaining("POLICY_SOURCE_EVENT_TYPE_INVALID");
    }

    private JsonNode absenteePayload(String notificationType, String template, String requestId) {
        String hash = NotificationPolicyGuard.destinationSha256("WHATSAPP", "919999999999");
        OffsetDateTime evaluatedAt = OffsetDateTime.now().minusSeconds(5);
        OffsetDateTime expiresAt = evaluatedAt.plusSeconds(90);
        return objectMapper.readTree("""
                {"sourceEventType":"attendance.absentee-notification-requested.v1",
                 "sourceEventId":"%1$s","absenteeRequestId":"%2$s",
                 "notificationType":"%3$s","template":"%4$s",
                 "channel":"WHATSAPP","destination":"919999999999",
                 "schoolId":10,"studentId":1,"recipientType":"GUARDIAN","recipientId":"guardian-1",
                 "policyEvidence":{
                   "decision":"ALLOW","purpose":"SCHOOL_COMMUNICATIONS","lawfulBasis":"CONSENT",
                   "preference":"ENABLED","consentEventId":"consent-1","consentNoticeVersion":"notice-v1",
                   "guardianId":"guardian-1","schoolId":10,"studentId":1,"channel":"WHATSAPP",
                   "destinationSha256":"%5$s","sourceEventId":"%1$s",
                   "evaluatedAt":"%6$s","expiresAt":"%7$s","policyVersion":"guardian-communications.v2"}}
                """.formatted(EVENT_ID, requestId, notificationType, template, hash, evaluatedAt, expiresAt));
    }

    private static NotificationInboxEvent event(String eventId) {
        NotificationInboxEvent event = new NotificationInboxEvent();
        event.setEventId(eventId);
        event.setEventType("notification.requested.v1");
        return event;
    }
}
