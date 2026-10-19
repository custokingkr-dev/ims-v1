package com.custoking.ims.operationsservice.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Asks identity-service whether a user is an eligible operator. Like {@link ModuleEntitlementClient} it uses a shared
 * machine token on an ordinary path and mints a Cloud Run identity token when the peer is a Cloud Run URL. Answers are
 * never cached: an assignment must reflect the operator's current access.
 */
@Component
public class OperatorDirectoryClient implements OperatorEligibility {
    private static final ParameterizedTypeReference<Map<String, Object>> BODY = new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final HttpClient metadataClient;
    private final String baseUrl;
    private final String token;
    private final String cloudRunAuthMode;

    public OperatorDirectoryClient(
            @Value("${operations.identity.base-url:}") String baseUrl,
            @Value("${operations.identity.operator-directory-token:}") String token,
            @Value("${operations.identity.cloud-run-auth:auto}") String cloudRunAuthMode,
            @Value("${operations.identity.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${operations.identity.read-timeout-ms:5000}") int readTimeoutMs) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.cloudRunAuthMode = cloudRunAuthMode == null ? "auto" : cloudRunAuthMode.trim().toLowerCase(Locale.ROOT);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(0, connectTimeoutMs));
        requestFactory.setReadTimeout(Math.max(0, readTimeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl.isBlank() ? "http://localhost" : this.baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public boolean eligible(long userId, long schoolId, String catalogCategory) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(token)) {
            throw new IllegalStateException("operator directory is not configured");
        }
        try {
            Map<String, Object> body = restClient.get()
                    .uri(uri -> uri.path("/api/v1/rbac/operator-directory/eligible")
                            .queryParam("userId", userId).queryParam("schoolId", schoolId)
                            .queryParam("catalogCategory", catalogCategory).build())
                    .headers(this::applyHeaders)
                    .retrieve()
                    .body(BODY);
            return body != null && Boolean.TRUE.equals(body.get("eligible"));
        } catch (RestClientException failure) {
            throw new IllegalStateException("operator directory is unavailable", failure);
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.set("X-Operator-Directory-Service-Token", token);
        String identityToken = cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) headers.setBearerAuth(identityToken);
    }

    private String cloudRunIdentityToken() {
        if ("never".equals(cloudRunAuthMode)) return "";
        if ("auto".equals(cloudRunAuthMode) && !baseUrl.contains(".run.app")) return "";
        String audience = URLEncoder.encode(baseUrl, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=" + audience))
                .timeout(Duration.ofSeconds(2)).header("Metadata-Flavor", "Google").GET().build();
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

    private static String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
