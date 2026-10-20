SET LOCAL app.bypass_rls = 'on';
-- Erasure retains delivery/event identifiers, terminal tombstones and counters, but removes payload PII.
CREATE OR REPLACE FUNCTION reporting.safe_event_json(value text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN RETURN value::jsonb; EXCEPTION WHEN invalid_text_representation THEN RETURN '{}'::jsonb; END $$;
CREATE OR REPLACE FUNCTION reporting.redact_deleted_student_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE body jsonb; sid bigint;
BEGIN
 body:=reporting.safe_event_json(NEW.payload);
 IF NEW.event_type LIKE 'student.%' AND (body->>'id') ~ '^[0-9]{1,18}$' THEN sid:=(body->>'id')::bigint;
 ELSIF (body->>'studentId') ~ '^[0-9]{1,18}$' THEN sid:=(body->>'studentId')::bigint; END IF;
 IF sid IS NOT NULL AND EXISTS(SELECT 1 FROM reporting.student_projection_tombstones WHERE student_id=sid) THEN
  NEW.payload:=jsonb_build_object('id',sid,'studentId',sid,'redacted',true)::text;
  NEW.envelope:='{"redacted":true}'; NEW.last_error:=NULL;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reporting_inbox_erasure BEFORE INSERT OR UPDATE OF payload ON reporting.reporting_event_inbox
 FOR EACH ROW EXECUTE FUNCTION reporting.redact_deleted_student_event();
CREATE OR REPLACE FUNCTION reporting.suppress_deleted_student_fact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.student_id IS NOT NULL THEN
  PERFORM pg_advisory_xact_lock(710000000000000000 + NEW.student_id);
  IF EXISTS(SELECT 1 FROM reporting.student_projection_tombstones WHERE student_id=NEW.student_id) THEN RETURN NULL; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fee_fact_erasure BEFORE INSERT OR UPDATE ON reporting.fact_fee_assignment FOR EACH ROW EXECUTE FUNCTION reporting.suppress_deleted_student_fact();
CREATE TRIGGER payment_fact_erasure BEFORE INSERT OR UPDATE ON reporting.fact_payment FOR EACH ROW EXECUTE FUNCTION reporting.suppress_deleted_student_fact();
CREATE OR REPLACE FUNCTION reporting.erase_student_inbox_payloads() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 UPDATE reporting.reporting_event_inbox SET payload=payload
 WHERE (event_type LIKE 'student.%' AND reporting.safe_event_json(payload)->>'id'=NEW.student_id::text)
    OR reporting.safe_event_json(payload)->>'studentId'=NEW.student_id::text;
 IF to_regclass('notification.notification_inbox_events') IS NOT NULL THEN
  EXECUTE 'UPDATE notification.notification_inbox_events SET payload=jsonb_build_object(''studentId'',$1,''redacted'',true)::text,status=''SUPPRESSED'',last_error=NULL WHERE reporting.safe_event_json(payload)->>''studentId''=$1::text' USING NEW.student_id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER student_inbox_erasure AFTER INSERT OR UPDATE ON reporting.student_projection_tombstones FOR EACH ROW EXECUTE FUNCTION reporting.erase_student_inbox_payloads();
UPDATE reporting.reporting_event_inbox SET payload=payload
 WHERE EXISTS(SELECT 1 FROM reporting.student_projection_tombstones t WHERE
 (event_type LIKE 'student.%' AND reporting.safe_event_json(payload)->>'id'=t.student_id::text)
 OR reporting.safe_event_json(payload)->>'studentId'=t.student_id::text);
