-- Trusted normalized reports cannot alter submission uncertainty or create a send attempt.
ALTER TABLE notification.generic_submissions ADD CONSTRAINT generic_receipt_binding_unique
 UNIQUE(event_id,school_id,request_sha256,correlation_id,provider_request_id);

CREATE TABLE notification.generic_delivery_results (
 event_id VARCHAR(120) PRIMARY KEY,
 school_id BIGINT NOT NULL CHECK(school_id>0),
 request_sha256 CHAR(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 correlation_id VARCHAR(51) NOT NULL CHECK(correlation_id ~ '^ims[0-9a-f]{48}$'),
 provider_request_id VARCHAR(160) NOT NULL CHECK(provider_request_id ~ '^[a-zA-Z0-9._:-]{1,160}$'),
 delivery_status VARCHAR(30) NOT NULL DEFAULT 'ACCEPTED'
   CHECK(delivery_status IN ('ACCEPTED','DELIVERED','DELIVERY_FAILED','REPORT_CONFLICT','SUPPRESSED')),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(event_id,school_id),
 FOREIGN KEY(event_id,school_id,request_sha256,correlation_id,provider_request_id)
   REFERENCES notification.generic_submissions(event_id,school_id,request_sha256,correlation_id,provider_request_id)
);
CREATE TABLE notification.generic_delivery_reports (
 report_sha256 CHAR(64) PRIMARY KEY CHECK(report_sha256 ~ '^[0-9a-f]{64}$'),
 event_id VARCHAR(120) NOT NULL,
 school_id BIGINT NOT NULL CHECK(school_id>0),
 evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 status VARCHAR(30) NOT NULL CHECK(status IN ('ACCEPTED','DELIVERED','DELIVERY_FAILED')),
 provider_at TIMESTAMPTZ NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 FOREIGN KEY(event_id,school_id) REFERENCES notification.generic_delivery_results(event_id,school_id)
);
CREATE INDEX generic_reports_evidence ON notification.generic_delivery_reports(event_id,school_id,evidence_sha256);
ALTER TABLE notification.generic_delivery_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.generic_delivery_results FORCE ROW LEVEL SECURITY;
ALTER TABLE notification.generic_delivery_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification.generic_delivery_reports FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification.generic_delivery_results
 USING(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on')
 WITH CHECK(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on');
CREATE POLICY tenant_isolation ON notification.generic_delivery_reports
 USING(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on')
 WITH CHECK(school_id=nullif(current_setting('app.current_school_id',true),'')::bigint OR current_setting('app.bypass_rls',true)='on');
REVOKE ALL ON notification.generic_delivery_results,notification.generic_delivery_reports FROM PUBLIC;
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
 REVOKE ALL ON notification.generic_delivery_results,notification.generic_delivery_reports FROM app_rt;
 GRANT SELECT ON notification.generic_delivery_results,notification.generic_delivery_reports TO app_rt;
 GRANT INSERT(event_id,school_id,request_sha256,correlation_id,provider_request_id,delivery_status)
   ON notification.generic_delivery_results TO app_rt;
 GRANT INSERT(report_sha256,event_id,school_id,evidence_sha256,status,provider_at)
   ON notification.generic_delivery_reports TO app_rt;
 GRANT UPDATE(delivery_status,updated_at) ON notification.generic_delivery_results TO app_rt;
END IF; END $$;

CREATE FUNCTION notification.guard_generic_delivery_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.delivery_status<>'ACCEPTED' THEN RAISE EXCEPTION 'Delivery result requires initial acceptance'; END IF;
  PERFORM 1 FROM notification.generic_submissions s
      JOIN notification.notification_inbox_events i ON i.event_id=s.event_id
      WHERE s.event_id=NEW.event_id AND s.school_id=NEW.school_id AND s.status='ACCEPTED' AND i.status='ACCEPTED'
        AND s.request_sha256=NEW.request_sha256 AND s.correlation_id=NEW.correlation_id AND s.provider_request_id=NEW.provider_request_id
      FOR UPDATE OF i;
  IF NOT FOUND THEN RAISE EXCEPTION 'Delivery result requires an exact accepted reservation'; END IF;
  RETURN NEW;
 END IF;
 IF NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.school_id IS DISTINCT FROM OLD.school_id
    OR NEW.request_sha256 IS DISTINCT FROM OLD.request_sha256 OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
    OR NEW.provider_request_id IS DISTINCT FROM OLD.provider_request_id THEN
  RAISE EXCEPTION 'Delivery result binding is immutable';
 END IF;
 IF (OLD.delivery_status='SUPPRESSED' AND NEW.delivery_status<>'SUPPRESSED')
    OR (OLD.delivery_status='REPORT_CONFLICT' AND NEW.delivery_status NOT IN ('REPORT_CONFLICT','SUPPRESSED'))
    OR (OLD.delivery_status IN ('DELIVERED','DELIVERY_FAILED') AND NEW.delivery_status NOT IN (OLD.delivery_status,'REPORT_CONFLICT','SUPPRESSED')) THEN
  RAISE EXCEPTION 'Delivery result cannot be downgraded';
 END IF;
 IF EXISTS(SELECT 1 FROM notification.notification_inbox_events WHERE event_id=NEW.event_id AND status='SUPPRESSED') THEN
  NEW.delivery_status:='SUPPRESSED';
 END IF;
 IF NEW.delivery_status IN ('DELIVERED','DELIVERY_FAILED') AND NOT EXISTS(
     SELECT 1 FROM notification.generic_delivery_reports WHERE event_id=NEW.event_id AND school_id=NEW.school_id AND status=NEW.delivery_status)
 THEN RAISE EXCEPTION 'Delivery result requires matching appended evidence'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_generic_delivery_result BEFORE INSERT OR UPDATE ON notification.generic_delivery_results
 FOR EACH ROW EXECUTE FUNCTION notification.guard_generic_delivery_result();

CREATE FUNCTION notification.guard_generic_delivery_report() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Delivery report evidence is immutable'; END IF;
 PERFORM 1 FROM notification.generic_delivery_results d
   JOIN notification.notification_inbox_events i ON i.event_id=d.event_id
   WHERE d.event_id=NEW.event_id AND d.school_id=NEW.school_id AND d.delivery_status<>'SUPPRESSED' AND i.status='ACCEPTED'
   FOR UPDATE OF i;
 IF NOT FOUND THEN RAISE EXCEPTION 'Delivery report requires an active accepted reservation'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_generic_delivery_report BEFORE INSERT OR UPDATE OR DELETE ON notification.generic_delivery_reports
 FOR EACH ROW EXECUTE FUNCTION notification.guard_generic_delivery_report();

-- Invoker privileges and existing tenant scope; no SECURITY DEFINER or cross-tenant lookup.
CREATE FUNCTION notification.suppress_generic_delivery_result() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='SUPPRESSED' THEN
  UPDATE notification.generic_delivery_results SET delivery_status='SUPPRESSED',updated_at=now()
    WHERE event_id=NEW.event_id AND delivery_status<>'SUPPRESSED';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER suppress_generic_delivery_result AFTER UPDATE ON notification.notification_inbox_events
 FOR EACH ROW EXECUTE FUNCTION notification.suppress_generic_delivery_result();
