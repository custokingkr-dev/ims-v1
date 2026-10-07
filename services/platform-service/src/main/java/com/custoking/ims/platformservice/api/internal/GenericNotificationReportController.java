package com.custoking.ims.platformservice.api.internal;

import com.custoking.ims.platformservice.application.GenericNotificationReport;
import com.custoking.ims.platformservice.application.GenericNotificationReportService;
import com.custoking.ims.platformservice.security.GoogleIdentityTokenVerifier;
import com.custoking.ims.platformservice.security.IdentityTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/** Disabled internal operator boundary. It is deliberately not a provider webhook. */
@RestController
@RequestMapping("/api/v1/internal/notifications/reports")
public class GenericNotificationReportController {
    private static final Set<String> FIELDS=Set.of("version","schoolId","eventId","requestSha256","correlationId",
            "providerRequestId","status","occurredAt","evidenceSha256");
    private static final AsyncNotificationReportBody BODY=new AsyncNotificationReportBody(java.time.Duration.ofSeconds(5));
    private final GenericNotificationReportService service;
    private final IdentityTokenVerifier identities;
    private final boolean enabled;
    private final String reportToken, sharedToken, providerToken;
    private final Set<String> callers, forbiddenCallers;
    private static final JsonMapper MAPPER=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Autowired
    public GenericNotificationReportController(GenericNotificationReportService service, GoogleIdentityTokenVerifier identities,
            @Value("${notification.report-reconciliation.enabled:false}") boolean enabled,
            @Value("${notification.report-reconciliation.token:}") String reportToken,
            @Value("${notification.status.token:}") String sharedToken,
            @Value("${notification.msg91.auth-key:}") String providerToken, Environment environment) {
        this(service,(IdentityTokenVerifier)identities,enabled,reportToken,sharedToken,providerToken,
                environment.getProperty("NOTIFICATION_REPORT_CALLER_SERVICE_ACCOUNTS",""),
                environment.getProperty("USER_CONTEXT_CALLER_SERVICE_ACCOUNTS","")+","+
                        environment.getProperty("NOTIFICATION_DELIVERY_CALLER_SERVICE_ACCOUNTS",""));
    }
    public GenericNotificationReportController(GenericNotificationReportService service, IdentityTokenVerifier identities,
            boolean enabled, String reportToken, String sharedToken, String providerToken, String callers, String forbiddenCallers) {
        this.service=service; this.identities=identities; this.enabled=enabled; this.reportToken=value(reportToken);
        this.sharedToken=value(sharedToken);this.providerToken=value(providerToken);
        this.callers=accounts(callers); this.forbiddenCallers=accounts(forbiddenCallers);
    }
    @PostMapping(value="/reconcile",consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void reconcile(@RequestHeader(value="Authorization",required=false) String authorization,
            @RequestHeader(value="X-Notification-Report-Token",required=false) String token,
            HttpServletRequest request,HttpServletResponse response) throws java.io.IOException {
        requireToken(token,"notification:report-reconcile");
        requireCaller(authorization);
        if (request.getQueryString()!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Report query parameters are not supported");
        var headers=request.getHeaderNames();
        while(headers.hasMoreElements()) {
            String name=headers.nextElement().toLowerCase(Locale.ROOT);
            if(name.startsWith("x-authenticated-") || name.equals("x-ims-principal-carrier-token"))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Forwarded user authority is not accepted");
        }
        BODY.receive(request,response,body->{
            final GenericNotificationReport report;
            try {report=parseBytes(body);}
            catch(RuntimeException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid normalized notification report");}
            try {service.reconcile(report);}
            catch(RuntimeException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notification report persistence unavailable");}
        });
    }
    private void requireToken(String token,String scope) {
        if(!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification report reconciliation is unavailable");
        if(!"notification:report-reconcile".equals(scope) || reportToken.getBytes(StandardCharsets.UTF_8).length<32
                || reportToken.getBytes(StandardCharsets.UTF_8).length>512 || reportToken.isBlank()
                || equal(reportToken,sharedToken) || equal(reportToken,providerToken) || callers.isEmpty()
                || !Collections.disjoint(callers,forbiddenCallers) || !equal(reportToken,token))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid notification report authority");
    }
    private void requireCaller(String authorization) {
        String identity=authorization!=null && authorization.startsWith("Bearer ")
                ? identities.verifiedEmail(authorization.substring(7)).orElse(null):null;
        if(identity==null || !callers.contains(identity.toLowerCase(Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid notification report authority");
    }
    static GenericNotificationReport parse(JsonNode node) {
        if(node==null || !node.isObject() || node.size()!=FIELDS.size()
                || node.properties().stream().anyMatch(f->!FIELDS.contains(f.getKey()))
                || !node.path("version").isIntegralNumber() || !"1".equals(node.path("version").toString())
                || !node.path("schoolId").isIntegralNumber() || !node.path("schoolId").canConvertToLong()) throw new IllegalArgumentException();
        for(String key:FIELDS) if(!Set.of("version","schoolId").contains(key) && !node.path(key).isString()) throw new IllegalArgumentException();
        return new GenericNotificationReport(node.path("schoolId").asLong(),node.path("eventId").asString(),
                node.path("requestSha256").asString(),node.path("correlationId").asString(),node.path("providerRequestId").asString(),
                GenericNotificationReport.Status.valueOf(node.path("status").asString()),
                OffsetDateTime.parse(node.path("occurredAt").asString()),node.path("evidenceSha256").asString());
    }
    static GenericNotificationReport parseBytes(byte[] bytes){return parse(MAPPER.readTree(bytes));}
    private static String value(String input) { return input==null?"":input; }
    private static boolean equal(String expected,String supplied) { return supplied!=null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)); }
    private static Set<String> accounts(String input) {
        return Arrays.stream(value(input).split(",")).map(String::trim).filter(v->!v.isEmpty()).map(v->v.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
