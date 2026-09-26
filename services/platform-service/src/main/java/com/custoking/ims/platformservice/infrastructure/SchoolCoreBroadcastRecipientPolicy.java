package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@Component
public class SchoolCoreBroadcastRecipientPolicy implements BroadcastRecipientPolicy {
    private final RestClient client;
    private final String baseUrl;
    private final String token;
    private final HttpClient metadata = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SchoolCoreBroadcastRecipientPolicy(RestClient.Builder builder,
            @Value("${school-core.base-url:}") String baseUrl,
            @Value("${notification.broadcast.policy-token:}") String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(15000);
        this.client = builder.clone().baseUrl(this.baseUrl.isBlank() ? "http://localhost" : this.baseUrl).requestFactory(factory).build();
    }

    public boolean configured() { return !baseUrl.isBlank() && !token.isBlank(); }

    public List<Recipient> resolve(long schoolId, UUID broadcastId, List<String> channels, List<Long> studentIds) {
        if (!configured()) throw new IllegalStateException("School recipient policy service is not configured");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schoolId", schoolId); body.put("broadcastId", broadcastId); body.put("channels", channels);
        body.put("communicationCategory", "SCHOOL_NOTICE"); body.put("audienceType", "ALL_PARENTS");
        if (studentIds != null) body.put("studentIds", studentIds);
        List<Recipient> recipients = client.post().uri("/api/v1/internal/notifications/broadcast-recipients")
                .header("X-Broadcast-Policy-Token", token).headers(headers -> {
                    if (URI.create(baseUrl).getHost().endsWith(".run.app")) headers.setBearerAuth(identityToken());
                }).body(body).retrieve().body(new ParameterizedTypeReference<List<Recipient>>() {});
        if (recipients == null) throw new IllegalStateException("Recipient policy response is missing");
        Set<String> seen = new HashSet<>();
        for (Recipient recipient : recipients) {
            if (recipient == null || recipient.schoolId() != schoolId || !channels.contains(recipient.channel()) || recipient.studentId() <= 0
                    || (studentIds != null && !studentIds.contains(recipient.studentId()))
                    || !eventId(broadcastId, recipient.studentId(), recipient.channel()).equals(recipient.eventId())
                    || !seen.add(recipient.eventId()) || recipient.reason() == null
                    || (recipient.allowed() && (recipient.guardianId() == null || recipient.destination() == null || recipient.destinationSha256() == null || recipient.policyEvidence() == null))) {
                throw new IllegalStateException("Recipient policy response does not match the requested scope");
            }
        }
        if (studentIds != null && seen.size() != studentIds.stream().distinct().count() * channels.stream().distinct().count()) {
            throw new IllegalStateException("Recipient policy response is incomplete");
        }
        return recipients;
    }

    public static String eventId(UUID id, long studentId, String channel) { return "broadcast:" + id + ":" + studentId + ":" + channel; }

    private String identityToken() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity?audience=" + URLEncoder.encode(baseUrl, StandardCharsets.UTF_8)))
                    .header("Metadata-Flavor", "Google").timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = metadata.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().isBlank()) throw new IllegalStateException("Unable to authenticate to school policy service");
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Policy authentication interrupted", error);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Policy authentication unavailable", error);
        }
    }
}
