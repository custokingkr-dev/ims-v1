package com.custoking.ims.platformservice.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/** Versioned, trusted internal assertion; never a raw vendor callback or resend command. */
public record GenericNotificationReport(long schoolId, String eventId, String requestSha256,
        String correlationId, String providerRequestId, Status status, OffsetDateTime occurredAt,
        String evidenceSha256) {
    public enum Status { ACCEPTED, DELIVERED, DELIVERY_FAILED }
    public GenericNotificationReport {
        if (schoolId <= 0 || eventId == null || !eventId.matches("[A-Za-z0-9._:-]{1,120}")
                || requestSha256 == null || !requestSha256.matches("[0-9a-f]{64}")
                || correlationId == null || !correlationId.equals(NotificationSubmissionResult.correlationId(eventId))
                || providerRequestId == null || !providerRequestId.matches("[a-zA-Z0-9._:-]{1,160}")
                || status == null || occurredAt == null || evidenceSha256 == null
                || !evidenceSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid normalized notification report");
        }
        // PostgreSQL stores microseconds: reject precision that could lose replay identity.
        if (occurredAt.getNano() % 1000 != 0) throw new IllegalArgumentException("Invalid report timestamp precision");
    }
    public String reportSha256() {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            for (String field : new String[]{"ims-generic-report-v1", Long.toString(schoolId), eventId,
                    requestSha256, correlationId, providerRequestId, status.name(),
                    occurredAt.toInstant().toString(), evidenceSha256}) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8); output.writeInt(value.length); output.write(value);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to bind normalized notification report", impossible);
        }
    }
    @Override public String toString() { return "GenericNotificationReport[status=" + status + ", evidence=REDACTED]"; }
}
