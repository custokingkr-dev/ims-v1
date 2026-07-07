ALTER TABLE firefighting_requests
    ADD COLUMN IF NOT EXISTS custoking_approved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fulfilled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS rejected_at TIMESTAMPTZ;
