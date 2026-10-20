package com.custoking.ims.schoolcoreservice.infrastructure;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Shared plumbing for school-core's calls to peer services: a bounded RestClient and this service's Cloud Run
 * identity token, which is the only credential a peer call carries (no shared token, no forwarded user context).
 * The peer's Cloud Run IAM and MachineCallerFilter check the token's signature, audience and service-account email.
 */
final class PeerHttp {
    static final String METADATA_IDENTITY_URL = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity";
    /** Identity tokens live an hour; like the gateway, reuse one for 45 minutes. */
    static final Duration TOKEN_REUSE = Duration.ofMinutes(45);

    private final String baseUrl;
    private final String cloudRunAuthMode;
    private final String metadataIdentityUrl;
    private final Clock clock;
    private final HttpClient metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile CachedToken cachedToken;
    final RestClient client;

    PeerHttp(String baseUrl, String cloudRunAuthMode, int connectTimeoutMs, int readTimeoutMs) {
        this(baseUrl, cloudRunAuthMode, connectTimeoutMs, readTimeoutMs, METADATA_IDENTITY_URL, Clock.systemUTC());
    }

    /** Tests point {@code metadataIdentityUrl} at a local server and move {@code clock} by hand. */
    PeerHttp(String baseUrl, String cloudRunAuthMode, int connectTimeoutMs, int readTimeoutMs, String metadataIdentityUrl, Clock clock) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.cloudRunAuthMode = cloudRunAuthMode == null ? "auto" : cloudRunAuthMode.trim().toLowerCase(Locale.ROOT);
        this.metadataIdentityUrl = metadataIdentityUrl;
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(0, connectTimeoutMs));
        factory.setReadTimeout(Math.max(0, readTimeoutMs));
        this.client = RestClient.builder().baseUrl(this.baseUrl.isBlank() ? "http://localhost" : this.baseUrl).requestFactory(factory).build();
    }

    boolean configured() { return StringUtils.hasText(baseUrl); }

    /** Adds the identity token for this peer when there is one; without it a peer behind Cloud Run IAM answers 403. */
    void authorize(HttpHeaders headers) {
        String identityToken = cloudRunIdentityToken();
        if (StringUtils.hasText(identityToken)) headers.setBearerAuth(identityToken);
    }

    /**
     * An identity token whose audience is the peer's base URL, or empty when the peer is not on Cloud Run ({@code auto}),
     * auth is disabled ({@code never}) or the metadata server does not answer. {@code format=full} makes the token carry
     * the service-account email the peer checks. A fetched token is reused for {@link #TOKEN_REUSE}; a failure is not.
     */
    private String cloudRunIdentityToken() {
        if ("never".equals(cloudRunAuthMode)) return "";
        if ("auto".equals(cloudRunAuthMode) && !baseUrl.contains(".run.app")) return "";
        Instant now = clock.instant();
        CachedToken cached = cachedToken;
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.token();
        String audience = URLEncoder.encode(baseUrl, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(metadataIdentityUrl + "?audience=" + audience + "&format=full"))
                .timeout(Duration.ofSeconds(2)).header("Metadata-Flavor", "Google").GET().build();
        try {
            HttpResponse<String> response = metadataClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || !StringUtils.hasText(response.body())) return "";
            cachedToken = new CachedToken(response.body(), now.plus(TOKEN_REUSE));
            return response.body();
        } catch (IOException ex) {
            return "";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private record CachedToken(String token, Instant expiresAt) { }

    private static String trimTrailingSlash(String value) {
        String text = value == null ? "" : value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
