# Container and code security posture — 21 August 2026

Written after a full sweep of every image this project runs. The point of this document is to stop the
next person re-investigating the same 85 alerts: it records what was checked, what was fixed, and what
is deliberately left alone with the reason.

## Summary

Every image now has **zero fixable vulnerabilities**, once PR #142 lands.

| image | CVEs | CRITICAL | HIGH | fixable |
|---|---|---|---|---|
| api-gateway | 0 | 0 | 0 | 0 |
| frontend | 0 | 0 | 0 | 0 |
| dashboard (`v11`) | 0 | 0 | 0 | 0 |
| 5 × Java service — **in production today** | 26 each | 0 | 0 | 9 each |
| 5 × Java service — **PR #142** | 17 each | 0 | 0 | **0** |

The 85 remaining alerts (5 services × 17) are Debian/Ubuntu packages with **no fixed version
published**. There is nothing to upgrade to. That number means "waiting on Ubuntu", not "nobody looked",
and it will not go down by trying harder.

## What was actually wrong

### The dashboard was the only image nothing had ever scanned

It has no source under `services/`, is not in the change detector, is not promoted through Cloud Deploy,
and was absent from `security-scan.yml`. It was shipped by hand with `docker build && docker push`, nine
revisions running — and it is the **only service on this project reachable from the internet**.

| | CVEs | CRITICAL | HIGH |
|---|---|---|---|
| v8 — what was running | 138 | 5 | 47 |
| v9 — base digest bumped | 16 | 1 | 7 |
| v10 — npm removed | **0** | 0 | 0 |

Every one was fixable. Nothing had failed; nothing had looked. The pipeline's HIGH/CRITICAL gate would
have refused that image outright — skipping the pipeline did not make it safer, only less examined.

Two fixes, and the second is the interesting one. After bumping the stale base digest, all 16 survivors
were **npm's own transitive dependencies**: `tar`, `brace-expansion`, `sigstore`, `picomatch`,
`ip-address`. This image has no `package.json`, no lockfile and no install step — it uses only Node
built-ins — so npm was pure dead weight that happened to constitute the entire remaining attack
surface. Deleting it takes the image to zero.

### An open redirect in the sign-in callback

CodeQL flagged `js/server-side-unvalidated-url-redirection`, next to a comment asserting the line was
safe. The comment was wrong:

```
state="/ops"                 ->  Location: "/ops"
state="https://evil.com/x"   ->  Location: "//evil.com/x"     <- off-site
state="//evil.com"           ->  Location: "//evil.com"       <- off-site
```

`replace(/^[^/]*/, "")` strips up to the **first** slash, so a scheme is removed but the `//` survives,
and every browser follows a scheme-relative URL off-site. Replaced with an allowlist of the two pages
that exist, and constrained on the way out as well as on the way back.

The same pass pinned `redirect_uri` (it was built from the client-supplied `Host` header) and reworded
the session expiry check to fail closed on a non-numeric value.

## What is deliberately NOT changed

- **The 85 unfixable OS CVEs.** No fixed version exists. Re-pinning to a newer base does not help: the
  current base has the same 17.
- **`ignore-unfixed: false` everywhere.** Filtering unfixable findings out would make the alert count
  look better and hide a genuinely unfixable CRITICAL if one ever appears. The count is honest; this
  document is the explanation for it.
- **`js/user-controlled-bypass` (high) on the session check.** Dismissed as a false positive with
  written justification. The guarded value is a session cookie — user-provided by definition — and
  `auth.mjs` `readSession` authenticates and decrypts an AES-256-GCM cookie, then re-checks
  expiry and the allowlist, before returning any identity. The cookie payload is not treated as identity
  until authenticated decryption succeeds.

## Where the gates are now

| gate | trigger | threshold |
|---|---|---|
| `build-release.yml` Trivy | push to `dev`/`main`, per service | HIGH/CRITICAL, blocks release |
| `security-scan.yml` | weekly, Sun 20:30 UTC, **all 8 images** | HIGH/CRITICAL |
| `ci-pr.yml` `service-test` | **pull_request only** | tests must pass |
| `tools/live-dashboard/release.sh` | manual dashboard release | HIGH/CRITICAL, refuses to push |

Two things to know about that table:

**`service-test` runs on `pull_request` only.** `build-release.yml` never calls it and the service
Dockerfiles build with `-Dmaven.test.skip=true`. A push to `dev` — *or a merge to `main`* — deploys
without running a single test. Any `services/**` change should reach production through a PR.

**The dashboard was added to `security-scan.yml` in this pass.** `release.sh` only gates a release
somebody performs; the weekly scan is what notices a base image going stale while nobody rebuilds,
which is exactly how 138 accumulated.

## Reproducing any of this

```bash
# Scan a running production image. Pull first -- the Trivy container has no
# registry credentials of its own and will fail with an unauthenticated DENIED.
docker pull "$IMAGE_REF"
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
  image --quiet --severity LOW,MEDIUM,HIGH,CRITICAL --scanners vuln "$IMAGE_REF"

# Dashboard: build, scan, push. Refuses to push on HIGH/CRITICAL.
./tools/live-dashboard/release.sh v12
```

A zero from Trivy is worth one extra check: confirm `Metadata.OS` was detected and that the `Results`
array actually lists targets. A scan that found no package database also reports zero.

On Windows, do **not** set `MSYS_NO_PATHCONV=1` around a `docker build` — it is needed to stop Git Bash
mangling container-internal paths such as `-o /out/x.json`, but it also breaks the build context, which
must be rewritten to a Windows path.
