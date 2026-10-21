package com.custoking.ims.operationsservice.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Asks identity-service's internal operator directory whether a user is an eligible operator, and for the names of the
 * staff the tracker shows. Identity's Cloud Run IAM and MachineCallerFilter admit only operations-service's service
 * account, proven by the identity token {@link PeerHttp} adds. Eligibility is never cached: an assignment must reflect
 * the operator's current access. Names are cached by {@code StaffNames}, not here.
 */
@Component
public class OperatorDirectoryClient implements OperatorEligibility, StaffDirectory {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> NAMES = new ParameterizedTypeReference<>() { };

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

    /**
     * One POST to identity's names route for up to {@link #MAX_IDS} ids. Only well-formed answers for ids that were asked
     * about are kept; identity leaves out anyone who is not staff.
     */
    @Override
    public Map<Long, String> fullNames(Collection<Long> userIds) {
        List<Long> ids = userIds == null ? List.of() : userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) throw new IllegalArgumentException("At most " + MAX_IDS + " user ids per names call");
        if (ids.isEmpty()) return Map.of();
        if (!http.configured()) throw new IllegalStateException("operator directory is not configured");
        List<Map<String, Object>> rows;
        try {
            rows = http.client.post().uri("/api/v1/internal/operator-directory/names").contentType(MediaType.APPLICATION_JSON)
                    .headers(http::authorize).body(new NamesBody(ids)).retrieve().body(NAMES);
        } catch (RestClientException failure) {
            throw new IllegalStateException("operator directory is unavailable", failure);
        }
        Set<Long> asked = Set.copyOf(ids);
        Map<Long, String> names = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                if (row != null && row.get("userId") instanceof Number id && row.get("fullName") instanceof String name
                        && asked.contains(id.longValue())) {
                    names.put(id.longValue(), name);
                }
            }
        }
        return names;
    }

    private record NamesBody(List<Long> userIds) { }
}
