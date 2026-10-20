"""Dev synthetic erasure export/replay plans. Never connects to SQL or mutates cloud state."""
import argparse
import datetime as dt
import hashlib
import ipaddress
import json
import pathlib
import re

PROJECT = 'custoking-dev'
SOURCE = 'custoking-db-dev'
DATABASE = 'custoking_dev'
BUCKET = 'custoking-dev-erasure-journal'
MARKER = 'SEC-ACPT-20261007'
CLONE = re.compile(r'^custoking-dev-security-restore-[0-9]{14}-[a-f0-9]{8}$')

class Rejected(ValueError):
    pass

def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode()

def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()

def timestamp(value):
    if not isinstance(value, str) or len(value)>40 or not value.endswith('Z'):
        raise Rejected('Explicit UTC timestamp required')
    try:
        parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise Rejected('Invalid UTC timestamp') from None
    return parsed

def positive(value):
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0 or value>9223372036854775807:
        raise Rejected('Positive integer identifier required')
    return value

def inventory(value, expected):
    if digest(value) != expected:
        raise Rejected('Reviewed inventory hash mismatch')
    if type(value.get('schemaVersion'))is not int or value.get('schemaVersion')!=1 or value.get('project') != PROJECT or value.get('sourceInstance') != SOURCE or value.get('database') != DATABASE or value.get('marker') != MARKER:
        raise Rejected('Exact dev source/fixture scope required')
    if not re.fullmatch(r'[a-f0-9]{64}', value.get('ownershipProofSha256', '')) or not re.fullmatch(r'[a-z0-9-]{8,64}', value.get('sourceLineageId', '')):
        raise Rejected('Reviewed ownership proof and external source lineage required')
    targets = value.get('targets', [])
    if not isinstance(targets,list) or len(targets)!=20 or not all(isinstance(row,dict)for row in targets):
        raise Rejected('Bounded exact target inventory required')
    expected_targets = [{'studentId': n, 'schoolId': 990007101 if n <= 990007310 else 990007102} for n in range(990007301, 990007321)]
    if sorted(targets, key=lambda row: row.get('studentId', 0)) != expected_targets:
        raise Rejected('Only the twenty independently verified synthetic targets are supported')
    return targets

def export_sql(value, expected):
    targets = inventory(value, expected)
    ids = ','.join(str(row['studentId']) for row in targets)
    # Do not filter on published_at or occurred_at: failed publication and transaction-start
    # timestamps must not hide terminal intent. Export every retained event in this fixed scope.
    return f"""BEGIN READ ONLY;
SET LOCAL statement_timeout='10s';
SET LOCAL lock_timeout='3s';
SELECT jsonb_build_object('schemaVersion',1,'project','{PROJECT}','sourceInstance','{SOURCE}',
 'database','{DATABASE}','marker','{MARKER}','inventorySha256','{expected}',
 'sourceLineageId','{value['sourceLineageId']}','capturedAt',to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),
 'records',coalesce(jsonb_agg(jsonb_build_object('eventId',id,'eventKey',event_key,'studentId',aggregate_id::bigint,
 'schoolId',school_id,'occurredAt',to_char(occurred_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')) ORDER BY id),'[]'::jsonb))
FROM tenant_school.outbox_events
WHERE event_type='student.deleted.v1' AND aggregate_type='Student' AND aggregate_id IN ({','.join("'"+str(row['studentId'])+"'" for row in targets)})
 AND event_key='StudentDeleted:'||aggregate_id AND payload->>'id'=aggregate_id AND payload->>'schoolId'=school_id::text;
COMMIT;
"""

def seal(value, expected_inventory, exported, now=None):
    targets = inventory(value, expected_inventory)
    now = now or dt.datetime.now(dt.timezone.utc)
    capture = timestamp(exported.get('capturedAt'))
    if abs((now - capture).total_seconds()) > 600:
        raise Rejected('Export must be freshly captured within ten minutes')
    for key in ['project', 'sourceInstance', 'database', 'marker', 'sourceLineageId']:
        if exported.get(key) != value.get(key):
            raise Rejected('Export provenance mismatch')
    if exported.get('inventorySha256') != expected_inventory:
        raise Rejected('Export inventory binding mismatch')
    records = exported.get('records', [])
    target_map = {row['studentId']: row['schoolId'] for row in targets}
    if not isinstance(records,list) or len(records) != len(targets):
        raise Rejected('Complete deletion inventory required; no partial gate')
    seen_students, seen_events = set(), set()
    normalized = []
    for row in records:
        if set(row) != {'eventId', 'eventKey', 'studentId', 'schoolId', 'occurredAt'}:
            raise Rejected('Unexpected journal fields; no PII or arbitrary SQL accepted')
        student, school, event = positive(row['studentId']), positive(row['schoolId']), positive(row['eventId'])
        if target_map.get(student) != school or row['eventKey'] != 'StudentDeleted:' + str(student):
            raise Rejected('Student/school/event binding mismatch')
        if student in seen_students or event in seen_events or timestamp(row['occurredAt']) > capture:
            raise Rejected('Duplicate or future deletion event')
        seen_students.add(student); seen_events.add(event); normalized.append(row)
    return {'schemaVersion': 1, 'project': PROJECT, 'sourceInstance': SOURCE, 'database': DATABASE,
            'marker': MARKER, 'sourceLineageId': value['sourceLineageId'], 'inventorySha256': expected_inventory,
            'capturedAt': exported['capturedAt'], 'coverage': {'targetCount': 20, 'recordCount': 20,
            'allRetainedScopedDeletionEventsIncluded': True, 'globalJournalComplete': False,
            'precommitDurabilityGuaranteed': False}, 'records': sorted(normalized, key=lambda row: row['studentId'])}

def storage_plan(journal, expected_hash):
    if digest(journal) != expected_hash:
        raise Rejected('Reviewed journal hash mismatch')
    if journal.get('project') != PROJECT or journal.get('sourceInstance') != SOURCE or journal.get('marker') != MARKER or not re.fullmatch(r'[a-z0-9-]{8,64}', journal.get('sourceLineageId', '')):
        raise Rejected('Exact scoped journal required')
    # Immutable manifest per export, not a mutable SQL sequence high-watermark. Sequence IDs
    # and occurred_at are not commit ordering and cannot prove the absence of concurrent gaps.
    key = 'synthetic/' + journal['sourceLineageId'] + '/' + expected_hash + '.json'
    objects = []
    for record in journal['records']:
        identity = {'sourceLineageId': journal['sourceLineageId'], 'studentId': record['studentId'], 'schoolId': record['schoolId'], 'eventId': record['eventId']}
        objects.append({'object': 'synthetic/' + journal['sourceLineageId'] + '/records/' + digest(identity) + '.json',
                        'body': record, 'bodySha256': digest(record), 'ifGenerationMatch': '0'})
    return {'mode': 'PLAN_ONLY_NO_NETWORK', 'bucket': BUCKET, 'object': key, 'bodySha256': expected_hash, 'stableRecordObjects': objects,
            'create': {'method': 'POST', 'url': 'https://storage.googleapis.com/upload/storage/v1/b/' + BUCKET + '/o',
                       'query': {'uploadType': 'media', 'name': key, 'ifGenerationMatch': '0'}, 'contentType': 'application/json'},
            'conflictRule': 'On412, GET existing exact object/generation and compare full canonical SHA256; unequal content fails closed',
            'readRule': 'Pin the verified returned object generation; never consume an unpinned latest object',
            'requiredReadback': ['bucket', 'object', 'generation', 'size', 'canonicalSha256'],
            'automaticUpload': False, 'retentionPeriodApproved': False}

def replay_sql(value, expected_inventory, journal, expected_journal, clone, clone_evidence, expected_clone_hash, generation, now=None):
    inventory(value, expected_inventory)
    if digest(journal) != expected_journal or journal.get('inventorySha256') != expected_inventory:
        raise Rejected('Journal hash/inventory mismatch')
    # Revalidate all terminal records rather than trusting a JSON passed to storage_plan.
    validated = seal(value, expected_inventory, {**journal, 'inventorySha256': expected_inventory}, now)
    if canonical(validated) != canonical(journal):
        raise Rejected('Journal schema or coverage changed')
    positive(generation)
    if not CLONE.fullmatch(clone) or clone == SOURCE or digest(clone_evidence) != expected_clone_hash:
        raise Rejected('Reviewed isolated clone required')
    if clone_evidence.get('project') != PROJECT or clone_evidence.get('sourceInstance') != SOURCE or clone_evidence.get('clone') != clone or clone_evidence.get('sourceLineageId') != value['sourceLineageId']:
        raise Rejected('Clone provenance mismatch')
    if clone_evidence.get('trafficAttached') is not False or clone_evidence.get('deliveryDisabled') is not True or clone_evidence.get('state') != 'RUNNABLE':
        raise Rejected('Clone must be isolated and delivery disabled')
    capture = timestamp(clone_evidence.get('capturedAt'))
    now = now or dt.datetime.now(dt.timezone.utc)
    if abs((now - capture).total_seconds()) > 600:
        raise Rejected('Clone readback must be fresh within ten minutes')
    ip = ipaddress.ip_address(clone_evidence.get('privateIp', ''))
    if ip.version != 4 or not ip.is_private or ip.is_loopback or ip.is_link_local or str(ip) == '10.92.0.3':
        raise Rejected('Primary SQL address forbidden')
    records = validated['records']; targets = ','.join(f"({row['studentId']},{row['schoolId']},'{timestamp(row['occurredAt']).isoformat()}'::timestamptz)" for row in records)
    # Existing fixtures provide a strong exact row fingerprint. No arbitrary business erase,
    # free-form table names, or cross-school fallback is generated by this prototype.
    return f"""-- PLAN ONLY. Target approved clone {clone}, private IP {ip}; NEVER primary {SOURCE}.
-- Journal SHA256 {expected_journal}, pinned GCS generation {generation}.
-- Delivery remains disabled. This SQL does NOT grant restoration-ready status.
BEGIN;
SET LOCAL statement_timeout='20s'; SET LOCAL lock_timeout='3s'; SET LOCAL app.bypass_rls='on';
CREATE TEMP TABLE reviewed_erasure_targets(student_id bigint PRIMARY KEY,school_id bigint NOT NULL,erasure_at timestamptz NOT NULL) ON COMMIT DROP;
INSERT INTO reviewed_erasure_targets VALUES {targets};
DO $guard$ BEGIN
 IF EXISTS(SELECT 1 FROM student.students s JOIN reviewed_erasure_targets t ON t.student_id=s.id
 WHERE s.school_id<>t.school_id OR s.admission_no<>'{MARKER}-STUDENT-'||(s.id-990007300)
 OR s.created_by IS DISTINCT FROM '{MARKER}' OR s.photo_url IS NOT NULL)
 OR EXISTS(SELECT 1 FROM reporting.dim_student s JOIN reviewed_erasure_targets t ON t.student_id=s.id WHERE s.school_id<>t.school_id)
 OR EXISTS(SELECT 1 FROM notification.notification_logs s JOIN reviewed_erasure_targets t ON t.student_id=s.student_id WHERE s.school_id<>t.school_id)
 THEN RAISE EXCEPTION 'Exact restored ownership/manifest mismatch; no writes permitted'; END IF;
END $guard$;
-- Re-seed terminal downstream fences BEFORE the source rows are removed.
INSERT INTO reporting.student_projection_tombstones(student_id,deleted_at,recorded_at)
SELECT student_id,erasure_at,clock_timestamp() FROM reviewed_erasure_targets
ON CONFLICT(student_id) DO UPDATE SET deleted_at=GREATEST(reporting.student_projection_tombstones.deleted_at,EXCLUDED.deleted_at);
DELETE FROM notification.notification_broadcast_recipients r USING reviewed_erasure_targets t WHERE r.student_id=t.student_id AND r.school_id=t.school_id;
DELETE FROM notification.notification_logs r USING reviewed_erasure_targets t WHERE r.student_id=t.student_id;
DELETE FROM reporting.event_student_contributions r USING reviewed_erasure_targets t WHERE r.student_id=t.student_id;
DELETE FROM reporting.fact_payment r USING reviewed_erasure_targets t WHERE r.student_id=t.student_id;
DELETE FROM reporting.fact_fee_assignment r USING reviewed_erasure_targets t WHERE r.student_id=t.student_id;
DELETE FROM reporting.dim_student r USING reviewed_erasure_targets t WHERE r.id=t.student_id AND r.school_id=t.school_id;
-- Synthetic scope has no child/financial/provider/photo records. Refuse rather than cascade.
DO $children$ BEGIN
 IF EXISTS(SELECT 1 FROM student.student_guardians r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM fee.payment_records r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM attendance.attendance_student_records r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM attendance.absentee_notifications r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM fee.fee_assignments r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM student.photo_import_rows r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM student.import_rows r JOIN reviewed_erasure_targets t ON r.applied_student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM student.student_review_items r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM student.student_promotion_batch_items r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM student.student_enrollments r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 OR EXISTS(SELECT 1 FROM student.student_consent_events r JOIN reviewed_erasure_targets t ON r.student_id=t.student_id)
 THEN RAISE EXCEPTION 'Full owner erasure workflow required for dependent records'; END IF;
END $children$;
DELETE FROM student.students s USING reviewed_erasure_targets t WHERE s.id=t.student_id AND s.school_id=t.school_id;
DO $verify$ BEGIN
 IF EXISTS(SELECT 1 FROM student.students s JOIN reviewed_erasure_targets t ON s.id=t.student_id)
 OR (SELECT count(*) FROM reporting.student_projection_tombstones p JOIN reviewed_erasure_targets t ON p.student_id=t.student_id)<>20
 THEN RAISE EXCEPTION 'Replay postconditions incomplete'; END IF;
END $verify$;
COMMIT;
"""

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['export-sql', 'seal', 'storage-plan', 'replay-sql'])
    parser.add_argument('--inventory', required=True); parser.add_argument('--inventory-sha256', required=True)
    parser.add_argument('--export'); parser.add_argument('--journal'); parser.add_argument('--journal-sha256')
    parser.add_argument('--clone'); parser.add_argument('--clone-evidence'); parser.add_argument('--clone-sha256')
    parser.add_argument('--generation', type=int); parser.add_argument('--output', required=True)
    args = parser.parse_args(); load = load_bounded_json
    inv = load(args.inventory); inventory(inv, args.inventory_sha256)
    if args.mode == 'export-sql': result = export_sql(inv, args.inventory_sha256)
    elif args.mode == 'seal': result = canonical(seal(inv, args.inventory_sha256, load(args.export))).decode()
    elif args.mode == 'storage-plan':
        journal = load(args.journal)
        validated = seal(inv, args.inventory_sha256, journal)
        if canonical(validated) != canonical(journal): raise Rejected('Journal schema/coverage mismatch')
        result = canonical(storage_plan(journal, args.journal_sha256)).decode()
    else: result = replay_sql(inv, args.inventory_sha256, load(args.journal), args.journal_sha256, args.clone, load(args.clone_evidence), args.clone_sha256, args.generation)
    # Preserve the reviewed operation journal; no overwrite or implicit execution.
    with pathlib.Path(args.output).open('x', encoding='utf-8', newline='\n') as output: output.write(result)
    print(json.dumps({'mode': 'PLAN_ONLY_NO_NETWORK', 'operation': args.mode, 'outputSha256': hashlib.sha256(result.encode()).hexdigest(), 'restorationReady': False, 'deliveryResume': False}))

def load_bounded_json(path):
    with pathlib.Path(path).open('rb') as stream: data=stream.read(131073)
    if len(data)>131072: raise Rejected('Artifact exceeds128KiB ceiling')
    value=json.loads(data.decode('utf-8'))
    if not isinstance(value,dict): raise Rejected('Artifact must be an object')
    return value

if __name__ == '__main__':
    try: main()
    except (Rejected, ValueError, KeyError, TypeError, OSError, RecursionError, AttributeError) as error:
        raise SystemExit('ERASURE_PLAN_REJECTED: ' + type(error).__name__) from None
