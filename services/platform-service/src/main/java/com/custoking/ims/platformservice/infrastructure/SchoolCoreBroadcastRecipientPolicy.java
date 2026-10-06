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
    private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(2);
    private final String baseUrl;
    private final String token;
    private final HttpClient metadata = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public SchoolCoreBroadcastRecipientPolicy(RestClient.Builder builder,
            @Value("${school-core.base-url:}") String baseUrl,
            @Value("${notification.broadcast.policy-token:}") String token) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token.trim();

    }

    public boolean configured() { return !baseUrl.isBlank() && !token.isBlank(); }

    public List<Recipient> resolve(long schoolId, UUID broadcastId, List<String> channels, List<Long> studentIds) {
        return resolveCurrent(schoolId,broadcastId,channels,studentIds,false);
    }
    public List<Recipient> resolveFeeReminders(long schoolId,String eventId,List<Long> studentIds) {
        UUID correlation=UUID.nameUUIDFromBytes(("fee-reminder:"+schoolId+":"+eventId).getBytes(StandardCharsets.UTF_8));
        return resolveCurrent(schoolId,correlation,List.of("SMS"),studentIds,true);
    }
    private List<Recipient> resolveCurrent(long schoolId,UUID broadcastId,List<String> channels,List<Long> studentIds,boolean feeReminder) {
        if (!configured()) throw new IllegalStateException("School recipient policy service is not configured");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schoolId", schoolId); body.put("broadcastId", broadcastId); body.put("channels", channels);
        body.put("communicationCategory", feeReminder ? "FEE_REMINDER" : "SCHOOL_NOTICE"); body.put("audienceType", feeReminder ? "EXPLICIT_STUDENTS" : "ALL_PARENTS");
        if (studentIds != null) body.put("studentIds", studentIds);
        if(!capacity.tryAcquire()) throw new IllegalStateException("Recipient policy capacity unavailable");
        List<Recipient> recipients;
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> response=null;
        try {
            var request=HttpRequest.newBuilder(URI.create(baseUrl+"/api/v1/internal/notifications/broadcast-recipients"+(feeReminder ? "/fee-reminders" : "")))
                .timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-Broadcast-Policy-Token",token);
            if(URI.create(baseUrl).getHost().endsWith(".run.app")) {
                String oidc=identityToken(); request.header("Authorization","Bearer "+oidc).header("X-Serverless-Authorization","Bearer "+oidc);
            }
            response=client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build(), info->new LimitedBody());
            var answer=response.get(5,java.util.concurrent.TimeUnit.SECONDS);
            if(answer.statusCode()!=200) throw new IllegalStateException("Recipient policy unavailable");
            recipients=mapper.readValue(answer.body(),new tools.jackson.core.type.TypeReference<List<Recipient>>(){});
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Recipient policy interrupted");
        } catch(Exception unavailable) { throw new IllegalStateException("Recipient policy unavailable"); }
        finally { if(response!=null && !response.isDone()) response.cancel(true); capacity.release(); }
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

    /** Bounds the complete response, including a peer that continuously dribbles bytes. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription=value;value.request(1); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>2*1024*1024) { subscription.cancel();result.completeExceptionally(new IllegalStateException("Policy response too large"));return; }
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
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
