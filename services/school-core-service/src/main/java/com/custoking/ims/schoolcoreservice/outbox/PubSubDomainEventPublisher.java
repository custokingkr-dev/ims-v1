package com.custoking.ims.schoolcoreservice.outbox;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.PublisherInterface;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real {@link DomainEventPublisher}: publishes the canonical event envelope
 * (per {@code docs/EVENT-ENVELOPE-CONTRACT.md}) to Google Cloud Pub/Sub.
 *
 * <p>Only active when {@code school-core.outbox.pubsub.topic-id} (env
 * {@code SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID}) is configured â€” i.e. in deployed
 * environments. Locally/in tests, where the topic is unset, this component
 * does not register and {@link LoggingDomainEventPublisher} (registered via
 * {@link OutboxPublisherConfiguration}'s {@code @ConditionalOnMissingBean})
 * remains the {@link DomainEventPublisher}.
 *
 * <p>Authenticates via Application Default Credentials (the Cloud Run
 * service account) â€” no key files are read or handled here.
 *
 * <p><b>At-least-once contract:</b> {@link #publish} blocks on the publish
 * future and lets any failure propagate as an unchecked exception. The
 * {@link OutboxRelay} only marks a row published after {@link #publish}
 * returns normally, so a thrown exception here leaves the row unpublished
 * for retry on the next relay tick.
 *
 * <p><b>A timed-out publish is not a failed one.</b> The client already holds
 * the message and keeps sending it; cancelling its future does not withdraw
 * it, it only loses the outcome. So a publish that outlasts its timeout is
 * kept, by event id, and is never cancelled: the relay's retry of that row
 * waits for it (and marks the row published once it succeeds) instead of
 * sending the event a second time. Only a publish known to have failed is
 * sent again. A retry that lands on another instance still sends a copy,
 * which consumers drop by {@code eventId}, so delivery stays at least once.
 *
 * <p>The first publish after the service starts opens the connection and
 * fetches credentials, on a newly started and possibly throttled instance,
 * so it is allowed {@code first-publish-timeout-ms} rather than the usual
 * {@code publish-timeout-ms}.
 */
@Component
@ConditionalOnProperty(name = "school-core.outbox.pubsub.topic-id")
public class PubSubDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PubSubDomainEventPublisher.class);
    private static final long SHUTDOWN_AWAIT_SECONDS = 30;
    /** The longest any one publish is waited for, whatever is configured. */
    static final long MAX_PUBLISH_TIMEOUT_MS = 60_000;
    /**
     * How long a settled publish is kept for the relay's retry of its row to collect. The relay retries a failed row within
     * an hour at most (its backoff caps at 3600 s), and a pending publish is always kept: the client settles it in the end.
     */
    private static final long SETTLED_RETENTION_NANOS = TimeUnit.HOURS.toNanos(1);

    private final ObjectMapper objectMapper;
    private final PublisherInterface publisher;
    private final long publishTimeoutMs;
    private final long firstPublishTimeoutMs;
    /** Publishes that outlasted their timeout, by event id: they may still complete, so the event is not sent again. */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();
    /** Whether a publish has completed since the service started, after which the usual timeout applies. */
    private volatile boolean connected;

    private record InFlight(ApiFuture<String> future, long sinceNanos) { }

    @Autowired
    public PubSubDomainEventPublisher(
            ObjectMapper objectMapper,
            @Value("${school-core.outbox.pubsub.topic-id}") String topicId,
            @Value("${school-core.outbox.pubsub.project-id:}") String projectId,
            @Value("${school-core.outbox.pubsub.publish-timeout-ms:5000}") long publishTimeoutMs,
            @Value("${school-core.outbox.pubsub.first-publish-timeout-ms:20000}") long firstPublishTimeoutMs) throws IOException {
        this(objectMapper, newPublisher(topicId, projectId), publishTimeoutMs, firstPublishTimeoutMs);
    }

    PubSubDomainEventPublisher(ObjectMapper objectMapper, PublisherInterface publisher, long publishTimeoutMs, long firstPublishTimeoutMs) {
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.publishTimeoutMs = bounded(publishTimeoutMs);
        this.firstPublishTimeoutMs = Math.max(this.publishTimeoutMs, bounded(firstPublishTimeoutMs));
    }

    private static long bounded(long timeoutMs) {
        return Math.max(1, Math.min(timeoutMs, MAX_PUBLISH_TIMEOUT_MS));
    }

    private static Publisher newPublisher(String topicId, String projectId) throws IOException {
        TopicName topicName = TopicName.of(resolveProjectId(projectId), topicId);
        Publisher publisher = Publisher.newBuilder(topicName).build();
        log.info("PubSubDomainEventPublisher active: publishing to {}", topicName);
        return publisher;
    }

    /** How long the next publish is waited for: the cold-start allowance until a publish has completed, then the usual timeout. */
    long publishTimeoutMs() {
        return connected ? publishTimeoutMs : firstPublishTimeoutMs;
    }

    @Override
    public void publish(EventEnvelope envelope) {
        publishWithin(envelope, publishTimeoutMs());
    }

    void publishWithin(EventEnvelope envelope, long remainingBudgetMs) {
        String eventId = envelope.eventId();
        forgetSettledPublishes();
        ApiFuture<String> future = earlierPublish(eventId);
        if (future == null) future = publisher.publish(buildMessage(envelope, objectMapper));
        try {
            String messageId = awaitPublish(future, Math.min(publishTimeoutMs(), remainingBudgetMs));
            inFlight.remove(eventId);
            connected = true;
            log.debug("Published event {} ({}) as Pub/Sub message {}",
                    envelope.eventId(), envelope.eventType(), messageId);
        } catch (TimeoutException ex) {
            inFlight.putIfAbsent(eventId, new InFlight(future, System.nanoTime()));
            throw new IllegalStateException("Pub/Sub publish deadline exceeded for event " + eventId
                    + "; the publish is still in flight and is collected on the next attempt rather than sent again", ex);
        } catch (InterruptedException ex) {
            inFlight.putIfAbsent(eventId, new InFlight(future, System.nanoTime()));
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing event " + eventId + " to Pub/Sub", ex);
        } catch (ExecutionException ex) {
            inFlight.remove(eventId);
            throw new IllegalStateException(
                    "Failed to publish event " + eventId + " to Pub/Sub", ex.getCause() != null ? ex.getCause() : ex);
        }
    }

    /**
     * The publish of this event that an earlier attempt left in flight, while it may still complete or has completed: sending
     * the event again would duplicate it. One that failed or was cancelled is forgotten, so the event is sent again.
     */
    private ApiFuture<String> earlierPublish(String eventId) {
        InFlight earlier = inFlight.get(eventId);
        if (earlier == null) return null;
        if (earlier.future().isDone() && !succeeded(earlier.future())) {
            inFlight.remove(eventId, earlier);
            return null;
        }
        return earlier.future();
    }

    private static boolean succeeded(ApiFuture<String> done) {
        if (done.isCancelled()) return false;
        try {
            done.get();
            return true;
        } catch (ExecutionException | CancellationException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Drops settled publishes no retry has come for, such as a row another instance published in the meantime. */
    private void forgetSettledPublishes() {
        long now = System.nanoTime();
        inFlight.values().removeIf(entry -> entry.future().isDone() && now - entry.sinceNanos() > SETTLED_RETENTION_NANOS);
    }

    /**
     * Waits up to {@code timeoutMs} (bounded by {@link #MAX_PUBLISH_TIMEOUT_MS}) for a publish. A publish that takes longer is
     * left running, never cancelled: the client sends the message anyway, and cancelling would only lose the outcome.
     */
    static String awaitPublish(ApiFuture<String> future, long timeoutMs)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(bounded(timeoutMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Builds the Pub/Sub message for an envelope: canonical envelope JSON
     * (payload nested as an object, never a stringified string) as the
     * message data, plus the routing/filtering attributes required by
     * {@code docs/EVENT-ENVELOPE-CONTRACT.md}. Package-visible and free of
     * any Pub/Sub client I/O so it can be unit-tested without a live topic.
     */
    static PubsubMessage buildMessage(EventEnvelope envelope, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", envelope.schemaVersion());
        root.put("eventId", envelope.eventId());
        root.put("eventKey", envelope.eventKey());
        root.put("eventType", envelope.eventType());
        root.put("eventVersion", envelope.eventVersion());
        root.put("aggregateType", envelope.aggregateType());
        root.put("aggregateId", envelope.aggregateId());
        root.put("occurredAt", envelope.occurredAt() == null ? null : envelope.occurredAt().toString());
        if (envelope.schoolId() != null) {
            root.put("schoolId", envelope.schoolId());
        }
        root.set("payload", parsePayload(envelope.payloadJson(), objectMapper));

        String envelopeJson = objectMapper.writeValueAsString(root);

        PubsubMessage.Builder builder = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(envelopeJson))
                .putAllAttributes(java.util.Map.of(
                        "eventId", nullToEmpty(envelope.eventId()),
                        "eventKey", nullToEmpty(envelope.eventKey()),
                        "eventType", nullToEmpty(envelope.eventType()),
                        "aggregateType", nullToEmpty(envelope.aggregateType()),
                        "aggregateId", nullToEmpty(envelope.aggregateId())));
        putIfPresent(builder, "traceparent", envelope.traceParent());
        putIfPresent(builder, "tracestate", envelope.traceState());
        return builder.build();
    }

    private static JsonNode parsePayload(String payloadJson, ObjectMapper objectMapper) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(payloadJson);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void putIfPresent(PubsubMessage.Builder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.putAttributes(key, value);
        }
    }

    private static String resolveProjectId(String configuredProjectId) {
        if (configuredProjectId != null && !configuredProjectId.isBlank()) {
            return configuredProjectId;
        }
        String fromEnv = System.getenv("GOOGLE_CLOUD_PROJECT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        fromEnv = System.getenv("GCP_PROJECT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        // Prod sets school-core.outbox.pubsub.project-id (SCHOOL_CORE_OUTBOX_PUBSUB_PROJECT_ID=$PROJECT_ID
        // in cloudbuild), so this throw only fires on a misconfiguration.
        throw new IllegalStateException(
                "school-core.outbox.pubsub.topic-id is set but no project id is configured "
                        + "(school-core.outbox.pubsub.project-id / GOOGLE_CLOUD_PROJECT / GCP_PROJECT)");
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (publisher instanceof Publisher client) {
            client.shutdown();
            client.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
