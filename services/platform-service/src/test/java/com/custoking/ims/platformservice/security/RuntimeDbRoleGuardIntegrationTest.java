package com.custoking.ims.platformservice.security;

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
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'runtime'; CREATE ROLE dangerous BYPASSRLS; CREATE SCHEMA reporting; CREATE TABLE reporting.items(id int, school_id bigint); ALTER TABLE reporting.items ENABLE ROW LEVEL SECURITY; ALTER TABLE reporting.items FORCE ROW LEVEL SECURITY; CREATE POLICY isolation ON reporting.items USING (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint); GRANT USAGE ON SCHEMA reporting TO app_rt; GRANT SELECT ON reporting.items TO app_rt;").update();
    }
    static JdbcClient client(String user,String password) {
        return JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),user,password));
    }
    RuntimeDbRoleGuard guard(JdbcClient jdbc) {
        MockEnvironment env=new MockEnvironment(); env.setActiveProfiles("prod"); env.setProperty("app.runtime-db-schemas","reporting,notification,audit");
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
        owner.sql("ALTER TABLE reporting.items OWNER TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE reporting.items OWNER TO owner").update(); }
    }
    @Test void alteredAppRtPrivilegesAreRejected() {
        owner.sql("ALTER ROLE app_rt BYPASSRLS").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER ROLE app_rt NOBYPASSRLS").update(); }
    }
    @Test void tenantStateIsClearedAfterSuccessErrorAndConnectionReuse() throws Exception {
        owner.sql("GRANT SELECT ON reporting.items TO app_rt; TRUNCATE reporting.items; INSERT INTO reporting.items VALUES (1,10),(2,20)").update();
        try(var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername("app_rt"); pool.setPassword("runtime"); pool.setMaximumPoolSize(1);
            var scoped=new TenantAwareDataSource(pool);
            TenantContext.set(new TenantContext(1L,"user@test","ADMIN",10L,null));
            try(var connection=scoped.getConnection(); var st=connection.createStatement()) {
                try(var rs=st.executeQuery("SELECT id FROM reporting.items")) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); assertFalse(rs.next()); }
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
            try(var connection=scoped.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT id FROM reporting.items")) {
                assertTrue(rs.next()); assertEquals(2,rs.getInt(1)); assertFalse(rs.next());
            }
            TenantContext.clear();
            try(var connection=scoped.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT count(*) FROM reporting.items")) { rs.next(); assertEquals(0,rs.getInt(1)); }
        } finally { TenantContext.clear(); }
    }
    @Test void disablingForceRlsIsRejected() {
        owner.sql("ALTER TABLE reporting.items NO FORCE ROW LEVEL SECURITY").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE reporting.items FORCE ROW LEVEL SECURITY").update(); }
    }
    @Test void dedicatedRoleCallbackPreservesColumnRestrictionsAndDeniesForeignSchemas() throws Exception {
        owner.sql("CREATE ROLE ims_platform_rt LOGIN PASSWORD 'isolated' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB NOINHERIT; CREATE TABLE reporting.commands(state text,immutable text); GRANT SELECT,INSERT ON reporting.commands TO app_rt; GRANT UPDATE(state) ON reporting.commands TO app_rt; CREATE SCHEMA student; CREATE TABLE student.private_contacts(phone text); GRANT USAGE ON SCHEMA student TO app_rt; GRANT SELECT ON student.private_contacts TO app_rt;").update();
        try(var resource=getClass().getResourceAsStream("/db/migration/reporting/afterMigrate__dedicated_runtime_acl.sql")) {
            assertNotNull(resource); owner.sql(new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).update();
        }
        JdbcClient isolated=client("ims_platform_rt","isolated");
        isolated.sql("INSERT INTO reporting.commands VALUES ('NEW','fixed')").update();
        isolated.sql("UPDATE reporting.commands SET state='DONE'").update();
        assertThrows(org.springframework.dao.DataAccessException.class,()->isolated.sql("UPDATE reporting.commands SET immutable='forged'").update());
        assertThrows(org.springframework.dao.DataAccessException.class,()->isolated.sql("SELECT * FROM student.private_contacts").query(String.class).list());
        org.springframework.mock.env.MockEnvironment env=new org.springframework.mock.env.MockEnvironment();env.setActiveProfiles("prod"); env.setProperty("app.runtime-db-schemas","reporting,notification,audit");
        assertDoesNotThrow(()->new RuntimeDbRoleGuard(isolated,env,"ims_platform_rt").verifyRuntimeRole());
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
