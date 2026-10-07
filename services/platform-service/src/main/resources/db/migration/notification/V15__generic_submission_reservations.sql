CREATE TABLE notification.generic_submissions (
 event_id VARCHAR(120) PRIMARY KEY,
 school_id BIGINT NOT NULL CHECK(school_id>0),
 request_sha256 CHAR(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTING' CHECK(status IN ('SUBMITTING','ACCEPTED','UNKNOWN')),
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE notification.generic_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.generic_submissions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.generic_submissions
 USING(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on')
 WITH CHECK(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on');
REVOKE ALL ON notification.generic_submissions FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
 REVOKE ALL ON notification.generic_submissions FROM app_rt;
 GRANT SELECT,INSERT ON notification.generic_submissions TO app_rt;
 GRANT UPDATE(status,updated_at) ON notification.generic_submissions TO app_rt;
END IF; END $$;

-- A stale synchronous command cannot reset a committed reservation through a JPA save.
-- Erasure's SUPPRESSED/redacted transition is always permitted and cannot resume delivery.
CREATE FUNCTION notification.guard_generic_reserved_inbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status IN ('SUBMITTING','UNKNOWN') AND NEW.status<>'SUPPRESSED' THEN
  IF NEW.payload IS DISTINCT FROM OLD.payload
     OR (OLD.status='SUBMITTING' AND NEW.status NOT IN ('SUBMITTING','PROCESSED','UNKNOWN'))
     OR (OLD.status='UNKNOWN' AND NEW.status<>'UNKNOWN') THEN
   RAISE EXCEPTION 'Reserved notification cannot be reset';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_generic_reserved_inbox BEFORE UPDATE ON notification.notification_inbox_events
 FOR EACH ROW EXECUTE FUNCTION notification.guard_generic_reserved_inbox();
