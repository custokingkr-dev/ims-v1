package com.custoking.ims.billingservice.outbox;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tests {@link PubSubDomainEventPublisher#buildMessage} in isolation
 * from any live Pub/Sub topic: asserts the {@link PubsubMessage} built from
 * an {@link EventEnvelope} carries the canonical envelope JSON (with
 * {@code payload} nested as a JSON object, never a stringified string) as
 * its data, plus the five routing/filtering attributes required by
 * {@code docs/EVENT-ENVELOPE-CONTRACT.md}.
 */
class PubSubDomainEventPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildMessage_setsCanonicalEnvelopeJsonAsData_withPayloadNestedAsObject() {
        EventEnvelope envelope = new EventEnvelope(
                "ims.event-envelope.v1",
                "11111111-1111-1111-1111-111111111111",
                "InvoiceUpserted:42",
                "billing.invoice-upserted.v1",
                "v1",
                "SuperadminInvoice",
                "42",
                OffsetDateTime.of(2026, 6, 25, 12, 34, 56, 0, ZoneOffset.UTC),
                7L,
                "{\"id\":\"42\",\"total\":199.5}");

        PubsubMessage message = PubSubDomainEventPublisher.buildMessage(envelope, objectMapper);

        String json = message.getData().toString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("schemaVersion").asText()).isEqualTo("ims.event-envelope.v1");
        assertThat(root.get("eventId").asText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(root.get("eventKey").asText()).isEqualTo("InvoiceUpserted:42");
        assertThat(root.get("eventType").asText()).isEqualTo("billing.invoice-upserted.v1");
        assertThat(root.get("eventVersion").asText()).isEqualTo("v1");
        assertThat(root.get("aggregateType").asText()).isEqualTo("SuperadminInvoice");
        assertThat(root.get("aggregateId").asText()).isEqualTo("42");
        assertThat(root.get("occurredAt").asText()).isEqualTo("2026-06-25T12:34:56Z");
        assertThat(root.get("schoolId").asLong()).isEqualTo(7L);

        // payload must nest as a JSON OBJECT, not a stringified string.
        JsonNode payload = root.get("payload");
        assertThat(payload.isObject()).isTrue();
        assertThat(payload.get("id").asText()).isEqualTo("42");
        assertThat(payload.get("total").asDouble()).isEqualTo(199.5);

        assertThat(message.getAttributesMap())
                .containsEntry("eventId", "11111111-1111-1111-1111-111111111111")
                .containsEntry("eventKey", "InvoiceUpserted:42")
                .containsEntry("eventType", "billing.invoice-upserted.v1")
                .containsEntry("aggregateType", "SuperadminInvoice")
                .containsEntry("aggregateId", "42")
                .hasSize(5);
    }

    @Test
    void buildMessage_blankPayload_nestsEmptyObject() {
        EventEnvelope envelope = new EventEnvelope(
                "ims.event-envelope.v1",
                "id-1",
                "key-1",
                "billing.invoice-upserted.v1",
                "v1",
                "SuperadminInvoice",
                "1",
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "");

        PubsubMessage message = PubSubDomainEventPublisher.buildMessage(envelope, objectMapper);

        JsonNode root = objectMapper.readTree(message.getData().toString(StandardCharsets.UTF_8));
        assertThat(root.get("payload").isObject()).isTrue();
        assertThat(root.get("payload").isEmpty()).isTrue();
        assertThat(root.has("schoolId")).isFalse();
    }

    @Test
    void buildMessage_dataIsUtf8EncodedJson() {
        EventEnvelope envelope = new EventEnvelope(
                "ims.event-envelope.v1",
                "id-2",
                "key-2",
                "billing.invoice-upserted.v1",
                "v1",
                "SuperadminInvoice",
                "2",
                OffsetDateTime.now(ZoneOffset.UTC),
                3L,
                "{\"a\":1}");

        PubsubMessage message = PubSubDomainEventPublisher.buildMessage(envelope, objectMapper);

        assertThat(message.getData()).isEqualTo(
                ByteString.copyFromUtf8(message.getData().toString(StandardCharsets.UTF_8)));
    }

    @Test
    void buildMessage_addsW3cTraceContextAttributesWhenPresent() {
        EventEnvelope envelope = new EventEnvelope(
                "ims.event-envelope.v1",
                "id-3",
                "key-3",
                "billing.invoice-upserted.v1",
                "v1",
                "SuperadminInvoice",
                "3",
                OffsetDateTime.now(ZoneOffset.UTC),
                4L,
                "{\"a\":1}",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "vendor=value");

        PubsubMessage message = PubSubDomainEventPublisher.buildMessage(envelope, objectMapper);

        assertThat(message.getAttributesMap())
                .containsEntry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .containsEntry("tracestate", "vendor=value");
    }
}
