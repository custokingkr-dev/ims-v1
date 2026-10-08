package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.*;
import com.custoking.ims.platformservice.persistence.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Dormant explicitly constructed bridge for a separately selected SMS-v3 account contract.
 * Uses the existing internal reporter authority, not a claim of vendor-origin authentication.
 * No Spring bean, endpoint, network I/O, UNKNOWN recovery or resend capability.
 */
final class Msg91SmsV3ReportBridge {
    private final NotificationReportAuthority authority;
    private final GenericAcceptedReportBindingRepository bindings;
    private final GenericNotificationReportRepository reports;
    private final Msg91SmsV3ReportCodec.Profile profile;
    private final TransactionTemplate transaction;
    Msg91SmsV3ReportBridge(NotificationReportAuthority authority,GenericAcceptedReportBindingRepository bindings,
            GenericNotificationReportRepository reports,PlatformTransactionManager manager,Msg91SmsV3ReportCodec.Profile profile) {
        if(authority==null || bindings==null || reports==null || manager==null || profile==null) throw new IllegalArgumentException("Explicit report bridge dependencies required");
        this.authority=authority;this.bindings=bindings;this.reports=reports;this.profile=profile;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }
    boolean reconcile(String authorization,String token,long school,String event,byte[] raw) {
        authority.verify(authorization,token); // Authentication before parsing, scope lookup or SQL.
        if(raw==null || raw.length==0 || raw.length>8192) throw invalid();
        byte[] owned=raw.clone();
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            var binding=bindings.find(school,event).orElse(null);
            if(binding==null) return false; // UNKNOWN/SUPPRESSED/missing bindings cannot authorize themselves.
            GenericNotificationReport candidate;
            try {
                var payload=Msg91WireJson.read(binding.payload());
                if(!payload.path("schoolId").isIntegralNumber() || !payload.path("schoolId").canConvertToLong() || payload.path("schoolId").asLong()!=school
                    || !"SMS".equals(payload.path("channel").asString())) throw invalid();
                var request=new NotificationDeliveryRequest(event,text(payload,"template"),text(payload,"channel"),
                    text(payload,"recipientType"),text(payload,"recipientId"),binding.payload());
                if(!NotificationSubmissionResult.requestSha256(request).equals(binding.requestSha256())
                    || !NotificationSubmissionResult.correlationId(event).equals(binding.correlationId())) throw invalid();
                // A reservation fingerprint does not prove the sender chose destination: legacy
                // routing aliases and raw passthrough override it. Admit only this canonical subset.
                if(payload.has("mobile") || payload.has("phone") || payload.has("to") || payload.has("recipientMobile")
                    || (payload.has("msg91Body") && !payload.path("msg91Body").isNull())
                    || !payload.path("destination").isString()) throw invalid();
                String destination=payload.path("destination").asString();
                if(!destination.matches("[0-9]{10,15}")) throw invalid();
                var accepted=new GenericNotificationReport(school,event,binding.requestSha256(),binding.correlationId(),
                    binding.providerRequestId(),GenericNotificationReport.Status.ACCEPTED,binding.submittedAt(),"0".repeat(64));
                candidate=new Msg91SmsV3ReportCodec().candidate(owned,profile,new Msg91SmsV3ReportCodec.AcceptedBinding(accepted,sha(destination)));
            } catch(RuntimeException malformed) { throw invalid(); }
            return reports.reconcile(candidate); // Existing exact receipt/time/erasure/replay fences remain authoritative.
        }));
    }
    private static String text(tools.jackson.databind.JsonNode node,String field){var value=node.get(field);return value==null||value.isNull()?null:value.asString();}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Unrecognized SMS-v3 report binding");}
}
