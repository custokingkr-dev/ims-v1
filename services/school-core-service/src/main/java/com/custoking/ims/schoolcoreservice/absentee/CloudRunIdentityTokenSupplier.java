package com.custoking.ims.schoolcoreservice.absentee;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Mints a Google-signed OIDC identity token for a private Cloud Run peer. Same mechanism as
 * platform-service's {@code ApprovalCommandClient}: audience = the peer's base URL, token from the
 * metadata server; any failure yields {@code ""} so the header is simply omitted (the peer's IAM
 * then answers 403, which the worker records as a transient failure rather than crashing).
 */
@FunctionalInterface
public interface CloudRunIdentityTokenSupplier {

    String tokenFor(String audience);

    /** {@code auto} activates only for {@code *.run.app} URLs; {@code never} disables; {@code always} forces. */
    static CloudRunIdentityTokenSupplier metadataServer(String authMode) {
        String mode = authMode == null ? "auto" : authMode.trim().toLowerCase();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return audience -> {
            if ("never".equals(mode)) return "";
            if ("auto".equals(mode) && (audience == null || !audience.contains(".run.app"))) return "";
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience="
                                    + URLEncoder.encode(audience, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Metadata-Flavor", "Google")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 ? response.body() : "";
            } catch (IOException ex) {
                return "";
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "";
            }
        };
    }
}
