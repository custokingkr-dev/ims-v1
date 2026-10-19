package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** Asks identity-service which operator an order goes to, with a machine token the gateway never carries. */
@Component
public class IdentityOperatorClient implements OperatorResolver {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;
    private final String token;

    public IdentityOperatorClient(
            @Value("${catalog.operator-directory.base-url:}") String baseUrl,
            @Value("${catalog.operator-directory.token:}") String token,
            @Value("${catalog.operator-directory.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${catalog.operator-directory.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${catalog.operator-directory.read-timeout-ms:5000}") int readTimeoutMs) {
        this.http = new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs);
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public Resolution resolve(long schoolId, String catalogCategory) {
        if (!http.configured() || !StringUtils.hasText(token)) throw new IllegalStateException("operator directory is not configured");
        try {
            Map<String, Object> answer = http.client.get()
                    .uri(uri -> uri.path("/api/v1/rbac/operator-directory/resolve")
                            .queryParam("schoolId", schoolId).queryParam("catalogCategory", catalogCategory).build())
                    .headers(this::headers).retrieve().body(BODY);
            if (answer == null || !(answer.get("outcome") instanceof String outcome)) throw new IllegalStateException("operator directory returned no outcome");
            Long userId = answer.get("userId") instanceof Number number ? number.longValue() : null;
            return new Resolution(outcome, userId);
        } catch (RestClientException failure) {
            throw new IllegalStateException("operator directory is unavailable", failure);
        }
    }

    private void headers(HttpHeaders headers) {
        headers.set("X-Operator-Directory-Service-Token", token);
        String identityToken = http.cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) headers.setBearerAuth(identityToken);
    }
}
