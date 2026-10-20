# Dev Pub/Sub configuration and IAM proof

Dev repairs completed after the successful seven-service release 37524985447 at `9509d20`. The sanitized [readback proof](dev-pubsub-metadata-proof.json) covers four topics and four subscriptions. Metadata and IAM calls explicitly target `custoking-dev` with 60-second native deadlines. No messages or application data were read or published. Configuration success does **not** prove delivery, restart, replay or drain.

| Verified configuration readback | Result |
|---|---|
| Reporting topic publishers | Exactly school-core/operations/billing runtime SAs, topic-scoped |
| Runtime project Pub/Sub grants | None directly observed; official project Pub/Sub service-agent grant retained |
| Reporting and notification push | Both present, expected push SAs and platform audiences; numeric reporting alias and hashed notification alias both accepted by live target allowlist |
| Source subscriptions | Correct source and DLQ topics, 10 delivery attempts, retry from 10 to 600 seconds, seven-day retention and no expiration |
| Acknowledgement deadlines | Reporting 10 seconds and notification 30 seconds, matching helper assertions |
| Both DLQ inspection subscriptions | Present, correct DLQ topics, seven-day retention and no expiration |
| Service-agent grants | Publisher on both DLQs and subscriber on both source subscriptions |
| Notification push identity grant | Exact push-SA OIDC-only serviceAccountOpenIdTokenCreator binding; platform invoker present |

Reporting Apply succeeded in 54.59 seconds and notification Apply in 95.92 seconds, each within the 180-second process deadline. The first reporting attempt partially configured resources but failed its acknowledgement-deadline check: the helper asserted 10 seconds without setting it, leaving the existing 60 seconds. The helper now sets `--ack-deadline=10`. The proof retains the failed attempt, successful retry and independent readback.

The scoped metadata comparisons report zero remaining configuration findings. The proof preserves the original missing notification and inspection subscriptions, absent reporting retry policy, 31-day expiration and five DLQ attempts. The URL alias difference is informational: `SERVICE_OIDC_AUDIENCES` allows both numeric canonical and hashed status URLs, and the platform verifier checks that configured set.

Both helpers preserve native exit codes despite Windows CLI progress on stderr and restore the caller's error preference. Three controlled regressions pass: native success/failure handling, the exact notification OIDC grant, and reporting's acknowledgement-deadline setting. The PowerShell 5 signed-probe regression also passed with 12 mocked service/revision reads, no mutations, a 30-second deadline and a plain JavaScript string in a job manifest below 64 KiB. All 24 offline architecture audits passed.

[Google push authentication](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions) requires getOpenIdToken; [Google service-account roles](https://docs.cloud.google.com/iam/docs/service-account-permissions) identifies the OIDC-only role. Existing project roles/pubsub.serviceAgent remains broader and supplies inherited token permissions; this change does not establish complete project least privilege. Explicit project-selector CLI predefined-role lookup failed earlier, so permission support is cited from official documentation rather than falsely recorded as a successful CLI role lookup.

Residual owner actions: independently prove valid harmless-fixture message delivery, consumer state, duplicate handling, dead-letter recovery/replay and restart/idle drain. Notification event topic has no explicit publisher IAM binding in this snapshot; enabling an actual producer is a separate reviewed integration requirement, not fabricated by this repair. Organization/folder inherited grants and deny policies, administrative custom-role expansion and future IAM drift remain outside this scoped metadata audit. Do not infer user/tenant success, SMTP/provider delivery or capacity certification from Pub/Sub configuration. Reads are non-atomic while root verification proceeds; retain capture timestamps. No tokens, service environments, application records or message bodies are in this proof.

Independent privacy review redacted one personal administrative IAM member from the project policy proof, retaining its role/category/count. Exact managed service-account metadata remains for operational comparisons. No token/password/environment/application-row fields or private filesystem paths were found in the new Pub/Sub proofs.
