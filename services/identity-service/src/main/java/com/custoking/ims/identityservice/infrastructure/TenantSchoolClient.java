package com.custoking.ims.identityservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class TenantSchoolClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_BODY = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final HttpClient metadataClient;
    private final String baseUrl;
    private final String token;
    private final String cloudRunAuthMode;

    public TenantSchoolClient(
            RestClient.Builder restClientBuilder,
            @Value("${identity.tenant-school.base-url:}") String baseUrl,
            @Value("${identity.tenant-school.token:}") String token,
            @Value("${identity.tenant-school.cloud-run-auth:auto}") String cloudRunAuthMode) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.cloudRunAuthMode = cloudRunAuthMode == null ? "auto" : cloudRunAuthMode.trim().toLowerCase();
        this.restClient = restClientBuilder.baseUrl(this.baseUrl.isBlank() ? "http://localhost" : this.baseUrl).build();
        this.metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public TenantSchoolRef school(Long schoolId) {
        Map<String, Object> row = get("/api/v1/schools/" + schoolId, "school not found");
        return new TenantSchoolRef(longValue(row.get("id"), schoolId), text(row.get("name")));
    }

    public TenantSchoolRef zone(Long zoneId) {
        Map<String, Object> row = get("/api/v1/zones/" + zoneId, "zone not found");
        return new TenantSchoolRef(longValue(row.get("id"), zoneId), text(row.get("name")));
    }

    public void assignZoneAdmin(Long zoneId, Long userId, Long assignedBy) {
        post("/api/v1/zones/" + zoneId + "/admins", Map.of(
                "userId", userId,
                "assignedBy", assignedBy == null ? "" : assignedBy));
    }

    public void retireZoneAdmins(Long zoneId, Iterable<Long> userIds) {
        post("/api/v1/zones/" + zoneId + "/admins/retire", Map.of("userIds", userIds));
    }

    private Map<String, Object> get(String path, String notFoundMessage) {
        requireConfigured();
        try {
            return restClient.get()
                    .uri(path)
                    .headers(this::applyHeaders)
                    .retrieve()
                    .body(MAP_BODY);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundMessage, ex);
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getResponseBodyAsString(), ex);
        }
    }

    private void post(String path, Map<String, Object> body) {
        requireConfigured();
        try {
            restClient.post()
                    .uri(path)
                    .headers(this::applyHeaders)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException ex) {
            throw new ResponseStatusException(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getResponseBodyAsString(), ex);
        }
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "tenant-school service is not configured");
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.set("X-Tenant-School-Token", token);
        String identityToken = cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) {
            headers.setBearerAuth(identityToken);
        }
    }

    private String cloudRunIdentityToken() {
        if ("never".equals(cloudRunAuthMode)) {
            return "";
        }
        if ("auto".equals(cloudRunAuthMode) && !baseUrl.contains(".run.app")) {
            return "";
        }
        String audience = URLEncoder.encode(baseUrl, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=" + audience))
                .timeout(Duration.ofSeconds(2))
                .header("Metadata-Flavor", "Google")
                .GET()
                .build();
        try {
            HttpResponse<String> response = metadataClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : "";
        } catch (IOException ex) {
            return "";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private Long longValue(Object value, Long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return Long.parseLong(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    public record TenantSchoolRef(Long id, String name) {
    }
}
