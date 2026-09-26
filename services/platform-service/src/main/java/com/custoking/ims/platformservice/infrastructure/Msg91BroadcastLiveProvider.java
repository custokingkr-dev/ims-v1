package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.BroadcastLiveProvider;
import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy;
import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** EMAIL-only, fixed official endpoint. No retries: an ambiguous attempt requires reconciliation. */
@Component
@EnableConfigurationProperties({Msg91Properties.class, LiveBroadcastEmailProperties.class})
public class Msg91BroadcastLiveProvider implements BroadcastLiveProvider {
    static final URI ENDPOINT = URI.create("https://control.msg91.com/api/v5/email/send");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int RESPONSE_LIMIT = 16_384;
    static final int REQUEST_LIMIT = 65_536;
    private final LiveBroadcastConfiguration live;
    private final LiveBroadcastEmailProperties email;
    private final Msg91Properties credentials;
    private final ObjectMapper mapper;
    private final Transport transport;

    @Autowired
    public Msg91BroadcastLiveProvider(LiveBroadcastConfiguration live, LiveBroadcastEmailProperties email,
                                     Msg91Properties credentials, ObjectMapper mapper) {
        this(live, email, credentials, mapper, new SingleRequestTransport());
    }

    // Package-private injection is for offline tests, not an arbitrary runtime URL/configuration.
    Msg91BroadcastLiveProvider(LiveBroadcastConfiguration live, LiveBroadcastEmailProperties email,
                              Msg91Properties credentials, ObjectMapper mapper, Transport transport) {
        this.live = live; this.email = email; this.credentials = credentials; this.mapper = mapper; this.transport = transport;
    }

    @Override public boolean configured() {
        String sender = normalized(email.getSenderEmail());
        String domain = normalized(email.getDomain());
        return live.configured() && credentials.getAuthKey().matches("[A-Za-z0-9_-]{16,256}")
                && email.isTemplateVerified() && validEmail(sender) && domain.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                && sender.substring(sender.indexOf('@') + 1).equals(domain)
                && safeText(email.getSenderName(), 100) && !email.getSenderName().isBlank()
                && email.getTemplateId().matches("[A-Za-z0-9_-]{1,150}");
    }

    @Override public Prepared prepare(BroadcastDispatchRepository.QueuedRecipient row, BroadcastRecipientPolicy.Recipient decision) {
        if (!configured()) throw new IllegalStateException("Live broadcast email is not configured");
        if (row == null || decision == null || !"LIVE".equals(row.mode()) || !"EMAIL".equals(row.channel())
                || !live.schoolAllowed(row.schoolId()) || !decision.allowed() || row.schoolId() != decision.schoolId()
                || row.studentId() != decision.studentId() || !Objects.equals(row.channel(), decision.channel())
                || !Objects.equals(row.eventId(), decision.eventId()) || !Objects.equals(row.guardianId(), decision.guardianId())
                || row.guardianId() == null || row.guardianId().isBlank() || row.broadcastId() == null
                || !SchoolCoreBroadcastRecipientPolicy.eventId(row.broadcastId(), row.studentId(), row.channel()).equals(row.eventId())
                || !Objects.equals(row.destinationSha256(), decision.destinationSha256())
                || !live.destinationAllowed(row.destinationSha256())) {
            throw new IllegalArgumentException("Live broadcast recipient does not match the approved scope");
        }
        String destination = normalized(decision.destination());
        if (!validEmail(destination) || !sha256(destination).equals(row.destinationSha256())
                || !safeText(row.title(), 255) || row.title().isBlank() || !safeMessage(row.message(), 10_000)) {
            throw new IllegalArgumentException("Live broadcast recipient or content is invalid");
        }
        String correlation = Msg91NotificationDeliveryProvider.correlationId(row.eventId());
        String body = body(destination, correlation, htmlText(row.title()), htmlText(row.message()));
        if (body.getBytes(StandardCharsets.UTF_8).length > REQUEST_LIMIT) throw new IllegalArgumentException("Live broadcast content is too large");
        return new Prepared("EMAIL", correlation, row.destinationSha256(), sha256(body), sha256(normalized(email.getSenderEmail())), body);
    }

    @Override public Result submit(Prepared prepared) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provider submission cannot run inside a database transaction");
        }
        if (!configured()) return rejected("LIVE_EMAIL_NOT_CONFIGURED");
        if (!validPrepared(prepared)) return rejected("INVALID_PREPARED_EMAIL");
        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(REQUEST_TIMEOUT)
                    .header("accept", "application/json").header("content-type", "application/json")
                    .header("authkey", credentials.getAuthKey())
                    .POST(singleUseBody(prepared.body())).build();
            Reply reply = transport.post(request); // Exactly one application-level attempt.
            return result(reply);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknown("PROVIDER_INTERRUPTED");
        } catch (Exception failure) {
            // Transport/parser exceptions may contain auth headers, destination or body: never log or wrap them.
            return unknown("PROVIDER_OUTCOME_UNCONFIRMED");
        }
    }

    private boolean validPrepared(Prepared value) {
        try {
            if (value == null || !"EMAIL".equals(value.channel()) || value.body() == null
                    || value.body().getBytes(StandardCharsets.UTF_8).length > REQUEST_LIMIT
                    || value.correlationId() == null || !value.correlationId().matches("ims[0-9a-f]{48}")
                    || !sha256(value.body()).equals(value.requestSha256())
                    || !sha256(normalized(email.getSenderEmail())).equals(value.senderSha256())
                    || !live.destinationAllowed(value.destinationSha256())) return false;
            JsonNode body = mapper.readTree(value.body());
            String destination = body.path("recipients").path(0).path("to").path(0).path("email").asText("");
            JsonNode variables = body.path("recipients").path(0).path("variables");
            if (!validEmail(destination) || !destination.equals(normalized(destination)) || !sha256(destination).equals(value.destinationSha256())
                    || !variables.path("title").isString() || !variables.path("message").isString()) return false;
            String title = variables.path("title").asText();
            String message = variables.path("message").asText();
            // Exact regeneration rejects added recipients, CC/BCC, caller templates and arbitrary JSON passthrough.
            return safeText(title, 2_000) && safeMessage(message, 60_000)
                    && !title.contains("<") && !message.contains("<")
                    && body(destination, value.correlationId(), title, message).equals(value.body());
        } catch (RuntimeException invalid) { return false; }
    }

    private String body(String destination, String correlation, String title, String message) {
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("email", destination); to.put("CRQID", correlation);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("title", title); variables.put("message", message);
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("to", List.of(to)); recipient.put("variables", variables);
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("name", email.getSenderName()); from.put("email", normalized(email.getSenderEmail()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", List.of(recipient)); body.put("from", from);
        body.put("domain", normalized(email.getDomain())); body.put("template_id", email.getTemplateId());
        return mapper.writeValueAsString(body);
    }

    private Result result(Reply reply) {
        if (reply == null || reply.body() == null || reply.body().getBytes(StandardCharsets.UTF_8).length > RESPONSE_LIMIT) return unknown("PROVIDER_RESPONSE_INVALID");
        // Only explicit client-side refusal statuses are definitive; 408/409/425/429/5xx may follow acceptance.
        if (Set.of(400, 401, 403, 404, 405, 413, 415, 422).contains(reply.statusCode())) return rejected("PROVIDER_HTTP_REJECTED");
        if (reply.statusCode() != 200) return unknown("PROVIDER_HTTP_UNCONFIRMED");
        try {
            JsonNode response = mapper.readTree(reply.body());
            JsonNode errors = response.path("errors");
            String id = response.path("data").path("unique_id").asText("");
            if ("success".equals(response.path("status").asText()) && response.path("hasError").isBoolean()
                    && !response.path("hasError").asBoolean() && errors.isObject() && errors.isEmpty()
                    && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                return new Result("ACCEPTED", UUID.fromString(id).toString(), "PROVIDER_QUEUED");
            }
            return unknown("PROVIDER_RESPONSE_UNCONFIRMED");
        } catch (RuntimeException invalid) { return unknown("PROVIDER_RESPONSE_INVALID"); }
    }

    private static Result rejected(String reason) { return new Result("REJECTED", null, reason); }
    private static Result unknown(String reason) { return new Result("UNKNOWN", null, reason); }
    private static String normalized(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static boolean validEmail(String value) { return value != null && value.length() <= 254 && value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}"); }
    private static boolean safeText(String value, int max) { return value != null && value.length() <= max && value.chars().noneMatch(c -> c < 32 || c == 127); }
    private static boolean safeMessage(String value, int max) { return value != null && !value.isBlank() && value.length() <= max && value.chars().noneMatch(c -> (c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 127); }
    private static String htmlText(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }

    @FunctionalInterface interface Transport { Reply post(HttpRequest request) throws Exception; }
    record Reply(int statusCode, String body) { @Override public String toString() { return "Reply[statusCode=" + statusCode + ", body=REDACTED]"; } }

    /** Even if JVM retry settings change, the body cannot be subscribed/retransmitted a second time. */
    static HttpRequest.BodyPublisher singleUseBody(String value) {
        HttpRequest.BodyPublisher delegate = HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8);
        AtomicBoolean consumed = new AtomicBoolean();
        return new HttpRequest.BodyPublisher() {
            public long contentLength() { return delegate.contentLength(); }
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                if (consumed.compareAndSet(false, true)) { delegate.subscribe(subscriber); return; }
                subscriber.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() {} });
                subscriber.onError(new IOException("Provider request replay is prohibited"));
            }
        };
    }

    static final class SingleRequestTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        @Override public Reply post(HttpRequest request) throws Exception {
            CompletableFuture<HttpResponse<String>> response = client.sendAsync(request, info -> new LimitedBodySubscriber(RESPONSE_LIMIT));
            try {
                HttpResponse<String> received = response.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                return new Reply(received.statusCode(), received.body());
            } finally { if (!response.isDone()) response.cancel(true); }
        }
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<String> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        public CompletionStage<String> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Provider response exceeded its limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(new IOException("Provider response was not completed")); }
        public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
    }
}
