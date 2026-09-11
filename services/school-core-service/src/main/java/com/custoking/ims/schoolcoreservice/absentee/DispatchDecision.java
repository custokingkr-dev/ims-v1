package com.custoking.ims.schoolcoreservice.absentee;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Result of re-evaluating the guardian-communications policy at dispatch time. Shape-compatible with
 * the {@code policyEvidence} object platform-service's {@code NotificationPolicyGuard} verifies,
 * so the worker can hand it over without translation.
 */
public record DispatchDecision(
        boolean allowed,
        String reason,
        String guardianId,
        String destination,
        String consentEventId,
        String consentNoticeVersion,
        String channel,
        Long schoolId,
        long studentId,
        OffsetDateTime evaluatedAt,
        OffsetDateTime expiresAt,
        String sourceEventId) {

    public static final String POLICY_VERSION = "guardian-communications.v2";
    public static final String PURPOSE = "SCHOOL_COMMUNICATIONS";

    public static DispatchDecision denied(String reason) {
        return new DispatchDecision(false, reason, null, null, null, null, null, null, 0, null, null, null);
    }

    public static DispatchDecision allowed(String guardianId, String destination, String consentEventId,
                                           String consentNoticeVersion, String channel, Long schoolId,
                                           long studentId, OffsetDateTime evaluatedAt, OffsetDateTime expiresAt,
                                           String sourceEventId) {
        return new DispatchDecision(true, "ALLOWED", guardianId, destination, consentEventId, consentNoticeVersion,
                normalizeChannel(channel), schoolId, studentId, evaluatedAt, expiresAt, sourceEventId);
    }

    public String destinationSha256() {
        return destinationSha256(channel, destination);
    }

    /** The evidence object the platform guard verifies field-by-field before the provider is called. */
    public Map<String, Object> evidence() {
        if (!allowed) throw new IllegalStateException("Denied decisions do not have policy evidence");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("decision", "ALLOW");
        evidence.put("purpose", PURPOSE);
        evidence.put("lawfulBasis", "CONSENT");
        evidence.put("preference", "ENABLED");
        evidence.put("consentEventId", consentEventId);
        evidence.put("consentNoticeVersion", consentNoticeVersion);
        evidence.put("guardianId", guardianId);
        evidence.put("schoolId", schoolId);
        evidence.put("studentId", studentId);
        evidence.put("channel", channel);
        evidence.put("destinationSha256", destinationSha256());
        evidence.put("sourceEventId", sourceEventId);
        evidence.put("evaluatedAt", evaluatedAt.toString());
        evidence.put("expiresAt", expiresAt.toString());
        evidence.put("policyVersion", POLICY_VERSION);
        return evidence;
    }

    /** Same normalization as {@code GuardianCommunicationPolicy.destinationSha256} and the platform guard. */
    public static String destinationSha256(String channel, String destination) {
        String normalized = switch (normalizeChannel(channel)) {
            case "SMS", "WHATSAPP" -> destination == null ? "" : destination.replaceAll("[^0-9]", "");
            case "EMAIL" -> destination == null ? "" : destination.trim().toLowerCase(Locale.ROOT);
            default -> "";
        };
        if (normalized.isBlank()) throw new IllegalArgumentException("destination is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String normalizeChannel(String channel) {
        return channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
    }
}
