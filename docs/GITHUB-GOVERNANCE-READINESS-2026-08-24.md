# GitHub Governance Readiness — 24 August 2026

Inspection time: 23 August 2026 20:08 UTC (24 August 2026 01:38 IST)
Repository: `custokingkr-dev/ims-v1`
Inspected commit: `6f7e4fbb4972ca96d35fe320910f1ee623f6d89c`

## Outcome

No live GitHub setting was changed during this inspection. The authenticated principal,
`bagrodiashubham`, has `write` and `triage` but not `maintain` or `admin`. Branch protection,
rulesets, Environment protection and dependency-alert settings require repository administration.
Attempting a partial mutation with this principal would not close the blocker and was deliberately
avoided.

The administrator-ready configuration remains implemented in
`scripts/configure-security-governance-controls.ps1`. Its dry run completed successfully on 24 August.
The script now also preserves the two live production reviewer identities and the `main`-only policy
while changing only `prevent_self_review` to `true` and retaining `can_admins_bypass=false`.

The production governance-readiness audit also completed against `custoking-prod`. It returned
`blockerCount=2`, consisting exactly of missing `main` protection and missing `dev` protection. The apply
path was negatively tested: it fails without `-AllowExternalMutation`, and with that consent it fails
before mutation because the current principal is not an administrator. A final live read confirmed zero
rulesets, both branches still unprotected, two production reviewers, admin bypass still disabled and
self-review still allowed.

## Live repository evidence

| Control | Live result |
| --- | --- |
| Visibility | Public |
| Default branch | `main` |
| Repository rulesets | None |
| `main` classic protection | Absent; branch reports `protected=false` and protection API returns 404 |
| `dev` classic protection | Absent; branch reports `protected=false` and protection API returns 404 |
| Current principal | `bagrodiashubham`, role `write`; `admin=false`, `maintain=false` |
| Administrative collaborator | Existing account `custokingkr-dev`; no new principal was inferred or added |
| `dev` Environment | Custom deployment policy containing exactly branch `dev` |
| `prod` Environment | Custom deployment policy containing exactly branch `main` |
| Production reviewers | Existing users `bagrodiashubham` (ID `105185939`) and `custokingkr-dev` (ID `274906704`) |
| Production admin bypass | Disabled |
| Production self-review | Allowed (`prevent_self_review=false`); still open |

The earlier documentation saying that the development Environment had no branch restriction is stale:
the live API now returns one `dev` branch rule. The scheduled cost-control workflow no longer uses a
deployment Environment, so it is not affected by that restriction.

## Exact required checks

The successful pull-request runs for commit `6f7e4fbb4972ca96d35fe320910f1ee623f6d89c`
were inspected through both the Actions jobs API and the commit check-runs API:

- CI run `32484711132`: job/check `summary` succeeded.
- CodeQL run `32484711018`: jobs/checks `analyze (java-kotlin)` and
  `analyze (javascript-typescript)` succeeded.

Those are the exact strict contexts prepared by the governance script:

```text
summary
analyze (java-kotlin)
analyze (javascript-typescript)
```

The commit also exposes GitHub Advanced Security's aggregate `CodeQL` check. It is not substituted for
the two explicitly named workflow jobs because the prepared policy and prior review selected the stable
per-language checks.

The local PR workflow now contains additional static-boundary and browser-E2E gates aggregated by the
same `summary` job. Before an administrator enables protection, a fresh pull request containing those
workflow changes must prove that `summary` still appears and succeeds. Re-read the check-runs API from
that commit; do not apply protection from source names alone.

## Exact administrator action after the fresh PR

Run the dry run first and retain its JSON outside the repository evidence if it contains operational
details:

```powershell
./scripts/configure-security-governance-controls.ps1 -ProjectId custoking-prod
```

After the fresh PR proves all three contexts, an authenticated repository administrator can apply only
the reviewed GitHub controls:

```powershell
./scripts/configure-security-governance-controls.ps1 `
  -ProjectId custoking-prod `
  -ApplyBranchProtection `
  -ApplyEnvironmentPolicy `
  -AllowExternalMutation
```

The planned branch policy requires one approval, dismisses stale reviews, requires approval after the
last push, requires resolved conversations, includes administrators, requires strict checks, and blocks
force pushes and deletion on both `main` and `dev`. It does not require code-owner review because the
repository has no checked-in `CODEOWNERS` file.

After applying, export both branch protection documents and both Environment documents, then negatively
test direct push, force push, branch deletion, failing-check merge and production self-review. Do not
weaken review or force-push controls merely to repair a mistyped check context.

## Data lifecycle policy status

No retention, erasure or school-offboarding policy was approved or fabricated during this work. The
repository repeatedly states that engineering cannot infer retention periods or lawful basis for minors'
data and that qualified legal/privacy, product, security, export-custody and backup-erasure owners remain
unassigned. Existing documents provide technical workflow requirements and currently configured storage
durations, but not the named decision owners and approvals needed for a policy.

Accordingly DATA-02 remains blocked. The required artifact must still map students, guardians, consent,
attendance, fees, imports, source files, photos, notifications, audit events, logs, traces, backups and
support/release artifacts to purpose, owner, trigger, retention, legal hold, action, proof and exception;
then a two-person synthetic offboarding drill must verify export, access freeze, idempotent erasure,
retained-record expiry and control-tenant preservation.
