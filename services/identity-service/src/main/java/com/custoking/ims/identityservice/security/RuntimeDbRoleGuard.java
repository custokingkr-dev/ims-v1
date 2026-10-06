package com.custoking.ims.identityservice.security;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Checks effective privileges, not the display name of the database login. */
@Component
public class RuntimeDbRoleGuard implements org.springframework.beans.factory.SmartInitializingSingleton {
    private final JdbcClient jdbc;
    private final Environment environment;
    private final String expectedRole;
    private final java.util.List<String> schemas;
    public RuntimeDbRoleGuard(JdbcClient jdbc, Environment environment) {
        this(jdbc,environment,"app_rt",environment.getProperty("app.runtime-db-schemas", "identity"));
    }
    public RuntimeDbRoleGuard(JdbcClient jdbc, Environment environment, String expectedRole) {
        this(jdbc,environment,expectedRole,environment.getProperty("app.runtime-db-schemas", "identity"));
    }
    @Autowired
    public RuntimeDbRoleGuard(JdbcClient jdbc, Environment environment,
            @Value("${app.runtime-db-role:app_rt}") String expectedRole,
            @Value("${app.runtime-db-schemas:identity}") String schemas) {
        this.schemas=java.util.Arrays.stream(schemas.split(",")).map(String::trim).toList();
        if(this.schemas.isEmpty() || !java.util.Set.of("identity","tenant_school","student","attendance","fee","catalog","workflow","firefighting","reporting","notification","audit","billing").containsAll(this.schemas))
            throw new IllegalArgumentException("Unrecognized runtime database schema");
        this.expectedRole=expectedRole;
        this.jdbc = jdbc;
        this.environment = environment;
    }
    @Override public void afterSingletonsInstantiated() { verifyRuntimeRole(); }
    public void verifyRuntimeRole() {
        // Tests/local migrations deliberately use an owner. Deployed profiles never do.
        if (!environment.matchesProfiles("prod", "dev")
                && environment.getProperty("K_SERVICE") == null) return;
        boolean safe = jdbc.sql("""
            WITH reachable AS (
                SELECT oid, rolname, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb
                FROM pg_roles
                WHERE oid = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                   OR pg_has_role(current_user, oid, 'MEMBER')
            )
            SELECT current_user = :expectedRole
              AND session_user = current_user
              AND NOT EXISTS (SELECT 1 FROM reachable
                    WHERE rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)
              AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datdba IN (SELECT oid FROM reachable))
              AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname NOT IN ('pg_catalog','information_schema')
                      AND n.nspname NOT LIKE 'pg_toast%'
                      AND c.relkind IN ('r','p')
                      AND c.relowner IN (SELECT oid FROM reachable))
              AND NOT EXISTS (SELECT 1 FROM pg_namespace n
                    WHERE n.nspname NOT IN ('pg_catalog','information_schema')
                      AND n.nspname NOT LIKE 'pg_toast%'
                      AND n.nspowner IN (SELECT oid FROM reachable))
              AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE c.relrowsecurity AND n.nspname IN (:schemas)
                      AND (NOT c.relforcerowsecurity OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid=c.oid)))
            """).param("expectedRole",expectedRole).param("schemas",schemas).query(Boolean.class).single();
        if (!safe) throw new IllegalStateException(
                "Unsafe runtime database credentials: require configured runtime role without privileged memberships, ownership or missing RLS policies");
    }
}
