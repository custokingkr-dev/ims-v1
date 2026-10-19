package com.custoking.ims.schoolcoreservice.infrastructure;

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
import java.time.Duration;
import java.util.Locale;

/** Shared plumbing for school-core's calls to peer services: a bounded RestClient and Cloud Run identity tokens. */
final class PeerHttp {
    private final String baseUrl;
    private final String cloudRunAuthMode;
    private final HttpClient metadataClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final RestClient client;

    PeerHttp(String baseUrl, String cloudRunAuthMode, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.cloudRunAuthMode = cloudRunAuthMode == null ? "auto" : cloudRunAuthMode.trim().toLowerCase(Locale.ROOT);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(0, connectTimeoutMs));
        factory.setReadTimeout(Math.max(0, readTimeoutMs));
        this.client = RestClient.builder().baseUrl(this.baseUrl.isBlank() ? "http://localhost" : this.baseUrl).requestFactory(factory).build();
    }

    boolean configured() { return StringUtils.hasText(baseUrl); }

    /** A Cloud Run identity token for the peer, or empty when the peer is not on Cloud Run or auth is disabled. */
    String cloudRunIdentityToken() {
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
