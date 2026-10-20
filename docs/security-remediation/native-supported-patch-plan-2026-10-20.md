# Supported Noble curl update — 2026-10-20

An actionable supported update exists for the current Java images. Ubuntu's [USN-8932-1 publication](https://ubuntu.com/security/notices/USN-8932-1.json), published October 14, lists Noble security packages `curl` and `libcurl4t64` at `8.5.0-2ubuntu10.16`. The [exact package changelog](https://changelogs.ubuntu.com/changelogs/pool/main/c/curl/curl_8.5.0-2ubuntu10.16/changelog) identifies the backports, and the official security archive lists amd64 binaries. The accepted baseline release's scans report `8.5.0-2ubuntu10.15` in all five Java services. [Evidence and exact publication hashes](acceptance-curl-supported-update-2026-10-20.json).

The update addresses these nine current CVEs: **CVE-2026-86409, CVE-2026-86414, CVE-2026-87862, CVE-2026-87863, CVE-2026-87864, CVE-2026-92388, CVE-2026-92389, CVE-2026-92391 and CVE-2026-94266**. They account for 90 baseline finding rows across two packages and five services. This is a baseline count, not a prediction of the next scanner result.

The bounded review successfully read all 32 current Canonical CVE records. Their Noble affected ranges still have no fixed events, including curl records that reference newer publications. Empty fixed-version fields therefore do not establish that no supported fix exists. Retain the scanner/vendor disagreement; do not suppress findings or replace the published Noble backport with an unrelated upstream ABI.

## Patch and acceptance sequence

Use the existing [committed patch-cycle mechanism](runtime-patch-epoch.md) to select the reviewed update. The inspected baseline cycle is `2026-10-08`; a new reviewed cycle such as `2026-10-20` changes all seven source image identities and reruns the package upgrade layer. A documentation change or a repeated fresh scan alone cannot update cached runtime bytes.

1. Pass normal source checks for the cycle change and build through the existing supported Ubuntu repositories.
2. Bind each selected source index to its platform runtime digest using the actual release manifest, then independently read the deployed Ready revisions.
3. Inspect each changed Java runtime's installed `curl` and `libcurl4t64` versions. Require Noble `8.5.0-2ubuntu10.16` or a later supported fix; retain package/publisher evidence and exact image digests.
4. Run fresh exact-image scans and ordinary dev release acceptance, including readiness, rollback retention and authorization/CSP checks. Preserve remaining findings and any contradictory scanner results.

**This note records a supported fix and plan only. No new runtime package acceptance, production rollout, native applicability closure or whole-plan completion is claimed.** The source descriptor was downloaded, but its signature was not independently verified in this research pass; normal package signature validation must remain enabled during the build.

## Remaining native boundaries

All five Java Dockerfiles deliberately install curl, declare a localhost HTTP health check using it, start Java through `sh -c`, and create `appuser` during the build. This establishes concrete consumers and prevents claiming curl or the shell is unused. It does not prove Cloud Run executes Docker's `HEALTHCHECK`. The inspected health command does not select the curl advisory features, but other native/dependency/operator consumers remain unverified.

| Finding family | Bounded conclusion |
| --- | --- |
| PCRE2 | Upstream fixes exist, including [10.49 JIT guidance](https://github.com/PCRE2Project/pcre2/security/advisories/GHSA-r9hj-j2rw-4q3m). Two advisories specify a [32-bit conversion path](https://github.com/PCRE2Project/pcre2/security/advisories/GHSA-q8g2-wprr-34m9) and a [32-bit compile path](https://github.com/PCRE2Project/pcre2/security/advisories/GHSA-fmgr-6ggq-9859). Exact current binary/API/architecture evidence is needed before an applicability conclusion; no supported Noble replacement was established here. |
| Dash and Cyrus SASL | Dash findings need matcher build configuration or Unicode escape handling. The [SASL record](https://raw.githubusercontent.com/canonical/ubuntu-security-notices/refs/heads/main/osv/cve/2026/UBUNTU-CVE-2026-107161.json) concerns the DIGEST-MD5 plugin. Core/database-library package presence alone cannot prove that plugin's execution or absence. |
| nscd, journald and UID helpers | Prior named-binary absence evidence still binds the unchanged billing/identity/operations runtime digests. It does not bind replacement school/platform images, every consumer, or managed host state. No global `NOT_AFFECTED` claim follows. |
| systemd | Newer Noble `255.4-1ubuntu8.18` has a [DNSSEC trust-anchor update](https://changelogs.ubuntu.com/changelogs/pool/main/s/systemd/systemd_255.4-1ubuntu8.18/changelog). This does not establish a fix for CVE-2026-40228. |
| GnuTLS, Expat, tar, glibc, shadow and zlib | No additional supported security fix or fully evidenced inapplicability was established in this bounded pass. Earlier source discrepancies and scoped prerequisite observations remain separate from current binary/consumer and vendor acceptance. |

No source package was removed, custom native library substituted, image started or changed, workload executed, or vulnerability finding suppressed by this research.
