# Final application dev release — 2026-10-07

[Release 37614977208](https://github.com/custokingkr-dev/ims-v1/actions/runs/37614977208) succeeded for source `f901c5f0631d9fcff617374c8e569401c1af84d6`, merged in [PR319](https://github.com/custokingkr-dev/ims-v1/pull/319). All seven service suites passed, all five isolated owner migrations succeeded, all seven serial Cloud Deploy rollouts completed, and image signing/approval steps passed. [Exact sanitized evidence](acceptance-final-dev-release.json).

An independent readback found each latest-created revision equal to latest-ready, Ready=true, one untagged 100% traffic target, and exact runtime digest matching the release manifest. All five exact migration jobs were independently absent. Gateway health was UP and frontend HTTP returned200. The release artifact did not probe private Java HTTP status.

The seven digest-bound HIGH/CRITICAL verdicts passed: **two fresh scans (school and platform), five verified cached verdicts within the 24-hour policy**. The five unchanged image scans were not rerun. These gates do not assert absence of every vulnerability.

The application suites total **2,397 tests, 2,396 passed and one intentional opt-in identity benchmark skip**, with zero failures/errors. Java183+385+195+95+893=1,751; gateway99; frontend unit431 and browser116. Each service's final aggregate was counted once, without adding shared/reactor totals again.

Fresh post-promotion [anonymous frontend header/CSP/browser checks](acceptance-final-frontend.json) passed and are linked to the exact revision/digest. A fresh read-only [runtime secret preflight](acceptance-runtime-secret-readback.json) passed all seven revisions and two ancestor policies, found zero remaining forbidden accessor memberships, and retained migration-owner and identity-signer access. No secret values were read and no IAM writes were performed by this recheck.

This release deploys the owner-policy HTTP lock-boundary correction and retains the existing dedicated roles, authentication, workflow/billing, file and browser controls. Its passing regression suites provide evidence against affected behavior; they are not a guarantee that every feature or possible cyberattack has been exhaustively tested. Live synthetic broker/student/photo/account cleanup is recorded separately. Production services have not been changed.
