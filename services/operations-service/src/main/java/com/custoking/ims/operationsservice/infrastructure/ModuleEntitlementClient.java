package com.custoking.ims.operationsservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reads a school's active module entitlements from school-core (tenant-school domain) so
 * operations-service can gate FIREFIGHTING endpoints. Mirrors identity-service's
 * TenantSchoolClient for RestClient construction, the X-Tenant-School-Token header, and
 * Cloud Run OIDC identity-token minting (auto mode via the GCE metadata server).
 *
 * activeModules() throws (rather than swallowing) on any peer/config failure so the caller
 * (FirefightingModuleInterceptor) can fail-open on catch — availability of the firefighting
 * workflow must never regress because of an entitlement-lookup outage.
 */
@Component
public class ModuleEntitlementClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_BODY = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final HttpClient metadataClient;
    private final String baseUrl;
    private final String token;
    private final String cloudRunAuthMode;
    private final long ttlMs;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Set<String> modules, long expiresAt) {
    }

    public ModuleEntitlementClient(
            RestClient.Builder restClientBuilder,
            @Value("${operations.tenant-school.base-url:}") String baseUrl,
            @Value("${operations.tenant-school.token:}") String token,
            @Value("${operations.tenant-school.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${operations.tenant-school.module-cache-ttl-ms:60000}") long ttlMs,
            @Value("${operations.tenant-school.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${operations.tenant-school.read-timeout-ms:5000}") int readTimeoutMs) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.cloudRunAuthMode = cloudRunAuthMode == null ? "auto" : cloudRunAuthMode.trim().toLowerCase(Locale.ROOT);
        this.ttlMs = Math.max(0, ttlMs);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(0, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(0, readTimeoutMs));
        this.restClient = restClientBuilder
                .baseUrl(this.baseUrl.isBlank() ? "http://localhost" : this.baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * Active module codes (UPPERCASE) for a school. Throws on peer/config failure so the
     * caller fails-open.
     */
    public Set<String> activeModules(Long schoolId) {
        if (schoolId == null || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
            throw new IllegalStateException("tenant-school module lookup not configured");
        }
        Cached hit = cache.get(schoolId);
        if (hit != null && hit.expiresAt() > monotonicNow()) {
            return hit.modules();
        }
        List<Map<String, Object>> rows = restClient.get()
                .uri("/api/v1/schools/{id}/modules/active", schoolId)
                .headers(this::applyHeaders)
                .retrieve()
                .body(LIST_BODY);
        Set<String> codes = (rows == null ? List.<Map<String, Object>>of() : rows).stream()
                .map(r -> String.valueOf(r.get("moduleCode")).toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        cache.put(schoolId, new Cached(codes, monotonicNow() + ttlMs));
        return codes;
    }

    private long monotonicNow() {
        return System.currentTimeMillis();
    }

    // Gateway-authoritative caller context. The gateway strips any client-supplied copies and
    // re-sets these from the verified JWT, so forwarding them onward to tenant-school is safe and
    // lets tenant-school apply the SAME tenant scope (e.g. superadmin) the original caller had.
    private static final String[] AUTH_CONTEXT_HEADERS = {
            "X-Authenticated-User-Id", "X-Authenticated-Email", "X-Authenticated-Role",
            "X-Authenticated-School-Id", "X-Authenticated-Zone-Id"
    };

    private void applyHeaders(HttpHeaders headers) {
        headers.set("X-Tenant-School-Token", token);
        forwardAuthenticatedContext(headers);
        String identityToken = cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) {
            headers.setBearerAuth(identityToken);
        }
    }

    private void forwardAuthenticatedContext(HttpHeaders headers) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        for (String name : AUTH_CONTEXT_HEADERS) {
            String value = request.getHeader(name);
            if (StringUtils.hasText(value)) {
                headers.set(name, value);
            }
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

    private String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
