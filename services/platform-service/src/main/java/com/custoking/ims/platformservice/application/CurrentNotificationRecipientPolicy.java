package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.infrastructure.SchoolCoreBroadcastRecipientPolicy;
import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Current owner evidence supplements, rather than replaces, the admitted producer contract. */
@Component
public class CurrentNotificationRecipientPolicy {
    private final SchoolCoreBroadcastRecipientPolicy owner;
    private final ObjectMapper mapper;
    public CurrentNotificationRecipientPolicy(SchoolCoreBroadcastRecipientPolicy owner, ObjectMapper mapper) {
        this.owner=owner; this.mapper=mapper;
    }
    public void requireCurrent(NotificationInboxEvent event) {
        JsonNode payload=mapper.readTree(event.getPayload());
        new NotificationPolicyGuard().requireAllowed(event,payload);
        long school=payload.get("schoolId").asLong(), student=payload.get("studentId").asLong();
        String channel=payload.get("channel").asString();
        List<BroadcastRecipientPolicy.Recipient> decisions;
        try {
            decisions=switch(payload.get("sourceEventType").asString()) {
                case "fees.fee-reminder-requested.v1" -> {
                    if(!"SMS".equals(channel)) throw new NotificationSuppressedException("CURRENT_POLICY_CHANNEL_UNSUPPORTED");
                    yield owner.resolveFeeReminders(school,event.getEventId(),List.of(student));
                }
                case "school.broadcast-requested.v1" -> owner.resolve(school,
                    UUID.nameUUIDFromBytes(event.getEventId().getBytes(StandardCharsets.UTF_8)),List.of(channel),List.of(student));
                case "attendance.absentee-notification-requested.v1" -> owner.resolveAbsentee(school,student,channel,
                    event.getEventId(),required(payload,"absenteeNotificationId"),required(payload.get("variables"),"attendanceDate"),
                    sha256(required(payload.get("variables"),"message")));
                default -> throw new NotificationSuppressedException("CURRENT_POLICY_PURPOSE_UNSUPPORTED");
            };
        } catch(NotificationSuppressedException denied) { throw denied; }
        catch(RuntimeException unavailable) { throw new NotificationSuppressedException("CURRENT_POLICY_UNAVAILABLE"); }
        if(decisions==null || decisions.size()!=1) throw new NotificationSuppressedException("CURRENT_POLICY_INCOMPLETE");
        var decision=decisions.getFirst();
        if(!decision.allowed()) throw new NotificationSuppressedException("CURRENT_POLICY_DENIED");
        if(decision.policyEvidence()==null) throw new NotificationSuppressedException("CURRENT_POLICY_EVIDENCE_MISSING");
        if(decision.schoolId()!=school || decision.studentId()!=student || !channel.equals(decision.channel())
            || !payload.get("recipientId").asString().equals(decision.guardianId())
            || !NotificationPolicyGuard.destinationSha256(channel,payload.get("destination").asString()).equals(decision.destinationSha256())
            || !decision.destinationSha256().equals(NotificationPolicyGuard.destinationSha256(channel,decision.destination())))
            throw new NotificationSuppressedException("CURRENT_POLICY_RECIPIENT_CHANGED");
        // Rebind only the owner correlation ID. All other evidence remains owner-issued and must
        // pass the same freshness, consent, school, student, guardian and destination checks.
        var current=mapper.readTree(event.getPayload());
        var evidence=mapper.valueToTree(decision.policyEvidence());
        if("attendance.absentee-notification-requested.v1".equals(payload.get("sourceEventType").asString())) {
            if(!"ABSENTEE_ALERT".equals(required(evidence,"notificationCategory"))
                || !required(payload,"absenteeNotificationId").equals(required(evidence,"absenteeNotificationId"))
                || !required(payload.get("variables"),"attendanceDate").equals(required(evidence,"attendanceDate"))
                || !sha256(required(payload.get("variables"),"message")).equals(required(evidence,"messageSha256"))
                || !event.getEventId().equals(required(evidence,"sourceEventId")))
                throw new NotificationSuppressedException("CURRENT_POLICY_ABSENCE_MISMATCH");
        }
        ((tools.jackson.databind.node.ObjectNode)evidence).put("sourceEventId",event.getEventId());
        ((tools.jackson.databind.node.ObjectNode)current).set("policyEvidence",evidence);
        new NotificationPolicyGuard().requireAllowed(event,current);
    }
    private static String required(JsonNode node,String field) {
        JsonNode value=node==null?null:node.get(field);
        if(value==null || !value.isString() || value.asString().isBlank())
            throw new NotificationSuppressedException("CURRENT_POLICY_BINDING_MISSING");
        return value.asString();
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
}
