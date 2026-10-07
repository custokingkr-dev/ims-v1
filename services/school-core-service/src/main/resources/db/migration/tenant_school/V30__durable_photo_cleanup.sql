-- Machine outbox: no public tenant query surface, matching tenant_school.outbox_events.
-- No student/school FK: erased identities and cleanup evidence must survive parent removal.
CREATE TABLE tenant_school.photo_cleanup_outbox (
 id uuid PRIMARY KEY, erasure_operation_id uuid NOT NULL,
 school_id bigint NOT NULL, student_id bigint NOT NULL,
 bucket text NOT NULL, object_key text NOT NULL,
 object_generation bigint CHECK (object_generation > 0),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','LEASED','DONE','BLOCKED')),
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 lease_token uuid, lease_until timestamptz, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz, last_error varchar(64),
 UNIQUE (erasure_operation_id,bucket,object_key)
);
CREATE INDEX photo_cleanup_pending ON tenant_school.photo_cleanup_outbox(next_attempt_at,created_at) WHERE state IN ('PENDING','LEASED');
REVOKE ALL ON tenant_school.photo_cleanup_outbox FROM PUBLIC;
DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='app_rt') THEN
 -- Owner defaults can already have granted DELETE (or broader table privileges).
 -- Reset the baseline before the dedicated-role callback copies it.
 REVOKE ALL ON tenant_school.photo_cleanup_outbox FROM app_rt;
 GRANT SELECT,INSERT,UPDATE ON tenant_school.photo_cleanup_outbox TO app_rt;
END IF; END $$;
