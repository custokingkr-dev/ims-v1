"""Bounded offline owner replay preparation. Never connects, erases, or authorizes restore."""
import argparse, hashlib, importlib.util, json, pathlib, sys
p=pathlib.Path(__file__).with_name('verify-dev-erasure-coverage.py')
s=importlib.util.spec_from_file_location('coverage_adapter',p);coverage=importlib.util.module_from_spec(s);s.loader.exec_module(coverage)
require=coverage.require
canonical=coverage.canonical

def prepare(bundle, reviewed_hash):
    coverage.verify(bundle,reviewed_hash)
    tasks=[]
    for epoch in bundle['epochs']:
        for obj in epoch['objects']:
            body=coverage.parse_json(obj['bodyUtf8'])
            tasks.append({k:body[k] for k in ('intentId','operationId','schoolId','studentId','studentIncarnation','restoreEpoch') } | {
                'object':obj['object'],'generation':obj['generation'],'sha256':obj['sha256']})
    tasks.sort(key=lambda t:t['intentId'])
    return dict(schemaVersion=1,mode='OWNER_REPLAY_PREPARATION_ONLY',bundleSha256=reviewed_hash,
        sourceLineageId=bundle['lineageId'],tasks=tasks,restorationReady=False,deliveryResume=False)

def literal(value):
    # Only verified bundle strings reach SQL; quote defensively regardless.
    return "'"+str(value).replace("'","''")+"'"

def snapshot_sql(bundle, reviewed_hash):
    plan=prepare(bundle,reviewed_hash)
    if not plan['tasks']:
        return "BEGIN READ ONLY; SET LOCAL search_path=pg_catalog; SELECT '[]'::json; ROLLBACK;\n"
    rows=[]
    for t in plan['tasks']:
        rows.append('('+','.join([literal(t['intentId']),literal(t['operationId'])+'::uuid',str(t['schoolId']),str(t['studentId']),literal(t['studentIncarnation'])+'::uuid',literal(bundle['lineageId']),literal(t['restoreEpoch'])+'::uuid',literal(t['object']),literal(t['generation'])+'::bigint',literal(t['sha256'])])+')')
    return """BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path=pg_catalog;
SET LOCAL statement_timeout='10s'; SET LOCAL lock_timeout='2s'; SET LOCAL row_security=off;
WITH targets(intent,operation,school,student,incarnation,lineage,epoch,object,generation,sha) AS (VALUES %s),
observed AS (
 SELECT t.intent AS "intentId", CASE
 WHEN EXISTS(SELECT 1 FROM student.erasure_journal_receipts x WHERE
   (x.operation_id=t.operation OR (x.student_incarnation=t.incarnation AND x.restore_epoch=t.epoch)) AND x.intent_id<>t.intent) THEN 'CONFLICT'
 WHEN r.intent_id IS NOT NULL AND (r.operation_id<>t.operation OR r.school_id<>t.school OR r.student_id<>t.student OR r.student_incarnation<>t.incarnation OR r.source_lineage_id<>t.lineage OR r.restore_epoch<>t.epoch OR r.journal_object<>t.object OR r.journal_generation<>t.generation OR r.journal_sha256<>t.sha) THEN 'CONFLICT'
 WHEN s.id IS NOT NULL AND (s.school_id<>t.school OR s.erasure_incarnation<>t.incarnation) THEN 'CONFLICT'
 WHEN s.id IS NOT NULL THEN 'OWNER_REPLAY_REQUIRED'
 WHEN r.intent_id IS NOT NULL THEN 'SOURCE_ERASURE_RECEIPT_MATCHED'
 ELSE 'EXTERNAL_ONLY_SOURCE_ABSENT' END AS state
 FROM targets t LEFT JOIN student.students s ON s.id=t.student
 LEFT JOIN student.erasure_journal_receipts r ON r.intent_id=t.intent)
SELECT coalesce(jsonb_agg(to_jsonb(observed) ORDER BY "intentId"),'[]'::jsonb) FROM observed;
ROLLBACK;
"""%(','.join(rows))

def reconcile(bundle, reviewed_hash, observations):
    plan=prepare(bundle,reviewed_hash)
    require(isinstance(observations,list) and len(observations)==len(plan['tasks']))
    expected={t['intentId'] for t in plan['tasks']};seen=set();counts={}
    for row in observations:
        require(isinstance(row,dict) and set(row)=={'intentId','state'} and row['intentId'] in expected and row['intentId'] not in seen)
        require(row['state'] in ('CONFLICT','OWNER_REPLAY_REQUIRED','SOURCE_ERASURE_RECEIPT_MATCHED','EXTERNAL_ONLY_SOURCE_ABSENT'))
        seen.add(row['intentId']);counts[row['state']]=counts.get(row['state'],0)+1
    require(seen==expected)
    # Snapshot observations are not execution receipts or physical generation verification.
    return dict(schemaVersion=1,mode='UNATTESTED_SNAPSHOT_RECONCILIATION_ONLY',bundleSha256=reviewed_hash,
        taskCount=len(expected),counts=counts,quarantineRequired=counts.get('CONFLICT',0)>0,
        ownerReplayOutstanding=counts.get('OWNER_REPLAY_REQUIRED',0)>0,
        downstreamReconciliationRequired=True,physicalObjectGenerationsVerified=False,
        restorationReady=False,deliveryResume=False)

def main():
    a=argparse.ArgumentParser(description=__doc__);a.add_argument('mode',choices=['plan','snapshot-sql','reconcile']);a.add_argument('--bundle',required=True);a.add_argument('--reviewed-canonical-sha256',required=True);a.add_argument('--observations');a.add_argument('--out',required=True);args=a.parse_args()
    bundle=coverage.parse_json(coverage.read_regular_bounded(args.bundle).decode('utf-8'))
    if args.mode=='plan':result=prepare(bundle,args.reviewed_canonical_sha256)
    elif args.mode=='snapshot-sql':result=snapshot_sql(bundle,args.reviewed_canonical_sha256)
    else:
        require(args.observations is not None)
        result=reconcile(bundle,args.reviewed_canonical_sha256,coverage.parse_json(coverage.read_regular_bounded(args.observations).decode('utf-8')))
    with pathlib.Path(args.out).open('x',encoding='utf-8') as f:f.write(result if isinstance(result,str) else json.dumps(result,indent=2)+'\n')
if __name__=='__main__':
    try:main()
    except Exception:print('OWNER_REPLAY_PREPARATION_REJECTED',file=sys.stderr);sys.exit(1)
