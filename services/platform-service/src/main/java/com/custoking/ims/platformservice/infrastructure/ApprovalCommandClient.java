package com.custoking.ims.platformservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Outbound HTTP client that lets reporting/platform-service *write* approval decisions into the
 * owning services (school-core-service for catalog supply orders, operations-service for
 * firefighting requests) instead of updating their schemas directly. An event projection can only
 * read/derive local facts -- it must never UPDATE another service's tables.
 */
@Component
public class ApprovalCommandClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_BODY = new ParameterizedTypeReference<>() {
    };

    // Gateway-authoritative caller context. Forwarded so the owning service can attribute the
    // action to the real actor; the role is always overridden to SUPERADMIN because only a
    // superadmin can reach this decision (the target endpoints gate the sensitive transitions on
    // TenantScope.requireSuperAdmin()).
    private static final String[] FORWARD_HEADERS = {"X-Authenticated-User-Id", "X-Authenticated-Email"};

    private final RestClient schoolCoreClient;
    private final RestClient operationsClient;
    private final HttpClient metadataClient;
    private final String schoolCoreBaseUrl;
    private final String operationsBaseUrl;
    private final String schoolCoreAuthMode;
    private final String operationsAuthMode;
    private final String catalogToken;
    private final String firefightingToken;
    private final boolean schoolCoreConfigured;
    private final boolean operationsConfigured;

    public ApprovalCommandClient(
            RestClient.Builder restClientBuilder,
            @Value("${school-core.base-url:}") String schoolCoreBaseUrl,
            @Value("${catalog.read-token:}") String catalogToken,
            @Value("${operations.base-url:}") String operationsBaseUrl,
            @Value("${firefighting.read-token:}") String firefightingToken,
            @Value("${school-core.cloud-run-auth:auto}") String schoolCoreAuthMode,
            @Value("${operations.cloud-run-auth:auto}") String operationsAuthMode,
            @Value("${school-core.connect-timeout-ms:3000}") int schoolCoreConnectTimeoutMs,
            @Value("${school-core.read-timeout-ms:5000}") int schoolCoreReadTimeoutMs,
            @Value("${operations.connect-timeout-ms:3000}") int operationsConnectTimeoutMs,
            @Value("${operations.read-timeout-ms:5000}") int operationsReadTimeoutMs) {
        String trimmedSchoolCoreBaseUrl = trimTrailingSlash(schoolCoreBaseUrl);
        String trimmedOperationsBaseUrl = trimTrailingSlash(operationsBaseUrl);
        this.schoolCoreBaseUrl = trimmedSchoolCoreBaseUrl;
        this.operationsBaseUrl = trimmedOperationsBaseUrl;
        this.schoolCoreAuthMode = schoolCoreAuthMode == null ? "auto" : schoolCoreAuthMode.trim().toLowerCase();
        this.operationsAuthMode = operationsAuthMode == null ? "auto" : operationsAuthMode.trim().toLowerCase();
        this.catalogToken = catalogToken == null ? "" : catalogToken.trim();
        this.firefightingToken = firefightingToken == null ? "" : firefightingToken.trim();
        this.schoolCoreConfigured = StringUtils.hasText(trimmedSchoolCoreBaseUrl) && StringUtils.hasText(this.catalogToken);
        this.operationsConfigured = StringUtils.hasText(trimmedOperationsBaseUrl) && StringUtils.hasText(this.firefightingToken);

        this.schoolCoreClient = restClientBuilder.clone()
                .baseUrl(trimmedSchoolCoreBaseUrl.isBlank() ? "http://localhost" : trimmedSchoolCoreBaseUrl)
                .requestFactory(requestFactory(schoolCoreConnectTimeoutMs, schoolCoreReadTimeoutMs))
                .build();
        this.operationsClient = restClientBuilder.clone()
                .baseUrl(trimmedOperationsBaseUrl.isBlank() ? "http://localhost" : trimmedOperationsBaseUrl)
                .requestFactory(requestFactory(operationsConnectTimeoutMs, operationsReadTimeoutMs))
                .build();
        // Used to mint Cloud Run OIDC identity tokens (audience = the private peer's URL) so the
        // gateway-less service-to-service call is accepted by the peer's IAM-gated ingress. Inert
        // locally (auto mode only activates for *.run.app base URLs).
        this.metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(0, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(0, readTimeoutMs));
        return requestFactory;
    }

    public Map<String, Object> approveCatalog(String orderId) {
        requireConfigured(schoolCoreConfigured, "school-core");
        return exchange(schoolCoreClient, "/api/v1/supply/orders/" + orderId + "/superadmin-approve", null,
                this::applySchoolCoreHeaders, "school-core");
    }

    public Map<String, Object> rejectCatalog(String orderId, String reason) {
        requireConfigured(schoolCoreConfigured, "school-core");
        return exchange(schoolCoreClient, "/api/v1/supply/orders/" + orderId + "/superadmin-reject",
                Map.of("reason", reason == null ? "" : reason), this::applySchoolCoreHeaders, "school-core");
    }

    public Map<String, Object> approveFirefightingBursar(String code, String note) {
        requireConfigured(operationsConfigured, "operations");
        return exchange(operationsClient, "/api/v1/ff/requests/" + code + "/approve-bursar",
                Map.of("note", note == null ? "" : note), this::applyOperationsHeaders, "operations");
    }

    public Map<String, Object> approveFirefightingPrincipal(String code, String note) {
        requireConfigured(operationsConfigured, "operations");
        return exchange(operationsClient, "/api/v1/ff/requests/" + code + "/approve-principal",
                Map.of("note", note == null ? "" : note), this::applyOperationsHeaders, "operations");
    }

    public Map<String, Object> approveFirefightingCustoking(String code) {
        requireConfigured(operationsConfigured, "operations");
        return exchange(operationsClient, "/api/v1/ff/requests/" + code + "/approve-custoking", null,
                this::applyOperationsHeaders, "operations");
    }

    public Map<String, Object> rejectFirefighting(String code, String actorName, String reason) {
        requireConfigured(operationsConfigured, "operations");
        return exchange(operationsClient, "/api/v1/ff/requests/" + code + "/reject",
                Map.of("actorName", actorName == null ? "" : actorName, "reason", reason == null ? "" : reason),
                this::applyOperationsHeaders, "operations");
    }

    private Map<String, Object> exchange(RestClient client, String path, Map<String, Object> body,
            java.util.function.Consumer<HttpHeaders> headerCustomizer, String peerName) {
        try {
            RestClient.RequestBodySpec spec = client.post().uri(path).headers(headerCustomizer::accept);
            if (body != null) {
                spec.body(body);
            }
            Map<String, Object> response = spec.retrieve().body(MAP_BODY);
            return response == null ? Map.of() : response;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, peerName + " approval target not found", ex);
        } catch (HttpClientErrorException ex) {
            // A 4xx from the owning service means the request is no longer in the expected state
            // (e.g. someone else already decided it) -- surface as a conflict rather than a 5xx.
            throw new ResponseStatusException(HttpStatus.CONFLICT, peerName + " rejected the approval command: " + ex.getResponseBodyAsString(), ex);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, peerName + " service is unavailable", ex);
        }
    }

    private void requireConfigured(boolean configured, String peerName) {
        if (!configured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, peerName + " service is not configured");
        }
    }

    private void applySchoolCoreHeaders(HttpHeaders headers) {
        headers.set("X-Catalog-Service-Token", catalogToken);
        applySuperAdminContext(headers);
        applyCloudRunAuth(headers, schoolCoreBaseUrl, schoolCoreAuthMode);
    }

    private void applyOperationsHeaders(HttpHeaders headers) {
        headers.set("X-Firefighting-Service-Token", firefightingToken);
        applySuperAdminContext(headers);
        applyCloudRunAuth(headers, operationsBaseUrl, operationsAuthMode);
    }

    private void applyCloudRunAuth(HttpHeaders headers, String baseUrl, String authMode) {
        String identityToken = cloudRunIdentityToken(baseUrl, authMode);
        if (StringUtils.hasText(identityToken)) {
            headers.setBearerAuth(identityToken);
        }
    }

    /**
     * Mint a Google-signed OIDC identity token (audience = the peer's base URL) from the Cloud Run
     * metadata server. Private Cloud Run services reject calls without it at the ingress before the
     * app runs. "auto" activates only for *.run.app URLs; "never" disables it (both leave local HTTP
     * and tests unaffected). Any failure returns "" so the token is simply omitted.
     */
    private String cloudRunIdentityToken(String baseUrl, String authMode) {
        if ("never".equals(authMode)) {
            return "";
        }
        if ("auto".equals(authMode) && !baseUrl.contains(".run.app")) {
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

    private void applySuperAdminContext(HttpHeaders headers) {
        forwardCallerContext(headers);
        headers.set("X-Authenticated-Role", "SUPERADMIN");
    }

    private void forwardCallerContext(HttpHeaders headers) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        for (String name : FORWARD_HEADERS) {
            String value = request.getHeader(name);
            if (StringUtils.hasText(value)) {
                headers.set(name, value);
            }
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
