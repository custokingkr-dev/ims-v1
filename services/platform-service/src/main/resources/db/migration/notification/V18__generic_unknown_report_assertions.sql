-- Unverified operator assertions only. No receipt, status transition or retry authority.
ALTER TABLE notification.generic_submissions ADD CONSTRAINT generic_unknown_binding_unique UNIQUE(event_id,school_id,request_sha256,correlation_id);
CREATE TABLE notification.generic_unknown_report_assertions (
 assertion_sha256 CHAR(64) PRIMARY KEY CHECK(assertion_sha256 ~ '^[0-9a-f]{64}$'),
 event_id VARCHAR(120) NOT NULL, school_id BIGINT NOT NULL CHECK(school_id>0),
 request_sha256 CHAR(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 correlation_id VARCHAR(51) NOT NULL CHECK(correlation_id ~ '^ims[0-9a-f]{48}$'),
 claimed_provider_request_id VARCHAR(160) NOT NULL CHECK(claimed_provider_request_id ~ '^[a-zA-Z0-9._:-]{1,160}$'),
 evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 asserted_at TIMESTAMPTZ NOT NULL,
 reporter_service_account VARCHAR(254) NOT NULL CHECK(reporter_service_account ~ '^[a-z0-9._-]+@[a-z0-9.-]+[.]iam[.]gserviceaccount[.]com$'),
 assertion_kind TEXT NOT NULL DEFAULT 'UNVERIFIED_OPERATOR_ASSERTION' CHECK(assertion_kind='UNVERIFIED_OPERATOR_ASSERTION'),
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(event_id,school_id,request_sha256,correlation_id) REFERENCES notification.generic_submissions(event_id,school_id,request_sha256,correlation_id)
);
CREATE INDEX generic_unknown_assertions_event ON notification.generic_unknown_report_assertions(event_id,school_id);
ALTER TABLE notification.generic_unknown_report_assertions ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.generic_unknown_report_assertions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.generic_unknown_report_assertions
 USING(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on')
 WITH CHECK(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on');
REVOKE ALL ON notification.generic_unknown_report_assertions FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
 REVOKE ALL ON notification.generic_unknown_report_assertions FROM app_rt;
 GRANT SELECT ON notification.generic_unknown_report_assertions TO app_rt;
 GRANT INSERT(assertion_sha256,event_id,school_id,request_sha256,correlation_id,claimed_provider_request_id,evidence_sha256,asserted_at,reporter_service_account) ON notification.generic_unknown_report_assertions TO app_rt;
END IF; END $$;
CREATE FUNCTION notification.guard_generic_unknown_report_assertion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Unverified evidence is append-only'; END IF;
 PERFORM 1 FROM notification.generic_submissions s JOIN notification.notification_inbox_events i ON i.event_id=s.event_id
 WHERE s.event_id=NEW.event_id AND s.school_id=NEW.school_id AND s.request_sha256=NEW.request_sha256 AND s.correlation_id=NEW.correlation_id
 AND s.status='UNKNOWN' AND s.provider_request_id IS NULL AND i.status='UNKNOWN'
 AND NEW.asserted_at>=s.submitted_at-interval '5 minutes' AND NEW.asserted_at<=now()+interval '5 minutes' FOR UPDATE OF i;
 IF NOT FOUND THEN RAISE EXCEPTION 'Unverified evidence requires an exact unresolved reservation'; END IF;
 IF NOT EXISTS(SELECT 1 FROM notification.generic_unknown_report_assertions WHERE assertion_sha256=NEW.assertion_sha256)
 AND (SELECT count(*) FROM notification.generic_unknown_report_assertions WHERE event_id=NEW.event_id)>=16 THEN
 RAISE EXCEPTION 'Unverified evidence limit reached'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_generic_unknown_report_assertion BEFORE INSERT OR UPDATE OR DELETE ON notification.generic_unknown_report_assertions FOR EACH ROW EXECUTE FUNCTION notification.guard_generic_unknown_report_assertion();
