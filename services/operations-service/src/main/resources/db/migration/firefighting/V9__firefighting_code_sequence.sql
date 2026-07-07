-- Fix: firefighting request-code minting used `SELECT MAX(numeric(code))+1 FROM firefighting_requests`
-- which runs under RLS as app_rt (scoped to the caller's own school). The firefighting_requests PK
-- is global (code), so two different schools minted the same FF-<n> -> duplicate-key 500 on
-- submitting an Urgent Procurement request. (Same class of bug as catalog order-id minting;
-- see catalog V5__catalog_order_id_sequence.sql.)
--
-- Replace with a real sequence: sequences ignore RLS, are global and concurrency-safe.

CREATE SEQUENCE IF NOT EXISTS firefighting.seq_firefighting_request_code;

-- Seed above the current global MAX FF-<n>. This migration runs as the RLS-exempt owner
-- (Flyway user), so the MAX sees every school's rows. setval() sets the current value;
-- the next nextval() returns current+1, so new codes never collide with existing ones.
-- Floor of 2 matches the prior nextCode() COALESCE default (first code minted is FF-003).
SELECT setval(
    'firefighting.seq_firefighting_request_code',
    GREATEST(
        2,
        COALESCE(
            (SELECT MAX(NULLIF(regexp_replace(code, '[^0-9]', '', 'g'), '')::bigint)
             FROM firefighting.firefighting_requests
             WHERE code LIKE 'FF-%'),
            2
        )
    )
);

-- Grant runtime access to app_rt where that role exists (prod/dev). No-op in test
-- environments (Testcontainers) where app_rt is not provisioned. Prod also auto-grants
-- via ALTER DEFAULT PRIVILEGES, so this is a belt-and-suspenders explicit grant.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT USAGE, SELECT ON SEQUENCE firefighting.seq_firefighting_request_code TO app_rt;
    END IF;
END $$;
