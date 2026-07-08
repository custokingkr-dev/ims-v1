package com.custoking.ims.platformservice.security;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Transaction-local RLS bypass for contextless event-projection writers.
 *
 * <p>Reporting fact/dimension tables carry a {@code tenant_isolation} RLS policy (see
 * {@code reporting/V22__enable_rls_facts_dims.sql}) so ordinary reads stay tenant-scoped. But the
 * projector repositories that populate those tables are invoked by outbox/event consumers that run
 * with NO {@link TenantContext} — there is no authenticated school to satisfy the policy's
 * {@code WITH CHECK} clause.
 *
 * <p>Call {@link #allow(JdbcClient)} as the FIRST statement inside each {@code @Transactional}
 * projector upsert method. It sets {@code app.bypass_rls} to {@code on} using {@code set_config}'s
 * transaction-local form (third argument {@code true}), so the bypass is automatically cleared when
 * the transaction commits or rolls back and never leaks onto a pooled connection's next borrower.
 */
public final class ProjectorRls {

    private ProjectorRls() {
    }

    public static void allow(JdbcClient jdbc) {
        jdbc.sql("DO $$ BEGIN PERFORM set_config('app.bypass_rls', 'on', true); END $$;").update();
    }
}
