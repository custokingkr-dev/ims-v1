package com.custoking.ims.operationsservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Calls school-core's internal catalogue routes for the order workflow engine: an order's design files, and the status
 * push that shows the workflow's current step on the order (design §3.5, §3.6). School-core's Cloud Run IAM and
 * MachineCallerFilter admit only operations-service's service account, proven by the identity token {@link PeerHttp}
 * adds. Failures surface as {@link IllegalStateException}.
 */
@Component
public class SchoolCoreCatalogClient implements CatalogOrderGateway {
    private static final ParameterizedTypeReference<List<Map<String, Object>>> DRAFTS = new ParameterizedTypeReference<>() { };

    private final PeerHttp http;

    @Autowired
    public SchoolCoreCatalogClient(
            @Value("${operations.tenant-school.base-url:}") String baseUrl,
            @Value("${operations.tenant-school.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${operations.tenant-school.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${operations.tenant-school.read-timeout-ms:5000}") int readTimeoutMs) {
        this(new PeerHttp(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs));
    }

    SchoolCoreCatalogClient(PeerHttp http) {
        this.http = http;
    }

    @Override
    public List<DraftImage> draftImages(String orderId) {
        requireConfigured();
        try {
            List<Map<String, Object>> rows = http.client.get().uri("/api/v1/internal/catalog-orders/{id}/draft-images", orderId)
                    .headers(http::authorize).retrieve().body(DRAFTS);
            if (rows == null) return List.of();
            return rows.stream().map(row -> new DraftImage(((Number) row.get("id")).longValue(),
                    String.valueOf(row.get("contentType")), String.valueOf(row.get("originalFilename")))).toList();
        } catch (RestClientException | ClassCastException | NullPointerException failure) {
            throw new IllegalStateException("the order catalogue is unavailable", failure);
        }
    }

    /**
     * The status with the step it names (design §3.5 {@code tracker-status}); a school-core that predates the step fields
     * ignores them.
     */
    @Override
    public void pushStatus(String orderId, String status, long trackerVersion, WorkflowStep step) {
        requireConfigured();
        StatusBody body = step == null ? new StatusBody(status, trackerVersion, null, null, null, null, null)
                : new StatusBody(status, trackerVersion, step.definitionId(), step.stepOrder(), step.stepTitle(), step.totalSteps(),
                        step.completedSteps());
        try {
            http.client.put().uri("/api/v1/internal/catalog-orders/{id}/tracker-status", orderId).contentType(MediaType.APPLICATION_JSON)
                    .headers(http::authorize).body(body).retrieve().toBodilessEntity();
        } catch (RestClientResponseException refused) {
            // Kept as the queued status's last error, so a status the catalogue keeps refusing is told apart from an outage.
            String answer = "the order catalogue answered HTTP " + refused.getStatusCode().value();
            // School-core's 409 (the order is cancelled or still a draft) and 404 (it no longer exists) can show no tracker
            // status, now or later; both are dropped rather than retried into a dead letter.
            int code = refused.getStatusCode().value();
            if (code == 409 || code == 404) throw new OrderClosedException(answer, code, refused);
            throw new IllegalStateException(answer, refused);
        } catch (RestClientException failure) {
            throw new IllegalStateException("the order catalogue is unavailable", failure);
        }
    }

    private record StatusBody(String status, long trackerVersion, String definitionId, Integer stepOrder, String stepTitle,
                              Integer totalSteps, Integer completedSteps) { }

    private void requireConfigured() {
        if (!http.configured()) throw new IllegalStateException("the order catalogue is not configured");
    }
}
