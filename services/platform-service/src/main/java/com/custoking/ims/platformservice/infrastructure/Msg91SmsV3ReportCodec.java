package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.GenericNotificationReport;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** Dormant pure decoder of the official SMS webhook-v3 example, not an authenticated receiver.
 * No Spring component or account default. An actual selected account must prove this profile before use.
 * https://msg91.com/help/webhooks/how-can-i-get-the-delivery-reports-on-my-webhook-url
 */
final class Msg91SmsV3ReportCodec {
    private static final Set<String> FIELDS=Set.of("date","number","senderId","amount","requestId","INMSID","CRQID","credit","userId","campaignName","status","desc");
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    record Profile(String userId,String senderId,ZoneOffset providerDateOffset) {
        Profile { if(userId==null || !userId.matches("[a-zA-Z0-9._:-]{1,120}") || senderId==null || !senderId.matches("[a-zA-Z0-9]{1,20}") || providerDateOffset==null) throw new IllegalArgumentException("Explicit SMS-v3 profile required"); }
        @Override public String toString(){return "SmsV3Profile[REDACTED]";}
    }
    record AcceptedBinding(GenericNotificationReport accepted,String destinationSha256) {
        AcceptedBinding { if(accepted==null || accepted.status()!=GenericNotificationReport.Status.ACCEPTED || destinationSha256==null || !destinationSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Existing accepted binding required"); }
        @Override public String toString(){return "AcceptedBinding[REDACTED]";}
    }
    GenericNotificationReport candidate(byte[] bytes,Profile profile,AcceptedBinding binding) {
        if(bytes==null || bytes.length==0 || bytes.length>8192 || profile==null || binding==null) throw invalid();
        try {
            String utf8=StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            if(utf8.startsWith("\uFEFF")) utf8=utf8.substring(1); // Preserve the supported UTF-8 BOM.
            var node=JSON.readTree(utf8);
            if(!node.isObject() || node.size()!=FIELDS.size() || node.properties().stream().anyMatch(f->!FIELDS.contains(f.getKey()) || !f.getValue().isString() || f.getValue().asString().length()>256)) throw invalid();
            var original=binding.accepted();
            String number=node.path("number").asString();
            if(!number.matches("[0-9]{7,15}") || !sha(number.getBytes(StandardCharsets.UTF_8)).equals(binding.destinationSha256())
                || !profile.userId().equals(node.path("userId").asString()) || !profile.senderId().equals(node.path("senderId").asString())
                || !original.providerRequestId().equals(node.path("requestId").asString()) || !original.correlationId().equals(node.path("CRQID").asString())) throw invalid();
            GenericNotificationReport.Status status;
            if("1".equals(node.path("status").asString()) && "DELIVERED".equals(node.path("desc").asString())) status=GenericNotificationReport.Status.DELIVERED;
            else if("2".equals(node.path("status").asString()) && "FAILED".equals(node.path("desc").asString())) status=GenericNotificationReport.Status.DELIVERY_FAILED;
            else throw invalid();
            var timestamp=LocalDateTime.parse(node.path("date").asString(),DATE).atOffset(profile.providerDateOffset());
            return new GenericNotificationReport(original.schoolId(),original.eventId(),original.requestSha256(),original.correlationId(),original.providerRequestId(),status,timestamp,sha(bytes));
        } catch(RuntimeException | java.nio.charset.CharacterCodingException malformed){throw invalid();}
    }
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Unrecognized SMS-v3 report; no status authority");}
}
