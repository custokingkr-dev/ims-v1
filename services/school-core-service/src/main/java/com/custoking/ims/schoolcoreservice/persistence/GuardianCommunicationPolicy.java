package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative school-communications policy evaluated where guardian and consent data is owned.
 *
 * <p>The consent ledger permits a null guardian id, but does not define whether that means every
 * guardian or only a student-level decision. Until that policy is explicit, this evaluator accepts
 * only a current grant tied to the selected primary guardian.</p>
 */
final class GuardianCommunicationPolicy {

    static final String PURPOSE = "SCHOOL_COMMUNICATIONS";
    static final String POLICY_VERSION = "guardian-communications.v2";
    static final Duration EVIDENCE_TTL = Duration.ofMinutes(2);

    private final JdbcClient jdbc;

    GuardianCommunicationPolicy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Decision evaluate(Long schoolId, long studentId, String channel) {
        if (schoolId == null) {
            return Decision.denied("SCHOOL_SCOPE_MISSING");
        }
        Snapshot snapshot = jdbc.sql("""
                        SELECT g.id AS guardian_id, g.status AS guardian_status,
                               sg.receives_notifications, g.contact_verified_at,
                               g.phone, g.email,
                               consent.id AS consent_event_id,
                               consent.guardian_id AS consent_guardian_id,
                               consent.status AS consent_status,
                               consent.lawful_basis AS consent_lawful_basis,
                               consent.notice_version AS consent_notice_version,
                               consent.expires_at AS consent_expires_at
                        FROM student.students s
                        LEFT JOIN student.student_guardians sg
                          ON sg.student_id = s.id
                         AND sg.school_id = s.school_id
                         AND sg.is_primary = TRUE
                        LEFT JOIN student.guardians g
                          ON g.id = sg.guardian_id
                         AND g.school_id = s.school_id
                        LEFT JOIN LATERAL (
                            SELECT ce.id, ce.guardian_id, ce.status, ce.lawful_basis,
                                   ce.notice_version, ce.expires_at
                            FROM student.student_consent_events ce
                            WHERE ce.school_id = s.school_id
                              AND ce.student_id = s.id
                              AND ce.purpose = 'SCHOOL_COMMUNICATIONS'
                              AND ce.effective_at <= now()
                            ORDER BY ce.effective_at DESC, ce.recorded_at DESC, ce.id DESC
                            LIMIT 1
                        ) consent ON TRUE
                        WHERE s.id = :studentId
                          AND s.school_id = :schoolId
                          AND s.deleted_at IS NULL
                        """)
                .param("studentId", studentId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> new Snapshot(
                        rs.getString("guardian_id"),
                        rs.getString("guardian_status"),
                        rs.getBoolean("receives_notifications"),
                        rs.getObject("contact_verified_at", OffsetDateTime.class),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getString("consent_event_id"),
                        rs.getString("consent_guardian_id"),
                        rs.getString("consent_status"),
                        rs.getString("consent_lawful_basis"),
                        rs.getString("consent_notice_version"),
                        rs.getObject("consent_expires_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);
        return decide(snapshot, channel, OffsetDateTime.now(), schoolId, studentId);
    }

    static Decision decide(Snapshot snapshot, String channel, OffsetDateTime now,
                           Long schoolId, long studentId) {
        if (snapshot == null) return Decision.denied("STUDENT_NOT_FOUND");
        if (blank(snapshot.guardianId())) return Decision.denied("NO_PRIMARY_GUARDIAN");
        if (!"ACTIVE".equals(snapshot.guardianStatus())) return Decision.denied("GUARDIAN_INACTIVE");
        if (!snapshot.receivesNotifications()) return Decision.denied("NOTIFICATION_PREFERENCE_DISABLED");
        if (snapshot.contactVerifiedAt() == null) return Decision.denied("CONTACT_NOT_VERIFIED");

        String normalizedChannel = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        String destination = switch (normalizedChannel) {
            case "SMS", "WHATSAPP" -> snapshot.phone();
            case "EMAIL" -> snapshot.email();
            default -> null;
        };
        if (!normalizedChannel.equals("SMS")
                && !normalizedChannel.equals("WHATSAPP")
                && !normalizedChannel.equals("EMAIL")) {
            return Decision.denied("UNSUPPORTED_CHANNEL");
        }
        if (blank(destination)) return Decision.denied("DESTINATION_MISSING");
        if (blank(snapshot.consentEventId())) return Decision.denied("SCHOOL_COMMUNICATIONS_DECISION_MISSING");
        if (blank(snapshot.consentGuardianId())) return Decision.denied("CONSENT_GUARDIAN_UNSPECIFIED");
        if (!snapshot.guardianId().equals(snapshot.consentGuardianId())) {
            return Decision.denied("CONSENT_GUARDIAN_MISMATCH");
        }
        if (!"GRANTED".equals(snapshot.consentStatus())) {
            return Decision.denied("SCHOOL_COMMUNICATIONS_NOT_GRANTED");
        }
        if (!"CONSENT".equals(snapshot.consentLawfulBasis())) {
            return Decision.denied("LAWFUL_BASIS_NOT_CONSENT");
        }
        if (blank(snapshot.consentNoticeVersion())) {
            return Decision.denied("CONSENT_NOTICE_VERSION_MISSING");
        }
        if (snapshot.consentExpiresAt() != null && !snapshot.consentExpiresAt().isAfter(now)) {
            return Decision.denied("SCHOOL_COMMUNICATIONS_EXPIRED");
        }
        OffsetDateTime evidenceExpiresAt = now.plus(EVIDENCE_TTL);
        if (snapshot.consentExpiresAt() != null && snapshot.consentExpiresAt().isBefore(evidenceExpiresAt)) {
            evidenceExpiresAt = snapshot.consentExpiresAt();
        }
        return new Decision(true, "ALLOWED", snapshot.guardianId(), destination,
                snapshot.consentEventId(), snapshot.consentNoticeVersion(), normalizedChannel,
                schoolId, studentId, now, evidenceExpiresAt);
    }

    record Snapshot(
            String guardianId,
            String guardianStatus,
            boolean receivesNotifications,
            OffsetDateTime contactVerifiedAt,
            String phone,
            String email,
            String consentEventId,
            String consentGuardianId,
            String consentStatus,
            String consentLawfulBasis,
            String consentNoticeVersion,
            OffsetDateTime consentExpiresAt) {
    }

    record Decision(
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
            OffsetDateTime expiresAt) {

        static Decision denied(String reason) {
            return new Decision(false, reason, null, null, null, null, null,
                    null, 0, null, null);
        }

        Map<String, Object> evidence(String sourceEventId) {
            if (!allowed) throw new IllegalStateException("Denied decisions do not have policy evidence");
            if (blank(sourceEventId)) throw new IllegalArgumentException("sourceEventId is required");
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
            evidence.put("destinationSha256", destinationSha256(channel, destination));
            evidence.put("sourceEventId", sourceEventId);
            evidence.put("evaluatedAt", evaluatedAt.toString());
            evidence.put("expiresAt", expiresAt.toString());
            evidence.put("policyVersion", POLICY_VERSION);
            return evidence;
        }
    }

    static String destinationSha256(String channel, String destination) {
        String normalizedChannel = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        String normalized = switch (normalizedChannel) {
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
