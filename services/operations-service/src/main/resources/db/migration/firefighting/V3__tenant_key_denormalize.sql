-- Group A: firefighting_requests already has a (nullable) school_id set by the app.
ALTER TABLE firefighting.firefighting_requests ALTER COLUMN school_id SET NOT NULL;

-- Group B: ff_quotations derives its tenant from its parent request (same schema).
ALTER TABLE firefighting.ff_quotations ADD COLUMN IF NOT EXISTS school_id BIGINT;
UPDATE firefighting.ff_quotations q
   SET school_id = r.school_id
  FROM firefighting.firefighting_requests r
 WHERE r.code = q.request_id AND q.school_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_ff_quotations_school_request
    ON firefighting.ff_quotations (school_id, request_id);
