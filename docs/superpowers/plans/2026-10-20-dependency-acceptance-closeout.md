# Dependency and acceptance closeout implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Resolve the thirteen open dependency PRs, repair release command output parsing, and refresh security/operations acceptance with current evidence.

**Architecture:** Integrate the requested dependency versions once on current dev, preserving its newer workflow and security controls. Keep pipeline behavior and acceptance evidence in separately reviewable commits. Merge only after current combined CI and independent review pass, then verify the dev release.

**Tech Stack:** Maven/Java 25, Node 24/npm 11, PowerShell 7, GitHub Actions, Google Cloud.

**Spec:** User instruction of 2026-10-20; `docs/security-remediation/acceptance-owner-actions.md`; open PRs 303–306, 309–312, 315, 354–357.

## Global constraints

- Do not deploy production, invent owner policy, expose secrets, or substitute software tests for physical hardware/live-provider acceptance.
- Retain exact image digest/signature, dependency convergence, vulnerability and governance gates.
- PR305 and PR315 request the same Jackson target; resolve both through one compatible upgrade.
- Keep pending acceptance explicitly open until its required evidence exists.

## Review focus

- Dependency alignment: Jackson core/annotations/databind must remain compatible.
- Action SHA allowlist and workflow pins must agree with upstream commit provenance.
- Cold Maven bootstrap must validate its checksum on both Linux and Windows.
- Native stderr must not contaminate digest/JSON stdout; nonzero failures remain failures.
- Dated acceptance records must not claim current state or blanket completion.

## Task 1: Dependency integration

**Files:** frontend and gateway package manifests/locks; frontend Dockerfile; parent/service POMs; Maven wrapper properties; affected workflow pins; corresponding reviewed pin/test catalog.

- [x] Inspect all thirteen diffs, existing failed checks and publisher release metadata.
- [x] Apply the exact requested supported versions to current dev; regenerate npm locks.
- [x] Update reviewed action pins after verifying upstream refs. Update the Maven expected version alongside its distribution checksum; preserve the current wrapper implementation.
- [ ] Run frontend unit/build/browser tests, gateway tests, wrapper bootstrap/reactor tests and static audits.
- [ ] Obtain combined Linux/Windows CI, image scans and CodeQL; resolve failures without weakening gates.
- [ ] Review, merge to dev, verify deployment and close superseded dependency PRs with a reconciliation record.

## Task 2: Release command streams

**Files:** `scripts/resolve-release-image-worker.ps1`, `scripts/tests/resolve-release-images-test.ps1`.

- [ ] Reproduce warning-bearing valid stdout failures.
- [ ] Separate stdout/stderr and retain explicit error classification.
- [ ] Run positive and fail-closed regression scenarios on supported platforms.
- [ ] Review and integrate the verified commit into the combined PR.

## Task 3: Acceptance refresh

**Files:** new dated report/evidence under `docs/security-remediation`; stale checklist references where warranted.

- [ ] Read current owner-state metadata using explicit dev scope and inspect previous closeouts.
- [ ] Complete available bounded read-only checks and reconcile previously completed revision cleanup.
- [ ] Identify concrete remaining implementation work and owner inputs; ask for required inputs while independent work continues.
- [ ] Record exact evidence and limitations without claiming unavailable live acceptance.
- [ ] Integrate sanitized evidence and report all unresolved dependencies accurately.
