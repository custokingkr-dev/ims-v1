-- Fix: order-id minting used `SELECT MAX(id)+1 FROM catalog_orders` which runs under RLS
-- as app_rt (scoped to the caller's own school). The catalog_orders PK is global, so two
-- different schools minted the same CK-<n> -> duplicate-key 500 on place order.
--
-- Replace with a real sequence: sequences ignore RLS, are global and concurrency-safe.

CREATE SEQUENCE IF NOT EXISTS catalog.seq_catalog_order_id;

-- Seed above the current global MAX CK-<n>. This migration runs as the RLS-exempt owner
-- (Flyway user), so the MAX sees every school's rows. setval() sets the current value;
-- the next nextval() returns current+1, so new ids never collide with existing ones.
SELECT setval(
    'catalog.seq_catalog_order_id',
    GREATEST(
        1000,
        COALESCE(
            (SELECT MAX(NULLIF(regexp_replace(id, '[^0-9]', '', 'g'), '')::bigint)
             FROM catalog.catalog_orders
             WHERE id LIKE 'CK-%'),
            1000
        )
    )
);

-- Grant runtime access to app_rt where that role exists (prod/dev). No-op in test
-- environments (Testcontainers) where app_rt is not provisioned. Prod also auto-grants
-- via ALTER DEFAULT PRIVILEGES, so this is a belt-and-suspenders explicit grant.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rt') THEN
        GRANT USAGE, SELECT ON SEQUENCE catalog.seq_catalog_order_id TO app_rt;
    END IF;
END $$;
