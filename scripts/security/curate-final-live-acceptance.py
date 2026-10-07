"""Publish only bounded synthetic acceptance proofs after all actual checks pass."""
import argparse,hashlib,json,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2];TMP=ROOT/'tmp';DOCS=ROOT/'docs/security-remediation'
def load(name):
 value=json.loads((TMP/name).read_text(encoding='utf-8'))
 if value.get('project')!='custoking-dev' or value.get('marker')!='SEC-ACPT-20261007':raise ValueError('Exact synthetic proof provenance required')
 return value
def main():
 p=argparse.ArgumentParser();p.add_argument('--apply-dev',action='store_true');a=p.parse_args()
 if not a.apply_dev:raise ValueError('Explicit --apply-dev is required')
 lower=load('security-lower-role-live-proof.json');broker=load('dev-pubsub-restart-proof.json');erasure=load('security-final-live-erasure-proof.json');inspection=load('security-final-erasure-inspection.json');cleanup=load('security-final-fixture-cleanup-proof.json');jobs=load('security-final-owned-jobs-cleanup.json')
 if not lower.get('passed') or not broker.get('restartVerified') or not erasure.get('passed') or not cleanup.get('passed') or not all(x.get('independentlyAbsent') for x in jobs.get('jobs',[])):raise ValueError('Incomplete final live acceptance')
 if any(inspection.get(k)!=v for k,v in dict(reservedStudentsRemaining=0,schoolStudentsRemaining=0,deletionOutbox=20,publishedDeletionOutbox=20,processedDeletionInbox=20,studentTombstones=20,reportingProjectionRemaining=0).items()):raise ValueError('Erasure propagation incomplete')
 first=load('security-final-erasure-inspection-first.json');rollback=load('security-final-cleanup-rollback-proof.json');factors=load('security-final-factor-reset-proof.json')
 if not rollback.get('rollbackVerified') or not factors.get('resetAudited'):raise ValueError('Maintenance/failure provenance missing')
 failed=[j for j in jobs.get('jobs',[]) if j['name'].startswith('ims-sec-final-disable-parents-')]
 if len(failed)!=2:raise ValueError('Failed and successful cleanup job provenance required')
 rollback.setdefault('failedOwnedJob',failed[0])
 first['queryCorrection']='Initial inbox lookup incorrectly joined bare outbox row ID. Authoritative OutboxRelay eventId is school-core: followed by row ID. Initial processedDeletionInbox=0 is a query mismatch, not failed delivery; initial18 tombstones were a genuine partial asynchronous observation.'
 public={'acceptance-lower-role.json':lower,'dev-synthetic-pubsub-restart-proof.json':broker,'acceptance-final-live-erasure.json':dict(project='custoking-dev',marker='SEC-ACPT-20261007',factorMaintenance=factors,browser=erasure,initialInspection=first,database=inspection,failedCleanupRollback=rollback,fixtures=cleanup),'acceptance-final-temporary-jobs.json':jobs}
 for name,value in public.items():(DOCS/name).write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
 paths=['scripts/security/final-live-fixture-acceptance.py','scripts/security/run-final-live-erasure.mjs','scripts/security/curate-final-live-acceptance.py','scripts/security/verify-dev-pubsub-restart.py','scripts/security/dev-synthetic-pubsub-drill.py','scripts/tests/final_live_fixture_acceptance_test.py']
 sources=[dict(path=path,sha256=hashlib.sha256((ROOT/path).read_text(encoding='utf-8').replace('\r\n','\n').encode()).hexdigest()) for path in paths]
 (DOCS/'acceptance-final-live-sources.json').write_text(json.dumps(sources,indent=2)+'\n',encoding='utf-8')
 before=erasure['photo']['before'];after=erasure['photo']['after']
 retention=before.get('softDeletePolicy',{}).get('retentionDurationSeconds')
 text=f'''# Final live development acceptance — 2026-10-07

Executed only in `custoking-dev`, with marker `SEC-ACPT-20261007`. Production configuration/data and business rows were not changed. Credentials and access tokens are excluded from these artifacts. The main implementation ledger remains authoritative for broader rollout and owner dependencies.

| Executed check | Result | Evidence |
| --- | --- | --- |
| Real VIEWER normal login; own student read; foreign object; valid own-school update; admin-only owned-user assignments; forged role/school/carrier headers | 8/8 passed: own read200, foreign404, denied write/admin403 | [Lower role](acceptance-lower-role.json) |
| Actual Cloud Run platform revision replacement from `{broker['beforeRevision']}` to `{broker['afterRevision']}`; duplicate and late upsert after retained terminal deletion | Persisted dedupe and tombstone suppression passed; projection remained absent | [Broker restart](dev-synthetic-pubsub-restart-proof.json) |
| Fresh same-origin browser passkey registration/assertion on two SCHOOL_ADMIN actors; renewed assertion immediately before each school's DELETE requests | Deployed server verified actual Chromium CTAP2 signatures | [Browser and erasure](acceptance-final-live-erasure.json) |
| Actual production-code DELETE endpoint for exactly twenty reserved marker students990007301..990007320 | 20/20 returned permanent deletion200; source students0, deletion outbox20, published20, processed downstream inbox20, tombstones20, reporting projections0 | [Live erasure](acceptance-final-live-erasure.json) |
| One nonce-owned synthetic12×12 PNG through actual photo upload, stored in the application's private GCS schema; application photo deletion after database commit | Current object and exact formerly-live generation returned NOT_FOUND; exact soft-deleted generation observed: {str(after['softDeletedGenerationObserved']).lower()} | [Photo before/after](acceptance-final-live-erasure.json) |
| Three ordinary synthetic users201/202/205 disabled; sessions/assignments/factors revoked; normal login and old access-token rejection; two school parents and their isolated class/year/sections removed | Password login and revoked access401 for each; remaining source fixture parents0;20 student and2 broker tombstones preserved | [Fixture closure](acceptance-final-live-erasure.json) |
| Every temporary job owned by this final workflow | Independently absent after deletion | [Job closure](acceptance-final-temporary-jobs.json) |

The VIEWER's live catalog legitimately grants `role:read`, so the role catalog's200 response was preserved as a corrected initial harness expectation. The admin-only assignment read was bounded to that VIEWER's own user ID. The update body was structurally valid, referenced the existing marker class/section and enabled STUDENTS entitlement, and used an allowed Origin with bearer authentication; cookie CSRF authentication was not involved. Returned403 was not a step-up precondition denial.

The WebAuthn proof uses software authenticators at the actual HTTPS frontend RP origin. It does not prove physical security-key interoperability or hardware attestation. The source owner performed a strictly marker-bound audited maintenance reset of lost virtual factors on actors201/202 before new browser enrollment; recovery approvers203/204 remained disabled and were not reactivated. The reset is not counted as a dual-control recovery ceremony.

The broker result certifies retained PostgreSQL inbox/dedupe/tombstone state across actual revision replacement and a fresh process. It does not certify an in-flight crash, disaster-scale replay, full-backlog drain, or zero downtime.

The first erasure inspection at {first['checkedAtUtc']} saw all20 published deletion outbox records and18 tombstones while delivery was still asynchronous. Its inbox query incorrectly matched bare outbox IDs instead of the publisher's `school-core:` plus row ID, so its reported processed0 is retained as a harness query error rather than a failed broker delivery. The corrected authoritative query subsequently verified20 processed events and20 tombstones. The initial observation is retained in the curated erasure JSON.

The first parent-cleanup transaction failed in its initial precondition because the helper referenced nonexistent `tenant_school.school_staff`; the authoritative schema uses `tenant_school.staff_members`. It stopped before user/parent mutations. Independent bounded owner inspection at {rollback['checkedAtUtc']} verified rollback: all3 ordinary actors and2 school parents remained active/present, sections/class/year remained present, and no fixture-disable audit was inserted; the prior student erasures and20 tombstones remained intact. Only the table lookup was corrected before the successful retry. Required audit/tombstones were retained, and no CASCADE or constraint change was used. The failed job was independently absent.

The school runtime already had its existing bucket role and performed the actual deletion; runtime IAM was not broadened. Bucket versioning was enabled: {str(before['versioningEnabled']).lower()}; the existing soft-delete retention was {retention}seconds. Retention, versioning and soft-delete settings matched before and after this test. Live NOT_FOUND is not physical purge: soft-deleted recovery retention remains governed by the existing bucket policy. No unrelated object or bucket retention policy was changed or scanned.

The final fixture rerun and erasure owner jobs pinned the immutable PostgreSQL image, referenced the managed owner password without exporting it, set statement/task deadlines and bounded mutation lock waits, verified execution completion and independently checked exact job absence. Their SQL inspected only reserved IDs, exact marker parents and bounded deletion-event counts. Two earlier lower-role jobs were recovered through a bounded owned-prefix log lookup and independently confirmed absent. HTTP requests deny redirects and enforce response caps and deadlines; four controlled regression tests cover bearer redirect rejection, trickled-body cancellation, error-response caps, incomplete job execution, cleanup failure and secret-output suppression. [Source digests](acceptance-final-live-sources.json) normalize CRLF to LF.

Immutable identity security audit and required terminal tombstones/deletion outbox evidence were retained. Ordinary actors are soft-disabled and historical synthetic authentication evidence remains; no broad SQL purge was used. The original business workspace was not edited, provider email/SMS was not sent, and obsolete Cloud Run revisions were not deleted by this workflow.

Remaining owner/external dependencies are physical security keys, actual provider deletion/delivery acceptance, dedicated dashboard OAuth registration/secrets/callback and human allowlist, actual protected dashboard web rollout, approved recovery/retention targets and external durable erasure-journal operation, asynchronous Firestore TTL cleanup, bucket recovery-copy expiry/physical purge, and separately approved production rollout/obsolete-revision retirement. These are not implied by passing synthetic development acceptance.
'''
 (DOCS/'acceptance-final-live.md').write_text(text,encoding='utf-8')
 print(json.dumps(dict(project='custoking-dev',curatedArtifacts=len(public)+2,passed=True)))
if __name__=='__main__':
 try:main()
 except (ValueError,KeyError,FileNotFoundError):raise SystemExit('Final live curation refused; proof incomplete or provenance mismatch')
