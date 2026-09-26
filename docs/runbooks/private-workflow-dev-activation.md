# Dev private quotation storage and broadcast checks

Scope: `custoking-dev`, Cloud Run and storage in `asia-south2`, Scheduler in `asia-south1`. Every cloud command must pass `--project=custoking-dev`; the operator's default project may be production. No SMTP or live provider activation belongs to this procedure.

## Read-only baseline, 26 September 2026

Inspection found no dedicated quotation bucket or `FIREFIGHTING_QUOTATION_DOCUMENT_BUCKET` environment variable; no `broadcast-policy-token-dev` secret among the 24 dev secret metadata records; no `ims-async-scheduler-dev` service account; and neither expected operations/platform async job in `asia-south1`. That location contained only the hourly cost-metric job. The inspected workloads used their dedicated `ims-operations-dev`, `ims-platform-dev`, and `ims-school-core-dev` identities, minimum instances zero, and CPU throttling enabled. Platform already had Cloud Run invoker on school-core. Its provider was `logging` with `MSG91_DRY_RUN=true`, and no broadcast-mode/readiness override was present.

These observations describe the pre-activation state. They do not assert that a current deployment contains the new application endpoints or migrations, or that a worker has processed a quotation cleanup or broadcast.

## Review and provision prerequisites

Run the plan first. It reads metadata only and prints no secret content:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-private-workflow-dev.ps1 -ProjectId custoking-dev
```

After reviewing its concrete plan, the following command provisions dev prerequisites. This is the mutation step; the read-only inspection did not execute it:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\configure-private-workflow-dev.ps1 -ProjectId custoking-dev -Apply
```

Use `-SkipSchedulers` only when deliberately staging the bucket/secret before the application deployment. The full apply performs these bounded changes:

1. Create `gs://custoking-dev-quotation-documents` in `asia-south2` with uniform bucket-level access and enforced public access prevention. An existing bucket with different location/privacy fails closed rather than being repurposed. A globally occupied bucket name causes creation to fail; it is never silently replaced.
2. Create the project custom role `quotationDocumentRuntime` from `deploy/gcp/quotation-document-runtime-role.yaml`. Its only permissions are `storage.buckets.get` and `storage.objects.create/get/delete`. Bind it **on this bucket only** to `ims-operations-dev@custoking-dev.iam.gserviceaccount.com`. There is no object-list, IAM-management, public-reader, or project-wide storage grant.

   Storage can briefly reject a newly created role with the specific HTTP 400 "does not exist in the resource's hierarchy" response. The helper retries only that exact role error, at most six attempts with ten-second spacing, after rechecking the role's four permissions and bucket membership/privacy in the explicit dev project. Other errors fail immediately. Do not substitute a broad predefined role. The isolated regression checks in `scripts/test-private-workflow-role-retry.ps1` invoke no cloud commands.
3. Create `broadcast-policy-token-dev`, with a user-managed replica in `asia-south2`, and one cryptographically random initial version if no version exists. Send the secret through stdin, never through an argument, file, or log. Existing versions are reused; a disabled latest version requires review rather than implicit rotation. Grant this secret's accessor role only to the platform and school-core runtime identities. Both services trim the shared configuration value and reference the same secret's `latest` version.
4. Preserve/reconcile platform's service-scoped school-core invoker binding. All bindings are additive; no complete IAM policy is overwritten.
5. Reuse `configure-async-relay-scheduler.ps1` with its new optional service filter, selecting only operations and platform. It creates the dedicated `ims-async-scheduler-dev` identity, grants invoker only on those services, configures audience-bound OIDC POST jobs, triggers them, and requires new 2xx Cloud Run request evidence after the verification start time. Existing defaults for the scheduler script's four-service use remain unchanged. The private-workflow helper refuses to provision a drain unless platform explicitly uses the logging provider and dry-run mode.

The helper does not deploy code, update environment variables, enable broadcast processing, edit school/guardian consent, enqueue broadcasts, or send messages.

## Deploy the application and configuration in order

1. Complete the reviewed application release and migrations first. Required feature migrations include operations `firefighting/V12` and `V13`, and platform `notification/V11`; migration privilege checks are a separate release gate.
2. Provision the bucket, role, shared secret, and bindings before deploying manifests that reference the new secret. A missing secret version or accessor permission must remain a deployment blocker.
3. Reconcile the reviewed dev targets, then release the three service manifests separately from the application snapshot and target configuration. Operations receives `FIREFIGHTING_QUOTATION_DOCUMENT_BUCKET=${project_id}-quotation-documents`; platform and school-core receive `BROADCAST_POLICY_TOKEN` from `broadcast-policy-token-${env}`. Platform's literal fallback is `OFF`/`false`; the reviewed dev target supplies `DRY_RUN`/`true` after authenticated Scheduler verification. Follow the exact [persistent dev configuration sequence](../product/dev-broadcast-activation.md). Other environments retain `OFF`/`false`.
4. Run the selected scheduler verification after the new application revision is ready. A configured job or old 2xx log is insufficient. The operations handler must expose `quotationDocumentsChecked`, and the platform handler must expose `broadcastChecksAttempted`; verify the new handler response and actual durable work outcomes as applicable. Request-driven jobs are required because idle throttled instances cannot guarantee in-process timers.

The common manifests are used for other environments too. This dev-only provisioning script must not be used to bypass their separate infrastructure prerequisites or approval gates.

## Acceptance and optional DRY_RUN activation

Use active Local Demo School, school ID `1`, as the previously approved dev target. Use synthetic request/quotation content and a tiny valid test PDF or image; record only identifiers and byte/hash comparisons in evidence, not private file contents or guardian destinations.

- Confirm private-file capabilities report configured storage and appropriate permission. Save a draft and quotation with stable idempotency keys, upload, retrieve, and compare bytes. Replace/remove the file and verify retirement and scheduled cleanup. A user outside the school must not be able to retrieve it. Preserve the expected intentional draft/quotation state in the acceptance record instead of silently deleting unrelated data.
- Verify platform's capabilities and school-core policy connection. Missing or expired consent and unverified contacts remain exclusions. Never modify an existing person's consent, contact verification, or notification preferences for a test. A separately journaled fixture may use a run-owned synthetic student, synthetic guardian, non-routable `.invalid` email and explicitly labelled synthetic consent; this is a simulation and does not establish real guardian consent or inbox delivery. Verify the fixture's identity through the private policy read before approving, require every other recipient to be excluded, and withdraw the synthetic consent after testing.
- Only after a new authenticated platform drain succeeds may a separately reviewed dev-only configuration set `BROADCAST_DISPATCH_MODE=DRY_RUN` and `BROADCAST_WORKER_READY=true`. Keep `NOTIFICATION_DELIVERY_PROVIDER=logging` and `MSG91_DRY_RUN=true`. Preview the authorized school, approve the exact fingerprint, queue the dry run, and verify per-recipient results, retry/deduplication, and zero confirmed recipient deliveries. If no recipients are eligible, report that limitation rather than claiming an end-to-end eligible send path.
- Keep activation in the reconciled dev target parameters so later Cloud Deploy releases preserve the reviewed state. Production must retain OFF/false. Never describe a runtime-only override as reconciled source configuration.

Live messaging remains blocked by the provider contract. MSG91 request correlation is not a proven duplicate-suppression guarantee. No part of this procedure removes the live guard or enables a real provider.

## Bounded synthetic broadcast acceptance

Use `scripts/run-dev-broadcast-acceptance.ps1` after the core workflow runner has created its exact run-owned student. Read that student's ID from the core journal; do not substitute an existing student. For `product-20260926-a1`, the checked admission number is `QA-product-20260926-a1`, name `Synthetic Student product-20260926-a1`, and section `QA-PRODUCT-20260926-A1` in school `1`. Review the plan first:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-dev-broadcast-acceptance.ps1 -ProjectId custoking-dev -StudentId <journal-student-id> -RunId product-20260926-a1
```

After the ready revision and persistent dev configuration are verified, repeat with `-Apply` for the separately authorized synthetic acceptance. The operator needs read access to the dev bootstrap/policy secrets, private school-core invocation, and dev runtime/Scheduler/log metadata. Optional `-PolicyInvokerServiceAccount <dev-service-account>` obtains an audience-bound impersonated identity token; otherwise it uses the operator identity token. All tokens remain in process. Missing private policy access stops before guardian creation. No IAM grant is performed by this helper.

The helper creates only the synthetic guardian and explicitly labelled consent, uses an `acceptance.invalid` email, and previews at most 200 recipients. It requires exactly one eligible destination with no duplicates, confirms the private policy binds it to the owned student/guardian/grant, and checks the stored approved list before queueing. Existing denied recipients must remain suppressed with zero attempts. It checks one dry-run attempt, queue replay without another attempt, and withdrawal **before** a second queue with a suppressed outcome. Both queues require paired natural Scheduler and Cloud Run 2xx evidence after acceptance, on the ready revision; the helper never manually calls the drain. Zero delivered means no recipient delivery receipt, not verified inbox delivery.

The business limits are 55 API requests, 30 cloud reads, and 12 minutes. Cleanup has a separate ten-request, three-minute reserve, including one reserved logout request. It reconciles only the exact run-owned student and synthetic guardian, withdraws any remaining test grant with a stable key, and verifies that the private policy denies delivery. An uncertain cleanup remains explicitly incomplete. Journals use exclusive creation at `artifacts/product-dev-release-2026-09-26/<runId>-broadcast-journal.json` and contain synthetic payloads/IDs and safe outcomes, never credentials or ordinary guardian destinations. An existing journal blocks restarting. Preserve the journal after any timeout or uncertain write; no new-key retry is attempted.

Offline tests make no cloud calls:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\run-dev-broadcast-acceptance-test.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\tests\read-dev-capacity-evidence-test.ps1
```

The capacity collector separately reads raw Logging samples from the last two hours to establish freshness; its month-total aggregation timestamp is not a freshness substitute. Capacity still requires all existing cost, fixture, deployment and monitoring gates.
