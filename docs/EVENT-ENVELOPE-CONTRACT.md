# Event Envelope Contract

All externally published outbox messages use a canonical JSON envelope as Pub/Sub or HTTP message data.

## Envelope

```json
{
  "schemaVersion": "ims.event-envelope.v1",
  "eventId": "outbox-row-uuid",
  "eventKey": "PaymentRecorded:123",
  "eventType": "fees.payment-recorded.v1",
  "eventVersion": "v1",
  "aggregateType": "Payment",
  "aggregateId": "123",
  "occurredAt": "2026-06-25T12:34:56Z",
  "publishedAttempt": 1,
  "producer": "backend",
  "tenantId": "optional",
  "schoolId": "optional",
  "actorUserId": "optional",
  "correlationId": "optional",
  "payload": {
    "domainSpecific": "event body"
  }
}
```

## Rules

- `schemaVersion` is required and currently fixed to `ims.event-envelope.v1`.
- `eventId` is the outbox event id and is the consumer idempotency key.
- `eventKey` is a business-level uniqueness key.
- `eventType` is a dotted, versioned business fact such as `students.student-created.v1`.
- `payload` contains the original domain event body.
- Consumers must ignore unknown envelope fields.
- Consumers must be idempotent by `eventId`.
- Pub/Sub attributes still include `eventId`, `eventKey`, `eventType`, `aggregateType`, and `aggregateId` for routing/filtering compatibility.

## Notification Compatibility

`notification-service` accepts both:

- legacy raw notification payload message data;
- canonical envelope message data with the notification request under `payload`.

The service stores the unwrapped notification payload in `notification_inbox_events.payload` so provider adapters do not depend on the envelope structure.

## Reporting Ingress

`reporting-service` accepts Pub/Sub-wrapped canonical envelope messages and direct canonical envelope payloads at:

```text
POST /api/v1/pubsub/reporting-events
```

The endpoint stores events idempotently in `reporting.reporting_event_inbox` by `eventId`. `ReportingEventInboxProcessor` drains `RECEIVED` rows into `reporting.command_center_feed` with `source_type = EVENT_INBOX` and `source_id = eventId`, so feed projection is also idempotent.

Local compose routes these event type prefixes to reporting-service:

- `fees.`
- `attendance.`
- `supply.`
- `firefighting.`
- `workflow.`
- `students.`
- `schools.`
- `identity.`

`notifications.` remains routed to notification-service.
