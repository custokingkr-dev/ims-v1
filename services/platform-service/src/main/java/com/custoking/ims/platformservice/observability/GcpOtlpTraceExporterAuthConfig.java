package com.custoking.ims.platformservice.observability;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpHttpSpanExporterBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class GcpOtlpTraceExporterAuthConfig {

    private static final List<String> CLOUD_PLATFORM_SCOPE = List.of("https://www.googleapis.com/auth/cloud-platform");

    @Bean
    OtlpHttpSpanExporterBuilderCustomizer gcpOtlpTraceExporterAuthCustomizer() {
        GoogleCredentialsHeaders headers = new GoogleCredentialsHeaders();
        return (OtlpHttpSpanExporterBuilder builder) -> builder.setHeaders(headers::get);
    }

    private static final class GoogleCredentialsHeaders {

        private volatile GoogleCredentials credentials;

        Map<String, String> get() {
            try {
                GoogleCredentials currentCredentials = credentials();
                currentCredentials.refreshIfExpired();
                AccessToken accessToken = currentCredentials.getAccessToken();
                if (accessToken == null || accessToken.getTokenValue() == null || accessToken.getTokenValue().isBlank()) {
                    currentCredentials.refresh();
                    accessToken = currentCredentials.getAccessToken();
                }
                if (accessToken == null || accessToken.getTokenValue() == null || accessToken.getTokenValue().isBlank()) {
                    throw new IOException("Google credentials did not return an access token");
                }
                Map<String, String> headers = new HashMap<>();
                headers.put("Authorization", "Bearer " + accessToken.getTokenValue());
                quotaProject().ifPresent((quotaProject) -> headers.put("x-goog-user-project", quotaProject));
                return Map.copyOf(headers);
            }
            catch (IOException ex) {
                throw new UncheckedIOException("Failed to refresh Google credentials for OTLP trace export", ex);
            }
        }

        private GoogleCredentials credentials() throws IOException {
            GoogleCredentials currentCredentials = this.credentials;
            if (currentCredentials == null) {
                synchronized (this) {
                    currentCredentials = this.credentials;
                    if (currentCredentials == null) {
                        currentCredentials = GoogleCredentials.getApplicationDefault().createScoped(CLOUD_PLATFORM_SCOPE);
                        this.credentials = currentCredentials;
                    }
                }
            }
            return currentCredentials;
        }

        private java.util.Optional<String> quotaProject() {
            return firstPresent("GOOGLE_CLOUD_QUOTA_PROJECT", "GOOGLE_CLOUD_PROJECT", "GCP_PROJECT");
        }

        private java.util.Optional<String> firstPresent(String... names) {
            for (String name : names) {
                String value = System.getenv(name);
                if (value != null && !value.isBlank()) {
                    return java.util.Optional.of(value);
                }
            }
            return java.util.Optional.empty();
        }
    }
}
