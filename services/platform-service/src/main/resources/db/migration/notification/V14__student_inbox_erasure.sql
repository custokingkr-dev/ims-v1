SET LOCAL app.bypass_rls = 'on';
DO $guard$ BEGIN
-- Platform migrates reporting first; standalone notification RLS fixtures have no reporting domain.
IF to_regclass('reporting.student_projection_tombstones') IS NULL THEN RETURN; END IF;
CREATE OR REPLACE FUNCTION notification.redact_deleted_student_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE body jsonb; sid bigint;
BEGIN
 body:=reporting.safe_event_json(NEW.payload);
 IF (body->>'studentId') ~ '^[0-9]{1,18}$' THEN sid:=(body->>'studentId')::bigint; END IF;
 IF sid IS NOT NULL AND EXISTS(SELECT 1 FROM reporting.student_projection_tombstones WHERE student_id=sid) THEN
  NEW.payload:=jsonb_build_object('studentId',sid,'redacted',true)::text;
  NEW.status:='SUPPRESSED'; NEW.last_error:=NULL;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER notification_inbox_erasure BEFORE INSERT OR UPDATE OF payload ON notification.notification_inbox_events FOR EACH ROW EXECUTE FUNCTION notification.redact_deleted_student_event();
UPDATE notification.notification_inbox_events SET payload=payload WHERE EXISTS(SELECT 1 FROM reporting.student_projection_tombstones t WHERE reporting.safe_event_json(payload)->>'studentId'=t.student_id::text);

END $guard$;
