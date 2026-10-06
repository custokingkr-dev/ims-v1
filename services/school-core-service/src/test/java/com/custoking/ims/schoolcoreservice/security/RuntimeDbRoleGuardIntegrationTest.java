package com.custoking.ims.schoolcoreservice.security;

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
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'runtime'; CREATE ROLE dangerous BYPASSRLS; CREATE SCHEMA tenant_school; CREATE TABLE tenant_school.items(id int, school_id bigint); ALTER TABLE tenant_school.items ENABLE ROW LEVEL SECURITY; ALTER TABLE tenant_school.items FORCE ROW LEVEL SECURITY; CREATE POLICY isolation ON tenant_school.items USING (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint); GRANT USAGE ON SCHEMA tenant_school TO app_rt; GRANT SELECT ON tenant_school.items TO app_rt;").update();
    }
    static JdbcClient client(String user,String password) {
        return JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),user,password));
    }
    RuntimeDbRoleGuard guard(JdbcClient jdbc) {
        MockEnvironment env=new MockEnvironment(); env.setActiveProfiles("prod"); env.setProperty("app.runtime-db-schemas","tenant_school,student,attendance,fee,catalog");
        return new RuntimeDbRoleGuard(jdbc,env);
    }
    @AfterAll static void stop() { if(pg != null) pg.stop(); }
    @Test void explicitRepairAuditDenyPoliciesPreserveIsolationAndSatisfyGuard() throws Exception {
        owner.sql("CREATE SCHEMA student; CREATE TABLE student.guardian_safe_create_repair_runs(id int); CREATE TABLE student.guardian_safe_create_repair_actions(id int); ALTER TABLE student.guardian_safe_create_repair_runs ENABLE ROW LEVEL SECURITY; ALTER TABLE student.guardian_safe_create_repair_runs FORCE ROW LEVEL SECURITY; ALTER TABLE student.guardian_safe_create_repair_actions ENABLE ROW LEVEL SECURITY; ALTER TABLE student.guardian_safe_create_repair_actions FORCE ROW LEVEL SECURITY; INSERT INTO student.guardian_safe_create_repair_runs VALUES(1); INSERT INTO student.guardian_safe_create_repair_actions VALUES(1)").update();
        try {
            var runtime=client("app_rt","runtime");
            assertThrows(IllegalStateException.class, () -> guard(runtime).verifyRuntimeRole());
            try(var resource=getClass().getResourceAsStream("/db/migration/student/V38__explicit_repair_audit_deny_policies.sql")) {
                assertNotNull(resource);
                owner.sql(new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).update();
            }
            assertDoesNotThrow(() -> guard(runtime).verifyRuntimeRole());
            for(String table : java.util.List.of("guardian_safe_create_repair_runs","guardian_safe_create_repair_actions")) {
                assertFalse(owner.sql("SELECT has_table_privilege('app_rt', 'student."+table+"', 'SELECT')").query(Boolean.class).single());
                assertTrue(owner.sql("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='student."+table+"'::regclass").query(Boolean.class).single());
                // An accidental future runtime grant cannot defeat this policy.
                owner.sql("GRANT USAGE ON SCHEMA student TO app_rt; GRANT SELECT,INSERT ON student."+table+" TO app_rt").update();
                assertEquals(0, runtime.sql("SELECT count(*) FROM student."+table).query(Integer.class).single());
                assertThrows(org.springframework.dao.DataAccessException.class, () -> runtime.sql("INSERT INTO student."+table+" VALUES(2)").update());
            }
        } finally { owner.sql("DROP SCHEMA student CASCADE").update(); }
    }
    @Test void directAndReachableReplicationPrivilegesAreRejected() {
        owner.sql("ALTER ROLE app_rt REPLICATION").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER ROLE app_rt NOREPLICATION").update(); }
        owner.sql("CREATE ROLE replication_capability REPLICATION; GRANT replication_capability TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("REVOKE replication_capability FROM app_rt; DROP ROLE replication_capability").update(); }
    }
    @Test void runtimeIsSafeButOwnerRejected() {
        assertDoesNotThrow(() -> guard(client("app_rt","runtime")).verifyRuntimeRole());
        assertThrows(IllegalStateException.class,() -> guard(owner).verifyRuntimeRole());
    }
    @Test void inheritedBypassAndOwnershipAreRejected() {
        owner.sql("GRANT dangerous TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("REVOKE dangerous FROM app_rt").update(); }
        owner.sql("ALTER TABLE tenant_school.items OWNER TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE tenant_school.items OWNER TO owner").update(); }
    }
    @Test void alteredAppRtPrivilegesAreRejected() {
        owner.sql("ALTER ROLE app_rt BYPASSRLS").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER ROLE app_rt NOBYPASSRLS").update(); }
    }
    @Test void tenantStateIsClearedAfterSuccessErrorAndConnectionReuse() throws Exception {
        owner.sql("GRANT SELECT ON tenant_school.items TO app_rt; TRUNCATE tenant_school.items; INSERT INTO tenant_school.items VALUES (1,10),(2,20)").update();
        try(var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(pg.getJdbcUrl()); pool.setUsername("app_rt"); pool.setPassword("runtime"); pool.setMaximumPoolSize(1);
            var scoped=new TenantAwareDataSource(pool);
            TenantContext.set(new TenantContext(1L,"user@test","ADMIN",10L,null));
            try(var connection=scoped.getConnection(); var st=connection.createStatement()) {
                try(var rs=st.executeQuery("SELECT id FROM tenant_school.items")) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); assertFalse(rs.next()); }
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
            try(var connection=scoped.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT id FROM tenant_school.items")) {
                assertTrue(rs.next()); assertEquals(2,rs.getInt(1)); assertFalse(rs.next());
            }
            TenantContext.clear();
            try(var connection=scoped.getConnection(); var st=connection.createStatement(); var rs=st.executeQuery("SELECT count(*) FROM tenant_school.items")) { rs.next(); assertEquals(0,rs.getInt(1)); }
        } finally { TenantContext.clear(); }
    }
    @Test void disablingForceRlsIsRejected() {
        owner.sql("ALTER TABLE tenant_school.items NO FORCE ROW LEVEL SECURITY").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE tenant_school.items FORCE ROW LEVEL SECURITY").update(); }
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
