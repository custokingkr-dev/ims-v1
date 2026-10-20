ALTER TABLE audit.audit_events ADD COLUMN provenance varchar(32) NOT NULL DEFAULT 'LEGACY_UNVERIFIED';
ALTER TABLE audit.audit_events ADD CONSTRAINT audit_event_provenance CHECK (provenance IN ('LEGACY_UNVERIFIED','CLIENT_TELEMETRY','SERVER_BUSINESS'));
CREATE TABLE audit.ingest_quotas (
    quota_key varchar(128) PRIMARY KEY,
    used integer NOT NULL CHECK (used > 0),
    expires_at timestamptz NOT NULL
);
DO $$ BEGIN IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
    GRANT SELECT,INSERT,UPDATE,DELETE ON audit.ingest_quotas TO app_rt;
END IF; END $$;
