package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the notebook order tracker in operations-service with a machine token that the gateway strips from client
 * requests and never injects, so only services can use it. Every failure surfaces as {@link IllegalStateException}.
 */
@Component
public class OperationsTrackerClient implements TrackerGateway {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;
    private final String token;

    public OperationsTrackerClient(
            @Value("${catalog.tracker.base-url:}") String baseUrl,
            @Value("${catalog.tracker.token:}") String token,
            @Value("${catalog.tracker.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${catalog.tracker.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${catalog.tracker.read-timeout-ms:5000}") int readTimeoutMs) {
        this.http = new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs);
        this.token = token == null ? "" : token.trim();
    }

    @Override
    public void create(NewTracker tracker) {
        requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", tracker.orderId());
        body.put("schoolId", tracker.schoolId());
        body.put("customised", tracker.customised());
        body.put("orderedCount", tracker.orderedCount());
        body.put("placedAt", tracker.placedAt().toString());
        body.put("timeZone", tracker.timeZone());
        body.put("placedBy", tracker.placedBy());
        body.put("assigneeUserId", tracker.assigneeUserId());
        body.put("schoolName", tracker.schoolName());
        try {
            http.client.post().uri("/api/v1/workflows/notebook-orders").contentType(MediaType.APPLICATION_JSON)
                    .headers(this::headers).body(body).retrieve().toBodilessEntity();
        } catch (RestClientException failure) {
            throw new IllegalStateException("notebook tracker is unavailable", failure);
        }
    }

    @Override
    public ImageAccess imageAccess(String orderId, long userId, String role) {
        requireConfigured();
        try {
            Map<String, Object> answer = http.client.post()
                    .uri("/api/v1/workflows/notebook-orders/{order}/design/image-access", orderId)
                    .contentType(MediaType.APPLICATION_JSON).headers(this::headers)
                    .body(Map.of("userId", userId, "role", role == null ? "" : role))
                    .retrieve().body(BODY);
            boolean allowed = answer != null && Boolean.TRUE.equals(answer.get("allowed"));
            Object reason = answer == null ? null : answer.get("reason");
            return new ImageAccess(allowed, reason == null ? null : String.valueOf(reason));
        } catch (RestClientException failure) {
            throw new IllegalStateException("notebook tracker is unavailable", failure);
        }
    }

    private void requireConfigured() {
        if (!http.configured() || !StringUtils.hasText(token)) throw new IllegalStateException("notebook tracker is not configured");
    }

    private void headers(HttpHeaders headers) {
        headers.set("X-Notebook-Tracker-Service-Token", token);
        String identityToken = http.cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) headers.setBearerAuth(identityToken);
    }
}
