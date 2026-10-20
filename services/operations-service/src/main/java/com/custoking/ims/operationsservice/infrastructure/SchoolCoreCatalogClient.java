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
 * Calls school-core's internal catalogue routes for the notebook order tracker. School-core's Cloud Run IAM and
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

    @Override
    public void pushStatus(String orderId, String status, long trackerVersion) {
        requireConfigured();
        try {
            http.client.put().uri("/api/v1/internal/catalog-orders/{id}/tracker-status", orderId).contentType(MediaType.APPLICATION_JSON)
                    .headers(http::authorize).body(new StatusBody(status, trackerVersion)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException refused) {
            // Kept as the queued status's last error, so a status the catalogue keeps refusing is told apart from an outage.
            throw new IllegalStateException("the order catalogue answered HTTP " + refused.getStatusCode().value(), refused);
        } catch (RestClientException failure) {
            throw new IllegalStateException("the order catalogue is unavailable", failure);
        }
    }

    private record StatusBody(String status, long trackerVersion) { }

    private void requireConfigured() {
        if (!http.configured()) throw new IllegalStateException("the order catalogue is not configured");
    }
}
