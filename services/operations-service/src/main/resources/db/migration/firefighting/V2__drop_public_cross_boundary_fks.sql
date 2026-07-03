ALTER TABLE firefighting.firefighting_requests
    DROP CONSTRAINT IF EXISTS fk_ff_request_school,
    DROP CONSTRAINT IF EXISTS fk_ff_vendor_paid_by;
