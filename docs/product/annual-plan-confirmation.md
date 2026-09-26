# Annual-plan confirmation

Confirmation records the reviewed supply requirements for one school and its configured current academic year. It does not place orders, notify staff, or contact providers.

`GET /api/v1/catalog/annual-plan/review?schoolId=...` returns the actual current-year items, a fingerprint of their ordered content, and the confirmation for that exact fingerprint, if one exists. Platform users must choose a school. School users remain bound to their authenticated school. The existing order-module entitlement and plan permissions apply.

`POST /api/v1/catalog/annual-plan/confirm?schoolId=...` accepts `{ "fingerprint": "..." }`. A missing or stale fingerprint, an empty plan, or a plan above the 10,000-item complete-review limit fails with a conflict. The authenticated actor is supplied by the server. A successful transaction stores the immutable item snapshot, its school/year, revision, actor, and time in `catalog.annual_plan_confirmations`, and appends `catalog.annual-plan-confirmed.v1` to the existing outbox. That internal event contains identifiers and summary metadata, not item descriptions. Its presence is not a notification receipt.

Concurrent duplicate confirmation requests return the original confirmation and produce one event. Item saves and confirmations share a school-scoped database lock. Editing an item changes its fingerprint: a subsequent review returns no current confirmation until that revised snapshot is confirmed. Previous confirmed snapshots remain intact. Reverting to an exactly matching historical snapshot returns its original confirmation.

The UI waits for the returned confirmation ID and matching school/year/fingerprint before reporting success. A lost or invalid response requires a refresh to reconcile the saved state. An item-save retry retains its item reference; the repository prevents a reference from moving to another school or academic year. The displayed year comes from the server; the screen does not present unsupported historical-year tabs.

Deploy catalog migration `V24__annual_plan_confirmations.sql` with the application change. It enables and forces row-level security and conditionally grants the existing `app_rt` role only `SELECT` and `INSERT` on the new table. No provider configuration or external-send capability is required. This change has been tested locally; no environment migration or notification was performed by this task.
