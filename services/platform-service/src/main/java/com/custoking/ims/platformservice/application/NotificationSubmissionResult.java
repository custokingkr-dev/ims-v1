package com.custoking.ims.platformservice.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Request-bound provider acceptance only. No submission result proves recipient delivery. */
public record NotificationSubmissionResult(Status status,String eventId,String correlationId,String requestSha256,
        String providerRequestId,String reason) {
    public enum Status { ACCEPTED, REJECTED, UNKNOWN }
    public NotificationSubmissionResult {
        if(status==null || eventId==null || eventId.isBlank() || eventId.length()>120
                || correlationId==null || !correlationId.matches("ims[0-9a-f]{48}")
                || requestSha256==null || !requestSha256.matches("[0-9a-f]{64}")
                || reason==null || !reason.matches("[A-Z][A-Z0-9_]{0,79}")
                || (status==Status.ACCEPTED && (providerRequestId==null || !providerRequestId.matches("[a-zA-Z0-9._:-]{1,160}")))
                || (status!=Status.ACCEPTED && providerRequestId!=null))
            throw new IllegalArgumentException("Invalid provider submission evidence");
    }
    public static NotificationSubmissionResult of(NotificationDeliveryRequest request,Status status,String id,String reason) {
        return new NotificationSubmissionResult(status,request.eventId(),correlationId(request.eventId()),requestSha256(request),id,reason);
    }
    public boolean binds(NotificationDeliveryRequest request) {
        return eventId.equals(request.eventId()) && correlationId.equals(correlationId(request.eventId())) && requestSha256.equals(requestSha256(request));
    }
    public static String correlationId(String eventId) { return "ims"+sha256(eventId.getBytes(StandardCharsets.UTF_8)).substring(0,48); }
    public static String requestSha256(NotificationDeliveryRequest request) {
        // Length prefixes prevent ambiguous concatenation and bind every provider-facing field.
        try {
            var bytes=new java.io.ByteArrayOutputStream();var output=new java.io.DataOutputStream(bytes);
            for(String field:new String[]{request.eventId(),request.template(),request.channel(),request.recipientType(),request.recipientId(),request.payload()}) {
                if(field==null) { output.writeInt(-1);continue; }
                byte[] value=field.getBytes(StandardCharsets.UTF_8);output.writeInt(value.length);output.write(value);
            }
            return sha256(bytes.toByteArray());
        } catch(java.io.IOException impossible) { throw new IllegalStateException("Unable to bind submission",impossible); }
    }
    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable",impossible); }
    }
    @Override public String toString() { return "NotificationSubmissionResult[status="+status+", evidence=REDACTED, reason="+reason+"]"; }
}
