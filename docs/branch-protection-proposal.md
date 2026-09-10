# Branch Protection Proposal — `custokingkr-dev/ims-v1`

**Status:** proposal only. Nothing in this document has been applied.
**Prepared:** 10 September 2026 (IST)
**Repository:** `custokingkr-dev/ims-v1` — public, User-owned, default branch `main`
**Apply script:** `scripts/enable-branch-protection.sh` (dry-run by default, requires `--apply`; tested — §9)

---

## 1. Executive summary

| Question | Answer |
| --- | --- |
| Is `main`/`dev` protected today? | **No.** `protected=false` on both; `GET /branches/{b}/protection` returns 404; `GET /rulesets` returns `[]`. |
| Can the current CLI principal apply this? | **No.** `gh` is authenticated as `bagrodiashubham` with role `write`. The admin account is `custokingkr-dev`. |
| Which checks are safe to require? | Exactly three: `summary`, `analyze (java-kotlin)`, `analyze (javascript-typescript)`. |
| Which checks would deadlock PRs? | Every `service-test (…)`, `docker-build (…)`, `maven-wrapper-validation (…)` name, and every `build-release.yml` check. Proof in §3. |
| Classic protection or ruleset? | **Classic branch protection.** Reasoning in §6. |
| Required reviews? | **No.** Solo developer — see §5. |

The three-context set is not new. It is already hard-coded in this repo at
`scripts/verify-github-governance-checks.py:21-25` and documented in
`docs/GITHUB-GOVERNANCE-READINESS-2026-08-24.md:59-63`. That document explicitly deferred
enabling protection until a fresh PR proved the contexts still report:

> The local PR workflow now contains additional static-boundary and browser-E2E gates aggregated by the
> same `summary` job. Before an administrator enables protection, a fresh pull request containing those
> workflow changes must prove that `summary` still appears and succeeds. Re-read the check-runs API from
> that commit; do not apply protection from source names alone.
> — `docs/GITHUB-GOVERNANCE-READINESS-2026-08-24.md:69-72`

**That precondition is now satisfied.** §3.4 shows `summary` present on all 19 PRs sampled from
2026-09, including PRs whose only changes were `CONTRIBUTING.md` and `.github/`.

This proposal differs from the older plan in three deliberate ways — `strict`, `enforce_admins`,
and required reviews. Those deltas, and the conflict they create with the repo's own verifier, are
documented in §7. **Read §7 before applying.**

---

## 2. Why the "conditionally skipped job" trap is not what it looks like here

The stated worry was: a job gated by `if:` will hang a doc-only PR in
*"Expected — Waiting for status to be reported"*. There are two distinct failure modes, and only one
of them is real in this repo.

### (a) Job skipped by `if:` inside a workflow that DID run → reports `skipped` → **does not block**

GitHub creates the check run and completes it with `conclusion: "skipped"`. Classic branch
protection treats `skipped` as satisfying the requirement. Confirmed live on PR #208
(`CONTRIBUTING.md`-only):

```
service-test (${{ matrix.name }})            skipped   github-actions
docker-build (${{ matrix.name }})            skipped   github-actions
maven-wrapper-validation (${{ matrix.os }})  skipped   github-actions
```

### (b) Workflow never triggers at all → no check run is ever created → **blocks forever**

Neither PR-triggered workflow has a path filter, so this mode is impossible for `ci-pr.yml` and
`codeql-analysis.yml`:

```yaml
# .github/workflows/ci-pr.yml:3-6
on:
  pull_request:
    branches: [main, dev]
  workflow_dispatch:
```

```yaml
# .github/workflows/codeql-analysis.yml:3-9
on:
  pull_request:
    branches: [main, dev]
  push:
    branches: [main, dev]
  schedule:
    - cron: "15 19 * * 3"
  workflow_dispatch:
```

`grep -n -E "paths|paths-ignore" ` over both files returns nothing. Every PR targeting `main` or
`dev` runs both workflows in full.

**But mode (b) *is* real for `build-release.yml`**, which is push-only:

```yaml
# .github/workflows/build-release.yml:3-6
on:
  push:
    branches: [dev, main]
  workflow_dispatch:
```

Its checks (`release`, `resolve-target`, `build-images (…)`, `release-static-gate`,
`release-secret-scan`, `release-service-test (…)`, `no-deployment`,
`configuration-reconciliation-required`) appear on a PR head SHA **only when that SHA happens to be
a branch tip** — i.e. on `dev → main` promotion PRs, where the head SHA is the tip of `dev` and the
push-to-`dev` run already reported against it. On an ordinary feature-branch PR they never appear.
Requiring any of them deadlocks every feature PR. §3.4 shows this directly: PR #232 (`dev → main`)
carries them; PRs #206/#208/#228/#229 (feature → `dev`) do not.

### (c) The trap that actually bites here: **the check *name* changes**

This is the real hazard, and it is worse than a plain skip. When a matrix job is skipped by `if:`,
the matrix is never expanded, so GitHub reports the **raw, unevaluated template string** as the
check-run name. The name is therefore not stable across PRs:

| PR | Change shape | Name that appeared |
| --- | --- | --- |
| #208 | `CONTRIBUTING.md` only | `service-test (${{ matrix.name }})` — `skipped` |
| #206 | `services/` only | `service-test (school-core-service)` — `success` |
| #228 | `frontend/` only | `service-test (frontend)` — `failure` |

Three different strings for one job. Consequences:

- Require `service-test (frontend)` → PR #206 and PR #208 never produce that name → **pending forever**.
- Require `service-test (${{ matrix.name }})` → PR #206 and PR #228 never produce it → **pending forever**.

There is **no** requireable name for `service-test`, `docker-build`, or `maven-wrapper-validation`.
The `summary` job is the designed answer to this, and it already exists.

---

## 3. Complete check-run inventory

### 3.1 `ci-pr.yml` — runs on every PR to `main`/`dev`

| Exact check name | Gating | Every PR? | Requireable |
| --- | --- | --- | --- |
| `promotion-source-policy` | none — no `needs:`, no `if:` | Yes | Safe (redundant with `summary`) |
| `detect / detect` | reusable-workflow call, no `if:` | Yes | **Avoid** — `build-release.yml:107` also defines a job `detect`, so this name is produced by two workflows from the same app (`15368`); on promotion PRs it appeared twice on one SHA |
| `duplicate-class-drift` | none | Yes | Safe (redundant) |
| `static-architecture-audits` | none | Yes | Safe (redundant) |
| `privacy-technical-controls` | none | Yes | Safe (redundant) |
| `secret-scan` | none (job id; no `name:` key) | Yes | Safe (redundant) |
| `maven-wrapper-validation (${{ matrix.os }})` | `if:` on `mvnw`/`.mvn/wrapper/` changes | Only when skipped | **DEADLOCK** |
| `maven-wrapper-validation (ubuntu-latest)` / `(windows-latest)` | same | Only when it runs | **DEADLOCK** |
| `service-test (${{ matrix.name }})` | `if: needs.detect.outputs.has_service_changes == 'true'` | Only when skipped | **DEADLOCK** |
| `service-test (<service>)` | dynamic matrix from `detect` | Only for that service | **DEADLOCK** |
| `docker-build (${{ matrix.name }})` | same `if:` | Only when skipped | **DEADLOCK** |
| `docker-build (<service>)` | dynamic matrix | Only for that service | **DEADLOCK** |
| **`summary`** | `needs:` all 9 jobs, **`if: always()`** | **Yes — always** | **REQUIRE** |

The gating lines, verbatim:

```yaml
# ci-pr.yml:100-106  maven-wrapper-validation
    needs: detect
    if: >-
      ${{
        contains(needs.detect.outputs.changed_files, 'mvnw') ||
        contains(needs.detect.outputs.changed_files, '.mvn/wrapper/')
      }}
```

```yaml
# ci-pr.yml:157-162  service-test
  service-test:
    name: service-test (${{ matrix.name }})
    needs: detect
    if: ${{ needs.detect.outputs.has_service_changes == 'true' }}
    strategy:
      matrix: ${{ fromJSON(needs.detect.outputs.service_matrix) }}
```

```yaml
# ci-pr.yml:207-212  docker-build
  docker-build:
    name: docker-build (${{ matrix.name }})
    needs: detect
    if: ${{ needs.detect.outputs.has_service_changes == 'true' }}
```

And the aggregator that makes all of the above requireable through one stable name:

```yaml
# ci-pr.yml:246-248
  summary:
    needs: [promotion-source-policy, detect, duplicate-class-drift, static-architecture-audits, privacy-technical-controls, maven-wrapper-validation, service-test, docker-build, secret-scan]
    if: always()
```

```yaml
# ci-pr.yml:265-285  the gate inside summary
      - name: Enforce required job results
        run: |
          failed=0
          for result in \
            "${{ needs.promotion-source-policy.result }}" \
            ... \
            "${{ needs.secret-scan.result }}"; do
            if [[ "$result" != "success" && "$result" != "skipped" ]]; then
              failed=1
            fi
          done
          if [[ "$failed" -ne 0 ]]; then
            echo "One or more required CI jobs failed or were cancelled." >&2
            exit 1
          fi
```

`summary` therefore fails on `failure`, `cancelled`, and `timed_out` in any dependency, and passes
on `success` or `skipped`. That is precisely the semantics classic branch protection needs, delivered
under one name that never changes.

### 3.2 `codeql-analysis.yml` — runs on every PR to `main`/`dev`

| Exact check name | Gating | Every PR? | Requireable |
| --- | --- | --- | --- |
| `analyze (java-kotlin)` | static `matrix.include`, no `if:` | Yes | **REQUIRE** |
| `analyze (javascript-typescript)` | static `matrix.include`, no `if:` | Yes | **REQUIRE** |
| `CodeQL` | GitHub Advanced Security aggregate, app id `57789` | Yes | Works, but not chosen — see below |

The matrix is a fixed literal, so it always expands to the same two names:

```yaml
# codeql-analysis.yml:22-30
    strategy:
      fail-fast: false
      matrix:
        include:
          - language: java-kotlin
            build-mode: none
          - language: javascript-typescript
            build-mode: none
```

`CodeQL` is excluded because it is produced by a different app (`github-advanced-security`, id
`57789`) whose emission depends on code-scanning configuration rather than on a workflow job we
control. The two `analyze (…)` jobs come from `github-actions` (id `15368`) and are pinned to that
app in the payload. This matches the choice already recorded at
`docs/GITHUB-GOVERNANCE-READINESS-2026-08-24.md:65-67`.

### 3.3 `build-release.yml` — push-triggered, **never** requireable

`release`, `resolve-target`, `release-static-gate`, `release-secret-scan`,
`release-service-test (<name>)`, `build-images (<long matrix tuple>)`, `no-deployment`,
`configuration-reconciliation-required`. Present on promotion-PR head SHAs only. Requiring any of
them blocks every feature PR permanently.

No other workflow in `.github/workflows/` triggers on `pull_request`. The remaining files are
reusable (`_*.yml`, `workflow_call`) or scheduled/manual (`security-scan.yml`,
`gcp-cost-controls.yml`, `gcp-governance-audit.yml`, `reconcile-deployment-config.yml`,
`recovery-drill.yml`, `rollback.yml`).

### 3.4 Live evidence

Command used:

```bash
gh api "repos/custokingkr-dev/ims-v1/commits/<sha>/check-runs?per_page=100" \
  --jq '.check_runs[] | "\(.name)\t\(.conclusion)\t\(.app.slug)"' | sort
```

| Check name | #208 `CONTRIBUTING.md` only | #229 `.github` only | #206 `services` only | #228 `frontend` only | #232 promotion → `main` |
| --- | --- | --- | --- | --- | --- |
| `summary` | success | success | success | **failure** | success |
| `analyze (java-kotlin)` | success | success | success | success | success ×2 |
| `analyze (javascript-typescript)` | success | success | success | success | success ×2 |
| `CodeQL` | success | success | success | success | success |
| `promotion-source-policy` | success | success | success | success | success |
| `detect / detect` | success | success | success | success | success ×2 |
| `duplicate-class-drift` | success | success | success | success | success |
| `static-architecture-audits` | success | success | success | success | success |
| `privacy-technical-controls` | success | success | success | success | success |
| `secret-scan` | success | success | success | success | success |
| `service-test (${{ matrix.name }})` | skipped | skipped | — | — | — |
| `service-test (school-core-service)` | — | — | success | — | — |
| `service-test (frontend)` | — | — | — | **failure** | success |
| `docker-build (${{ matrix.name }})` | skipped | skipped | — | — | — |
| `docker-build (school-core-service)` | — | — | success | — | — |
| `docker-build (frontend)` | — | — | — | success | success |
| `maven-wrapper-validation (${{ matrix.os }})` | skipped | skipped | skipped | skipped | skipped |
| `release`, `resolve-target`, `build-images (…)`, `release-static-gate`, `release-secret-scan`, `release-service-test (frontend)` | — | — | — | — | success |
| `no-deployment`, `configuration-reconciliation-required` | — | — | — | — | skipped |

`—` means the name did not exist on that commit at all. Every `—` in a row is a permanent deadlock
if that row is required.

**`summary` stability sweep**, PRs #208 through #232 (19 PRs, all of 2026-09):

```
PR#232 summary=[success]   PR#231 summary=[success]   PR#230 summary=[success]
PR#229 summary=[success]   PR#228 summary=[failure]   PR#227 summary=[success]
PR#226 summary=[success]   PR#225 summary=[success]   PR#224 summary=[success]
PR#223 summary=[success]   PR#222 summary=[success]   PR#221 summary=[success]
PR#220 summary=[success]   PR#213 summary=[success]   PR#212 summary=[success]
PR#211 summary=[success]   PR#210 summary=[success]   PR#209 summary=[success]
PR#208 summary=[success]
```

Present on 19/19, never missing, never pending. **PR #228 is the whole case for this proposal:**
`summary=failure` and it was merged anyway.

Producer app ids, read from PR #208 (`filter=latest`):

```
summary                          app_id=15368  github-actions
analyze (java-kotlin)            app_id=15368  github-actions
analyze (javascript-typescript)  app_id=15368  github-actions
CodeQL                           app_id=57789  github-advanced-security
```

---

## 4. Who can apply this

```console
$ gh api repos/custokingkr-dev/ims-v1 --jq '{permissions, visibility, owner_type: .owner.type}'
{"permissions":{"admin":false,"maintain":false,"pull":true,"push":true,"triage":true},
 "visibility":"public","owner_type":"User"}

$ gh api user --jq '.login'
bagrodiashubham

$ gh api repos/custokingkr-dev/ims-v1/collaborators/bagrodiashubham/permission --jq '.role_name'
write
```

The `gh` CLI is authenticated as **`bagrodiashubham`**, role **`write`**. Branch protection requires
**admin**. The admin is the repository owner account **`custokingkr-dev`** (user id `274906704`, per
`docs/GITHUB-GOVERNANCE-READINESS-2026-08-24.md:40`).

`scripts/enable-branch-protection.sh` must therefore be run by a shell authenticated as
`custokingkr-dev`, e.g.:

```bash
gh auth switch --user custokingkr-dev     # or: GH_TOKEN=<custokingkr-dev PAT with `repo`> ./scripts/...
```

The script preflights this and refuses to proceed if `.permissions.admin` is not `true`.

**This two-account split is an asset, not a nuisance** — see §5.

---

## 5. Controls proposed (and one deliberately omitted)

### Omitted: `required_pull_request_reviews`

```console
$ gh pr list --state merged --limit 12 --json number,author,mergedBy
#232 author=bagrodiashubham mergedBy=bagrodiashubham
#231 author=bagrodiashubham mergedBy=bagrodiashubham
... (all 12 identical)

$ gh pr view 228 --json reviews --jq '.reviews'
[]
```

Every PR is authored and merged by one person, with zero reviews on record. GitHub does not let an
author approve their own PR, so `required_approving_review_count: 1` would stop all normal work.

A strict caveat, because it matters for §7: because `custokingkr-dev` and `bagrodiashubham` are two
*distinct* accounts, the owner *could* satisfy a 1-approval rule by approving from the other account.
It is therefore not a mathematical deadlock — but it is self-review with extra steps, it produces no
real review signal, and combined with the existing plan's `dismiss_stale_reviews: true` +
`require_last_push_approval: true` it forces an account switch after **every** push. Not proposed.

### Proposed payload (identical for `main` and `dev`)

```json
{
  "required_status_checks": {
    "strict": false,
    "checks": [
      { "context": "summary",                         "app_id": 15368 },
      { "context": "analyze (java-kotlin)",           "app_id": 15368 },
      { "context": "analyze (javascript-typescript)", "app_id": 15368 }
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "block_creations": false,
  "required_conversation_resolution": false,
  "lock_branch": false,
  "allow_fork_syncing": false
}
```

| Setting | Value | Why |
| --- | --- | --- |
| `required_status_checks.checks` | the 3 stable contexts, `app_id` pinned | Only names that report on 100% of PRs. Pinning `app_id: 15368` stops any other GitHub App from satisfying `summary` with a forged check run. |
| `required_status_checks.strict` | **`false`** | `strict: true` requires every PR to be up to date with its base before merge. At ~20 merges/day into `dev`, each merge invalidates every other open PR and forces a rebase plus a full CI re-run — Playwright E2E, Trivy image scans and Docker builds. Non-strict keeps the merge button usable. |
| `enforce_admins` | **`false`** | The escape hatch. See below. |
| `required_pull_request_reviews` | `null` | §5, above. |
| `allow_force_pushes` | `false` | Blocks history rewrite on `main`/`dev`. |
| `allow_deletions` | `false` | Blocks branch deletion. |
| `required_linear_history` | `false` | The repo uses merge commits (`allow_merge_commit: true`) and merge-commit PRs are already in history. `true` would break the current merge style. |
| `required_conversation_resolution` | `false` | No second reviewer exists to open threads; adds friction with no signal. (The older plan set `true`.) |
| `block_creations` | `false` | Both branches already exist. |
| `restrictions` | `null` | Push restrictions are org/team-only; not applicable to a User-owned repo. |

### `enforce_admins: false` — recommended, and unusually well-justified here

Normally `enforce_admins: false` is a weak choice because it exempts everyone with admin. Here there
is exactly one admin, and **it is not the account that does the work**:

- `bagrodiashubham` (role `write`) authors and merges every PR → **fully bound by protection, no bypass**.
- `custokingkr-dev` (role `admin`) is the break-glass account → **can bypass**.

So the daily-driver account gets no escape hatch — which is exactly the enforcement the repo needs —
while a genuine emergency (a flaky check wedging a production hotfix, GitHub Actions degraded, a
required context renamed by mistake) is recoverable by switching accounts, without deleting the
protection rule.

Choosing `enforce_admins: true` instead would mean a mistyped context string locks *every* account
out of `main` with no recovery except an admin deleting the protection — the same one API call, but
made under incident pressure rather than deliberately. The escape hatch costs nothing here because
the account that would abuse it is not the account in daily use.

**If the two accounts are ever consolidated into one, revisit this** — a single admin account with
`enforce_admins: false` means the protection is advisory only.

### Recommended alongside (repo settings, not branch protection)

`allow_auto_merge` is currently `false`. Turning it on is the single biggest quality-of-life win for
a solo dev under required checks: open the PR, hit auto-merge, walk away; GitHub merges when the
three contexts go green and holds if any fail. `delete_branch_on_merge` is also `false` while
`CONTRIBUTING.md:32` says "Delete branches after merging". Both are behind `--with-repo-settings`
in the script, off by default, and are `PATCH /repos/{owner}/{repo}` — not branch protection.

---

## 6. Classic branch protection vs. ruleset — recommendation

**Recommendation: classic branch protection.**

The usual reason to prefer a ruleset is `bypass_actors`, which is more granular than the binary
`enforce_admins`. That advantage does not pay for itself here:

1. **Granular bypass has nothing to be granular about.** There is one human and two accounts. The
   role split described in §5 already produces exactly the bypass topology a ruleset would express
   — bound daily-driver, exempt break-glass — using one boolean.
2. **Repository rulesets on a User-owned repo cannot name a user as a bypass actor.** Bypass actors
   resolve to repository roles, teams, apps or deploy keys; teams need an organization. So the
   achievable bypass is "the admin role", which is what `enforce_admins: false` already grants.
3. **Rollback is one call and is atomic.** `DELETE /branches/{branch}/protection` removes everything.
   A ruleset rollback means finding its numeric id first — worse under incident pressure.
4. **Layering makes deadlocks harder to debug.** Rulesets compose with classic protection and with
   each other, and the most restrictive wins. When a PR is stuck, "read one protection document" beats
   "enumerate rulesets, resolve `includes_parents`, evaluate ref patterns, intersect".
5. **`evaluate` (dry-run) enforcement — the one ruleset feature that would genuinely help — is not
   available on a User-owned repository.** The repo's own verifier already treats it as non-enforcing
   (`scripts/verify-github-governance-checks.py:144`).
6. **The existing tooling supports both, so this is not a lock-in.** `verify-github-governance-checks.py`
   reads classic protection *and* rulesets and unions them (`classic_status_source`,
   `ruleset_status_sources`), so migrating later costs nothing.

**Switch to a ruleset when any of these becomes true:** a second collaborator joins and needs a
different bypass from the owner; a bot or GitHub App needs to push to a protected branch; you want
one policy across several repos; or you want tag protection or a merge queue in the same object.

---

## 7. Conflicts with existing repo tooling — READ BEFORE APPLYING

Three files in this repo already encode a *different* branch-protection policy. This proposal
knowingly diverges. Do not apply it without deciding what to do about each.

### 7.1 `scripts/configure-security-governance-controls.ps1:244-247` sets the deadlock

```powershell
$protection = [ordered]@{
  required_status_checks = [ordered]@{ strict = $true; contexts = @($RequiredStatusChecks) }
  enforce_admins = $true
  required_pull_request_reviews = [ordered]@{
    dismiss_stale_reviews = $true
    require_code_owner_reviews = $false
    required_approving_review_count = 1
    require_last_push_approval = $true
  }
```

Running that script with `-ApplyBranchProtection -AllowExternalMutation` applies `strict: true`,
`enforce_admins: true` and 1 required approval — every setting this proposal argues against, all at
once, on both branches. **Do not run its apply path.** `scripts/enable-branch-protection.sh` is the
proposed replacement.

Note also that `scripts/audit-security-governance-controls.ps1:127-130` asserts the *literal strings*
`"required_approving_review_count"`, `"required_conversation_resolution"`, `"analyze (java-kotlin)"`
and `"analyze (javascript-typescript)"` are present in that PowerShell file, and that audit runs in
the `static-architecture-audits` job on **every PR**. Editing the PowerShell script to remove those
keys will fail CI. Any cleanup there needs the audit updated in the same PR.

### 7.2 `scripts/verify-github-governance-checks.py` will report a blocker on `strict: false`

```python
# scripts/verify-github-governance-checks.py:328-329
        if sources and not strict:
            blockers.append(f"branch '{branch}' does not require strict up-to-date status checks")
```

With `strict: false`, the verifier returns `ready=false` with two blockers (one per branch) and exits
non-zero. Its required-context list already matches this proposal exactly:

```python
# scripts/verify-github-governance-checks.py:21-25
DEFAULT_REQUIRED_CHECKS = (
    "summary",
    "analyze (java-kotlin)",
    "analyze (javascript-typescript)",
)
```

**This is the one genuine open decision in this proposal.** Options:

- **(a) Accept `strict: false` and relax the verifier** — add a `--allow-non-strict` flag so
  strictness is asserted only when requested. Recommended; keeps the fail-closed default for anyone
  who wants it. Requires a matching test in `scripts/tests/test_verify_github_governance_checks.py`.
- **(b) Accept `strict: true`** and live with constant rebases. Given the current merge cadence into
  `dev` and a CI run that includes Playwright and Trivy, this is expensive; it is also the setting
  most likely to cause someone to disable protection out of frustration.
- **(c) Apply `strict: false` and knowingly leave the verifier red.** Worst option — it trains people
  to ignore a governance check.

The verifier is **not** currently run against the live repo by any workflow — only its fixture-based
unit tests run in CI (`ci-pr.yml:71-72`, `python3 -m unittest scripts.tests.test_verify_github_governance_checks`).
So (a) is a contained change and nothing is red in the meantime.

### 7.3 `docs/GITHUB-GOVERNANCE-READINESS-2026-08-24.md:94-97` documents the old plan

> The planned branch policy requires one approval, dismisses stale reviews, requires approval after the
> last push, requires resolved conversations, includes administrators, requires strict checks, and blocks
> force pushes and deletion on both `main` and `dev`.

If this proposal is accepted, that paragraph should be superseded with a pointer to this document.

---

## 8. `CONTRIBUTING.md` — lines that are false today

### 8.1 False claim of enforced review

> `CONTRIBUTING.md:215`
> ```
> 4. One approval required from a code owner before merging.
> ```

Two independent falsehoods:

- **No approval is required.** `protected=false`; 12/12 recent PRs merged with `reviews: []`.
- **There are no code owners.** No `CODEOWNERS` file exists at `.github/CODEOWNERS`, `CODEOWNERS`, or
  `docs/CODEOWNERS`. The prior audit noted the same at
  `docs/GITHUB-GOVERNANCE-READINESS-2026-08-24.md:96-97`.

### 8.2 Softly false — "must be green" is enforced by nothing

> `CONTRIBUTING.md:208`
> ```
> 3. CI must be green before review. `CI / PR` runs:
> ```

Accurate as a norm, unenforced as a rule — PR #228 merged with `service-test (frontend)` and
`summary` both `failure`. After this proposal is applied the sentence becomes true and should say so.

### 8.3 False — a merge style that is only "preferred"

> `CONTRIBUTING.md:216`
> ```
> 5. Squash-merge preferred to keep `main` history linear.
> ```

`allow_merge_commit: true` and `allow_rebase_merge: true` are both enabled and merge commits are in
recent history (`c6ec7215 Merge pull request #213 …`). `main` history is not linear. Either enforce
squash-only in repo settings or soften the wording. This proposal sets
`required_linear_history: false` to match reality.

### 8.4 False — CI job names that do not exist

> `CONTRIBUTING.md:172-173`
> ```
>   - Test migrations locally: `mvn flyway:migrate` against a fresh DB, or via the
>     `db-migration-test` CI job.
> ```

There is no `db-migration-test` job anywhere in `.github/`. Same defect in the PR template:

> `.github/pull_request_template.md:23`
> ```
> - [ ] CI is green (backend-test, db-migration-test, frontend-build, secret-scan)
> ```

Of those four, only `secret-scan` exists. `backend-test`, `db-migration-test` and `frontend-build`
are all non-existent.

### 8.5 False — a `backend/` directory that does not exist

`CONTRIBUTING.md:41` (`cd backend`), `:169`
(`backend/src/main/resources/db/migration/V<N>__<description>.sql`), `:182` (`cd backend && mvn test`),
`:186`, and `:229` (`backend/.owasp-suppressions.xml`) all reference `backend/`. There is no
`backend/` directory. Migrations live under `services/<service>/src/main/resources/db/migration/`,
per service. (Out of scope for branch protection, but it is in the same section and every path is
wrong.)

### 8.6 Drafted replacement for the "Pull request process" section

Replaces `CONTRIBUTING.md:203-216`. Apply **after** the protection is enabled, not before.

````markdown
## Pull request process

1. Open your PR against `dev` (or `main` for hotfixes). `CI / PR` only runs for
   pull requests targeting `main` or `dev`. Pull requests to `main` must come from
   `dev` — the `promotion-source-policy` job fails any other source branch.
2. Fill in the PR template completely — incomplete PRs will be returned.
3. CI must be green before merging, and this is enforced. `main` and `dev` both
   require these three status checks to pass before the merge button unlocks:
   - `summary` — the `CI / PR` aggregate. It rolls up `promotion-source-policy`,
     `detect`, `duplicate-class-drift`, `static-architecture-audits`,
     `privacy-technical-controls`, `maven-wrapper-validation`, `service-test`,
     `docker-build` and `secret-scan`, and fails if any of them failed or was
     cancelled. Jobs that are correctly skipped for your change (for example
     `service-test` on a docs-only PR) do not fail it.
   - `analyze (java-kotlin)` and `analyze (javascript-typescript)` — from the
     separate `Security / CodeQL` workflow.

   The per-service `service-test (<name>)` and `docker-build (<name>)` checks are
   deliberately **not** required individually: their check names change with the
   set of services your PR touches, so requiring one would block every PR that
   touches a different service. `summary` covers them.
4. Trivy runs inside `docker-build` on **every** pull request and fails the build
   on any HIGH or CRITICAL finding — not only on pushes to `main`.
5. Review is not enforced by branch protection: this repository has a single
   maintainer and GitHub does not allow approving your own pull request. Self-merge
   after green CI is the expected flow.
6. Force-pushing to `main` or `dev`, and deleting either branch, are blocked.
7. Branches do **not** have to be up to date with the base branch before merging
   (`strict` status checks are off), so a merge into `dev` does not invalidate your
   open PR. Rebase when you actually have a conflict.
8. Any merge style is allowed. Prefer squash for feature branches; promotion PRs
   from `dev` to `main` use a merge commit.
9. Delete your branch after merging.
````

Also fix `.github/pull_request_template.md:23`:

```markdown
- [ ] CI is green (`summary`, `analyze (java-kotlin)`, `analyze (javascript-typescript)`)
```

---

## 9. Script test evidence

`scripts/enable-branch-protection.sh` was exercised against the live API in dry-run mode on
10 September 2026. **No write was performed**; `main`/`dev` remain `protected=false`, rulesets
remain `[]`, and `allow_auto_merge`/`delete_branch_on_merge` remain `false`.

The script depends on `gh` only. A standalone `jq` is deliberately **not** required — it is absent
from the stock Git Bash environment on this machine, so all JSON filtering goes through
`gh api --jq` (gh embeds its own jq engine) and the payload is assembled with a small escaper.

| # | Scenario | Expected | Result |
| --- | --- | --- | --- |
| 1 | Default invocation, no flags | Dry run, no writes | Pass — prints payload, `[dry run] would PUT …` for both branches |
| 2 | Unprotected branch "before" state | Reports 404 as unprotected | Pass — `before : (unprotected — protection API returns 404)` |
| 3 | `--apply` as a non-admin | Refuse before any write | Pass — exit 1, `ERROR: refusing to attempt a write without admin rights.` |
| 4 | Preflight, proposed 3 contexts | All OK | Pass — all three `OK … app_id=15368` |
| 5 | Preflight with `service-test (frontend)` against the docs-only PR #208 SHA | Reject | Pass — `FAIL … no check run with this name … would leave every PR stuck` |
| 6 | Preflight with `service-test (${{ matrix.name }})` | Reject | Pass — `FAIL … contains an unexpanded matrix template` |
| 7 | Preflight with `CodeQL` (app `57789`) while pinning `15368` | Reject | Pass — `FAIL … emitted by app id(s) 57789, but the payload pins app_id=15368` |
| 8 | `--remove` dry run | Print the two DELETEs, write nothing | Pass |

Tests 5–7 are the important ones: they prove the anti-deadlock preflight catches all three ways a
required context can be wrong — a name that only exists on *some* PRs, an unexpanded matrix
template, and a name emitted by an app other than the one pinned in the payload.

Test 5 verbatim, against `92ca7a416944afba4fdb97140ccf4ae000f43a5b` (PR #208, `CONTRIBUTING.md` only):

```
Preflight commit : 92ca7a416944afba4fdb97140ccf4ae000f43a5b

  OK    summary  (conclusion=success, app_id=15368)
  FAIL  service-test (frontend)
        no check run with this name on 92ca7a416944afba4fdb97140ccf4ae000f43a5b — requiring it would leave every
        PR stuck in 'Expected - Waiting for status to be reported'.
  FAIL  service-test (${{ matrix.name }})
        contains an unexpanded matrix template — this name only appears when the
        job is SKIPPED, so requiring it deadlocks every PR that runs the job.
  OK    analyze (java-kotlin)  (conclusion=success, app_id=15368)
  OK    analyze (javascript-typescript)  (conclusion=success, app_id=15368)

ERROR: preflight failed. Requiring the contexts above would deadlock pull requests.
```

One authoring gotcha, found while testing: a matrix-template context must be written in **single**
quotes inside `REQUIRED_CONTEXTS`. In double quotes, bash parses `${{` as a bad substitution and the
script dies at parse time before the guard can report anything.

Adding these files is CI-safe. The only PR-CI audit that enumerates the `scripts/` directory
(`audit-cloudsql-transport-security.ps1:135`) filters on `-Filter "*.ps1"`, so a `.sh` file is
invisible to it; every other governance audit asserts against explicitly named files.

---

## 10. Apply and roll back

Preview (safe, read-only, no admin needed):

```bash
bash scripts/enable-branch-protection.sh
```

Apply — **must** be run from a shell authenticated as `custokingkr-dev`:

```bash
bash scripts/enable-branch-protection.sh --apply
```

The script refuses to apply unless (1) the token has `admin`, and (2) every context it is about to
require was observed `success` on a real recent commit — the anti-deadlock preflight.

### Rollback

Full removal, per branch, immediate:

```bash
gh api --method DELETE repos/custokingkr-dev/ims-v1/branches/main/protection
gh api --method DELETE repos/custokingkr-dev/ims-v1/branches/dev/protection
```

Or `bash scripts/enable-branch-protection.sh --remove --apply`.

Partial rollback — drop one wedged context but keep force-push and deletion blocked:

```bash
gh api --method PATCH repos/custokingkr-dev/ims-v1/branches/main/protection/required_status_checks \
  --input - <<'JSON'
{ "checks": [ { "context": "summary", "app_id": 15368 } ] }
JSON
```

### Verify after applying

```bash
gh api repos/custokingkr-dev/ims-v1/branches/main/protection \
  --jq '{contexts: [.required_status_checks.checks[].context],
         strict: .required_status_checks.strict,
         admins: .enforce_admins.enabled,
         force_push: .allow_force_pushes.enabled,
         deletions: .allow_deletions.enabled}'
```

Expected:

```json
{"contexts":["summary","analyze (java-kotlin)","analyze (javascript-typescript)"],
 "strict":false,"admins":false,"force_push":false,"deletions":false}
```

Then open one throwaway docs-only PR and confirm the merge button unlocks. That is the real test:
it is the change shape that the naive configuration would have deadlocked.
