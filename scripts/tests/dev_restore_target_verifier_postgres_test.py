"""Local TLS/full migration-SQL rehearsal, not a Cloud SQL restore proof."""
import os
import pathlib
import subprocess
import time
import unittest
import uuid
import socket
import threading
import importlib
import multiprocessing
from unittest.mock import patch
from scripts.tests.dev_restore_target_verifier_test import v,record,TARGET

class RestorePostgresTests(unittest.TestCase):
    @classmethod
    def command(cls,*args,input=None):
        result=subprocess.run(list(args),input=input.encode('utf-8') if input is not None else None,capture_output=True,timeout=60)
        if result.returncode:
            raise RuntimeError('CONTROLLED_LOCAL_COMMAND_FAILED')
        return result.stdout.decode('utf-8')

    @classmethod
    def sql(cls,text,database='custoking_dev'):
        return cls.command('docker','exec','-i',cls.name,'psql','-U','postgres','-d',database,'-XAt','-v','ON_ERROR_STOP=1',input=text)

    @classmethod
    def setUpClass(cls):
        cls.name='ims-restore-verifier-'+uuid.uuid4().hex[:12]
        cls.command('docker','run','--detach','--rm','--name',cls.name,'-p','127.0.0.1::5432',
                    '-e','POSTGRES_PASSWORD=controlled-local-only','postgres:16')
        try:
            for _ in range(100):
                probe=subprocess.run(['docker','exec',cls.name,'pg_isready','-h','127.0.0.1','-U','postgres'],capture_output=True,timeout=5)
                if probe.returncode==0:break
                time.sleep(.2)
            else:raise RuntimeError('LOCAL_POSTGRES_NOT_READY')
            cls.sql("CREATE ROLE appuser LOGIN PASSWORD 'controlled-local-only' NOSUPERUSER NOBYPASSRLS;CREATE ROLE app_rt NOLOGIN;CREATE DATABASE custoking_dev OWNER appuser;",'postgres')
            # Twelve complete checked-in migration SQL chains, each transaction matching
            # Flyway transactional execution/search path. This does NOT run Flyway callbacks.
            chains=[('identity-service',['identity']),('billing-service',['billing']),
                    ('school-core-service',['tenant_school','student','attendance','fee','catalog']),
                    ('operations-service',['workflow','firefighting']),('platform-service',['reporting','notification','audit'])]
            cls.migrations=0
            for service,schemas in chains:
                for schema in schemas:
                    folder=v.ROOT/'services'/service/'src/main/resources/db/migration'
                    if len(schemas)>1:folder/=schema
                    cls.sql('CREATE SCHEMA '+schema+' AUTHORIZATION appuser;')
                    paths=sorted(folder.glob('V*__*.sql'),key=lambda p:tuple(int(part) for part in p.name.split('__')[0][1:].replace('_','.').split('.')))
                    if not paths:raise RuntimeError('MIGRATION_CHAIN_ABSENT')
                    for path in paths:
                        cls.sql('BEGIN;SET LOCAL ROLE appuser;SET LOCAL search_path TO '+schema+',public;SET LOCAL app.bypass_rls=\'on\';\n'+path.read_text(encoding='utf-8')+'\nCOMMIT;')
                        cls.migrations+=1
            cls.sql('CREATE ROLE ims_platform_rt NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;')
            cls.sql('CREATE ROLE ims_school_core_rt NOLOGIN NOSUPERUSER NOBYPASSRLS NOINHERIT;'
                    'GRANT USAGE ON SCHEMA student,tenant_school TO ims_school_core_rt;'
                    'GRANT SELECT,INSERT ON student.erasure_journal_receipts TO ims_school_core_rt;'
                    'GRANT SELECT,INSERT,UPDATE ON tenant_school.photo_cleanup_outbox TO ims_school_core_rt;')
            cls.sql("CREATE ROLE ims_restore_verify LOGIN PASSWORD 'controlled-local-only' NOSUPERUSER NOBYPASSRLS NOINHERIT;"
                    "REVOKE TEMP ON DATABASE custoking_dev FROM PUBLIC;GRANT CONNECT ON DATABASE custoking_dev TO ims_restore_verify;")
            # Locally generated unique CA and server certificate; never reuse real keys.
            script='''set -eu
mkdir /tmp/restore-tls
cd /tmp/restore-tls
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days 1 -subj /CN=controlled-clone-ca >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj /CN=controlled-clone >/dev/null 2>&1
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 1 >/dev/null 2>&1
openssl req -x509 -newkey rsa:2048 -nodes -keyout wrong.key -out wrong.pem -days 1 -subj /CN=controlled-other-ca >/dev/null 2>&1
chmod 600 server.key
chown postgres:postgres server.key server.pem ca.pem
'''
            cls.command('docker','exec','-i',cls.name,'sh',input=script)
            cls.sql("ALTER SYSTEM SET ssl='on';ALTER SYSTEM SET ssl_cert_file='/tmp/restore-tls/server.pem';ALTER SYSTEM SET ssl_key_file='/tmp/restore-tls/server.key';SELECT pg_reload_conf();")
            cls.ca=cls.command('docker','exec',cls.name,'cat','/tmp/restore-tls/ca.pem')
            cls.wrong=cls.command('docker','exec',cls.name,'cat','/tmp/restore-tls/wrong.pem')
            cls.port=int(cls.command('docker','port',cls.name,'5432/tcp').strip().rsplit(':',1)[1])
            time.sleep(.3)
        except BaseException:
            cls.command('docker','stop',cls.name)
            raise

    @classmethod
    def tearDownClass(cls):
        cls.command('docker','stop',cls.name)

    def catalog(self,cert=None):
        with patch.dict(os.environ,{'IMS_RESTORE_VERIFY_PASSWORD':'controlled-local-only'}):
            return v._catalog('127.0.0.1',cert or self.ca,self.port)

    def test_all_four_delivery_fences_disable_rejected_by_actual_catalog(self):
        for trigger in ('guard_generic_reserved_inbox','guard_generic_delivery_result',
                        'guard_generic_delivery_report','suppress_generic_delivery_result','guard_generic_unknown_report_assertion'):
            table=v.TRIGGER_SHAPES[trigger][0]
            try:
                self.sql('ALTER TABLE notification.'+table+' DISABLE TRIGGER '+trigger+';')
                with self.assertRaises(ValueError):self.catalog()
            finally:self.sql('ALTER TABLE notification.'+table+' ENABLE TRIGGER '+trigger+';')
            self.assertEqual(12,self.catalog())

    def test_changed_shared_json_helper_rejected_then_restored(self):
        path='services/platform-service/src/main/resources/db/migration/reporting/V34__student_inbox_erasure.sql'
        try:
            self.sql("CREATE OR REPLACE FUNCTION reporting.safe_event_json(value text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$BEGIN RETURN '{}'::jsonb; END$$;")
            with self.assertRaises(ValueError):self.catalog()
        finally:
            self.sql('CREATE OR REPLACE FUNCTION reporting.safe_event_json(value text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$'+v.expected_body('reporting','safe_event_json',path)+'$$;')
        self.assertEqual(12,self.catalog())

    def test_unknown_assertion_force_and_runtime_dml_boundaries(self):
        table='notification.generic_unknown_report_assertions'
        for role in ('app_rt','ims_platform_rt'):
            for action in ('force','delete','truncate','assertion_kind','received_at','column_update'):
                try:
                    if action=='force':self.sql('ALTER TABLE '+table+' NO FORCE ROW LEVEL SECURITY;')
                    elif action in ('assertion_kind','received_at'):self.sql('GRANT INSERT('+action+') ON '+table+' TO '+role+';')
                    elif action=='column_update':self.sql('GRANT UPDATE(evidence_sha256) ON '+table+' TO '+role+';')
                    else:self.sql('GRANT '+action.upper()+' ON '+table+' TO '+role+';')
                    with self.assertRaises(ValueError):self.catalog()
                finally:
                    self.sql('ALTER TABLE '+table+' FORCE ROW LEVEL SECURITY;')
                    self.sql('REVOKE DELETE,TRUNCATE ON '+table+' FROM '+role+';REVOKE INSERT(assertion_kind,received_at),UPDATE(evidence_sha256) ON '+table+' FROM '+role+';')
                self.assertEqual(12,self.catalog())

    def test_all_twelve_schema_sql_chains_and_correct_tls_ca(self):
        self.assertGreater(self.migrations,200)
        self.assertEqual(12,self.catalog())

    def test_wrong_unique_ca_connection_rejected(self):
        import psycopg
        with self.assertRaises(psycopg.OperationalError) as caught:self.catalog(self.wrong)
        self.assertIn('certificate verify failed',str(caught.exception).lower())

    def test_inherited_gss_requirement_cannot_bypass_exact_tls_anchor(self):
        import psycopg
        with patch.dict(os.environ,{'PGGSSENCMODE':'require'}):
            self.assertEqual(12,self.catalog())
            with self.assertRaises(psycopg.OperationalError) as caught:self.catalog(self.wrong)
            self.assertIn('certificate verify failed',str(caught.exception).lower())

    def test_disabled_real_trigger_rejected_then_restored(self):
        self.sql('ALTER TABLE reporting.reporting_event_inbox DISABLE TRIGGER reporting_inbox_erasure;')
        try:
            with self.assertRaises(ValueError):self.catalog()
        finally:
            self.sql('ALTER TABLE reporting.reporting_event_inbox ENABLE TRIGGER reporting_inbox_erasure;')

    def test_changed_real_function_body_rejected_then_restored(self):
        self.sql('CREATE OR REPLACE FUNCTION student.require_immutable_erasure_incarnation() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END$$;')
        try:
            with self.assertRaises(ValueError):self.catalog()
        finally:
            body=v.expected_body('student','require_immutable_erasure_incarnation',v.FENCES[0][3])
            self.sql('CREATE OR REPLACE FUNCTION student.require_immutable_erasure_incarnation() RETURNS trigger LANGUAGE plpgsql AS $$'+body+'$$;')

    def test_real_cert_metadata_binding_rejects_same_source_ca(self):
        source=record(v.SOURCE,'10.1.1.1',self.ca)
        target=record(TARGET,'10.1.1.2',self.ca)
        with self.assertRaises(ValueError):v.validate_pair(source,target,TARGET)
        target['serverCaCert']['cert']=self.wrong
        self.assertEqual('10.1.1.2',v.validate_pair(source,target,TARGET)[0])

    def test_real_force_rls_tamper_rejected(self):
        self.sql('ALTER TABLE student.erasure_journal_receipts NO FORCE ROW LEVEL SECURITY;')
        try:
            with self.assertRaises(ValueError):self.catalog()
        finally:self.sql('ALTER TABLE student.erasure_journal_receipts FORCE ROW LEVEL SECURITY;')

    def test_real_terminal_delete_and_incarnation_insert_grants_rejected(self):
        for grant,revoke in (
            ('GRANT DELETE ON student.erasure_journal_receipts','REVOKE DELETE ON student.erasure_journal_receipts'),
            ('GRANT DELETE ON tenant_school.photo_cleanup_outbox','REVOKE DELETE ON tenant_school.photo_cleanup_outbox'),
            ('GRANT INSERT(erasure_incarnation) ON student.students','REVOKE INSERT(erasure_incarnation) ON student.students')):
            self.sql(grant+' TO ims_school_core_rt;')
            try:
                with self.assertRaises(ValueError):self.catalog()
            finally:self.sql(revoke+' FROM ims_school_core_rt;')

    def test_verifier_business_read_or_owner_membership_rejected(self):
        for grant,revoke in (('GRANT SELECT ON student.students TO ims_restore_verify;','REVOKE SELECT ON student.students FROM ims_restore_verify;'),
                             ('GRANT appuser TO ims_restore_verify;','REVOKE appuser FROM ims_restore_verify;')):
            self.sql(grant)
            try:
                with self.assertRaises(ValueError):self.catalog()
            finally:self.sql(revoke)

    def test_restored_default_search_path_cannot_shadow_catalog(self):
        self.sql("CREATE SCHEMA restore_shadow AUTHORIZATION appuser;"
                 "CREATE TABLE restore_shadow.pg_roles(dummy text);"
                 "GRANT USAGE ON SCHEMA restore_shadow TO ims_restore_verify;"
                 "GRANT SELECT ON restore_shadow.pg_roles TO ims_restore_verify;"
                 "ALTER ROLE ims_restore_verify SET search_path=restore_shadow,public;")
        try:self.assertEqual(12,self.catalog())
        finally:self.sql('ALTER ROLE ims_restore_verify RESET search_path;DROP SCHEMA restore_shadow CASCADE;')

    def test_runtime_privileged_role_attributes_rejected(self):
        for enable,disable in (('CREATEDB','NOCREATEDB'),('CREATEROLE','NOCREATEROLE'),
                               ('REPLICATION','NOREPLICATION'),('BYPASSRLS','NOBYPASSRLS'),('SUPERUSER','NOSUPERUSER')):
            self.sql('ALTER ROLE ims_school_core_rt '+enable+';')
            try:
                with self.assertRaises(ValueError):self.catalog()
            finally:self.sql('ALTER ROLE ims_school_core_rt '+disable+';')

    def test_runtime_function_ownership_rejected(self):
        self.sql('CREATE FUNCTION public.restore_verifier_fixture() RETURNS int LANGUAGE sql AS $$SELECT 1$$;'
                 'ALTER FUNCTION public.restore_verifier_fixture() OWNER TO ims_school_core_rt;')
        try:
            with self.assertRaises(ValueError):self.catalog()
        finally:self.sql('DROP FUNCTION public.restore_verifier_fixture();')

    def test_real_spawn_tls_blackhole_deadline_closes_process_and_pipes(self):
        # Canonical import is essential: spawn must pickle/import the actual source
        # child function, rather than fail on the unit fixture's temporary alias.
        canonical=importlib.import_module('scripts.security.verify-dev-restore-target')
        listener=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        listener.bind(('127.0.0.1',0))
        listener.listen(1)
        listener.settimeout(3)
        stop=threading.Event();observed=[]
        def blackhole():
            try:
                with listener.accept()[0] as connection:
                    connection.settimeout(3)
                    request=b''
                    while len(request)<8:
                        chunk=connection.recv(8-len(request))
                        if not chunk:return
                        request+=chunk
                    observed.append(request)
                    connection.sendall(b'S')
                    hello=b''
                    while len(hello)<5:
                        chunk=connection.recv(5-len(hello))
                        if not chunk:return
                        hello+=chunk
                    observed.append(hello)
                    stop.wait(5)
            except OSError:
                return
        server=threading.Thread(target=blackhole,name='controlled-restore-tls-blackhole')
        server.start()
        existing={child.pid for child in multiprocessing.active_children()}
        pipes=[]
        context=canonical.multiprocessing.get_context('spawn')
        original_pipe=context.Pipe
        def capture_pipe(*args,**kwargs):
            pair=original_pipe(*args,**kwargs);pipes.extend(pair);return pair
        started=time.monotonic()
        try:
            with patch.dict(os.environ,{'IMS_RESTORE_VERIFY_PASSWORD':'controlled-local-only'}), \
                    patch.object(context,'Pipe',side_effect=capture_pipe), \
                    patch.object(canonical.multiprocessing,'get_context',return_value=context):
                with self.assertRaisesRegex(ValueError,'^CATALOG_WHOLE_DEADLINE$'):
                    canonical.inspect_catalog('127.0.0.1',self.ca,timeout=.8,port=listener.getsockname()[1])
            self.assertLess(time.monotonic()-started,4)
            # Wire observations prove real child execution reached TLS negotiation.
            self.assertEqual(bytes.fromhex('0000000804d2162f'),observed[0])
            self.assertEqual(b'\x16\x03',observed[1][:2])
            self.assertEqual(existing,{child.pid for child in multiprocessing.active_children()})
            self.assertEqual(2,len(pipes))
            self.assertTrue(all(pipe.closed for pipe in pipes))
        finally:
            stop.set();listener.close();server.join(timeout=4)
        self.assertFalse(server.is_alive())

if __name__=='__main__':unittest.main()
