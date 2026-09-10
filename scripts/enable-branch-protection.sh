#!/usr/bin/env bash
#
# enable-branch-protection.sh — apply the reviewed branch-protection policy to main and dev.
#
# PROPOSAL: docs/branch-protection-proposal.md  (read it before running with --apply)
#
# This script does NOT run automatically and does NOT run from CI. It is for a human with
# repository-administrator rights. It is dry-run by default: without --apply it only reads the
# GitHub API and prints the payload it would send.
#
# ---------------------------------------------------------------------------------------------
# ROLLBACK — removes all protection from a branch, immediately, in one call:
#
#     gh api --method DELETE repos/custokingkr-dev/ims-v1/branches/main/protection
#     gh api --method DELETE repos/custokingkr-dev/ims-v1/branches/dev/protection
#
#   or:  bash scripts/enable-branch-protection.sh --remove --apply
#
# PARTIAL ROLLBACK — drop a wedged required context but keep force-push/deletion blocked:
#
#     gh api --method PATCH \
#       repos/custokingkr-dev/ims-v1/branches/main/protection/required_status_checks \
#       --input - <<'JSON'
#     { "checks": [ { "context": "summary", "app_id": 15368 } ] }
#     JSON
# ---------------------------------------------------------------------------------------------
#
# WHY ONLY THREE CONTEXTS
#   ci-pr.yml gates service-test, docker-build and maven-wrapper-validation behind `if:`
#   conditions on the `detect` job. When such a matrix job is skipped the matrix is never
#   expanded, so GitHub reports the RAW TEMPLATE as the check name, e.g.
#       service-test (${{ matrix.name }})     <- docs-only PR, conclusion "skipped"
#       service-test (frontend)               <- frontend PR
#       service-test (school-core-service)    <- services PR
#   No single string matches all three, so requiring any of them leaves PRs stuck forever in
#   "Expected — Waiting for status to be reported". The `summary` job (ci-pr.yml, `if: always()`,
#   `needs:` all nine jobs) aggregates them under one name that reports on 100% of PRs.
#
#   Checks from build-release.yml (release, build-images, resolve-target, release-*) are
#   push-triggered only and appear on a PR head SHA solely on dev->main promotions. Never
#   require them.
#
# Usage:
#   bash scripts/enable-branch-protection.sh                 # dry run (default)
#   bash scripts/enable-branch-protection.sh --apply         # apply (needs admin)
#   bash scripts/enable-branch-protection.sh --remove        # dry run of the rollback
#   bash scripts/enable-branch-protection.sh --remove --apply
#   bash scripts/enable-branch-protection.sh --apply --with-repo-settings
#   bash scripts/enable-branch-protection.sh --verify-sha <40-hex>   # pin the preflight commit
#   bash scripts/enable-branch-protection.sh --skip-verify           # bypass preflight (discouraged)
#
# Idempotent: PUT .../branches/{branch}/protection replaces the whole document, so re-running
# converges to the same state. The script prints a before/after diff of the fields it manages.

set -euo pipefail

REPO="${REPO:-custokingkr-dev/ims-v1}"
BRANCHES=(main dev)

# github-actions app id. Pinning it stops any other GitHub App from satisfying a required
# context with a check run of the same name.
GITHUB_ACTIONS_APP_ID=15368

# The only three check names that report on every pull request to main/dev.
# Kept identical to scripts/verify-github-governance-checks.py:21-25.
REQUIRED_CONTEXTS=(
  "summary"
  "analyze (java-kotlin)"
  "analyze (javascript-typescript)"
)

APPLY=0
REMOVE=0
SKIP_VERIFY=0
WITH_REPO_SETTINGS=0
VERIFY_SHA=""

die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }
rule() { printf '%s\n' "------------------------------------------------------------------"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --apply)              APPLY=1 ;;
    --remove)             REMOVE=1 ;;
    --skip-verify)        SKIP_VERIFY=1 ;;
    --with-repo-settings) WITH_REPO_SETTINGS=1 ;;
    --verify-sha)         VERIFY_SHA="${2:-}"; shift ;;
    --repo)               REPO="${2:-}"; shift ;;
    -h|--help)            sed -n '1,60p' "$0"; exit 0 ;;
    *)                    die "unknown argument: $1 (try --help)" ;;
  esac
  shift
done

# Only gh is required. All JSON filtering goes through `gh api --jq` (gh embeds its own jq
# engine), so a standalone jq binary is deliberately NOT a dependency — it is absent from the
# stock Git Bash environment this repo is developed in.
command -v gh >/dev/null 2>&1 || die "gh CLI not found on PATH."
gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"

# Minimal JSON string escaper, so the payload can be built without a local jq.
json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  printf '%s' "$s"
}

rule
info "Repository : $REPO"
info "Branches   : ${BRANCHES[*]}"
info "Mode       : $([ "$REMOVE" -eq 1 ] && echo REMOVE-PROTECTION || echo APPLY-PROTECTION) / $([ "$APPLY" -eq 1 ] && echo APPLY || echo 'DRY RUN (no writes)')"
rule

# ---------------------------------------------------------------------------- identity + rights
ACTOR="$(gh api user --jq '.login')"
PERMS="$(gh api "repos/$REPO" --jq '.permissions | "admin=\(.admin) maintain=\(.maintain) push=\(.push)"' 2>/dev/null || echo 'unknown')"
IS_ADMIN="$(gh api "repos/$REPO" --jq '.permissions.admin' 2>/dev/null || echo false)"

info "Authenticated as : $ACTOR"
info "Permissions      : $PERMS"

if [ "$IS_ADMIN" != "true" ]; then
  info ""
  info "This account is NOT a repository administrator."
  info "Branch protection requires admin. The admin account for $REPO is the owner,"
  info "'custokingkr-dev'; the everyday account 'bagrodiashubham' has only 'write'."
  info ""
  info "  gh auth switch --user custokingkr-dev"
  info "  # or run with: GH_TOKEN=<custokingkr-dev PAT with 'repo' scope>"
  info ""
  [ "$APPLY" -eq 1 ] && die "refusing to attempt a write without admin rights."
  info "(Dry run continues — read-only.)"
fi
rule

# ------------------------------------------------------------------------------------- removal
if [ "$REMOVE" -eq 1 ]; then
  for branch in "${BRANCHES[@]}"; do
    if [ "$APPLY" -eq 1 ]; then
      info "DELETE repos/$REPO/branches/$branch/protection"
      gh api --method DELETE "repos/$REPO/branches/$branch/protection" >/dev/null \
        && info "  removed." \
        || info "  nothing to remove (404)."
    else
      info "[dry run] would DELETE repos/$REPO/branches/$branch/protection"
    fi
  done
  rule
  info "Done."
  exit 0
fi

# ------------------------------------------------- anti-deadlock preflight on a real commit
# Require a context only after proving that GitHub actually emits that exact string, from the
# exact app id we are about to pin, on a real pull-request head commit. This is the check that
# would have caught "service-test (frontend)" before it wedged every non-frontend PR.
if [ "$SKIP_VERIFY" -eq 1 ]; then
  info "Preflight SKIPPED (--skip-verify). You are on your own for deadlocks."
else
  if [ -z "$VERIFY_SHA" ]; then
    info "Finding a recent merged PR head commit to verify against..."
    VERIFY_SHA="$(gh api "repos/$REPO/pulls?state=closed&per_page=30&sort=updated&direction=desc" \
      --jq '[.[] | select(.merged_at != null)][0].head.sha' 2>/dev/null || true)"
  fi
  [ -n "$VERIFY_SHA" ] && [ "$VERIFY_SHA" != "null" ] \
    || die "could not resolve a commit to verify against. Pass --verify-sha <40-hex>."

  info "Preflight commit : $VERIFY_SHA"
  # name <TAB> app_id <TAB> conclusion, one line per check run.
  RUNS="$(gh api "repos/$REPO/commits/$VERIFY_SHA/check-runs?filter=latest&per_page=100" \
    --jq '.check_runs[] | [.name, (.app.id | tostring), (.conclusion // "pending")] | @tsv')"
  info ""

  preflight_failed=0
  for context in "${REQUIRED_CONTEXTS[@]}"; do
    case "$context" in
      *'${{'*)
        info "  FAIL  $context"
        info "        contains an unexpanded matrix template — this name only appears when the"
        info "        job is SKIPPED, so requiring it deadlocks every PR that runs the job."
        preflight_failed=1
        continue
        ;;
    esac

    matched_app=""; matched_concl=""; other_apps=""
    # No pipe: the loop must run in this shell so the variables above survive it.
    while IFS=$'\t' read -r run_name run_app run_concl; do
      [ "$run_name" = "$context" ] || continue
      if [ "$run_app" = "$GITHUB_ACTIONS_APP_ID" ]; then
        matched_app="$run_app"; matched_concl="$run_concl"
      else
        other_apps="$other_apps$run_app "
      fi
    done <<< "$RUNS"

    if [ -n "$matched_app" ]; then
      if [ "$matched_concl" = "success" ] || [ "$matched_concl" = "skipped" ]; then
        info "  OK    $context  (conclusion=$matched_concl, app_id=$matched_app)"
      else
        info "  WARN  $context  (conclusion=$matched_concl, app_id=$matched_app)"
        info "        present and requireable, but not green on this commit."
      fi
    elif [ -n "$other_apps" ]; then
      info "  FAIL  $context"
      info "        emitted by app id(s) ${other_apps% }, but the payload pins app_id=$GITHUB_ACTIONS_APP_ID."
      preflight_failed=1
    else
      info "  FAIL  $context"
      info "        no check run with this name on $VERIFY_SHA — requiring it would leave every"
      info "        PR stuck in 'Expected - Waiting for status to be reported'."
      preflight_failed=1
    fi
  done
  info ""
  if [ "$preflight_failed" -ne 0 ]; then
    die "preflight failed. Requiring the contexts above would deadlock pull requests.
Fix REQUIRED_CONTEXTS, or pass --verify-sha with a commit that has the full check set."
  fi
  info "Preflight passed: every context exists on a real commit and comes from app $GITHUB_ACTIONS_APP_ID."
fi
rule

# ------------------------------------------------------------------------------------- payload
#
# strict=false           : do NOT force branches up-to-date before merging. With ~20 merges/day
#                          into dev, strict=true makes every merge invalidate every other open
#                          PR and re-run Playwright + Trivy + docker builds.
# enforce_admins=false   : escape hatch. 'bagrodiashubham' (write) authors and merges everything
#                          and is fully bound; only the break-glass owner 'custokingkr-dev'
#                          (admin) can bypass. Revisit if the two accounts are ever merged.
# required_pull_request_reviews=null
#                        : single maintainer; GitHub forbids approving your own PR, so requiring
#                          an approval would block all normal work.
# restrictions=null      : push restrictions are org/team-only; not available on a User-owned repo.
#
# enforce_admins, required_pull_request_reviews, required_status_checks and restrictions are
# REQUIRED keys in this PUT and must be present even when null.

CHECKS_JSON=""
for context in "${REQUIRED_CONTEXTS[@]}"; do
  [ -n "$CHECKS_JSON" ] && CHECKS_JSON="$CHECKS_JSON,"
  CHECKS_JSON="$CHECKS_JSON
      { \"context\": \"$(json_escape "$context")\", \"app_id\": $GITHUB_ACTIONS_APP_ID }"
done

PAYLOAD="{
  \"required_status_checks\": {
    \"strict\": false,
    \"checks\": [$CHECKS_JSON
    ]
  },
  \"enforce_admins\": false,
  \"required_pull_request_reviews\": null,
  \"restrictions\": null,
  \"required_linear_history\": false,
  \"allow_force_pushes\": false,
  \"allow_deletions\": false,
  \"block_creations\": false,
  \"required_conversation_resolution\": false,
  \"lock_branch\": false,
  \"allow_fork_syncing\": false
}"

info "Payload (identical for every branch):"
printf '%s\n' "$PAYLOAD"
rule

# --------------------------------------------------------------------------------------- apply
for branch in "${BRANCHES[@]}"; do
  info "Branch: $branch"

  # gh api prints the JSON error body to stdout AND exits non-zero on 404, so guard on exit
  # status rather than on emptiness — otherwise the 404 body is shown as the "before" state.
  if BEFORE="$(gh api "repos/$REPO/branches/$branch/protection" \
      --jq '"contexts=[\([(.required_status_checks.checks // [])[].context] | join(", "))] strict=\(.required_status_checks.strict // false) admins=\(.enforce_admins.enabled // false) force_push=\(.allow_force_pushes.enabled // false) deletions=\(.allow_deletions.enabled // false)"' \
      2>/dev/null)"; then
    info "  before : $BEFORE"
  else
    info "  before : (unprotected — protection API returns 404)"
  fi

  if [ "$APPLY" -ne 1 ]; then
    info "  [dry run] would PUT repos/$REPO/branches/$branch/protection"
    info ""
    continue
  fi

  printf '%s' "$PAYLOAD" | gh api --method PUT \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "repos/$REPO/branches/$branch/protection" --input - >/dev/null \
    || die "PUT failed for '$branch'. Nothing else was changed; earlier branches may already be protected.
Roll back with: gh api --method DELETE repos/$REPO/branches/<branch>/protection"

  gh api "repos/$REPO/branches/$branch/protection" \
    --jq '"  after  : contexts=\([.required_status_checks.checks[].context] | tostring) strict=\(.required_status_checks.strict) admins=\(.enforce_admins.enabled) force_push=\(.allow_force_pushes.enabled) deletions=\(.allow_deletions.enabled)"'
  info ""
done
rule

# ----------------------------------------------------------- optional, NOT branch protection
if [ "$WITH_REPO_SETTINGS" -eq 1 ]; then
  info "Repository settings (PATCH /repos/$REPO — not branch protection):"
  info "  allow_auto_merge=true      : queue a merge that lands when the 3 checks go green."
  info "  delete_branch_on_merge=true: matches CONTRIBUTING.md 'Delete branches after merging'."
  if [ "$APPLY" -eq 1 ]; then
    gh api --method PATCH "repos/$REPO" \
      -F allow_auto_merge=true -F delete_branch_on_merge=true --silent \
      && info "  applied."
  else
    info "  [dry run] would PATCH repos/$REPO allow_auto_merge=true delete_branch_on_merge=true"
  fi
  rule
fi

if [ "$APPLY" -eq 1 ]; then
  info "Applied. Verify with:"
  info "  gh api repos/$REPO/branches/main/protection --jq '{contexts:[.required_status_checks.checks[].context],strict:.required_status_checks.strict,admins:.enforce_admins.enabled}'"
  info ""
  info "Then open one throwaway docs-only PR and confirm the merge button unlocks —"
  info "that is the change shape a naive required-check configuration would deadlock."
  info ""
  info "Roll back with:"
  for branch in "${BRANCHES[@]}"; do
    info "  gh api --method DELETE repos/$REPO/branches/$branch/protection"
  done
else
  info "Dry run complete. No GitHub setting was changed."
  info "Re-run with --apply (as an administrator) to apply."
fi
