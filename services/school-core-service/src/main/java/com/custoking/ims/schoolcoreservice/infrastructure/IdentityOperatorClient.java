package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * Asks identity-service's internal operator directory which operator an order goes to. Identity's Cloud Run IAM and
 * MachineCallerFilter admit only school-core's service account, proven by the identity token {@link PeerHttp} adds.
 */
@Component
public class IdentityOperatorClient implements OperatorResolver {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;

    @Autowired
    public IdentityOperatorClient(
            @Value("${catalog.operator-directory.base-url:}") String baseUrl,
            @Value("${catalog.operator-directory.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${catalog.operator-directory.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${catalog.operator-directory.read-timeout-ms:5000}") int readTimeoutMs) {
        this(new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs));
    }

    IdentityOperatorClient(PeerHttp http) {
        this.http = http;
    }

    @Override
    public Resolution resolve(long schoolId, String catalogCategory) {
        if (!http.configured()) throw new IllegalStateException("operator directory is not configured");
        try {
            Map<String, Object> answer = http.client.get()
                    .uri(uri -> uri.path("/api/v1/internal/operator-directory/resolve")
                            .queryParam("schoolId", schoolId).queryParam("catalogCategory", catalogCategory).build())
                    .headers(http::authorize).retrieve().body(BODY);
            if (answer == null || !(answer.get("outcome") instanceof String outcome)) throw new IllegalStateException("operator directory returned no outcome");
            Long userId = answer.get("userId") instanceof Number number ? number.longValue() : null;
            return new Resolution(outcome, userId);
        } catch (RestClientResponseException answered) {
            // Kept as the queued order's last error: a 403 points at the caller allow-list or invoker grant, a 5xx at identity.
            throw new IllegalStateException("operator directory answered HTTP " + answered.getStatusCode().value(), answered);
        } catch (RestClientException failure) {
            throw new IllegalStateException("operator directory is unavailable", failure);
        }
    }
}
