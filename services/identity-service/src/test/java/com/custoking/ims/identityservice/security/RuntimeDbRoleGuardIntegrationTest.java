package com.custoking.ims.identityservice.security;

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
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'runtime'; CREATE ROLE dangerous BYPASSRLS; CREATE SCHEMA identity; CREATE TABLE identity.items(id int, school_id bigint); ALTER TABLE identity.items ENABLE ROW LEVEL SECURITY; ALTER TABLE identity.items FORCE ROW LEVEL SECURITY; CREATE POLICY isolation ON identity.items USING (school_id = nullif(current_setting('app.current_school_id', true),'')::bigint); GRANT USAGE ON SCHEMA identity TO app_rt; GRANT SELECT ON identity.items TO app_rt;").update();
    }
    static JdbcClient client(String user,String password) {
        return JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(),user,password));
    }
    RuntimeDbRoleGuard guard(JdbcClient jdbc) {
        MockEnvironment env=new MockEnvironment(); env.setActiveProfiles("prod"); env.setProperty("app.runtime-db-schemas","identity");
        return new RuntimeDbRoleGuard(jdbc,env);
    }
    @AfterAll static void stop() { if(pg != null) pg.stop(); }
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
        owner.sql("ALTER TABLE identity.items OWNER TO app_rt").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE identity.items OWNER TO owner").update(); }
    }
    @Test void alteredAppRtPrivilegesAreRejected() {
        owner.sql("ALTER ROLE app_rt BYPASSRLS").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER ROLE app_rt NOBYPASSRLS").update(); }
    }
    @Test void disablingForceRlsIsRejected() {
        owner.sql("ALTER TABLE identity.items NO FORCE ROW LEVEL SECURITY").update();
        try { assertThrows(IllegalStateException.class, () -> guard(client("app_rt","runtime")).verifyRuntimeRole()); }
        finally { owner.sql("ALTER TABLE identity.items FORCE ROW LEVEL SECURITY").update(); }
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
