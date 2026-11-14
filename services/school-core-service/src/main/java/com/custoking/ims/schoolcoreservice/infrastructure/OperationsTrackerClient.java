package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the order workflow engine's internal routes in operations-service ({@code /api/v1/internal/order-workflows},
 * design §3.5). Operations' Cloud Run IAM and MachineCallerFilter admit only school-core's service account, proven by the
 * identity token {@link PeerHttp} adds. Every failure surfaces as {@link IllegalStateException}.
 */
@Component
public class OperationsTrackerClient implements TrackerGateway {
    static final String CREATE_PATH = "/api/v1/internal/order-workflows";
    static final String DESIGN_FILE_ACCESS_PATH = "/api/v1/internal/order-workflows/{order}/design-file-access";
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;

    @Autowired
    public OperationsTrackerClient(
            @Value("${catalog.tracker.base-url:}") String baseUrl,
            @Value("${catalog.tracker.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${catalog.tracker.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${catalog.tracker.read-timeout-ms:5000}") int readTimeoutMs) {
        this(new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs));
    }

    OperationsTrackerClient(PeerHttp http) {
        this.http = http;
    }

    @Override
    public void create(NewTracker tracker) {
        requireConfigured();
        try {
            http.client.post().uri(CREATE_PATH).contentType(MediaType.APPLICATION_JSON)
                    .headers(http::authorize).body(body(tracker)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException answered) {
            // Kept as the queued order's last error: a 403 points at the caller allow-list or invoker grant, a 5xx at the engine.
            throw new IllegalStateException("order workflow engine answered HTTP " + answered.getStatusCode().value(), answered);
        } catch (RestClientException failure) {
            throw new IllegalStateException("order workflow engine is unavailable", failure);
        }
    }

    /** The create body: today's notebook fields plus the definition, item, number and any imported progress (design §3.5). */
    static Map<String, Object> body(NewTracker tracker) {
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
        body.put("definitionId", tracker.definitionId());
        body.put("categoryCode", tracker.categoryCode());
        body.put("itemCode", tracker.itemCode());
        body.put("itemNoun", tracker.itemNoun());
        body.put("orderNumber", tracker.orderNumber());
        List<Map<String, Object>> imported = tracker.importedProgress().stream().map(step -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stepCode", step.stepCode());
            row.put("sourceStageCode", step.sourceStageCode());
            row.put("stepOrder", step.stepOrder());
            row.put("status", step.status());
            row.put("completedAt", step.completedAt() == null ? null : step.completedAt().toString());
            row.put("completedBy", step.completedBy());
            row.put("details", step.details());
            row.put("note", step.note());
            return row;
        }).toList();
        body.put("importedProgress", imported);
        return body;
    }

    /**
     * Asks the engine whether this user may change the order's design files now. The engine answers an order it holds no
     * workflow for with {@code allowed: false}, never 403 or 404, so every refusal or error fails closed. The notebook-tracker
     * alias it once fell back to (design D6) is retired: only an engine older than ed8aeba5 had the alias without this route,
     * and none can serve on the schema since workflow V22.
     */
    @Override
    public ImageAccess imageAccess(String orderId, long userId, String role) {
        requireConfigured();
        Map<String, Object> question = Map.of("userId", userId, "role", role == null ? "" : role);
        try {
            Map<String, Object> answer = http.client.post().uri(DESIGN_FILE_ACCESS_PATH, orderId).contentType(MediaType.APPLICATION_JSON)
                    .headers(http::authorize).body(question).retrieve().body(BODY);
            boolean allowed = answer != null && Boolean.TRUE.equals(answer.get("allowed"));
            Object reason = answer == null ? null : answer.get("reason");
            return new ImageAccess(allowed, reason == null ? null : String.valueOf(reason));
        } catch (RestClientException failure) {
            throw new IllegalStateException("order workflow engine is unavailable", failure);
        }
    }

    private void requireConfigured() {
        if (!http.configured()) throw new IllegalStateException("order workflow engine is not configured");
    }
}
