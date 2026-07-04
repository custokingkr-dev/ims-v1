package com.custoking.ims.billingservice.outbox;

import java.time.OffsetDateTime;

/**
 * Canonical event envelope mirroring {@code docs/EVENT-ENVELOPE-CONTRACT.md}.
 *
 * <p>{@code eventId} is the outbox row id and is the consumer's idempotency key.
 * {@code payloadJson} carries the original domain event body as a JSON string
 * (stored verbatim from the {@code outbox_events.payload} JSONB column).
 */
public record EventEnvelope(
        String schemaVersion,
        String eventId,
        String eventKey,
        String eventType,
        String eventVersion,
        String aggregateType,
        String aggregateId,
        OffsetDateTime occurredAt,
        Long schoolId,
        String payloadJson) {
}
