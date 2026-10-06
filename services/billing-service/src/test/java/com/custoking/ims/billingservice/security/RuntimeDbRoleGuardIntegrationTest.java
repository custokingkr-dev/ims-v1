package com.custoking.ims.billingservice.security;

import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDbRoleGuardIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Real PostgreSQL requires Docker");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start(); owner=client("owner", "owner");
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'runtime'; CREATE ROLE dangerous BYPASSRLS; CREATE SCHEMA billing; CREATE TABLE billing.items(id int, school_id bigint); ALTER TABLE billing.items ENABLE ROW LEVEL SECURITY; ALTER TABLE billing.items FORCE ROW LEVEL SECURITY; CREATE POLICY isolation ON billing.items USING (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint); GRANT USAGE ON SCHEMA billing TO app_rt; GRANT SELECT ON billing.items TO app_rt;").update();
    }
    static JdbcClient client(String user,String password) {
        return JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),user,password));
    }
    RuntimeDbRoleGuard guard(JdbcClient jdbc) {
        MockEnvironment env=new MockEnvironment(); env.setActiveProfiles("prod"); env.setProperty("app.runtime-db-schemas","billing");
        return new RuntimeDbRoleGuard(jdbc,env);
    }
    @AfterAll static void stop() { if(pg != null) pg.stop(); }
    @Test void runtimeIsSafeButOwnerRejected() {
        assertDoesNotThrow(() -> guard(client("app_rt","runtime")).verifyRuntimeRole());
        assertThrows(IllegalStateException.class,() -> guard(owner).verifyRuntimeRole());
    }
    @Test void inheritedBypassAndOwnershipAreRejected() {
        owner.sql("GRANT dangerous TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("REVOKE dangerous FROM app_rt").update(); }
        owner.sql("ALTER TABLE billing.items OWNER TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE billing.items OWNER TO owner").update(); }
    }
    @Test void alteredAppRtPrivilegesAreRejected() {
        owner.sql("ALTER ROLE app_rt BYPASSRLS").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER ROLE app_rt NOBYPASSRLS").update(); }
    }
    @Test void tenantStateIsClearedAfterSuccessErrorAndConnectionReuse() throws Exception {
        owner.sql("GRANT SELECT ON billing.items TO app_rt; TRUNCATE billing.items; INSERT INTO billing.items VALUES (1,10),(2,20)").update();
        try(var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername("app_rt"); pool.setPassword("runtime"); pool.setMaximumPoolSize(1);
            var scoped=new TenantAwareDataSource(pool);
            TenantContext.set(new TenantContext(1L,"user@test","ADMIN",10L,null));
            try(var connection=scoped.getConnection(); var st=connection.createStatement()) {
                try(var rs=st.executeQuery("SELECT id FROM billing.items")) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); assertFalse(rs.next()); }
            }
            try(var connection=pool.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT current_setting('app.current_school_id'),current_setting('app.bypass_rls')")) {
                rs.next(); assertEquals("",rs.getString(1)); assertEquals("off",rs.getString(2));
            }
            TenantContext.set(new TenantContext(2L,"admin@test","SUPERADMIN",null,null));
            try(var connection=scoped.getConnection(); var st=connection.createStatement()) {
                connection.setAutoCommit(false);
                assertThrows(java.sql.SQLException.class,()->st.execute("SELECT 1/0"));
            }
            TenantContext.set(new TenantContext(3L,"other@test","ADMIN",20L,null));
            try(var connection=scoped.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT id FROM billing.items")) {
                assertTrue(rs.next()); assertEquals(2,rs.getInt(1)); assertFalse(rs.next());
            }
            TenantContext.clear();
            try(var connection=scoped.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT count(*) FROM billing.items")) { rs.next(); assertEquals(0,rs.getInt(1)); }
        } finally { TenantContext.clear(); }
    }
    @Test void disablingForceRlsIsRejected() {
        owner.sql("ALTER TABLE billing.items NO FORCE ROW LEVEL SECURITY").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE billing.items FORCE ROW LEVEL SECURITY").update(); }
    }
    @Test void databaseOwnershipAndPublicSchemaOwnershipAreRejected() {
        String database=pg.getDatabaseName();
        owner.sql("ALTER DATABASE "+database+" OWNER TO app_rt").update();
        try { assertThrows(IllegalStateException.class,()->guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER DATABASE "+database+" OWNER TO owner").update(); }
        owner.sql("ALTER SCHEMA public OWNER TO app_rt").update();
        try { assertThrows(IllegalStateException.class,()->guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER SCHEMA public OWNER TO pg_database_owner").update(); }
    }

}
