package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantAwareDataSource;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RLS-aware repo test: FeeReadRepository is constructed on an app_rt (RLS-enforced) pool,
 * mirroring FeeRlsIntegrationTest's bootstrap. Verifies that createBand/createItem stamp
 * school_id correctly and that bands()/deleteBand() respect RLS + the superadmin schoolId
 * filter.
 */
class FeeBandSchoolScopeRepoTest {

    static PostgreSQLContainer<?> PG;
    static DataSource appRt; // app_rt pool wrapped by TenantAwareDataSource
    static FeeReadRepository repo;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        // Migrate as the owner (bypasses RLS, like appuser in prod). tenant_school is needed
        // because bandRecord()/academicYear() LEFT JOIN tenant_school.academic_years.
        for (String schema : new String[] {"tenant_school", "fee"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            // Unprivileged runtime role, subject to RLS.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA fee TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA fee TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA fee TO app_rt");
            st.execute("GRANT USAGE ON SCHEMA tenant_school TO app_rt");
            st.execute("GRANT SELECT ON ALL TABLES IN SCHEMA tenant_school TO app_rt");

            st.execute("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('AY-2024', '2024-25', true)");
        }

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(2);
        appRt = new TenantAwareDataSource(pool);
        JdbcClient jdbc = JdbcClient.create(appRt);
        repo = new FeeReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearCtx() throws Exception {
        TenantContext.clear();
        // Clean up between tests as owner (bypasses RLS) so state doesn't leak.
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM fee.fee_assignments");
            st.execute("DELETE FROM fee.fee_items");
            st.execute("DELETE FROM fee.fee_bands");
        }
    }

    private Map<String, Object> bandRequest(String name, long schoolId) {
        Map<String, Object> req = new HashMap<>();
        req.put("name", name);
        req.put("classFrom", 1);
        req.put("classTo", 5);
        req.put("schedules", List.of("Annual"));
        req.put("schoolId", schoolId);
        return req;
    }

    @Test
    void createBand_stampsSchoolId_visibleToOwnSchool_notToOtherSchool() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        Map<String, Object> created = repo.createBand(bandRequest("Band A", 10));
        String bandId = String.valueOf(created.get("id"));

        // Visible under school 10's own context.
        List<FeeReadRepository.FeeBandRow> asSchool10 = repo.bands(null, null);
        assertThat(asSchool10).extracting(FeeReadRepository.FeeBandRow::id).contains(bandId);

        // Not visible under school 20's context (RLS).
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        List<FeeReadRepository.FeeBandRow> asSchool20 = repo.bands(null, null);
        assertThat(asSchool20).extracting(FeeReadRepository.FeeBandRow::id).doesNotContain(bandId);
    }

    @Test
    void bands_nullNull_underSchoolContext_returnsOnlyOwnSchoolBands() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        repo.createBand(bandRequest("School10 Band", 10));

        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        repo.createBand(bandRequest("School20 Band", 20));

        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        List<FeeReadRepository.FeeBandRow> asSchool10 = repo.bands(null, null);
        assertThat(asSchool10).extracting(FeeReadRepository.FeeBandRow::name).containsExactly("School10 Band");
    }

    @Test
    void bands_withExplicitSchoolId_underSuperadmin_filtersToThatSchool() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        repo.createBand(bandRequest("School10 Band", 10));

        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        repo.createBand(bandRequest("School20 Band", 20));

        // Superadmin (bypass RLS), explicit schoolId=20 filter.
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        List<FeeReadRepository.FeeBandRow> filtered = repo.bands(null, 20L);
        assertThat(filtered).extracting(FeeReadRepository.FeeBandRow::name).containsExactly("School20 Band");
    }

    @Test
    void deleteBand_ownBandWithNoAssignments_succeeds() {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        Map<String, Object> created = repo.createBand(bandRequest("Deletable", 10));
        String bandId = String.valueOf(created.get("id"));

        repo.deleteBand(bandId);

        List<FeeReadRepository.FeeBandRow> remaining = repo.bands(null, null);
        assertThat(remaining).extracting(FeeReadRepository.FeeBandRow::id).doesNotContain(bandId);
    }

    @Test
    void deleteBand_withSameSchoolAssignment_throwsNumberedIllegalArgumentException() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        Map<String, Object> created = repo.createBand(bandRequest("InUse", 10));
        String bandId = String.valueOf(created.get("id"));

        // Seed a same-school fee assignment against this band as owner (bypasses RLS on write,
        // but the assignment is still tagged school_id=10 so app_rt/school-10 context can see it).
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO fee.fee_assignments" +
                    "(id, band_discount, manual_discount, surcharge, net_payable, paid_amount, " +
                    " student_id, band_id, academic_year_id, version, school_id) VALUES " +
                    "('" + UUID.randomUUID() + "', 0.0, 0.0, 0.0, 5000, 0, 1, '" + bandId + "', 'AY-2024', 0, 10)");
        }

        assertThatThrownBy(() -> repo.deleteBand(bandId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 student");
    }
}
