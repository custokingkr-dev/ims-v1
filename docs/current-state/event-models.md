# Event Models and Async Architecture

Last verified: 2026-07-09.

## Current Pattern

Custoking uses a transactional outbox pattern for published domain events:

```text
domain mutation transaction
  -> append row to owning outbox table
  -> scheduled relay reads unpublished rows
  -> relay publishes canonical envelope to Pub/Sub
  -> relay marks row published only after publish succeeds
  -> Pub/Sub push sends message to platform-service
  -> platform-service records inbox row idempotently
  -> scheduled projector processes RECEIVED rows into reporting read models
```

Delivery semantics are at-least-once. Consumers must dedupe by `eventId`.

## Canonical Event Envelope

Source: `docs/EVENT-ENVELOPE-CONTRACT.md` and `EventEnvelope` records in outbox packages.

Required/current fields:

```json
{
  "schemaVersion": "ims.event-envelope.v1",
  "eventId": "producer-prefix:outbox-row-id",
  "eventKey": "business-key",
  "eventType": "domain.event-name.v1",
  "eventVersion": "v1",
  "aggregateType": "AggregateName",
  "aggregateId": "aggregate-id",
  "occurredAt": "timestamp",
  "schoolId": 1,
  "payload": {},
  "traceParent": "optional W3C traceparent",
  "traceState": "optional W3C tracestate"
}
```

Pub/Sub attributes include:

- `eventId`
- `eventKey`
- `eventType`
- `aggregateType`
- `aggregateId`
- `traceparent`
- `tracestate`

## Outbox Tables

Verified producers:

| Owning service | Outbox table | Publisher property |
| --- | --- | --- |
| school-core-service | `tenant_school.outbox_events` | `school-core.outbox.pubsub.topic-id` |
| operations-service | `firefighting.outbox_events` | `operations.outbox.pubsub.topic-id` |
| billing-service | `billing.outbox_events` | `billing.outbox.pubsub.topic-id` |

Outbox tables include trace context columns:

- `trace_parent`
- `trace_state`

Critical env var convention:

- `SCHOOL_CORE_OUTBOX_PUBSUB_TOPIC_ID`
- `OPERATIONS_OUTBOX_PUBSUB_TOPIC_ID`
- `BILLING_OUTBOX_PUBSUB_TOPIC_ID`

The `_ID` suffix is required for Spring relaxed binding to activate `PubSubDomainEventPublisher`. Using `*_OUTBOX_PUBSUB_TOPIC` would leave the logging/no-op publisher active.

## Outbox Startup Guard

The outbox-owning services have startup checks to prevent silently dropping events in the `prod` profile when the logging publisher is active. The check can be bypassed only by explicit config, and local compose sets the bypass for local development.

## Pub/Sub Topics

All current producers publish to the reporting topic:

- dev: `ims-reporting-events-v1-dev`
- prod: `ims-reporting-events-v1-prod`

Notification topics also exist:

- dev: `ims-notifications-events-v1-dev`
- prod: `ims-notifications-events-v1-prod`

No code-backed producer to the notification topic was verified in this pass. The notification push receiver supports the topic, but current documented business producers were all reporting events.

## Pub/Sub Push Ingress

Platform-service exposes two internal push endpoints:

- `POST /api/v1/pubsub/reporting-events`
- `POST /api/v1/pubsub/notifications`

Both subscriptions use Pub/Sub push with OIDC:

- OIDC service account: default compute service account
- OIDC audience: platform-service URL for the target env
- Ack deadline: 30 seconds

Both endpoints also require an application token:

- reporting push uses reporting token, accepted via header or token query parameter.
- notification push uses notification token, accepted via header or token query parameter.

The live subscriptions use query tokens. Token values are intentionally not documented.

## Reporting Inbox

Table:

```text
reporting.reporting_event_inbox
```

Behavior:

- Accepts canonical event envelopes.
- Accepts Pub/Sub wrapper envelopes and direct canonical envelopes.
- Requires `schemaVersion=ims.event-envelope.v1`.
- Requires `eventId` and `eventType`.
- Inserts idempotently with `ON CONFLICT (event_id) DO NOTHING`.
- New rows start with status `RECEIVED`.
- Projector marks rows `PROCESSED` or `FAILED`.
- Trace context is stored in `trace_parent` and `trace_state`.

Processor:

```text
ReportingEventInboxProcessor
```

Config:

- `REPORTING_EVENT_PROJECTION_ENABLED=true` by default.
- `REPORTING_EVENT_PROJECTION_FIXED_DELAY_MS=10000` by default.
- `REPORTING_EVENT_PROJECTION_BATCH_SIZE=50` by default.

## Notification Inbox

Table:

```text
notification.notification_inbox_events
```

Behavior:

- Accepts legacy raw notification payloads.
- Accepts canonical envelope payloads and unwraps `payload`.
- Stores `eventId`, event metadata, payload, trace context.
- Processes immediately on new push when possible.
- Existing processed event ids return without duplicate processing.

Config:

- `NOTIFICATION_INBOX_RETRY_ENABLED=true` by default.
- `NOTIFICATION_INBOX_RETRY_FIXED_DELAY_MS=30000` by default.
- `NOTIFICATION_INBOX_RETRY_BATCH_SIZE=25` by default.

Verified prod caveat: current live env has `MSG91_DRY_RUN=true` and no verified `NOTIFICATION_DELIVERY_PROVIDER=msg91`, so real provider delivery is not proven enabled.

## Published Business Event Types

Source: `outbox.append(...)` calls in producer code.

| Event type | Producer | Aggregate type | Event key pattern | Payload highlights | Consumer/projector |
| --- | --- | --- | --- | --- | --- |
| `billing.invoice-upserted.v1` | billing-service | `SuperadminInvoice` | `InvoiceUpserted:<id>` | invoice id, orderRef, school, schoolId, description, qty, rate, amount, GST, total, status, issued/due dates, notes, createdAt | `BillingInvoiceProjector` -> `reporting.billing_invoice_read`; feed-worthy |
| `school.upserted.v1` | school-core-service | `School` | `SchoolUpserted:<id>` | school id, name, shortCode, city, state, active | `ReferenceDimensionProjector` -> `reporting.dim_school` |
| `school-section.upserted.v1` | school-core-service | `SchoolSection` | `SchoolSectionUpserted:<sectionId>` | section id, name, schoolId, classId, className, active, teacherName | `ReferenceDimensionProjector` -> `reporting.dim_section` |
| `academic-year.upserted.v1` | school-core migrations/backfill verified as event type in projector | not verified from live append call in this pass | not verified | id, label, active expected by projector | `ReferenceDimensionProjector` -> `reporting.dim_academic_year` |
| `student.upserted.v1` | school-core-service | `Student` | `StudentUpserted:<id>` | student id, schoolId, admissionNo, fullName, rollNo, classId, sectionId, parentContact, phone, active, attendancePercent, fatherName | `StudentDimensionProjector` -> `reporting.dim_student` |
| `student-review-item.upserted.v1` | school-core-service | `StudentReviewItem` | `StudentReviewItemUpserted:<itemId>` | item id, schoolId, campaignId, status | `StudentReviewProjector` -> `reporting.fact_student_review_item` |
| `student-review-campaign.completed.v1` | school-core-service | `StudentReviewCampaign` | `StudentReviewCampaignCompleted:<campaignId>` | campaignId, schoolId, status | `StudentReviewProjector` -> campaign status update |
| `attendance-daily.upserted.v1` | school-core-service | `AttendanceDaily` | `AttendanceDailyUpserted:<id>` | id, schoolId, date, classId, sectionId, academicYearId, present/absent/late/leave counts, totalEnrolled | `AttendanceFactProjector` -> `reporting.fact_attendance_daily` |
| `fee-assignment.upserted.v1` | school-core-service | `FeeAssignment` | `FeeAssignmentUpserted:<assignmentId>` | assignment id, studentId, schoolId, academicYearId, netPayable, paidAmount, dueAmount, status, assignedAt | `FeeFactProjector` -> `reporting.fact_fee_assignment` |
| `payment.recorded.v1` | school-core-service | `Payment` | `PaymentRecorded:<paymentId>` | payment id, assignmentId, schoolId, studentId, amount, paidAt | `FeeFactProjector` -> `reporting.fact_payment` |
| `catalog-order.upserted.v1` | school-core-service | `CatalogOrder` | `CatalogOrderUpserted:<orderId>` | order id, schoolId, category, status, totalAmount, superadminApprovalStatus, vendorPaidAt, createdAt, requiredByDate, designStatus, notes | `CatalogFactProjector` -> `reporting.fact_catalog_order` |
| `firefighting-request.upserted.v1` | operations-service | `FirefightingRequest` | `FirefightingRequestUpserted:<code>` | code, title, category, urgency, status, estimatedBudget, schoolId, winnerVendor, winnerAmount, approval/payment timestamps, rejection info | `FirefightingFactProjector` -> `reporting.fact_firefighting_request` |

Only `billing.invoice-upserted.v1` is feed-worthy in the current projector code. Other projectors explicitly return `feedWorthy=false`.

## Projection Architecture

Interface:

```text
ReportingEventProjector
```

Each projector declares:

- `handledEventTypes()`
- `feedWorthy()`
- `project(event)`

`ReportingEventInboxProcessor` discovers all projector beans and indexes them by event type. It fails startup if two projector beans claim the same event type.

If a projector is feed-worthy, the processor also writes a `command_center_feed` row with:

- `source_type=EVENT_INBOX`
- `source_id=eventId`

This makes feed projection idempotent.

## Trace Propagation

Outbox writers store W3C trace context:

- `trace_parent`
- `trace_state`

Pub/Sub publishers send those values as attributes:

- `traceparent`
- `tracestate`

Platform push controllers create receive spans and pass trace context into projection spans through `TraceContextBridge`.

## Async Health Verification

From the latest manual check on 2026-07-09:

- Pub/Sub topics existed for dev/prod.
- Prod push subscriptions were ACTIVE.
- Startup logs showed real `PubSubDomainEventPublisher` active in dev/prod for billing, operations, and school-core.
- Prod outbox/inbox backlog audit found:
  - `billing.outbox_events`: pending/stuck counts 0
  - `firefighting.outbox_events`: pending/stuck counts 0
  - `tenant_school.outbox_events`: pending/stuck counts 0
  - `notification.notification_inbox_events`: open/error counts 0
  - `reporting.reporting_event_inbox`: open/error counts 0

Caveat: no new prod business event was created during the latest observability check, so a fresh producer-to-Pub/Sub-to-consumer mutation trace was not generated in that check.
