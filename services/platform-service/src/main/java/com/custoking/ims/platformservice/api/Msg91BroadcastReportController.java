package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import com.custoking.ims.platformservice.api.internal.AsyncNotificationReportBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Narrow provider callback; gateway service identity plus an account-specific callback secret. */
@RestController
@RequestMapping("/api/v1/notifications/provider-reports/msg91/email")
public class Msg91BroadcastReportController {
    private final String serviceToken;
    private final LiveBroadcastConfiguration configuration;
    private final BroadcastLiveRepository ledger;
    private static final AsyncNotificationReportBody BODY=new AsyncNotificationReportBody(java.time.Duration.ofSeconds(5),32768,"Provider report is too large");
    private static final JsonMapper REPORT_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final TransactionTemplate transaction;
    public Msg91BroadcastReportController(@Value("${notification.status.token:}") String serviceToken,
            LiveBroadcastConfiguration configuration, BroadcastLiveRepository ledger, PlatformTransactionManager manager) {
        this.serviceToken=serviceToken==null ? "" : serviceToken.trim(); this.configuration=configuration;
        this.ledger=ledger; transaction=new TransactionTemplate(manager);transaction.setTimeout(5);
    }
    @PostMapping(consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@RequestHeader(value="X-Notification-Service-Token",required=false) String token,
            @RequestHeader(value="X-MSG91-Webhook-Token",required=false) String callbackToken,
            HttpServletRequest request,HttpServletResponse response) throws java.io.IOException {
        requireToken(token,"notification:report");
        // Receipts must continue working after the live send gate is disabled.
        if (configuration.webhookToken().length()<32 || !equal(configuration.webhookToken(),callbackToken))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid provider callback credential");
        // Both credentials are checked before even inspecting length or acquiring a servlet stream.
        BODY.receive(request,response,this::record,(reply,status,reason)->reply.sendError(status,reason));
    }
    private void record(byte[] bytes) {
        final Report report;
        try {
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(json.startsWith("\uFEFF"))json=json.substring(1); // Preserve an existing UTF-8 BOM's interpretation.
            report=parse(REPORT_JSON.readTree(json));
        }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid provider report"); }
        try {
            transaction.executeWithoutResult(tx -> ledger.report(report.correlation(),report.providerId(),report.destinationHash(),report.senderHash(),
                    report.status(),report.at(),report.hash()));
        } catch (RuntimeException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Provider report persistence unavailable"); }
        // Unknown correlation/binding gets the same acknowledgement; it never creates a ledger.
    }
    private void requireToken(String token,String scope) {
        if (!"notification:report".equals(scope) || serviceToken.isBlank() || !equal(serviceToken,token))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid notification service token");
    }
    private static boolean equal(String expected,String value) { return value!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),value.getBytes(StandardCharsets.UTF_8)); }
    static Report parse(JsonNode value) {
        if (!value.isObject()) throw new IllegalArgumentException();
        Set<String> fields=Set.of("correlationId","providerMessageId","destination","sender","status","occurredAt");
        if (value.size()!=fields.size() || value.properties().stream().anyMatch(e -> !fields.contains(e.getKey()) || !e.getValue().isString())) throw new IllegalArgumentException();
        String correlation=value.get("correlationId").asString(), provider=value.get("providerMessageId").asString();
        if (!correlation.matches("ims[0-9a-f]{48}") || !provider.matches("[A-Za-z0-9._-]{1,200}")) throw new IllegalArgumentException();
        String destination=email(value.get("destination").asString()), sender=email(value.get("sender").asString());
        String status=switch(value.get("status").asString().toUpperCase(Locale.ROOT)) {
            case "QUEUED","ACCEPTED" -> "ACCEPTED";
            case "DELIVERED" -> "DELIVERED";
            case "FAILED" -> "DELIVERY_FAILED";
            default -> throw new IllegalArgumentException();
        };
        OffsetDateTime at=OffsetDateTime.parse(value.get("occurredAt").asString());
        String destinationHash=sha256(destination),senderHash=sha256(sender);
        return new Report(correlation,provider,destinationHash,senderHash,status,at,
                sha256(String.join("|",correlation,provider,destinationHash,senderHash,status,at.toInstant().toString())));
    }
    private static String email(String input) {
        String result=input.trim().toLowerCase(Locale.ROOT);
        if (result.length()>254 || !result.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) throw new IllegalArgumentException();
        return result;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    record Report(String correlation,String providerId,String destinationHash,String senderHash,String status,OffsetDateTime at,String hash) {}
}
