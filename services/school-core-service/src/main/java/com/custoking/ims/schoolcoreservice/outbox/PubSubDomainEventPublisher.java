package com.custoking.ims.schoolcoreservice.outbox;

import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Real {@link DomainEventPublisher}: publishes the canonical event envelope
 * (per {@code docs/EVENT-ENVELOPE-CONTRACT.md}) to Google Cloud Pub/Sub.
 *
 * <p>Only active when {@code school-core.outbox.pubsub.topic-id} (env
 * {@code SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID}) is configured — i.e. in deployed
 * environments. Locally/in tests, where the topic is unset, this component
 * does not register and {@link LoggingDomainEventPublisher} (registered via
 * {@link OutboxPublisherConfiguration}'s {@code @ConditionalOnMissingBean})
 * remains the {@link DomainEventPublisher}.
 *
 * <p>Authenticates via Application Default Credentials (the Cloud Run
 * service account) — no key files are read or handled here.
 *
 * <p><b>At-least-once contract:</b> {@link #publish} blocks on the publish
 * future and lets any failure propagate as an unchecked exception. The
 * {@link OutboxRelay} only marks a row published after {@link #publish}
 * returns normally, so a thrown exception here leaves the row unpublished
 * for retry on the next relay tick.
 */
@Component
@ConditionalOnProperty(name = "school-core.outbox.pubsub.topic-id")
public class PubSubDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PubSubDomainEventPublisher.class);
    private static final long SHUTDOWN_AWAIT_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private final Publisher publisher;

    public PubSubDomainEventPublisher(
            ObjectMapper objectMapper,
            @Value("${school-core.outbox.pubsub.topic-id}") String topicId,
            @Value("${school-core.outbox.pubsub.project-id:}") String projectId) throws IOException {
        this.objectMapper = objectMapper;
        String resolvedProjectId = resolveProjectId(projectId);
        TopicName topicName = TopicName.of(resolvedProjectId, topicId);
        this.publisher = Publisher.newBuilder(topicName).build();
        log.info("PubSubDomainEventPublisher active: publishing to {}", topicName);
    }

    @Override
    public void publish(EventEnvelope envelope) {
        PubsubMessage message = buildMessage(envelope, objectMapper);
        ApiFuture<String> future = publisher.publish(message);
        try {
            String messageId = future.get();
            log.debug("Published event {} ({}) as Pub/Sub message {}",
                    envelope.eventId(), envelope.eventType(), messageId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while publishing event " + envelope.eventId() + " to Pub/Sub", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(
                    "Failed to publish event " + envelope.eventId() + " to Pub/Sub", ex.getCause() != null ? ex.getCause() : ex);
        }
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

        return PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(envelopeJson))
                .putAllAttributes(java.util.Map.of(
                        "eventId", nullToEmpty(envelope.eventId()),
                        "eventKey", nullToEmpty(envelope.eventKey()),
                        "eventType", nullToEmpty(envelope.eventType()),
                        "aggregateType", nullToEmpty(envelope.aggregateType()),
                        "aggregateId", nullToEmpty(envelope.aggregateId())))
                .build();
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
        if (publisher != null) {
            publisher.shutdown();
            publisher.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }
}
