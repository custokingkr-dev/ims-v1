ALTER TABLE notification.generic_submissions ADD COLUMN correlation_id VARCHAR(51);
ALTER TABLE notification.generic_submissions ADD COLUMN provider_request_id VARCHAR(160);
ALTER TABLE notification.generic_submissions ADD COLUMN reason VARCHAR(80);
ALTER TABLE notification.generic_submissions DROP CONSTRAINT generic_submissions_status_check;
ALTER TABLE notification.generic_submissions ADD CONSTRAINT generic_submissions_status_check
 CHECK(status IN ('SUBMITTING','ACCEPTED','REJECTED','UNKNOWN'));
ALTER TABLE notification.generic_submissions ADD CONSTRAINT generic_receipt_correlation
 CHECK(correlation_id IS NULL OR correlation_id ~ '^ims[0-9a-f]{48}$');
ALTER TABLE notification.generic_submissions ADD CONSTRAINT generic_receipt_acceptance
 CHECK((status<>'ACCEPTED' OR (provider_request_id ~ '^[a-zA-Z0-9._:-]{1,160}$' AND provider_request_id IS NOT NULL AND correlation_id IS NOT NULL))
   AND (status='ACCEPTED' OR provider_request_id IS NULL));
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
 GRANT UPDATE(correlation_id,provider_request_id,reason) ON notification.generic_submissions TO app_rt;
END IF; END $$;

CREATE OR REPLACE FUNCTION notification.guard_generic_reserved_inbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status IN ('SUBMITTING','UNKNOWN','ACCEPTED','REJECTED') AND NEW.status<>'SUPPRESSED' THEN
  IF NEW.payload IS DISTINCT FROM OLD.payload
     OR (OLD.status='SUBMITTING' AND NEW.status NOT IN ('SUBMITTING','ACCEPTED','REJECTED','UNKNOWN'))
     OR (OLD.status IN ('UNKNOWN','ACCEPTED','REJECTED') AND NEW.status<>OLD.status) THEN
   RAISE EXCEPTION 'Reserved notification cannot be reset';
  END IF;
 END IF;
 RETURN NEW;
END $$;

CREATE FUNCTION notification.guard_generic_final_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'SUBMITTING' AND (NEW.status IS DISTINCT FROM OLD.status
     OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
     OR NEW.provider_request_id IS DISTINCT FROM OLD.provider_request_id
     OR NEW.reason IS DISTINCT FROM OLD.reason) THEN
  RAISE EXCEPTION 'Final provider submission evidence is immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_generic_final_receipt BEFORE UPDATE ON notification.generic_submissions
 FOR EACH ROW EXECUTE FUNCTION notification.guard_generic_final_receipt();
