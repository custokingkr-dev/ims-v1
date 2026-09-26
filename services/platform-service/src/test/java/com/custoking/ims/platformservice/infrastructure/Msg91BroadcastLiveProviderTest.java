package com.custoking.ims.platformservice.infrastructure;

import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Prepared;
import com.custoking.ims.platformservice.application.BroadcastRecipientPolicy.Recipient;
import com.custoking.ims.platformservice.application.LiveBroadcastConfiguration;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Msg91BroadcastLiveProviderTest {
    private static final String DESTINATION = "guardian@synthetic.invalid";
    private static final String HASH = Msg91BroadcastLiveProvider.sha256(DESTINATION);
    private static final UUID BROADCAST = UUID.fromString("847cd80f-c6fb-4d78-a13a-e667b0b316d3");
    private static final String EVENT = "broadcast:" + BROADCAST + ":1:EMAIL";
    private static final String ACCEPTED = "{\"data\":{\"unique_id\":\"a1d2e4b8-4d04-41f9-b8f2-34be6a5c6d49\"},\"errors\":{},\"status\":\"success\",\"hasError\":false}";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void fixedVerifiedConfigurationIsRequiredAndOffByDefault() {
        var f = fixture(request -> new Msg91BroadcastLiveProvider.Reply(200, ACCEPTED));
        assertThat(f.provider.configured()).isTrue();
        f.email.setTemplateVerified(false);
        assertThat(f.provider.configured()).isFalse();
        f.email.setTemplateVerified(true); f.email.setDomain("another.invalid");
        assertThat(f.provider.configured()).isFalse();
        f.email.setDomain("synthetic.invalid"); f.credentials.setAuthKey("\r\nsecret");
        assertThat(f.provider.configured()).isFalse();
        assertThat(new LiveBroadcastEmailProperties().isTemplateVerified()).isFalse();
    }

    @Test void buildsOnlyOneAuthoritativeRecipientAndStableCanonicalFingerprint() {
        var f = fixture(request -> { throw new AssertionError("prepare must not make HTTP calls"); });
        Prepared first = f.provider.prepare(row(), decision());
        assertThat(first).isEqualTo(f.provider.prepare(row(), decision()));
        assertThat(first.correlationId()).matches("ims[0-9a-f]{48}").hasSize(51);
        assertThat(first.requestSha256()).isEqualTo(Msg91BroadcastLiveProvider.sha256(first.body()));
        assertThat(first.senderSha256()).isEqualTo(Msg91BroadcastLiveProvider.sha256("sender@synthetic.invalid"));
        var body = mapper.readTree(first.body());
        assertThat(body.path("recipients").size()).isEqualTo(1);
        assertThat(body.path("recipients").path(0).path("to").size()).isEqualTo(1);
        assertThat(body.path("recipients").path(0).path("to").path(0).path("email").asText()).isEqualTo(DESTINATION);
        assertThat(body.path("recipients").path(0).path("to").path(0).path("CRQID").asText()).isEqualTo(first.correlationId());
        assertThat(body.path("recipients").path(0).path("variables").path("message").asText()).contains("&lt;script&gt;");
        assertThat(body.path("template_id").asText()).isEqualTo("school_notice");
        assertThat(body.has("CRQID")).isFalse();
        assertThat(body.has("attachments")).isFalse();
        assertThat(first.toString()).doesNotContain(DESTINATION, "script", "sender@");
    }

    @Test void rejectsDeniedMismatchedOrNotAllowlistedScopeBeforeTransport() {
        var f = fixture(request -> { throw new AssertionError("HTTP must be unreachable"); });
        Recipient d = decision();
        List<Recipient> invalid = List.of(
                new Recipient(2, 1, "EMAIL", EVENT, true, "", "g1", DESTINATION, HASH, Map.of()),
                new Recipient(1, 2, "EMAIL", EVENT, true, "", "g1", DESTINATION, HASH, Map.of()),
                new Recipient(1, 1, "EMAIL", EVENT, false, "DENIED", "g1", DESTINATION, HASH, Map.of()),
                new Recipient(1, 1, "EMAIL", EVENT, true, "", "g2", DESTINATION, HASH, Map.of()),
                new Recipient(1, 1, "EMAIL", EVENT, true, "", "g1", "other@synthetic.invalid", HASH, Map.of()),
                new Recipient(1, 1, "SMS", EVENT, true, "", "g1", DESTINATION, HASH, Map.of()));
        for (Recipient bad : invalid) assertThatThrownBy(() -> f.provider.prepare(row(), bad)).isInstanceOf(IllegalArgumentException.class);
        var r = row();
        assertThatThrownBy(() -> f.provider.prepare(new QueuedRecipient(r.id(), r.broadcastId(), 2, 1, "EMAIL", EVENT, "g1", HASH, 0, r.title(), r.message(), "LIVE"), d)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.provider.prepare(new QueuedRecipient(r.id(), r.broadcastId(), 1, 1, "EMAIL", EVENT, "g1", HASH, 0, r.title(), r.message(), "DRY_RUN"), d)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sendsExactlyOneFixedEndpointRequestAndRecordsAcceptanceNotDelivery() {
        List<HttpRequest> requests = new ArrayList<>();
        var f = fixture(request -> { requests.add(request); return new Msg91BroadcastLiveProvider.Reply(200, ACCEPTED); });
        var result = f.provider.submit(f.provider.prepare(row(), decision()));
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.reason()).isEqualTo("PROVIDER_QUEUED");
        assertThat(result.providerMessageId()).isEqualTo("a1d2e4b8-4d04-41f9-b8f2-34be6a5c6d49");
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().uri()).isEqualTo(Msg91BroadcastLiveProvider.ENDPOINT);
        assertThat(requests.getFirst().method()).isEqualTo("POST");
        assertThat(requests.getFirst().timeout()).contains(Duration.ofSeconds(10));
        // An unsafe generic-provider endpoint is never used by the dedicated live transport.
        f.credentials.setEmailEndpoint("http://localhost:9/unsafe");
        f.provider.submit(f.provider.prepare(row(), decision()));
        assertThat(requests.getLast().uri()).isEqualTo(Msg91BroadcastLiveProvider.ENDPOINT);
    }

    @Test void definiteRejectionIsNeverReportedAsDeliveryOrRetried() {
        for (int code : List.of(400, 401, 403, 404, 405, 413, 415, 422)) {
            AtomicInteger calls = new AtomicInteger();
            var f = fixture(request -> { calls.incrementAndGet(); return new Msg91BroadcastLiveProvider.Reply(code, "sensitive upstream error"); });
            var result = f.provider.submit(f.provider.prepare(row(), decision()));
            assertThat(result.status()).isEqualTo("REJECTED");
            assertThat(result.providerMessageId()).isNull();
            assertThat(result.reason()).doesNotContain("sensitive");
            assertThat(calls).hasValue(1);
        }
    }

    @Test void ambiguousHttpResponsesAreUnknownWithNoRetry() {
        for (int code : List.of(201, 202, 301, 307, 408, 409, 425, 429, 500, 502, 503)) {
            AtomicInteger calls = new AtomicInteger();
            var f = fixture(request -> { calls.incrementAndGet(); return new Msg91BroadcastLiveProvider.Reply(code, ACCEPTED); });
            assertThat(f.provider.submit(f.provider.prepare(row(), decision())).status()).isEqualTo("UNKNOWN");
            assertThat(calls).hasValue(1);
        }
    }

    @Test void malformedPartialAndContradictorySuccessesRemainUnknown() {
        for (String response : List.of("{", "{}", "[]", "null", "<html>secret</html>",
                ACCEPTED.replace("false", "true"), ACCEPTED.replace("\"errors\":{}", "\"errors\":{\"error\":\"failed\"}"),
                ACCEPTED.replace("a1d2e4b8-4d04-41f9-b8f2-34be6a5c6d49", "secret@synthetic.invalid"),
                "{\"status\":\"error\",\"hasError\":true}", "x".repeat(16_385))) {
            var f = fixture(request -> new Msg91BroadcastLiveProvider.Reply(200, response));
            var result = f.provider.submit(f.provider.prepare(row(), decision()));
            assertThat(result.status()).isEqualTo("UNKNOWN");
            assertThat(result.providerMessageId()).isNull();
            assertThat(result.toString()).doesNotContain("secret", DESTINATION);
        }
    }

    @Test void timeoutAndTransportErrorsAreUnknownWithoutExposingSecretsOrRetrying() {
        for (Exception error : List.of(new HttpTimeoutException("secret auth key and body"), new IOException(DESTINATION))) {
            AtomicInteger calls = new AtomicInteger();
            var f = fixture(request -> { calls.incrementAndGet(); throw error; });
            var result = f.provider.submit(f.provider.prepare(row(), decision()));
            assertThat(result.status()).isEqualTo("UNKNOWN");
            assertThat(result.toString()).doesNotContain("secret", DESTINATION);
            assertThat(calls).hasValue(1);
        }
    }

    @Test void interruptedSubmissionPreservesInterruptAndDoesNotRetry() {
        var f = fixture(request -> { throw new InterruptedException("sensitive"); });
        try {
            assertThat(f.provider.submit(f.provider.prepare(row(), decision())).reason()).isEqualTo("PROVIDER_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void refusesTransactionsAndTamperedOrStalePreparedBodiesBeforeNetwork() {
        var f = fixture(request -> { throw new AssertionError("HTTP must be unreachable"); });
        Prepared p = f.provider.prepare(row(), decision());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> f.provider.submit(p)).hasMessage("Provider submission cannot run inside a database transaction"); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        String tampered = p.body().replace("\"template_id\":", "\"bcc\":[{\"email\":\"attacker@synthetic.invalid\"}],\"template_id\":");
        assertThat(f.provider.submit(new Prepared(p.channel(), p.correlationId(), p.destinationSha256(), Msg91BroadcastLiveProvider.sha256(tampered), p.senderSha256(), tampered)).status()).isEqualTo("REJECTED");
        assertThat(f.provider.submit(new Prepared(p.channel(), p.correlationId(), p.destinationSha256(), "0".repeat(64), p.senderSha256(), p.body())).status()).isEqualTo("REJECTED");
        f.email.setTemplateId("changed_template");
        assertThat(f.provider.submit(p).status()).isEqualTo("REJECTED");
    }

    @Test void bodyPublisherCannotReplayEvenWhenJvmRetriesAreEnabledElsewhere() {
        var publisher = Msg91BroadcastLiveProvider.singleUseBody("private request");
        var first = new Probe(); var second = new Probe();
        publisher.subscribe(first); publisher.subscribe(second);
        assertThat(first.content.toString()).isEqualTo("private request");
        assertThat(first.failure).isNull();
        assertThat(second.content).isEmpty();
        assertThat(second.failure).isInstanceOf(IOException.class).hasMessage("Provider request replay is prohibited");
    }

    @Test void responseBodyIsBoundedAcrossChunksAndUpstreamExceptionsAreRedacted() {
        var body = new Msg91BroadcastLiveProvider.LimitedBodySubscriber(5);
        AtomicInteger cancelled = new AtomicInteger();
        body.onSubscribe(new Flow.Subscription() { public void request(long n) {} public void cancel() { cancelled.incrementAndGet(); } });
        body.onNext(List.of(ByteBuffer.wrap("abc".getBytes(StandardCharsets.UTF_8))));
        body.onNext(List.of(ByteBuffer.wrap("def".getBytes(StandardCharsets.UTF_8))));
        assertThat(cancelled).hasValue(1);
        assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
        var failure = new Msg91BroadcastLiveProvider.LimitedBodySubscriber(5);
        failure.onError(new IOException("sensitive body"));
        assertThatThrownBy(() -> failure.getBody().toCompletableFuture().join()).hasCauseInstanceOf(IOException.class)
                .hasMessageNotContaining("sensitive");
    }

    private Fixture fixture(Msg91BroadcastLiveProvider.Transport transport) {
        var live = new LiveBroadcastConfiguration(true, "EMAIL", true, "1", HASH, "w".repeat(40));
        var email = new LiveBroadcastEmailProperties(); email.setSenderEmail("Sender@Synthetic.Invalid");
        email.setSenderName("Synthetic school"); email.setDomain("synthetic.invalid"); email.setTemplateId("school_notice"); email.setTemplateVerified(true);
        var credentials = new Msg91Properties(); credentials.setAuthKey("a".repeat(32));
        return new Fixture(new Msg91BroadcastLiveProvider(live, email, credentials, mapper, transport), email, credentials);
    }
    private QueuedRecipient row() { return new QueuedRecipient(UUID.randomUUID(), BROADCAST, 1, 1, "EMAIL", EVENT, "g1", HASH, 0, "Test notice", "<script>never markup</script>\nA & B", "LIVE"); }
    private Recipient decision() { return new Recipient(1, 1, "EMAIL", EVENT, true, "ALLOWED", "g1", DESTINATION, HASH, Map.of()); }
    private record Fixture(Msg91BroadcastLiveProvider provider, LiveBroadcastEmailProperties email, Msg91Properties credentials) {}
    private static final class Probe implements Flow.Subscriber<ByteBuffer> {
        final StringBuilder content = new StringBuilder(); Throwable failure;
        public void onSubscribe(Flow.Subscription value) { value.request(Long.MAX_VALUE); }
        public void onNext(ByteBuffer bytes) { byte[] part = new byte[bytes.remaining()]; bytes.get(part); content.append(new String(part, StandardCharsets.UTF_8)); }
        public void onError(Throwable error) { failure = error; }
        public void onComplete() {}
    }
}
