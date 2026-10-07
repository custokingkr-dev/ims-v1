"""Read-only physical target attestation. Never authorizes restoration or delivery."""
import argparse
import hashlib
import ipaddress
import json
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
import threading
import multiprocessing
import signal
import sys
import time

PROJECT = 'custoking-dev'
SOURCE = 'custoking-db-dev'
DATABASE = 'custoking_dev'
VERIFY_ROLE = 'ims_restore_verify'
CLONE = re.compile(r'custoking-dev-security-restore-[0-9]{14}-[a-f0-9]{8}')
LIMIT = 2 * 1024 * 1024
WINDOWS_SUPERVISOR = r'''
import ctypes,json,subprocess,sys
from ctypes import wintypes as w
class Basic(ctypes.Structure):
 _fields_=[('a',ctypes.c_longlong),('b',ctypes.c_longlong),('flags',w.DWORD),('min',ctypes.c_size_t),('max',ctypes.c_size_t),('count',w.DWORD),('affinity',ctypes.c_size_t),('priority',w.DWORD),('schedule',w.DWORD)]
class IO(ctypes.Structure):
 _fields_=[('v'+str(i),ctypes.c_ulonglong) for i in range(6)]
class Extended(ctypes.Structure):
 _fields_=[('basic',Basic),('io',IO),('process',ctypes.c_size_t),('job',ctypes.c_size_t),('peakprocess',ctypes.c_size_t),('peakjob',ctypes.c_size_t)]
k=ctypes.WinDLL('kernel32',use_last_error=True)
k.CreateJobObjectW.restype=w.HANDLE
k.CreateJobObjectW.argtypes=[ctypes.c_void_p,w.LPCWSTR]
k.SetInformationJobObject.argtypes=[w.HANDLE,ctypes.c_int,ctypes.c_void_p,w.DWORD]
k.AssignProcessToJobObject.argtypes=[w.HANDLE,w.HANDLE]
k.GetCurrentProcess.restype=w.HANDLE
k.CloseHandle.argtypes=[w.HANDLE]
job=k.CreateJobObjectW(None,None)
limits=Extended();limits.basic.flags=0x2000
if not job or not k.SetInformationJobObject(job,9,ctypes.byref(limits),ctypes.sizeof(limits)) or not k.AssignProcessToJobObject(job,k.GetCurrentProcess()):
 sys.exit(125)
# Attach the supervisor before it creates children: no spawn/assignment race.
# Kernel closes its job handle on termination, killing every descendant.
code=subprocess.call(json.loads(sys.argv[1]))
# Normal supervisor exit closes its non-inheritable handle after establishing
# its exit code; no explicit self-termination via CloseHandle(job).
sys.exit(code)
'''
ROOT = pathlib.Path(__file__).resolve().parents[2]
FENCES = (
 ('student', 'student_immutable_erasure_incarnation', 'require_immutable_erasure_incarnation', 'services/school-core-service/src/main/resources/db/migration/student/V39__precommit_erasure_journal.sql'),
 ('reporting', 'reporting_inbox_erasure', 'redact_deleted_student_event', 'services/platform-service/src/main/resources/db/migration/reporting/V34__student_inbox_erasure.sql'),
 ('reporting', 'fee_fact_erasure', 'suppress_deleted_student_fact', 'services/platform-service/src/main/resources/db/migration/reporting/V34__student_inbox_erasure.sql'),
 ('reporting', 'payment_fact_erasure', 'suppress_deleted_student_fact', 'services/platform-service/src/main/resources/db/migration/reporting/V34__student_inbox_erasure.sql'),
 ('reporting', 'student_inbox_erasure', 'erase_student_inbox_payloads', 'services/platform-service/src/main/resources/db/migration/reporting/V34__student_inbox_erasure.sql'),
 ('notification', 'notification_inbox_erasure', 'redact_deleted_student_event', 'services/platform-service/src/main/resources/db/migration/notification/V14__student_inbox_erasure.sql'),
 ('notification', 'guard_generic_final_receipt', 'guard_generic_final_receipt', 'services/platform-service/src/main/resources/db/migration/notification/V16__generic_submission_receipts.sql'),
)
TRIGGER_SHAPES = {
 'student_immutable_erasure_incarnation': ('students',23,[]),
 'reporting_inbox_erasure': ('reporting_event_inbox',23,['payload']),
 'fee_fact_erasure': ('fact_fee_assignment',23,[]),
 'payment_fact_erasure': ('fact_payment',23,[]),
 'student_inbox_erasure': ('student_projection_tombstones',21,[]),
 'notification_inbox_erasure': ('notification_inbox_events',23,['payload']),
 'guard_generic_final_receipt': ('generic_submissions',19,[]),
}
SQL = """SELECT n.nspname,t.tgname,t.tgenabled,p.proname,left(p.prosrc,32769),c.relname,t.tgtype,
 ARRAY(SELECT a.attname FROM unnest(t.tgattr::smallint[]) v
 JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=v ORDER BY a.attname)
 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
 JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid
 WHERE NOT t.tgisinternal AND n.nspname IN ('student','reporting','notification')
 AND t.tgname IN ('student_immutable_erasure_incarnation','reporting_inbox_erasure',
 'fee_fact_erasure','payment_fact_erasure','student_inbox_erasure',
 'notification_inbox_erasure','guard_generic_final_receipt')
 AND p.pronamespace=n.oid AND NOT p.prosecdef AND p.proconfig IS NULL
 AND NOT t.tgisinternal AND t.tgqual IS NULL
 ORDER BY n.nspname,t.tgname LIMIT 8"""
SAFETY_SQL = """WITH relations AS (
 SELECT n.nspname||'.'||c.relname AS name,c.oid FROM pg_class c
 JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname IN ('student','tenant_school') AND c.relkind IN ('r','p'))
 SELECT
 EXISTS(SELECT 1 FROM pg_roles r WHERE rolname='ims_school_core_rt'
  AND NOT rolsuper AND NOT rolbypassrls AND NOT rolreplication
  AND NOT rolcreatedb AND NOT rolcreaterole
  AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_class WHERE relowner=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_database WHERE datdba=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_proc WHERE proowner=r.oid)),
 (SELECT count(*)=2 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='student' AND c.relname IN ('students','erasure_journal_receipts')
  AND c.relrowsecurity AND c.relforcerowsecurity
  AND EXISTS(SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)),
 NOT has_column_privilege('ims_school_core_rt',(SELECT oid FROM relations WHERE name='student.students'),'erasure_incarnation','INSERT'),
 NOT has_table_privilege('ims_school_core_rt',(SELECT oid FROM relations WHERE name='student.erasure_journal_receipts'),'DELETE'),
 NOT has_table_privilege('ims_school_core_rt',(SELECT oid FROM relations WHERE name='student.erasure_journal_receipts'),'UPDATE'),
 NOT has_table_privilege('ims_school_core_rt',(SELECT oid FROM relations WHERE name='student.erasure_journal_receipts'),'TRUNCATE'),
 NOT has_table_privilege('ims_school_core_rt',(SELECT oid FROM relations WHERE name='tenant_school.photo_cleanup_outbox'),'DELETE'),
 NOT has_table_privilege('ims_school_core_rt',(SELECT oid FROM relations WHERE name='tenant_school.photo_cleanup_outbox'),'TRUNCATE')"""

def verify_safety(row):
    if row is None or len(row) != 8 or any(value is not True for value in row):
        reject('RUNTIME_ACL_OR_RLS_FENCE_REJECTED')

ROLE_SQL = """SELECT current_user='ims_restore_verify'
 AND EXISTS(SELECT 1 FROM pg_roles r WHERE r.rolname=current_user
  AND NOT rolsuper AND NOT rolbypassrls AND NOT rolreplication
  AND NOT rolcreatedb AND NOT rolcreaterole
  AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_class WHERE relowner=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspowner=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_database WHERE datdba=r.oid)
  AND NOT EXISTS(SELECT 1 FROM pg_proc WHERE proowner=r.oid))
 AND NOT has_database_privilege(current_user,current_database(),'CREATE,TEMP')
 AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname IN ('identity','billing','tenant_school','student','attendance','fee','catalog',
   'workflow','firefighting','reporting','notification','audit') AND c.relkind IN ('r','p','v','m','f')
  AND (has_any_column_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,REFERENCES')
   OR has_table_privilege(current_user,c.oid,'DELETE,TRUNCATE,TRIGGER')))
 AND NOT EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname IN
  ('identity','billing','tenant_school','student','attendance','fee','catalog',
   'workflow','firefighting','reporting','notification','audit')
  AND has_schema_privilege(current_user,n.oid,'CREATE'))"""

def reject(code):
    raise ValueError(code)

def bounded_command(argv, timeout=20, limit=LIMIT):
    """No command output enters diagnostics. Stop a process exceeding the byte cap."""
    command = [sys.executable, '-c', WINDOWS_SUPERVISOR, json.dumps(argv)] if os.name == 'nt' else argv
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          start_new_session=os.name != 'nt') as process:
        def kill_tree():
            if os.name == 'nt':
                # The attached supervisor job kills descendants on handle closure.
                if process.poll() is None:
                    process.kill()
            else:
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
        data = bytearray()
        overflow = threading.Event()
        def consume():
            while True:
                chunk = os.read(process.stdout.fileno(),8192)
                if not chunk:
                    break
                if len(data) + len(chunk) > limit:
                    overflow.set()
                    kill_tree()
                    break
                data.extend(chunk)
        reader = threading.Thread(target=consume, daemon=True, name='restore-metadata-reader')
        reader.start()
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            kill_tree()
            process.wait(timeout=5)
            reader.join(timeout=5)
            reject('METADATA_DEADLINE')
        reader.join(timeout=5)
        if reader.is_alive() or overflow.is_set() or process.returncode:
            kill_tree()
            reader.join(timeout=5)
            reject('METADATA_READ_REJECTED')
        return bytes(data)

def remaining(deadline):
    value = deadline - time.monotonic()
    if value <= 0:
        reject('WHOLE_VERIFICATION_DEADLINE')
    return min(20, value)

def metadata(name, deadline=None):
    deadline = deadline or time.monotonic() + 45
    executable = shutil.which('gcloud.cmd') or shutil.which('gcloud')
    if not executable:
        reject('GCLOUD_REQUIRED')
    raw = bounded_command([executable, 'sql', 'instances', 'describe', name,
        '--project=' + PROJECT, '--format=json', '--quiet'],timeout=remaining(deadline))
    try:
        value = json.loads(raw)
        certificates = json.loads(bounded_command([executable, 'sql', 'ssl', 'server-ca-certs', 'list',
            '--instance=' + name, '--project=' + PROJECT, '--format=json', '--quiet'],timeout=remaining(deadline)))
        if not isinstance(certificates, list) or len(certificates) != 1 or certificates[0].get('cert') != value.get('serverCaCert', {}).get('cert'):
            reject('ROTATED_OR_UNPROVEN_CA_REJECTED')
        return value
    except (ValueError, UnicodeError):
        reject('INVALID_METADATA')

def anchor(value, name):
    if value.get('name') != name or value.get('project') != PROJECT or value.get('region') != 'asia-south2' or value.get('state') != 'RUNNABLE':
        reject('TARGET_IDENTITY_REJECTED')
    config = value.get('settings', {}).get('ipConfiguration', {})
    if config.get('serverCaMode') != 'GOOGLE_MANAGED_INTERNAL_CA':
        reject('UNSUPPORTED_CA_MODE')
    if config.get('authorizedNetworks') or config.get('ipv4Enabled') is True:
        reject('PUBLIC_TARGET_REJECTED')
    addresses = value.get('ipAddresses', [])
    if len(addresses) != 1 or addresses[0].get('type') != 'PRIVATE':
        reject('PRIVATE_ADDRESS_REQUIRED')
    try:
        address = ipaddress.ip_address(addresses[0]['ipAddress'])
    except (KeyError, ValueError):
        reject('PRIVATE_ADDRESS_REQUIRED')
    if address.version != 4 or not any(address in ipaddress.ip_network(net) for net in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')):
        reject('PRIVATE_ADDRESS_REQUIRED')
    ca = value.get('serverCaCert', {})
    cert = ca.get('cert', '')
    # A single exact anchor; never accept a rotated bundle or shared trust store.
    if not isinstance(cert, str) or len(cert) > 32768 or cert.count('-----BEGIN CERTIFICATE-----') != 1 or cert.count('-----END CERTIFICATE-----') != 1:
        reject('SINGLE_CA_REQUIRED')
    import ssl
    try:
        fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert)).hexdigest()
    except (ValueError, TypeError):
        reject('INVALID_CA')
    return str(address), cert, fingerprint

def validate_pair(source, target, name):
    if not CLONE.fullmatch(name):
        reject('NONCE_CLONE_REQUIRED')
    left = anchor(source, SOURCE)
    right = anchor(target, name)
    if left[0] == right[0] or left[2] == right[2]:
        reject('SOURCE_OR_SHARED_CA_REJECTED')
    return right

def expected_body(schema, function, migration):
    text = (ROOT / migration).read_text(encoding='utf-8').replace('\r\n', '\n')
    match = re.search(r'CREATE(?: OR REPLACE)? FUNCTION\s+' + re.escape(schema + '.' + function) + r'\([^)]*\).*?AS\s+\$\$(.*?)\$\$', text, re.S | re.I)
    if not match:
        reject('SOURCE_FENCE_UNAVAILABLE')
    return match.group(1).strip()

def verify_fences(rows):
    if len(rows) != len(FENCES) or sum(len(str(row)) for row in rows) > LIMIT:
        reject('CATALOG_SIZE_REJECTED')
    found = {(row[0], row[1]): row for row in rows}
    if len(found) != len(FENCES):
        reject('DUPLICATE_FENCE_REJECTED')
    for schema, trigger, function, migration in FENCES:
        row = found.get((schema, trigger))
        if row is None or row[2] not in ('O', 'A') or row[3] != function or row[4].replace('\r\n','\n').strip() != expected_body(schema, function, migration) or tuple(row[5:]) != TRIGGER_SHAPES[trigger]:
            reject('FENCE_MISSING_DISABLED_OR_CHANGED')
    return len(FENCES)

def _catalog(host, cert, port=5432):
    import psycopg
    password = os.environ.get('IMS_RESTORE_VERIFY_PASSWORD')
    if not password or len(password) > 1024:
        reject('PRIVATE_CREDENTIAL_REQUIRED')
    with tempfile.TemporaryDirectory(prefix='ims-restore-ca-') as temporary:
        ca = pathlib.Path(temporary) / 'clone-ca.pem'
        ca.write_text(cert, encoding='ascii')
        # Credentials and CA never enter CLI arguments, results or error messages.
        with psycopg.connect(host=host, port=port, dbname=DATABASE, user=VERIFY_ROLE, password=password,
                sslmode='verify-ca', sslrootcert=str(ca), gssencmode='disable',
                ssl_min_protocol_version='TLSv1.2', connect_timeout=10,
                options='-c search_path=pg_catalog -c default_transaction_read_only=on -c statement_timeout=10000 -c lock_timeout=1000', autocommit=True) as connection:
            with connection.cursor() as cursor:
                cursor.execute('BEGIN READ ONLY')
                try:
                    cursor.execute(ROLE_SQL)
                    if cursor.fetchone() != (True,):
                        reject('CATALOG_ONLY_ROLE_REQUIRED')
                    cursor.execute(SQL)
                    rows = cursor.fetchmany(1025)
                    count = verify_fences(rows)
                    cursor.execute(SAFETY_SQL)
                    verify_safety(cursor.fetchone())
                finally:
                    cursor.execute('ROLLBACK')
        return count

def _catalog_child(host, cert, pipe, port=5432):
    try:
        pipe.send(('OK', _catalog(host, cert, port)))
    except Exception:
        pipe.send(('REJECTED', 0))
    finally:
        pipe.close()

def inspect_catalog(host, cert, timeout=20, *, port=5432):
    # OS process deadline includes DNS, TLS, authentication and all catalog I/O.
    context = multiprocessing.get_context('spawn')
    receiver, sender = context.Pipe(duplex=False)
    process = context.Process(target=_catalog_child, args=(host, cert, sender, port))
    process.start()
    sender.close()
    try:
        if not receiver.poll(timeout):
            reject('CATALOG_WHOLE_DEADLINE')
        verdict, count = receiver.recv()
        if verdict != 'OK':
            reject('CATALOG_CONNECTION_OR_FENCE_REJECTED')
        return count
    finally:
        receiver.close()
        process.join(timeout=1)
        if process.is_alive():
            process.terminate()
            process.join(timeout=5)
        process.close()

def run(name, connect=False):
    if not CLONE.fullmatch(name):
        reject('NONCE_CLONE_REQUIRED')
    deadline = time.monotonic() + 150
    source, target = metadata(SOURCE,deadline), metadata(name,deadline)
    host, cert, fingerprint = validate_pair(source, target, name)
    result = {'project': PROJECT, 'target': name, 'physicalCatalogConnectionVerified': False,
              'fenceCount': 0, 'restorationReady': False, 'deliveryResume': False,
              'sourceLineageVerified': False, 'cloneCaSha256': fingerprint,
              'selectedRuntimeAclAndRlsVerified': False}
    if connect:
        result['fenceCount'] = inspect_catalog(host, cert,remaining(deadline))
        if validate_pair(metadata(SOURCE,deadline), metadata(name,deadline), name) != (host, cert, fingerprint):
            reject('TARGET_CHANGED_DURING_VERIFICATION')
        result['physicalCatalogConnectionVerified'] = True
        result['selectedRuntimeAclAndRlsVerified'] = True
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True)
    parser.add_argument('--connect-read-only', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.target, args.connect_read_only), sort_keys=True))
    except Exception:
        # Never expose psycopg/gcloud errors containing connection context.
        print(json.dumps({'status':'VERIFICATION_REJECTED','restorationReady':False,'deliveryResume':False}))
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
