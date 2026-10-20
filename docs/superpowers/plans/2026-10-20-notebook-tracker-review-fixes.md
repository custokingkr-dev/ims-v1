# Notebook Order Tracker Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the notebook order tracker (b83f4b1a) mergeable and deployable: CI-clean, wired for Cloud Run, secure in its permission model, and reliable across service boundaries.

**Architecture:** Machine calls move onto the existing `/api/v1/internal/**` + Cloud Run IAM + `MachineCallerFilter` pattern. Tracker creation and status delivery become small retried queues drained by the existing relay trigger. Item-scoped operators get a tracker-only permission set and no school context.

**Tech Stack:** Java 25 / Spring Boot 4 (identity, operations, school-core), Postgres with forced RLS, Node 24 gateway, React/Vite frontend, PowerShell audits, Cloud Run.

**Spec:** `docs/superpowers/specs/2026-10-20-notebook-tracker-review-fixes-design.md` (findings 1–26, owner decisions D1–D2); behaviour per `docs/product/notebook-order-workflow-tracker-v4.md`.

## Global Constraints

- Never edit `deploy/clouddeploy/targets-*.yaml` (a targets change blocks the release while reporting success).
- Never edit applied-or-shared migrations V8/V9 (workflow), V11 (identity), V27 (catalog). New migrations only: identity `V12`, workflow `V10` and `V11`, catalog `V28`.
- Every new table: `ENABLE` + `FORCE ROW LEVEL SECURITY` with the `tenant_isolation` policy shape used by the sibling tables in the same schema (including the `app.operator_schools` disjunct where siblings have it), and the `app_rt` grant block (the `afterMigrate` callback mirrors it).
- Every internal route lives in a controller under `api/internal/`, under `/api/v1/internal/`, registered in `MachineCallerFilter`, and in `$iamOnlyControllerContracts` in `scripts/audit-service-authorization-boundaries.ps1`. `MachineCallerFilter.java` stays byte-identical in all five services (`scripts/check-duplicate-class-drift.py`).
- No static shared tokens for service-to-service calls. Remove every `*_TOKEN` / `*-token` property this feature added.
- Owner decisions: D1 (pre-tracker notebook orders keep the old flow), D2 (item-scoped operators are tracker-only).
- Do not push, do not touch any cloud project. Commit on `feat/notebook-order-tracker` only, messages ending with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Per-service verification: `./mvnw -B --no-transfer-progress -f services/<svc>/pom.xml test` (Docker is available for Testcontainers). Gateway: `npm test` in `services/api-gateway`. Frontend: `npm run test:coverage && npm run build` in `frontend`. Static: `node scripts/generate-api-route-inventory.js --check`, `node scripts/generate-openapi-typescript-client.js --check`, `powershell -File scripts/audit-service-authorization-boundaries.ps1` (Windows PowerShell 5.1 runs these audits), `python -m unittest scripts.tests.service_authorization_machine_proof_test`, `python scripts/check-duplicate-class-drift.py`.

## Review Focus

1. A **production-shaped operator** (role OPERATIONS, real V4+V12 permissions, school context from `branch_id`, school-wide assignment) can save, complete and send a design. Pinned in Task 2 (`deployedOperatorCanWorkAssignedStep`).
2. An **item-only operator** calling student, fee and catalogue-order list endpoints with and without `schoolId` gets 403 or nothing from their school. Pinned in Task 2 (`itemOnlyOperatorHasNoSchoolContext`, `itemOnlyOperatorPermissionsAreTrackerOnly`).
3. **Operations unreachable at placement**: the order commits, the queue creates the tracker later, and a second drain creates no duplicate. Pinned in Task 3 (`placementCommitsWhenTrackerUnreachable`, `drainCreatesTrackerOnce`).
4. **Out-of-order status pushes**: an older `trackerVersion` arriving after a newer one changes nothing. Pinned in Task 4 (`staleTrackerVersionIsIgnored`).
5. **A pre-tracker notebook order** can still be quoted, approved and delivered and still appears in Order Approvals; a tracker-managed one cannot. Pinned in Task 3 (`legacyNotebookOrderKeepsOldFlow`, `trackerManagedOrderRefusesOldActions`).

---

### Task 1: Move machine calls onto the internal OIDC pattern and pass the authorization audit

**Files:**
- Create: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/api/internal/NotebookTrackerMachineController.java`
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/api/internal/CatalogTrackerMachineController.java`
- Create: `services/identity-service/src/main/java/com/custoking/ims/identityservice/api/internal/OperatorDirectoryController.java`
- Modify: `MachineCallerFilter.java` in all five services (identical edit)
- Modify: `NotebookTrackerController.java`, `CatalogOrderFormController.java` (remove machine methods), `OperatorScopeController.java` (remove directory methods; scoped guards)
- Modify: both `PeerHttp.java`; `OperatorDirectoryClient`, `SchoolCoreCatalogClient` (operations); `OperationsTrackerClient`, `IdentityOperatorClient` (school-core); the three `application.yml`
- Modify: `scripts/audit-service-authorization-boundaries.ps1` (`$iamOnlyControllerContracts`), `scripts/tests/service_authorization_machine_proof_test.py` (file list), `services/api-gateway/api-route-inventory.json` (regenerated)
- Test: `PeerClientsTest` (school-core), `OperatorDirectoryClientTest` (operations), new `*MachineControllerTest` per service, `OperatorScopeControllerTest`, `NotebookTrackerControllerTest`, `NotebookTrackerEndpointsTest`, the existing `MachineCallerFilter` test in each service

**Interfaces:**
- Produces the six routes and four allow-list env names in spec §3.1, exactly as written there. Each endpoint keeps its branch HTTP method and request/response bodies; only path and authentication change.
- Produces `PeerHttp` token behaviour: metadata URL `.../service-accounts/default/identity?audience=<baseUrl>&format=full`, cached per audience for 45 minutes; `cloud-run-auth` modes `auto|never|always` unchanged.
- Produces local trust: env `MACHINE_CALLER_LOCAL_TRUST=true` effective only when env `K_SERVICE` is unset.
- User-facing scopes: `NotebookTrackerController` reads use `requireToken(token, "workflow:read")`, writes `requireToken(token, "workflow:write")` (helper as in `WorkflowReadController.java:204`); `OperatorScopeController` reads `"identity:read"`, writes `"identity:write"` (helper as in `RbacReadController.java:288`).

- [ ] **Step 1: Write failing tests.** Per service, a machine-controller test asserting the route serves the existing body, and a filter test asserting: (a) a registered tracker route with a valid token whose email is in the allow-list passes; (b) an email not in the list gets 403; (c) a missing allow-list env gets 403; (d) `/api/v1/internal/notebook-trackers/a.b/image-access` (dot not allowed) gets 403; (e) with `MACHINE_CALLER_LOCAL_TRUST=true` and no `K_SERVICE`, a registered route passes without a token; (f) with `MACHINE_CALLER_LOCAL_TRUST=true` and `K_SERVICE=x`, it gets 403; (g) local trust never opens an unregistered internal route. In `PeerClientsTest`/`OperatorDirectoryClientTest`: the token request URL contains `format=full`; two calls within 45 minutes fetch the token once; no request carries a `*-service-token` or `X-Authenticated-*` header. In the user-facing controller tests: a request with a token lacking the scope gets 403.
- [ ] **Step 2: Run them; confirm they fail** for the missing routes/filter entries/behaviour (not compile errors in unrelated code).
- [ ] **Step 3: Implement.** Model the controllers on `school-core .../api/internal/IdentityDirectoryController.java` (Javadoc must contain `Cloud Run IAM`). Add filter registry lines (same edit in all five copies). Remove the old machine methods, their token properties and their header constants. Add the six audit contracts with exact `MethodMappings`, `Route`, `CallerEnv`, `Scheduler=$false`. Add the new controller file names to the machine-proof test list. Regenerate the route inventory.
- [ ] **Step 4: Verify.** All tests of identity, operations, school-core pass; `audit-service-authorization-boundaries.ps1` prints its pass line; route inventory `--check` passes; `check-duplicate-class-drift.py` passes; `python -m unittest scripts.tests.service_authorization_machine_proof_test` passes; gateway `npm test` passes.
- [ ] **Step 5: Commit** `fix(tracker): move machine calls onto the internal OIDC pattern`.

### Task 2: Tracker permissions and tracker-only item scope

**Files:**
- Create: `services/identity-service/src/main/resources/db/migration/V12__notebook_tracker_permissions.sql`
- Modify: `RbacReadRepository.java` (`effectivePermissions`), `IdentityAuthService.java` (`loginPermissions`, `responseFor`, `snapshot`), `JwtService.java` only if `sid` is not fed by `snapshot`
- Modify: operations `NotebookTrackerService.java` (`actor`, list/view/mutate school check), operations `TenantScope`/`TenantContext` only as needed
- Modify: school-core tracker-facing permission checks in `CatalogOrderFormController.java`/`TenantScope.java` only where the item-only set must reach them
- Test: `OperatorCatalogScopeIntegrationTest`, `IdentityAuthServiceRotationTest`, operations `NotebookTrackerServiceIntegrationTest`, school-core `NotebookTrackerIntegrationTest`

**Interfaces:**
- Produces permission codes `notebook-tracker:read` and `notebook-tracker:act` (granted to SUPERADMIN and OPERATIONS by V12).
- Produces the item-only permission set, exactly: `workspace:access`, `notebook-tracker:read`, `notebook-tracker:act`, `order:read`, `order:update`.
- Produces the rule: for OPERATIONS with non-empty operator scopes, `branchId`/`sid` = `branch_id` only if it is in the user's school-wide operator schools, else null. Users with no item scope are unchanged.

- [ ] **Step 1: Write failing tests** in `OperatorCatalogScopeIntegrationTest`: `itemOnlyOperatorPermissionsAreTrackerOnly` (introspection permissions equal the five codes exactly); `itemOnlyOperatorHasNoSchoolContext` (introspection `branchId` null, JWT `sid` absent/null); `operatorWithSchoolWideAndItemKeepsSchoolContext` (school-wide at A, item at B, `branch_id`=A → `branchId`=A, full OPERATIONS codes plus the two tracker codes); `effectivePermissionsIgnoreItemRows`. In operations `NotebookTrackerServiceIntegrationTest`, replace the injected `workflow:read/act` with the real codes and add `deployedOperatorCanWorkAssignedStep` (operator context with a school id and the OPERATIONS+V12 permissions, can save draft, complete, send design) and `operatorOutsideSchoolCannotViewTracker` (403/404). In school-core `NotebookTrackerIntegrationTest`: `itemOnlyOperatorCannotListSchoolOrders` (no school context → catalogue orders of other items not listed; student list 403).
- [ ] **Step 2: Run; confirm failures** are the asserted behaviours.
- [ ] **Step 3: Implement** per spec §3.2. Tracker `actor()` checks `notebook-tracker:read`/`:act`.
- [ ] **Step 4: Verify** identity, operations and school-core suites pass.
- [ ] **Step 5: Commit** `fix(identity): give item-scoped operators tracker-only access`.

### Task 3: Create the tracker after commit; keep pre-tracker orders on the old flow

**Files:**
- Create: `services/school-core-service/src/main/resources/db/migration/catalog/V28__notebook_tracker_creation_queue.sql`
- Create: `services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/NotebookTrackerCreationQueue.java`
- Modify: `CatalogOrderFormService.java` (PLACE, `startTracker`, `ensureTracker`, old-flow guards at the three `isNotebook` refusals, draft upload/delete peer call before the lock), `CatalogReadRepository.java` (Order Approvals query; `placed_by` from the order row), `OutboxRelayTriggerController.java` (school-core: drain the queue)
- Test: `NotebookTrackerIntegrationTest`, `CatalogOrderFormIntegrationTest`

**Interfaces:**
- Consumes: Task 1's `OperationsTrackerClient`/`IdentityOperatorClient`.
- Produces `attributes.trackerManaged` (boolean, set true at PLACE for form-v2 notebook orders) — Task 8 reads it from the order payload.
- Produces `NotebookTrackerCreationQueue.drain(int max): int` (returns trackers created) and `enqueue(String orderId, long schoolId)`.
- Table `catalog.notebook_tracker_creation_queue(order_id VARCHAR(255) PRIMARY KEY, school_id BIGINT NOT NULL, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error VARCHAR(500), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), FOREIGN KEY (order_id, school_id) REFERENCES catalog.catalog_orders (id, school_id) ON DELETE CASCADE)`. Backoff: `next_attempt_at = now() + least(2^attempts, 60) minutes`. Claim with `FOR UPDATE SKIP LOCKED` under the bypass scope, then re-scope to the row's school (pattern: `AbsenteeNotificationDeliveryRepository`).

- [ ] **Step 1: Write failing tests:** `placementCommitsWhenTrackerUnreachable` (tracker client throws → order is PLACED, `trackerPending`=true, one queue row, no exception to the caller); `placementMakesNoPeerCallInsideTransaction` (the tracker client is invoked only after commit — assert via a client stub that checks `TransactionSynchronizationManager.isActualTransactionActive()` is false); `drainCreatesTrackerOnce` (two drains → client create called until success, row deleted, `trackerPending`=false); `legacyNotebookOrderKeepsOldFlow` (a notebook order inserted without `trackerManaged` can be quoted, approved, delivered and is listed in Order Approvals); `trackerManagedOrderRefusesOldActions` (approve/deliver → 400; not listed in Order Approvals); `ensureTrackerRefusesClosedOrders` (CANCELLED/DELIVERED → 400; legacy order → 400).
- [ ] **Step 2: Run; confirm failures.**
- [ ] **Step 3: Implement** per spec §3.3 and D1. Restore the pre-feature behaviour for non-tracker-managed notebook orders by reference to `git show 8cb87e5b:services/school-core-service/src/main/java/com/custoking/ims/schoolcoreservice/persistence/CatalogOrderFormService.java`.
- [ ] **Step 4: Verify** school-core suite passes.
- [ ] **Step 5: Commit** `fix(catalog): create notebook trackers after commit and keep old orders on their flow`.

### Task 4: Reliable, ordered tracker status delivery

**Files:**
- Create: `services/operations-service/src/main/resources/db/migration/workflow/V10__tracker_status_outbox.sql`
- Create: `services/operations-service/src/main/java/com/custoking/ims/operationsservice/persistence/TrackerStatusOutbox.java`
- Modify: operations `NotebookTrackerService.java` (`mutate`, `pushStatus`), operations `OutboxRelayTriggerController.java` (drain), `SchoolCoreCatalogClient`/`CatalogOrderGateway` (send `trackerVersion`); school-core `CatalogTrackerMachineController` + `CatalogOrderFormService.applyTrackerStatus`
- Test: `NotebookTrackerServiceIntegrationTest`, `NotebookTrackerMigrationTest`, school-core `NotebookTrackerIntegrationTest`

**Interfaces:**
- Consumes: Task 1's tracker-status route.
- Request body for the status route: `{"status": "<CODE>", "trackerVersion": <long>}`; `trackerVersion` = `workflow_instances.version` after the mutation.
- Table `workflow.workflow_tracker_status_outbox(order_id VARCHAR(255) PRIMARY KEY, school_id BIGINT NOT NULL, status VARCHAR(41) NOT NULL, tracker_version BIGINT NOT NULL, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_error VARCHAR(500), updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`; upsert only when `EXCLUDED.tracker_version > tracker_version`; delete `WHERE order_id = ? AND tracker_version = ?sent`.
- Accepted status codes: the twelve `TrackerStep` enum names plus `COMPLETED`.

- [ ] **Step 1: Write failing tests:** `statusQueuedInSameTransaction` (a completed step leaves an outbox row with the new version even when the push fails); `drainDeliversAndDeletes`; `newerVersionReplacesQueuedStatus`; `rowSurvivesWhenNewerVersionArrivesMidPush` (delete-by-version leaves the newer row). School-core: `staleTrackerVersionIsIgnored` (apply v5 then v4 → status stays v5's, both return success); `unknownStatusCodeRejected` (400).
- [ ] **Step 2: Run; confirm failures.**
- [ ] **Step 3: Implement** per spec §3.3.
- [ ] **Step 4: Verify** operations and school-core suites pass.
- [ ] **Step 5: Commit** `fix(tracker): deliver order status through a retried, versioned outbox`.

### Task 5: Tracker correctness fixes

**Files:**
- Create: `services/operations-service/src/main/resources/db/migration/workflow/V11__tracker_constraints_and_grants.sql`
- Modify: operations `NotebookTrackerService.java`, `NotebookTrackerRepository.java`
- Test: `NotebookTrackerServiceIntegrationTest`, `NotebookTrackerMigrationTest`

**Interfaces:**
- Consumes: Task 4's `mutate` shape (status outbox write) — keep it intact.
- Produces `NotebookTrackerRepository.clearSignOffsFrom(long instanceId, int stepOrder)` and `resetDesignState(long instanceId, String state)` (state `NOT_SENT` or `SENT`; clears `approved_draft_ref`).

- [ ] **Step 1: Write failing tests:** `reopenDesigningResetsApproval` (approved order, reopen step 2 → design `NOT_SENT`, `approvedDraftRef` null; step 3 cannot complete until re-approved); `reopenHeadApprovalRequiresReapproval` (→ `SENT`); `reopenClearsLaterSignOffs` (signed step 8, reopen step 7 → step 8 has no sign-off); `editingSignedStepClearsSignOff` (changed data clears it; identical data keeps it); `designSendFetchesDraftsBeforeLock` (draft client stub asserts no transaction is active); `concurrentCreateReturnsExisting` (simulated duplicate-key → `created=false`, same instance id); `longStageNoteIs400` (4001 chars → 400). Migration test: `appendOnlyTablesDenyUpdateAndDelete` (as `app_rt`), `oneActiveStepPerInstance` (second ACTIVE row violates), `redundantAssigneeIndexDropped`.
- [ ] **Step 2: Run; confirm failures.**
- [ ] **Step 3: Implement** per spec §3.4. For send/approve: authorize and read the order id without a lock, fetch drafts, then `mutate` and re-check state under the lock.
- [ ] **Step 4: Verify** operations suite passes.
- [ ] **Step 5: Commit** `fix(tracker): reset approvals and sign-offs on reopen and tighten the schema`.

### Task 6: Deployment wiring, local stack and docs

**Files:**
- Modify: `deploy/cloudrun/operations-service.yaml`, `deploy/cloudrun/school-core-service.yaml`, `deploy/cloudrun/identity-service.yaml`
- Modify: `scripts/configure-runtime-service-accounts.ps1` (`$invokerMatrix`)
- Modify: `docker-compose.yml`, `docker-compose.local.yml`, `DEVELOPMENT_GUIDE.md`
- Modify: `docs/product/notebook-order-workflow-tracker-v4.md`, `docs/product/notebook-order-workflow-tracker-implementation-plan-v1.md` (status lines only)
- Create: `docs/runbooks/notebook-tracker-deploy.md`

**Interfaces:**
- Consumes: Task 1's env names; peer base-URL property names from the three `application.yml`.
- Allow-list values use `# from-param:` composites of existing params only, e.g. `ims-school-core-${env}@${project_id}.iam.gserviceaccount.com`. Peer URLs use `${operations_url}`, `${school_core_url}`, `${identity_url}`.
- `$invokerMatrix` gains: operations invoked by school-core; identity invoked by school-core and operations.

- [ ] **Step 1: Write failing checks.** Run `audit-service-authorization-boundaries.ps1` (fails: `CallerEnv` not in manifests) and `python -m unittest scripts.tests.runtime_secret_matrix_test scripts.tests.security_target_renderer_test` (record baseline).
- [ ] **Step 2: Implement.** Add env entries beside each manifest's existing `SERVICE_OIDC_AUDIENCES`. Base `docker-compose.yml`: school-core upload storage back to its pre-feature default (`git show 8cb87e5b:docker-compose.yml`), no tracker tokens, peer URLs kept. `docker-compose.local.yml`: `MACHINE_CALLER_LOCAL_TRUST: "true"` for the five Java services, `*_CLOUD_RUN_AUTH: never`, local upload storage. `DEVELOPMENT_GUIDE.md`: document the overlay command first. Runbook: the exact `configure-runtime-service-accounts.ps1 -Apply` invocation for dev and the post-deploy probe (a tracker created for a newly placed notebook order; status reaches the order).
- [ ] **Step 3: Verify** the authorization audit, `audit-deployment-boundaries.ps1`, `audit-service-contract-coverage.ps1`, and the python suites above pass; `docker compose -f docker-compose.yml -f docker-compose.local.yml config` succeeds; `git diff --stat -- deploy/clouddeploy` is empty.
- [ ] **Step 4: Commit** `chore(deploy): wire the notebook tracker for Cloud Run and the local stack`.

### Task 7: OpenAPI contracts for the hand-written tracker calls

**Files:**
- Modify/Create: `contracts/openapi/*.openapi.json` for the school-core and identity operations the frontend calls by hand (tracker summary, ensure tracker, draft images list/upload/delete, operator list, operator scope assign/revoke, operator provisioning with `catalogCategory`, `/sa/schools` if not already contracted)
- Regenerate: `frontend/src/generated/*`
- Modify: `frontend/src/features/tracker/api.ts`, `NotebookAdminPanel.tsx`, `NotebookWorklistPanel.tsx` (use generated clients)
- Test: `frontend/src/features/tracker/api.test.ts`, `scripts/tests/generate-openapi-typescript-client.test.js`

**Interfaces:**
- Consumes: Tasks 1–3 final request/response shapes (including `trackerManaged`).
- Produces generated client functions; Task 8 uses them and adds no hand-written fetches.

- [ ] **Step 1: Write failing test:** in `api.test.ts`, each former hand-written call goes through the generated client (recording transport asserts URL, method, body).
- [ ] **Step 2: Run; confirm failure.**
- [ ] **Step 3: Implement** following `contracts/openapi/notebookTrackerClient.v1.openapi.json` and its README entry; regenerate with `node scripts/generate-openapi-typescript-client.js`.
- [ ] **Step 4: Verify** `--check` passes for both generators, contract tests pass, frontend tests pass.
- [ ] **Step 5: Commit** `feat(contracts): contract the tracker's school-core and identity calls`.

### Task 8: Frontend fixes

**Files:**
- Modify: `frontend/src/features/tracker/{OrderDetailEntry,NotebookTracker,StepWorkArea,DraftImages,steps}.tsx/ts`, `tracker.css`
- Modify: `frontend/src/pages/workspace/panels/{NotebookAdminPanel,NotebookWorklistPanel}.tsx`, `ProductOrderDetail.tsx`, workspace nav config
- Test: `tracker.test.tsx`, `NotebookPanels.test.tsx`, `ProductOrderDetail.test.tsx`

**Interfaces:**
- Consumes: `trackerManaged` (Task 3), `notebook-tracker:read` (Task 2), generated clients (Task 7).

- [ ] **Step 1: Write failing tests:** `OrderDetailEntry` opens the tracker only for tracker-managed notebook orders and makes no tracker request for other orders; a 5xx shows an error with Retry, not the old screen; `ProductOrderDetail` shows quote/approve/deliver for a legacy notebook order and hides them for a tracker-managed one; set-up panel load failure shows an error alert, not "No operator assignments"; Revoke opens an in-app confirmation (no `window.confirm`) and cancelling sends nothing; an unassigned tracker offers "Assign order" which calls `assignOrder`; vendor card on step 2, step 7 shows vendor and contact; header shows delivery date and size/pages summary; internal notes show the author; step 6 shows "TBD"; step 2 preview shows the latest non-PDF draft and no PDF bytes are fetched for thumbnails; worklist due dates render in the school's time zone; a wholesale order renders 9 steps; stepper items expose their status as text; set-up tabs have `role="tabpanel"` and arrow-key navigation; worklist radios sit in a `fieldset` with a `legend`; "Notebook orders" nav shows for a user with `notebook-tracker:read` and no school.
- [ ] **Step 2: Run; confirm failures.**
- [ ] **Step 3: Implement.** Import `product-form.css` where `ck-product-*` classes are used; replace the inline `style={{width:70}}` with a class; use the existing `ck-*` classes and tokens.
- [ ] **Step 4: Verify** `npm run test:coverage && npm run build` passes; `npx tsc --noEmit -p .` passes.
- [ ] **Step 5: Commit** `fix(frontend): tracker error states, spec alignment and accessibility`.
