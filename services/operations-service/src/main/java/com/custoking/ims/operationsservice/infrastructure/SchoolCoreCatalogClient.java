package com.custoking.ims.operationsservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Calls school-core's machine endpoints for the notebook order tracker. The token is not injected by the gateway, which
 * also strips it from client requests, so only services can send it. Failures surface as {@link IllegalStateException}.
 */
@Component
public class SchoolCoreCatalogClient implements CatalogOrderGateway {
    private static final ParameterizedTypeReference<List<Map<String, Object>>> DRAFTS = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;
    private final String token;

    public SchoolCoreCatalogClient(
            @Value("${operations.tenant-school.base-url:}") String baseUrl,
            @Value("${operations.tenant-school.tracker-token:}") String token,
            @Value("${operations.tenant-school.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${operations.tenant-school.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${operations.tenant-school.read-timeout-ms:5000}") int readTimeoutMs) {
        this.http = new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs);
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public List<DraftImage> draftImages(String orderId) {
        requireConfigured();
        try {
            List<Map<String, Object>> rows = http.client.get().uri("/api/v1/catalog/orders/{id}/draft-images", orderId)
                    .headers(this::headers).retrieve().body(DRAFTS);
            if (rows == null) return List.of();
            return rows.stream().map(row -> new DraftImage(((Number) row.get("id")).longValue(),
                    String.valueOf(row.get("contentType")), String.valueOf(row.get("originalFilename")))).toList();
        } catch (RestClientException | ClassCastException | NullPointerException failure) {
            throw new IllegalStateException("the order catalogue is unavailable", failure);
        }
    }

    @Override
    public void pushStatus(String orderId, String status) {
        requireConfigured();
        try {
            http.client.put().uri("/api/v1/catalog/orders/{id}/tracker-status", orderId).contentType(MediaType.APPLICATION_JSON)
                    .headers(this::headers).body(Map.of("status", status)).retrieve().toBodilessEntity();
        } catch (RestClientException failure) {
            throw new IllegalStateException("the order catalogue is unavailable", failure);
        }
    }

    private void requireConfigured() {
        if (!http.configured() || !StringUtils.hasText(token)) throw new IllegalStateException("the order catalogue is not configured");
    }

    private void headers(HttpHeaders headers) {
        headers.set("X-Notebook-Tracker-Service-Token", token);
        String identityToken = http.cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) headers.setBearerAuth(identityToken);
    }
}
