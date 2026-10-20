package com.custoking.ims.operationsservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Asks identity-service's internal operator directory whether a user is an eligible operator. Identity's Cloud Run IAM
 * and MachineCallerFilter admit only operations-service's service account, proven by the identity token
 * {@link PeerHttp} adds. Answers are never cached: an assignment must reflect the operator's current access.
 */
@Component
public class OperatorDirectoryClient implements OperatorEligibility {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;

    @Autowired
    public OperatorDirectoryClient(
            @Value("${operations.identity.base-url:}") String baseUrl,
            @Value("${operations.identity.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${operations.identity.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${operations.identity.read-timeout-ms:5000}") int readTimeoutMs) {
        this(new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs));
    }

    OperatorDirectoryClient(PeerHttp http) {
        this.http = http;
    }

    @Override
    public boolean eligible(long userId, long schoolId, String catalogCategory) {
        if (!http.configured()) throw new IllegalStateException("operator directory is not configured");
        try {
            Map<String, Object> body = http.client.get()
                    .uri(uri -> uri.path("/api/v1/internal/operator-directory/eligible")
                            .queryParam("userId", userId).queryParam("schoolId", schoolId)
                            .queryParam("catalogCategory", catalogCategory).build())
                    .headers(http::authorize)
                    .retrieve()
                    .body(BODY);
            return body != null && Boolean.TRUE.equals(body.get("eligible"));
        } catch (RestClientException failure) {
            throw new IllegalStateException("operator directory is unavailable", failure);
        }
    }
}
