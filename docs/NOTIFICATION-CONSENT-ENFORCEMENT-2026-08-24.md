# Notification consent and preference enforcement evidence — 2026-08-24

## Scope inspected

The notification lifecycle was traced through:

- fee reminder request production in school-core;
- absentee notification queue creation in school-core;
- broadcast draft, approval, and send commands in platform-service;
- Pub/Sub notification inbox ingestion, retry/dead-letter handling, and provider dispatch;
- `student.guardians`, `student.student_guardians`, and the immutable
  `student.student_consent_events` ledger.

No provider configuration was changed. Production remains on the configured logging/dry-run path.

## Enforced policy

`guardian-communications.v2` permits an SMS, WhatsApp, or email guardian communication only when
all of the following are true at the school-core producer boundary:

1. the student and guardian are in the requested school;
2. the student has one primary guardian link;
3. the guardian is active;
4. `receives_notifications` is true;
5. the guardian contact has been verified;
6. the channel-specific destination exists;
7. the latest effective `SCHOOL_COMMUNICATIONS` ledger event is `GRANTED` and unexpired;
8. its lawful basis is explicitly `CONSENT`;
9. that consent event names the selected guardian.

A denied candidate is not queued and its destination is not returned in suppression audit data.
Safe reason codes identify the missing condition. An allowed fee request carries `policyEvidence`
containing the policy version, purpose, preference decision, guardian id, and immutable consent
event id. Queued absentee rows persist the same identifiers for audit.

Changing a guardian phone number or email address clears `contact_verified_at`, even when an edit
form reposts the prior `contactVerified=true` value. The replacement destination therefore cannot
inherit verification from the old address; it must be verified in a later unchanged-contact update.

Platform-service deliberately does not query school-core's student schema. The only provider-bound
contract currently admitted is `notification.requested.v1` / `fees.fee-reminder-requested.v1` /
`FEE_REMINDER` / `fee-reminder.v1` for a guardian. All other event/category/template/recipient
combinations fail closed. The v2 evidence binds the school, student, guardian, channel, normalized
destination SHA-256, source event, immutable consent event and notice version, evaluation time, and
expiry. Platform compares every binding and rejects future, expired, or more-than-two-minute-old
evidence before invoking the provider. Missing, invalid, stale, or mismatched evidence produces a
terminal `SUPPRESSED` inbox and delivery-attempt audit record. Suppression is acknowledged on
Pub/Sub redelivery and is neither retried nor dead-lettered.

The provider supports legacy destination aliases (`mobile`, `phone`, `to`, `recipientMobile`,
`email`, `recipientEmail`, and `whatsapp`) that can take precedence over `destination`. Every alias
present for the selected channel must normalize to the evidence-bound destination. Raw
`msg91Body` passthrough is rejected for this policy contract because it could otherwise carry an
unverifiable provider recipient.

Inbox processing now takes a pessimistic row lock before the provider call. Concurrent Pub/Sub
redelivery, scheduler runs, or replicas therefore serialize on one event and observe its terminal
state before attempting another send. A process crash after MSG91 accepts a request but before the
database transaction commits can still cause a provider replay because no documented MSG91
idempotency-key contract exists in this repository.

## Fail-closed ambiguity decisions

The schema permits a `student_consent_events.guardian_id` of null, but does not define whether that
is student-wide authorization, authorization for every linked guardian, or merely legacy data.
Such consent is therefore rejected as `CONSENT_GUARDIAN_UNSPECIFIED`; this implementation does not
invent a meaning.

Broadcasts currently contain only an audience type and channels. They have no communication
category, lawful-basis mapping, resolved recipients, guardian-selection rule, or per-recipient
policy evidence. Draft creation and approval remain available, but the repository's send command
now fails closed without marking the broadcast `SENT`.

Before broadcasts or non-primary guardian delivery can be enabled, product/privacy owners must
decide and encode:

- which categories are transactional school communications versus optional/marketing messages;
- whether legal obligation can authorize any transactional categories without consent;
- whether a null-guardian ledger event is student-wide and, if so, which linked guardians it covers;
- whether only the primary guardian receives notifications or every opted-in linked guardian;
- how audience types resolve into individual recipients and immutable policy evidence;
- how policy re-evaluation behaves for a scheduled message when consent changes before delivery.

## Live-provider release gate

The two-minute technical claim lifetime makes delayed and retry delivery fail closed, but it cannot
eliminate the interval between school-core's evaluation and the provider call. A withdrawal or
preference change committed inside that interval is not visible to platform-service because no
authenticated synchronous policy-check endpoint or consent-revocation projection exists today.
`MSG91_DRY_RUN=false` remains blocked for guardian communications until one of those mechanisms is
implemented and exercised end to end. The provider now rejects live mode during Spring startup and
again in the direct delivery method; its live HTTP dispatch path is absent. Changing only an
environment variable or supplying an auth key therefore cannot send a message. This is a technical
privacy gate, not a decision about which messages may rely on a non-consent lawful basis.

### Provider replay-contract findings

The current official [MSG91 Send SMS contract](https://docs.msg91.com/sms/send-sms) and its linked
[custom metadata guidance](https://msg91.com/help/text-sms/custom-metadata-parameters-for-tracking-api-requests)
were inspected on 2026-08-24. MSG91 describes `clientId`, `CRQID`, and `UUID` as optional correlation
metadata returned in reports/webhooks. It does not describe those values as duplicate-suppression or
idempotency keys. The inspected official [Email](https://docs.msg91.com/email/send-email),
[WhatsApp bulk-template](https://docs.msg91.com/whatsapp/template-bulk), and
[OTP](https://docs.msg91.com/otp/sendotp) request contracts also did not document a caller-supplied
idempotency key. The repository therefore does not infer replay safety from tracking metadata.

SMS payload construction now carries the immutable notification event id as `CRQID` for provider
report reconciliation. This is observability only and does not relax the live-delivery block. A
stable local event id cannot prevent a second vendor submission after a process crash unless MSG91
provides and commits to a matching duplicate-suppression contract.

### Inputs required to remove the technical block

The following explicit architecture/provider inputs remain required; no legal or product meaning is
assumed by the repository:

1. A named service owner must choose either an authenticated synchronous school-core policy check or
   a revocation projection, including its owning service, route/topic/schema version, workload
   identity or dedicated secret, timeout, freshness bound, and fail-closed behavior.
2. That contract must compare the current school, student, guardian, channel, normalized destination
   hash, consent event/version, and preference against the queued v2 evidence immediately before
   every provider attempt, including retries.
3. MSG91 must provide a written, channel-specific duplicate-suppression contract for SMS, Email,
   WhatsApp, and OTP: request field/header, uniqueness scope, retention window, concurrent duplicate
   behavior, and response semantics. Tracking/correlation metadata is insufficient.
4. If any channel has no vendor idempotency guarantee, the service owner must approve an alternative
   provider or a delivery design that cannot resubmit after an ambiguous provider response. The
   existing database row lock does not close the crash-after-accept/before-commit window.
5. Removal of the code-level block must add contract and failure-path tests, update this evidence,
   and update the static governance audit in the same reviewed change. Privacy/legal owners must
   separately approve any category/lawful-basis mapping; that approval cannot substitute for these
   technical controls.

Attendance migration V9 quarantines pre-v2 `QUEUED` rows as `SUPPRESSED` and requires complete v2
evidence columns for every newly queued row. It does not backfill authorization from historical
data. Its database constraint rejects null/blank policy identities, non-lowercase-hex destination
hashes, and evidence windows longer than the producer's two-minute claim lifetime.

## Verification

Focused automated coverage proves:

- an explicit current guardian grant is allowed and yields evidence;
- null-guardian consent, disabled preference, unverified contact, and expired grants fail closed;
- fee reminders expose no destination without policy and include evidence when allowed;
- a guardian delivery without evidence never calls the provider;
- valid matching evidence reaches the configured provider;
- recipient relabeling, tenant/destination transplant, future timestamps, and stale/expired evidence
  fail closed;
- provider destination aliases and raw provider bodies cannot bypass destination binding;
- changing a guardian phone/email clears prior contact verification;
- the V8-to-V9 migration quarantines legacy queued rows and rejects incomplete, malformed, or
  overlong policy evidence at the database boundary;
- a retry whose evidence aged out becomes terminally suppressed before the provider;
- suppression is terminal, audited, and acknowledged on redelivery.

Commands used:

```powershell
cd services/school-core-service
..\..\mvnw.cmd -q -Dtest=GuardianCommunicationPolicyTest,GuardianConsentRepositoryIntegrationTest,AbsenteeNotificationPolicyMigrationTest test

cd services/platform-service
..\..\mvnw.cmd -q -Dtest=NotificationDeliveryServiceTest,NotificationInboxProcessorTest,PubSubPushControllerTest test

# Final service-level regression runs
..\..\mvnw.cmd -q test

cd ..\school-core-service
..\..\mvnw.cmd -q test
```

Final Surefire evidence: school-core-service passed 530 tests with zero failures/errors/skips;
platform-service passed 242 tests with zero failures/errors/skips. Platform packaging with tests
skipped also completed successfully after the final health-metric change.
