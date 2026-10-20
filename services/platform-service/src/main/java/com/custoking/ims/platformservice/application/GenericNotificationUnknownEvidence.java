package com.custoking.ims.platformservice.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Unverified operator evidence only: never a provider receipt, status transition or resend authority. */
public record GenericNotificationUnknownEvidence(GenericNotificationReport assertion, String reporterServiceAccount) {
    public GenericNotificationUnknownEvidence {
        if(assertion==null || assertion.status()!=GenericNotificationReport.Status.ACCEPTED
                || reporterServiceAccount==null || reporterServiceAccount.length()>254
                || !reporterServiceAccount.matches("[a-z0-9._-]{1,64}@[a-z0-9.-]{1,128}\\.iam\\.gserviceaccount\\.com"))
            throw new IllegalArgumentException("Invalid uncertainty evidence");
    }
    public String attestationSha256() {
        try {
            var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes);
            for(String value:new String[]{"ims-unknown-operator-evidence-v1",assertion.reportSha256(),reporterServiceAccount}) {
                byte[] encoded=value.getBytes(StandardCharsets.UTF_8);output.writeInt(encoded.length);output.write(encoded);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(java.io.IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("Unable to bind uncertainty evidence",impossible); }
    }
    @Override public String toString() { return "GenericNotificationUnknownEvidence[authority=UNVERIFIED_OPERATOR_ASSERTION, evidence=REDACTED]"; }
}
