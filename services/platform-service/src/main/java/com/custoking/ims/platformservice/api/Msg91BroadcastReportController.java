package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastLiveRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Narrow provider callback; gateway service identity plus an account-specific callback secret. */
@RestController
@RequestMapping("/api/v1/notifications/provider-reports/msg91/email")
public class Msg91BroadcastReportController {
    private final String serviceToken;
    private final LiveBroadcastConfiguration configuration;
    private final BroadcastLiveRepository ledger;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public Msg91BroadcastReportController(@Value("${notification.status.token:}") String serviceToken,
            LiveBroadcastConfiguration configuration, BroadcastLiveRepository ledger, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.serviceToken=serviceToken==null ? "" : serviceToken.trim(); this.configuration=configuration;
        this.ledger=ledger; this.mapper=mapper; transaction=new TransactionTemplate(manager);
    }
    @PostMapping(consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> report(@RequestHeader(value="X-Notification-Service-Token",required=false) String token,
            @RequestHeader(value="X-MSG91-Webhook-Token",required=false) String callbackToken, @RequestBody byte[] bytes) {
        requireToken(token,"notification:report");
        // Receipts must continue working after the live send gate is disabled.
        if (configuration.webhookToken().length()<32 || !equal(configuration.webhookToken(),callbackToken))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid provider callback credential");
        if (bytes.length>32768) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Provider report is too large");
        final Report report;
        try { report=parse(mapper.readTree(bytes)); }
        catch (Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid provider report"); }
        try {
            transaction.executeWithoutResult(tx -> ledger.report(report.correlation(),report.providerId(),report.destinationHash(),report.senderHash(),
                    report.status(),report.at(),report.hash()));
        } catch (RuntimeException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Provider report persistence unavailable"); }
        // Unknown correlation/binding gets the same acknowledgement; it never creates a ledger.
        return Map.of("accepted",true);
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
