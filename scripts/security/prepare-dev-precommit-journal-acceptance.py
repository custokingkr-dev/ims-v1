"""Guarded fresh dev journal acceptance. Default mode performs no cloud calls.

Explicit apply modes use bounded fixed-project owner jobs. Browser deletion is a separate
normal authenticated CTAP2 ceremony; this helper never directly deletes a student.
"""
import argparse
import hashlib
import json
import pathlib
import re
import uuid

PROJECT = 'custoking-dev'
IDS = {'schoolId': 990008101, 'userId': 990008201, 'studentId': 990008301}
SHA = re.compile(r'[a-f0-9]{64}')
NONCE = re.compile(r'[a-f0-9]{12}')
JOURNAL_ENV_KEYS = frozenset('STUDENT_ERASURE_JOURNAL_' + suffix for suffix in (
    'ENABLED', 'PROJECT_ID', 'BUCKET', 'SOURCE_LINEAGE_ID', 'RESTORE_EPOCH', 'EPOCH_GENERATION', 'EPOCH_SHA256'))

def require(ok, code):
    if not ok:
        raise ValueError(code)

def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode('ascii')

def marker(nonce):
    require(isinstance(nonce, str) and NONCE.fullmatch(nonce), 'INVALID_NONCE')
    return 'SEC-JOURNAL-DEV-20261007-' + nonce

def validate_plan(plan):
    require(plan.get('project') == PROJECT and plan.get('region') == 'asia-south2', 'DEV_SCOPE_REQUIRED')
    require(plan.get('ids') == IDS, 'FRESH_RESERVED_IDS_REQUIRED')
    require(plan.get('marker') == marker(plan.get('nonce')), 'MARKER_BINDING_REQUIRED')
    require(isinstance(plan.get('sourceSha'), str) and re.fullmatch(r'[a-f0-9]{40}', plan['sourceSha']), 'EXACT_SOURCE_REQUIRED')
    services = plan.get('services', {})
    require(set(services) == {'custoking-school-core-service-dev', 'custoking-identity-service-dev', 'custoking-frontend-dev', 'custoking-api-gateway-dev'}, 'EXACT_SERVICES_REQUIRED')
    for name, entry in services.items():
        require(isinstance(entry, dict) and re.fullmatch(re.escape(name) + r'-[a-z0-9-]+', entry.get('revision', '')), 'EXACT_REVISION_REQUIRED')
        require(re.fullmatch(re.escape('asia-south2-docker.pkg.dev/custoking-dev/custoking/' + name.removesuffix('-dev')) + r'@sha256:[a-f0-9]{64}', entry.get('image', '')), 'IMMUTABLE_DEV_IMAGE_REQUIRED')
    env = plan.get('journalEnvironment', {})
    require(isinstance(env, dict) and set(env) == JOURNAL_ENV_KEYS, 'EXACT_JOURNAL_ENVIRONMENT_KEYS_REQUIRED')
    require(env.get('STUDENT_ERASURE_JOURNAL_ENABLED') == 'true', 'JOURNAL_ENABLED_REQUIRED')
    require(env.get('STUDENT_ERASURE_JOURNAL_PROJECT_ID') == PROJECT, 'JOURNAL_PROJECT_REQUIRED')
    require(env.get('STUDENT_ERASURE_JOURNAL_BUCKET') == 'custoking-dev-erasure-journal', 'JOURNAL_BUCKET_REQUIRED')
    for key in ('STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID', 'STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH'):
        require(str(uuid.UUID(env.get(key, ''))) == env[key], 'CANONICAL_UUID_REQUIRED')
    require(re.fullmatch(r'[1-9][0-9]{0,19}', env.get('STUDENT_ERASURE_JOURNAL_EPOCH_GENERATION', '')), 'PINNED_CONTROL_GENERATION_REQUIRED')
    require(SHA.fullmatch(env.get('STUDENT_ERASURE_JOURNAL_EPOCH_SHA256', '')), 'PINNED_CONTROL_HASH_REQUIRED')
    return plan

def collision_sql(plan):
    validate_plan(plan)
    m = plan['marker']
    # Includes durable deletion witnesses, not merely current source rows.
    return """BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SET LOCAL app.bypass_rls='on';
SELECT jsonb_build_object('marker','%s','database',current_database(),
 'source', (SELECT count(*) FROM student.students WHERE id=990008301),
 'school', (SELECT count(*) FROM tenant_school.schools WHERE id=990008101),
 'actor', (SELECT count(*) FROM identity.app_users WHERE id=990008201 OR email='%s@security-fixture.invalid'),
 'receipt', (SELECT count(*) FROM student.erasure_journal_receipts WHERE student_id=990008301),
 'photoQueue', (SELECT count(*) FROM tenant_school.photo_cleanup_outbox WHERE student_id=990008301),
 'sourceOutbox', (SELECT count(*) FROM tenant_school.outbox_events WHERE aggregate_id='990008301'),
 'reportingInbox', (SELECT count(*) FROM reporting.reporting_event_inbox WHERE aggregate_id='990008301'),
 'projection', (SELECT count(*) FROM reporting.dim_student WHERE id=990008301),
 'parents', (SELECT count(*) FROM tenant_school.school_classes WHERE id='%s-CLASS') + (SELECT count(*) FROM tenant_school.academic_years WHERE id='%s-YEAR') + (SELECT count(*) FROM tenant_school.school_sections WHERE id='%s-SECTION'),
 'tombstone', (SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id=990008301)); COMMIT;
""" % (m, m.lower(), m, m, m)

def verify_collision(plan, observed):
    validate_plan(plan)
    require(observed.get('marker') == plan['marker'] and observed.get('database') == 'custoking_dev', 'COLLISION_READBACK_BINDING_REQUIRED')
    for key in ('source', 'school', 'actor', 'receipt', 'photoQueue', 'sourceOutbox', 'reportingInbox', 'projection', 'parents', 'tombstone'):
        require(type(observed.get(key)) is int and observed[key] == 0, 'RESERVED_SCOPE_OCCUPIED:' + key)

def verify_ready(plan, observed):
    validate_plan(plan)
    require(observed.get('project') == PROJECT and observed.get('releaseSourceSha') == plan['sourceSha'], 'RELEASE_SOURCE_BINDING_REQUIRED')
    # releaseSourceSha must be collected from authenticated immutable release evidence;
    # Cloud Run revision name alone does not establish source provenance.
    require(observed.get('releaseEvidenceVerified') is True, 'VERIFIED_RELEASE_EVIDENCE_REQUIRED')
    require(set(observed.get('services', {})) == set(plan['services']), 'READBACK_SERVICES_REQUIRED')
    for name, wanted in plan['services'].items():
        actual = observed['services'][name]
        require(actual.get('revision') == wanted['revision'] and actual.get('image') == wanted['image'], 'READY_ARTIFACT_MISMATCH')
        require(actual.get('ready') is True and actual.get('trafficPercent') == 100 and actual.get('taggedOtherRevisions') == [], 'EXACT_READY_TRAFFIC_REQUIRED')
    require(observed.get('journalEnvironment') == plan['journalEnvironment'], 'JOURNAL_ENVIRONMENT_MISMATCH')

def verify_intent(plan, receipt, raw):
    validate_plan(plan)
    require(type(raw) is bytes and len(raw) <= 4096, 'INTENT_SIZE_REJECTED')
    body = json.loads(raw)
    env = plan['journalEnvironment']
    identity = dict(schemaVersion=1, project=PROJECT, sourceInstance='custoking-db-dev', database='custoking_dev',
        sourceLineageId=env['STUDENT_ERASURE_JOURNAL_SOURCE_LINEAGE_ID'], restoreEpoch=env['STUDENT_ERASURE_JOURNAL_RESTORE_EPOCH'],
        schoolId=IDS['schoolId'], studentId=IDS['studentId'], studentIncarnation=str(uuid.UUID(receipt['student_incarnation'])))
    intent_id = hashlib.sha256(canonical(identity)).hexdigest()
    # Java UUID.nameUUIDFromBytes uses raw MD5 bytes with UUID v3 bits, no namespace.
    operation = str(uuid.UUID(bytes=hashlib.md5(('ims-student-erasure:' + intent_id).encode('ascii')).digest(), version=3))
    expected = dict(identity, kind='student.erasure-intent.v1', intentId=intent_id, operationId=operation)
    require(raw == canonical(expected), 'NONCANONICAL_OR_FOREIGN_INTENT')
    expected_object = 'intents/' + identity['sourceLineageId'] + '/' + identity['restoreEpoch'] + '/' + intent_id + '.json'
    require(receipt.get('intent_id') == intent_id and receipt.get('operation_id') == operation, 'SQL_INTENT_IDENTITY_MISMATCH')
    require(receipt.get('student_id') == IDS['studentId'] and receipt.get('school_id') == IDS['schoolId'], 'SQL_SCHOOL_STUDENT_MISMATCH')
    require(receipt.get('source_lineage_id') == identity['sourceLineageId'] and receipt.get('restore_epoch') == identity['restoreEpoch'], 'SQL_EPOCH_MISMATCH')
    require(receipt.get('journal_object') == expected_object, 'SQL_OBJECT_MISMATCH')
    require(type(receipt.get('journal_generation')) is int and receipt['journal_generation'] > 0, 'SQL_GENERATION_REQUIRED')
    require(receipt.get('journal_sha256') == hashlib.sha256(raw).hexdigest(), 'SQL_CONTENT_HASH_MISMATCH')
    return dict(intentId=intent_id, object=expected_object, generation=receipt['journal_generation'], sha256=receipt['journal_sha256'], canonical=True)

def read_json(path):
    p = pathlib.Path(path)
    require(p.stat().st_size <= 262144, 'INPUT_SIZE_REJECTED')
    return json.loads(p.read_text(encoding='utf-8'))

def bounded_cloud(args, timeout=90, cap=2097152):
    import subprocess, tempfile, time, os
    # Disk-spooled bounded output avoids unrestricted communicate()/capture_output allocation.
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        proc = subprocess.Popen(['gcloud.cmd', *args, '--project=' + PROJECT], stdout=out, stderr=err)
        end = time.monotonic() + timeout
        while proc.poll() is None:
            if time.monotonic() >= end or os.fstat(out.fileno()).st_size > cap or os.fstat(err.fileno()).st_size > cap:
                proc.kill(); proc.wait(timeout=10)
                raise RuntimeError('CLOUD_DEADLINE_OR_SIZE_OUTPUT_WITHHELD')
            time.sleep(.05)
        require(os.fstat(out.fileno()).st_size <= cap and os.fstat(err.fileno()).st_size <= cap, 'CLOUD_OUTPUT_SIZE_REJECTED')
        require(proc.returncode == 0, 'CLOUD_FAILED_OUTPUT_WITHHELD')
        out.seek(0)
        return out.read(cap + 1).decode('utf-8')

def transport(plan):
    import importlib.util
    spec = importlib.util.spec_from_file_location('existing_fixture_transport', pathlib.Path(__file__).with_name('final-live-fixture-acceptance.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    module.MARKER = plan['marker']
    module.cloud = bounded_cloud
    return module

def actual_ready(plan, release):
    require(release.get('sourceHeadSha') == plan['sourceSha'] and release.get('conclusion') == 'success' and release.get('status') == 'completed', 'SUCCESSFUL_EXACT_RELEASE_REQUIRED')
    rows = {('custoking-' + r['service'] + '-dev'): r for r in release.get('services', [])}
    observed = dict(project=PROJECT, releaseSourceSha=release['sourceHeadSha'], releaseEvidenceVerified=True, services={}, journalEnvironment={})
    for name, wanted in plan['services'].items():
        require(name in rows and rows[name].get('revision') == wanted['revision'] and rows[name].get('runtimeImage') == wanted['image'], 'RELEASE_IMAGE_BINDING_REQUIRED')
        service = json.loads(bounded_cloud(['run', 'services', 'describe', name, '--region=asia-south2', '--format=json']))
        status = service['status']
        require(status.get('latestReadyRevisionName') == wanted['revision'] and status.get('latestCreatedRevisionName') == wanted['revision'], 'LATEST_EXACT_READY_REQUIRED')
        traffic = status.get('traffic', [])
        require(len(traffic) == 1 and traffic[0].get('revisionName') == wanted['revision'] and traffic[0].get('percent') == 100 and not traffic[0].get('tag'), 'NO_OLD_TRAFFIC_OR_TAGS_REQUIRED')
        revision = json.loads(bounded_cloud(['run', 'revisions', 'describe', wanted['revision'], '--region=asia-south2', '--format=json']))
        require(any(c.get('type') == 'Ready' and c.get('status') == 'True' for c in revision.get('status', {}).get('conditions', [])), 'REVISION_NOT_READY')
        container = revision['spec']['containers'][0]
        image = revision.get('status', {}).get('imageDigest') or container.get('image')
        require(image == wanted['image'], 'REVISION_DIGEST_MISMATCH')
        observed['services'][name] = dict(revision=wanted['revision'], image=image, ready=True, trafficPercent=100, taggedOtherRevisions=[])
        if name == 'custoking-school-core-service-dev':
            envrows = container.get('env', [])
            require(len({e['name'] for e in envrows}) == len(envrows), 'DUPLICATE_ENVIRONMENT_REJECTED')
            env = {e['name']: e.get('value') for e in envrows}
            observed['journalEnvironment'] = {k: env.get(k) for k in plan['journalEnvironment']}
            require(env.get('RUNTIME_DB_ROLE') == 'ims_school_core_rt', 'DEDICATED_RUNTIME_ROLE_REQUIRED')
            require(env.get('MSG91_DRY_RUN', 'true') == 'true', 'NO_EXTERNAL_SENDS_REQUIRED')
    verify_ready(plan, observed)
    return observed

def seed_sql(plan, hashed):
    import re
    m = plan['marker']
    require(re.fullmatch(r'\$2[aby]\$12\$[./A-Za-z0-9]{53}', hashed), 'BCRYPT_HASH_REQUIRED')
    # Collision checks are repeated under a fixed transaction advisory lock. No ON CONFLICT reuse.
    sql = collision_sql(plan).replace('BEGIN READ ONLY;', 'BEGIN; SET LOCAL lock_timeout=\'5s\'; SELECT pg_advisory_xact_lock(990008101);')
    sql = sql[:sql.index('SELECT jsonb_build_object')]
    sql += """DO $$ BEGIN IF EXISTS(SELECT 1 FROM student.students WHERE id=990008301) OR EXISTS(SELECT 1 FROM tenant_school.schools WHERE id=990008101) OR EXISTS(SELECT 1 FROM identity.app_users WHERE id=990008201 OR email='%s@security-fixture.invalid') OR EXISTS(SELECT 1 FROM student.erasure_journal_receipts WHERE student_id=990008301) OR EXISTS(SELECT 1 FROM reporting.student_projection_tombstones WHERE student_id=990008301) OR EXISTS(SELECT 1 FROM reporting.dim_student WHERE id=990008301) OR EXISTS(SELECT 1 FROM tenant_school.outbox_events WHERE aggregate_id='990008301') OR EXISTS(SELECT 1 FROM reporting.reporting_event_inbox WHERE aggregate_id='990008301') OR EXISTS(SELECT 1 FROM tenant_school.photo_cleanup_outbox WHERE student_id=990008301) OR EXISTS(SELECT 1 FROM tenant_school.school_classes WHERE id='%s-CLASS') OR EXISTS(SELECT 1 FROM tenant_school.academic_years WHERE id='%s-YEAR') OR EXISTS(SELECT 1 FROM tenant_school.school_sections WHERE id='%s-SECTION') OR (SELECT count(*) FROM identity.roles WHERE name='SCHOOL_ADMIN')<>1 THEN RAISE EXCEPTION 'Reserved fixture collision'; END IF; END $$;
INSERT INTO tenant_school.schools(id,name,short_code,active,created_at) VALUES(990008101,'%s','SJ%s',true,now());
INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled,notes) VALUES(990008101,'STUDENTS',true,'%s');
INSERT INTO identity.app_users(id,full_name,email,password_hash,role,branch_id,branch_name,created_at) VALUES(990008201,'%s','%s@security-fixture.invalid','%s','SCHOOL_ADMIN',990008101,'%s',now());
INSERT INTO identity.user_role_assignments(user_id,role_id,school_id,active) SELECT 990008201,id,990008101,true FROM identity.roles WHERE name='SCHOOL_ADMIN';
INSERT INTO tenant_school.school_classes(id,name,sort_order) VALUES('%s-CLASS','%s',99);
INSERT INTO tenant_school.academic_years(id,label,active) VALUES('%s-YEAR','%s',true);
INSERT INTO tenant_school.school_sections(id,name,active,school_class_id,school_id) VALUES('%s-SECTION','%s',true,'%s-CLASS',990008101);
INSERT INTO student.students(id,admission_no,full_name,created_at,updated_at,school_id,class_id,section_id,academic_year_id,created_by) VALUES(990008301,'%s-STUDENT','%s',now(),now(),990008101,'%s-CLASS','%s-SECTION','%s-YEAR','%s');
SELECT jsonb_build_object('marker','%s','studentIncarnation',(SELECT erasure_incarnation::text FROM student.students WHERE id=990008301),'seeded',(SELECT count(*) FROM student.students WHERE id=990008301 AND school_id=990008101 AND admission_no='%s-STUDENT' AND created_by='%s')); COMMIT;
""" % (m.lower(),m,m,m,m,plan['nonce'],m,m,m.lower(),hashed,m,m,m,m,m,m,m,m,m,m,m,m,m,m,m,m,m)
    return sql

def verify_sql(plan):
    m = plan['marker']
    children = ['student.student_guardians','student.student_enrollments','student.student_consent_events','student.student_review_items','student.photo_import_rows','student.student_promotion_batch_items','attendance.absentee_notifications','attendance.attendance_student_records','fee.payment_records','fee.fee_assignments']
    childsum = ' + '.join('(SELECT count(*) FROM '+t+' WHERE student_id=990008301)' for t in children)
    return "BEGIN READ ONLY; SET LOCAL statement_timeout='10s'; SET LOCAL app.bypass_rls='on'; SELECT jsonb_build_object('marker','"+m+"','receipt',(SELECT to_jsonb(r) FROM student.erasure_journal_receipts r WHERE student_id=990008301 AND school_id=990008101),'source',(SELECT count(*) FROM student.students WHERE id=990008301),'children',"+childsum+",'imports',(SELECT count(*) FROM student.import_rows WHERE applied_student_id=990008301),'tombstone',(SELECT count(*) FROM reporting.student_projection_tombstones WHERE student_id=990008301),'incarnationInsert',has_column_privilege('ims_school_core_rt','student.students','erasure_incarnation','INSERT'),'receiptDelete',has_table_privilege('ims_school_core_rt','student.erasure_journal_receipts','DELETE'),'photoDelete',has_table_privilege('ims_school_core_rt','tenant_school.photo_cleanup_outbox','DELETE')); COMMIT;"

def cleanup_sql(plan):
    m=plan['marker']
    return """BEGIN; SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='5s'; SET LOCAL app.bypass_rls='on'; SELECT pg_advisory_xact_lock(990008101);
DO $$ BEGIN IF EXISTS(SELECT 1 FROM student.students WHERE id=990008301 OR school_id=990008101) OR (SELECT count(*) FROM student.erasure_journal_receipts WHERE student_id=990008301 AND school_id=990008101)<>1 OR EXISTS(SELECT 1 FROM tenant_school.school_sections WHERE school_id=990008101 AND (id<>'%s-SECTION' OR name<>'%s')) OR EXISTS(SELECT 1 FROM tenant_school.school_sections WHERE school_class_id='%s-CLASS' AND school_id<>990008101) OR EXISTS(SELECT 1 FROM student.students WHERE class_id='%s-CLASS' OR academic_year_id='%s-YEAR') OR EXISTS(SELECT 1 FROM tenant_school.school_module_entitlements WHERE school_id=990008101 AND notes IS DISTINCT FROM '%s') OR (SELECT count(*) FROM identity.app_users WHERE id=990008201 AND full_name='%s' AND email='%s@security-fixture.invalid' AND branch_id=990008101 AND role='SCHOOL_ADMIN')<>1 OR (SELECT count(*) FROM tenant_school.schools WHERE id=990008101 AND name='%s')<>1 THEN RAISE EXCEPTION 'Exact cleanup ownership failed'; END IF; END $$;
UPDATE identity.app_users SET deleted_at=coalesce(deleted_at,now()),deleted_by='%s',credential_version=credential_version+1 WHERE id=990008201;
UPDATE identity.auth_sessions SET status='REVOKED' WHERE user_id=990008201;
UPDATE identity.user_role_assignments SET active=false,revoked_at=now() WHERE user_id=990008201;
INSERT INTO identity.rbac_audit_log(event_type,target_user_id,new_value,correlation_id) VALUES('SYNTHETIC_ORDINARY_ACTOR_DISABLED',990008201,'Exact journal acceptance fixture disabled','%s');
DELETE FROM tenant_school.school_sections WHERE id='%s-SECTION' AND school_id=990008101 AND name='%s';
DELETE FROM tenant_school.school_module_entitlements WHERE school_id=990008101 AND notes='%s';
DELETE FROM tenant_school.schools WHERE id=990008101 AND name='%s';
DELETE FROM tenant_school.school_classes WHERE id='%s-CLASS' AND name='%s';
DELETE FROM tenant_school.academic_years WHERE id='%s-YEAR' AND label='%s';
SELECT jsonb_build_object('marker','%s','disabled',(SELECT count(*) FROM identity.app_users WHERE id=990008201 AND deleted_at IS NOT NULL),'activeSessions',(SELECT count(*) FROM identity.auth_sessions WHERE user_id=990008201 AND status='ACTIVE'),'school',(SELECT count(*) FROM tenant_school.schools WHERE id=990008101),'receipt',(SELECT count(*) FROM student.erasure_journal_receipts WHERE student_id=990008301)); COMMIT;
""" % (m,m,m,m,m,m,m,m.lower(),m,m,m,m,m,m,m,m,m,m,m,m)

def main():
    import secrets, datetime
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan',required=True); parser.add_argument('--out',required=True)
    parser.add_argument('--apply-dev',action='store_true'); parser.add_argument('--mode',choices=['prepare','seed','verify','cleanup'],default='prepare')
    parser.add_argument('--release-evidence'); parser.add_argument('--release-evidence-sha256'); parser.add_argument('--browser-proof')
    args=parser.parse_args(); plan=validate_plan(read_json(args.plan))
    out=pathlib.Path(args.out); require(not out.exists(),'OUTPUT_ALREADY_EXISTS')
    if args.mode=='prepare':
        require(not args.apply_dev,'PREPARE_NEVER_APPLIES')
        result=dict(preparationOnly=True,project=PROJECT,marker=plan['marker'],ids=IDS,collisionPreflightSql=collision_sql(plan))
    else:
        require(args.apply_dev and args.release_evidence and args.release_evidence_sha256,'EXPLICIT_DEV_AND_PINNED_RELEASE_REQUIRED')
        releasepath=pathlib.Path(args.release_evidence)
        require(releasepath.stat().st_size<=262144,'RELEASE_SIZE_REJECTED')
        releasebytes=releasepath.read_bytes()
        require(SHA.fullmatch(args.release_evidence_sha256) and hashlib.sha256(releasebytes).hexdigest()==args.release_evidence_sha256,'RELEASE_HASH_MISMATCH')
        ready=actual_ready(plan,json.loads(releasebytes))
        module=transport(plan); private=module.TMP/('dev-journal-'+plan['nonce']+'-private.json')
        if args.mode=='seed':
            require(not private.exists(),'PRIVATE_CONFIG_ALREADY_EXISTS')
            verify_collision(plan,module.owner_sql('journal-collision',collision_sql(plan)))
            import bcrypt
            password=secrets.token_urlsafe(32); hashed=bcrypt.hashpw(password.encode(),bcrypt.gensalt(rounds=12)).decode()
            config=dict(project=PROJECT,guardedReady=True,expectedSourceSha=plan['sourceSha'],marker=plan['marker'],nonce=plan['nonce'],email=plan['marker'].lower()+'@security-fixture.invalid',password=password,**IDS,admissionNumber=plan['marker']+'-STUDENT',role='SCHOOL_ADMIN',origin=module.ORIGIN)
            # Preserve credentials on ambiguous seed result so exact-owner recovery stays possible.
            with private.open('x',encoding='utf-8') as f: json.dump(config,f)
            result=module.owner_sql('journal-seed',seed_sql(plan,hashed)); require(result.get('seeded')==1,'SEED_NOT_CONFIRMED')
            config['studentIncarnation']=str(uuid.UUID(result['studentIncarnation']))
            private.write_text(json.dumps(config),encoding='utf-8')
            result.update(privateConfig=str(private),ready=ready)
        elif args.mode=='verify':
            require(args.browser_proof,'APPLICATION_BROWSER_PROOF_REQUIRED')
            browser=read_json(args.browser_proof)
            require(browser.get('project')==PROJECT and browser.get('marker')==plan['marker'] and browser.get('studentId')==IDS['studentId'] and browser.get('expectedSourceSha')==plan['sourceSha'] and browser.get('passed') is True,'APPLICATION_PROOF_BINDING_REQUIRED')
            require(any(c.get('name')=='confirmed-application-delete' and c.get('status')==200 and c.get('passed') is True for c in browser.get('checks',[])),'APPLICATION_200_DELETE_REQUIRED')
            result=module.owner_sql('journal-verify',verify_sql(plan))
            require(result.get('source')==0 and result.get('children')==0 and result.get('imports')==0,'SOURCE_CHILDREN_NOT_ERASED')
            for k in ('incarnationInsert','receiptDelete','photoDelete'): require(result.get(k) is False,'RUNTIME_GRANT_TOO_BROAD:'+k)
            receipt=result.get('receipt'); require(isinstance(receipt,dict),'SQL_RECEIPT_REQUIRED')
            config=read_json(private)
            require(config.get('marker')==plan['marker'] and all(config.get(k)==v for k,v in IDS.items()),'PRIVATE_FIXTURE_BINDING_REQUIRED')
            require(config.get('studentIncarnation')==receipt.get('student_incarnation'),'ORIGINAL_INCARNATION_REQUIRED')
            generation=receipt.get('journal_generation'); obj=receipt.get('journal_object','')
            require(type(generation) is int and generation>0 and re.fullmatch(r'intents/[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9]{64}\.json',obj),'PINNED_INTENT_PATH_REQUIRED')
            raw=bounded_cloud(['storage','cat','gs://custoking-dev-erasure-journal/'+obj+'#'+str(generation)],60,4096).encode('utf-8')
            result['journal']=verify_intent(plan,receipt,raw)
            result['downstreamTombstoneObserved']=result.get('tombstone')==1
            result['photoChecked']=False
        else:
            config=read_json(private)
            require(config.get('marker')==plan['marker'] and all(config.get(k)==v for k,v in IDS.items()),'PRIVATE_FIXTURE_BINDING_REQUIRED')
            result=module.owner_sql('journal-cleanup',cleanup_sql(plan))
            require(result.get('disabled')==1 and result.get('activeSessions')==0 and result.get('school')==0 and result.get('receipt')==1,'CLEANUP_NOT_CONFIRMED')
            status,_=module.req('POST','/api/v1/auth/login',dict(email=config['email'],password=config['password']))
            require(status==401,'DISABLED_ACTOR_LOGIN_NOT_REJECTED')
            result['disabledActorLoginStatus']=status
            private.unlink(missing_ok=True); require(not private.exists(),'PRIVATE_CONFIG_REMAINS')
            result['privateConfigRemoved']=True
        result.update(project=PROJECT,mode=args.mode,checkedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':
    main()
