# Persistent dev broadcast dry-run configuration

The dev target supplies `broadcast_dispatch_mode=DRY_RUN` and `broadcast_worker_ready=true`. The platform manifest defaults to `OFF`/`false`; stage and prod explicitly retain those off values. Stage remains unavailable to the target renderer and reconciliation workflow. `notification_delivery_provider=logging` and `msg91_dry_run=true` remain mandatory for the dev opt-in. Live broadcast sending is not implemented.

The target renderer rejects incomplete, duplicate or unsafe broadcast parameters before reconciliation. Cloud Deploy substitutes `from-param` values after rendering and uses the manifest's literal fallback when a parameter is absent ([Google Cloud documentation](https://docs.cloud.google.com/deploy/docs/parameters)). The app independently requires the shared policy token, dry-run provider and worker readiness before accepting a dispatch.

## Required order

1. Finish the application release containing the protected broadcast drain and policy endpoints. Keep the current release off until its runtime revision is healthy.
2. Verify both private Scheduler jobs return authenticated 2xx against the intended dev services, with the configured service identities, audiences and secret access. The root deployment task recorded this prerequisite on 2026-09-26; retain the execution evidence with the release. This verifies invocation, not a completed synthetic broadcast.
3. Commit the target files plus renderer guard, test and this runbook as the configuration-only change. Do **not** include the platform manifest in this commit. `build-release.yml` intentionally suppresses releases when target/renderer changes require reconciliation; `force_full_deploy` does not bypass this boundary.
4. Run **Ops / Reconcile deployment configuration**, from `dev`, with `environment=dev`. The protected workflow applies only dev targets and pipelines. It creates no release or runtime revision. Inspect the resulting `platform-service-dev` target parameters before continuing. Do not reconcile prod or stage.
5. Commit the prepared `deploy/cloudrun/platform-service.yaml` change separately (and any other already-reviewed service manifests needed for this release). A platform-only manifest change selects only platform-service, sets `deployment_config_changed=true` and `deployment_reconciliation_required=false`, and uses the normal Cloud Deploy release path. Retain exact SHA/digest, rendered manifest and rollout evidence.
6. Verify the ready dev revision contains `DRY_RUN`, `true`, `logging`, and MSG91 dry-run `true`; verify current private Scheduler success and capabilities before a separately authorized synthetic acceptance dispatch. No provider messages are sent. A successful Scheduler tick with no queued work does not prove dispatch completion.

Later image-only dev releases preserve the existing runtime configuration; later Cloud Deploy manifest releases reapply the reconciled dev target values. Avoid an ad hoc `gcloud run services update --update-env-vars` activation: it lacks the durable target source of truth and can be reverted by the next manifest release.

To disable durably, change the dev target pair to `OFF`/`false`, reconcile dev, then use a separate platform manifest change/release to apply it. Applying a target alone does not update an existing runtime revision. If an emergency runtime disable is performed separately, record it and follow with this governed sequence so subsequent releases stay off.

Configuration-only validation: `powershell -ExecutionPolicy Bypass -File scripts/tests/broadcast-deployment-parameters-test.ps1`. It exercises the actual renderer and change resolver, including production/live-provider rejection, without requiring the later manifest commit or contacting Google Cloud. The later manifest commit adds its separate `scripts/tests/broadcast-manifest-parameters-test.ps1` test for parameter wiring and safe literal fallbacks.

No cloud write, workload or broadcast is performed by this test or by preparing these repository changes.
