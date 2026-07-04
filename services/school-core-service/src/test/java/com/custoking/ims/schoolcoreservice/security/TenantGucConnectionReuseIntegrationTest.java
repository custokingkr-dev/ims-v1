package com.custoking.ims.schoolcoreservice.security;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Certifies that {@link TenantAwareDataSource} does NOT leak the RLS tenant GUC
 * (app.current_school_id) across a reused *physical* connection.
 *
 * <p>Every other {@code *RlsIntegrationTest} in this package uses a pool with more than
 * one connection, so it never proves what happens when the exact same physical
 * connection is handed back out to a different tenant. This test pins the Hikari pool to
 * {@code maximumPoolSize=1} so both borrows in the reuse scenario are guaranteed to be the
 * same underlying connection, then drives {@link TenantAwareDataSource#getConnection()}
 * under two different {@link TenantContext}s to prove the session-level
 * {@code set_config(..., false)} call on every borrow fully overwrites the prior tenant's
 * value — i.e. tenant B can never see tenant A's rows on a recycled connection, and an
 * empty/no-tenant context fails closed (sees nothing) rather than inheriting the last
 * tenant seen on that connection.
 */
class TenantGucConnectionReuseIntegrationTest {

    private static final long SCHOOL_A = 10L;
    private static final long SCHOOL_B = 20L;

    static PostgreSQLContainer<?> PG;
    static DataSource appRt; // single-connection app_rt pool wrapped by TenantAwareDataSource

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        // Migrate as the owner (owns tables → bypasses RLS, like appuser in prod).
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("student").defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA student TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA student TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA student TO app_rt");
            // Seed: school 10 (A) x2, school 20 (B) x1 — as owner (bypasses RLS).
            st.execute("INSERT INTO student.students (admission_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES " +
                    "('A1','Alice',10,'c1','s1','y1'),('A2','Amy',10,'c1','s1','y1'),('B1','Bob',20,'c1','s1','y1')");
        }

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        // Pin the pool to a single physical connection so every borrow in this test
        // reuses the exact same connection object — the mechanic that proves (or
        // disproves) GUC leakage across tenants.
        pool.setMaximumPoolSize(1);
        appRt = new TenantAwareDataSource(pool);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearCtx() { TenantContext.clear(); }

    private List<Long> querySchoolIds() throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT school_id FROM student.students ORDER BY admission_no")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    @Test
    void tenantBOnReusedConnectionCannotSeeTenantA() throws Exception {
        // Borrow #1: school A context. With maximumPoolSize=1 this is the pool's one and
        // only physical connection.
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", SCHOOL_A, null));
        List<Long> asSeenByA = querySchoolIds();
        assertEquals(2, asSeenByA.size(), "School A must see exactly its 2 rows");
        assertTrue(asSeenByA.stream().allMatch(id -> id == SCHOOL_A), "School A must not see any other school's rows");

        // Borrow #2: school B context. Hikari has nowhere else to source a connection from
        // (pool size 1), so this MUST be the same physical connection just used for school A.
        // If the session-level GUC ever leaked/stuck, school B would still see school A's rows here.
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", SCHOOL_B, null));
        List<Long> asSeenByB = querySchoolIds();
        assertEquals(1, asSeenByB.size(), "School B must see exactly its 1 row");
        assertTrue(asSeenByB.stream().allMatch(id -> id == SCHOOL_B), "School B must not see any of school A's rows on the reused connection");
    }

    @Test
    void emptyContextFailsClosed() throws Exception {
        // Prime the (single) physical connection with a real tenant first...
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", SCHOOL_A, null));
        assertEquals(2, querySchoolIds().size(), "Sanity check: school A sees its rows before the empty-context borrow");

        // ...then borrow again (same physical connection) with no tenant context at all.
        // This must fail closed (0 rows), not silently inherit the previous tenant's GUC value.
        TenantContext.clear();
        List<Long> asSeenByEmpty = querySchoolIds();
        assertTrue(asSeenByEmpty.isEmpty(), "Empty/no tenant context must see zero rows, not the previous tenant's rows");
    }
}
