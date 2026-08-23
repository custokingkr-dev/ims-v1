package com.custoking.ims.platformservice.application;

import com.custoking.ims.platformservice.persistence.NotificationInboxEvent;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Validates producer-supplied evidence for guardian communications without reaching across the
 * platform/school-core data ownership boundary.
 */
final class NotificationPolicyGuard {

    private static final String POLICY_VERSION = "guardian-communications.v2";
    private static final Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(2);
    private static final Duration FUTURE_CLOCK_SKEW = Duration.ofSeconds(30);

    private final Clock clock;

    NotificationPolicyGuard() {
        this(Clock.systemUTC());
    }

    NotificationPolicyGuard(Clock clock) {
        this.clock = clock;
    }

    void requireAllowed(NotificationInboxEvent event, JsonNode payload) {
        requireExact(event.getEventType(), "notification.requested.v1", "POLICY_EVENT_TYPE_INVALID");
        requireExact(text(payload, "sourceEventType"), "fees.fee-reminder-requested.v1",
                "POLICY_SOURCE_EVENT_TYPE_INVALID");
        requireExact(text(payload, "notificationType"), "FEE_REMINDER", "POLICY_CATEGORY_INVALID");
        requireExact(text(payload, "template"), "fee-reminder.v1", "POLICY_TEMPLATE_INVALID");
        requireExact(text(payload, "recipientType"), "GUARDIAN", "POLICY_RECIPIENT_TYPE_INVALID");

        String sourceEventId = required(payload, "sourceEventId", "POLICY_SOURCE_EVENT_ID_MISSING");
        if (!sourceEventId.equals(event.getEventId())) deny("POLICY_SOURCE_EVENT_ID_MISMATCH");
        String reminderRequestId = required(payload, "reminderRequestId", "POLICY_REQUEST_ID_MISSING");
        if (!sourceEventId.equals(reminderRequestId)) deny("POLICY_REQUEST_ID_MISMATCH");

        JsonNode evidence = payload.get("policyEvidence");
        if (evidence == null || !evidence.isObject()) deny("POLICY_EVIDENCE_MISSING");
        if (!"ALLOW".equals(text(evidence, "decision"))) deny("POLICY_DECISION_NOT_ALLOWED");
        if (!"SCHOOL_COMMUNICATIONS".equals(text(evidence, "purpose"))) deny("POLICY_PURPOSE_INVALID");
        if (!"CONSENT".equals(text(evidence, "lawfulBasis"))) deny("POLICY_LAWFUL_BASIS_INVALID");
        if (!"ENABLED".equals(text(evidence, "preference"))) deny("NOTIFICATION_PREFERENCE_DISABLED");
        if (!POLICY_VERSION.equals(text(evidence, "policyVersion"))) deny("POLICY_VERSION_INVALID");

        String guardianId = required(evidence, "guardianId", "POLICY_GUARDIAN_MISSING");
        if (!guardianId.equals(text(payload, "recipientId"))) deny("POLICY_GUARDIAN_MISMATCH");
        required(evidence, "consentEventId", "POLICY_CONSENT_EVENT_MISSING");
        required(evidence, "consentNoticeVersion", "POLICY_CONSENT_VERSION_MISSING");
        if (!sourceEventId.equals(text(evidence, "sourceEventId"))) deny("POLICY_EVIDENCE_EVENT_MISMATCH");

        long schoolId = positiveLong(payload, "schoolId", "POLICY_SCHOOL_MISSING");
        long studentId = positiveLong(payload, "studentId", "POLICY_STUDENT_MISSING");
        if (schoolId != positiveLong(evidence, "schoolId", "POLICY_EVIDENCE_SCHOOL_MISSING")) {
            deny("POLICY_SCHOOL_MISMATCH");
        }
        if (studentId != positiveLong(evidence, "studentId", "POLICY_EVIDENCE_STUDENT_MISSING")) {
            deny("POLICY_STUDENT_MISMATCH");
        }

        String channel = normalizedChannel(text(payload, "channel"));
        if (!channel.equals(normalizedChannel(text(evidence, "channel")))) deny("POLICY_CHANNEL_MISMATCH");
        String destination = required(payload, "destination", "POLICY_DESTINATION_MISSING");
        String expectedHash = destinationSha256(channel, destination);
        if (!constantTimeEquals(expectedHash, text(evidence, "destinationSha256"))) {
            deny("POLICY_DESTINATION_MISMATCH");
        }
        requireProviderDestinationBinding(payload, channel, expectedHash);

        OffsetDateTime evaluatedAt = timestamp(evidence, "evaluatedAt", "POLICY_EVALUATED_AT_INVALID");
        OffsetDateTime expiresAt = timestamp(evidence, "expiresAt", "POLICY_EXPIRES_AT_INVALID");
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (evaluatedAt.isAfter(now.plus(FUTURE_CLOCK_SKEW))) deny("POLICY_EVIDENCE_FROM_FUTURE");
        if (!expiresAt.isAfter(evaluatedAt)) deny("POLICY_EVIDENCE_WINDOW_INVALID");
        if (expiresAt.isAfter(evaluatedAt.plus(MAX_EVIDENCE_AGE).plus(FUTURE_CLOCK_SKEW))) {
            deny("POLICY_EVIDENCE_WINDOW_TOO_LONG");
        }
        if (evaluatedAt.isBefore(now.minus(MAX_EVIDENCE_AGE))) deny("POLICY_EVIDENCE_STALE");
        if (!expiresAt.isAfter(now)) deny("POLICY_EVIDENCE_EXPIRED");
    }

    private static void requireExact(String actual, String expected, String reasonCode) {
        if (!expected.equals(actual)) deny(reasonCode);
    }

    private static String required(JsonNode node, String field, String reasonCode) {
        String value = text(node, field);
        if (blank(value)) deny(reasonCode);
        return value;
    }

    private static long positiveLong(JsonNode node, String field, String reasonCode) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) deny(reasonCode);
        try {
            long parsed = value.isIntegralNumber() ? value.asLong() : Long.parseLong(value.asText());
            if (parsed <= 0) deny(reasonCode);
            return parsed;
        } catch (NumberFormatException ex) {
            deny(reasonCode);
            return 0;
        }
    }

    private static OffsetDateTime timestamp(JsonNode node, String field, String reasonCode) {
        try {
            return OffsetDateTime.parse(required(node, field, reasonCode));
        } catch (java.time.format.DateTimeParseException ex) {
            deny(reasonCode);
            return null;
        }
    }

    private static String normalizedChannel(String value) {
        String normalized = value == null ? "" : value.trim().replace("-", "_").toUpperCase(Locale.ROOT);
        if ("WA".equals(normalized) || "WHATS_APP".equals(normalized)) normalized = "WHATSAPP";
        if (!"SMS".equals(normalized) && !"WHATSAPP".equals(normalized) && !"EMAIL".equals(normalized)) {
            deny("POLICY_CHANNEL_INVALID");
        }
        return normalized;
    }

    static String destinationSha256(String channel, String destination) {
        String normalized = switch (normalizedChannel(channel)) {
            case "SMS", "WHATSAPP" -> destination == null ? "" : destination.replaceAll("[^0-9]", "");
            case "EMAIL" -> destination == null ? "" : destination.trim().toLowerCase(Locale.ROOT);
            default -> "";
        };
        if (normalized.isBlank()) deny("POLICY_DESTINATION_MISSING");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * MSG91 supports legacy destination aliases and a raw provider body. The provider resolves
     * those fields before {@code destination}, so every present alias must bind to the same
     * normalized address and an opaque provider body is not admissible for this policy contract.
     */
    private static void requireProviderDestinationBinding(JsonNode payload, String channel, String expectedHash) {
        JsonNode providerBody = payload.get("msg91Body");
        if (providerBody != null && !providerBody.isNull()) {
            deny("POLICY_PROVIDER_BODY_NOT_ALLOWED");
        }
        String[] aliases = switch (channel) {
            case "SMS" -> new String[]{"mobile", "phone", "to", "recipientMobile"};
            case "WHATSAPP" -> new String[]{"whatsapp", "mobile", "phone", "to", "recipientMobile"};
            case "EMAIL" -> new String[]{"email", "to", "recipientEmail"};
            default -> new String[0];
        };
        for (String alias : aliases) {
            String value = text(payload, alias);
            if (!blank(value) && !constantTimeEquals(expectedHash, destinationSha256(channel, value))) {
                deny("POLICY_PROVIDER_DESTINATION_MISMATCH");
            }
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private static void deny(String reasonCode) {
        throw new NotificationSuppressedException(reasonCode);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
