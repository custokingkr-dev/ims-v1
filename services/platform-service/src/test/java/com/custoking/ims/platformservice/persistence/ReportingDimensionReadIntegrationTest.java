package com.custoking.ims.platformservice.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Reporting Decoupling SP1 Task 6 read-swap: the platform-wide "active schools"
 * KPI (backing {@link ReportingReadRepository#commandCenterSummary(Long, boolean)}) and the
 * active-academic-year resolution (backing, among others,
 * {@link ReportingReadRepository#lowAttendanceSections(Long, LocalDate)}) must read from the
 * new same-schema {@code reporting.dim_school} / {@code reporting.dim_academic_year}
 * projections populated by the outbox pipeline, instead of cross-reading
 * {@code tenant_school.schools} / {@code tenant_school.academic_years}.
 *
 * This test seeds ONLY the reporting dims — the {@code tenant_school} schema/tables are never
 * created at all in this database — so it would fail with a "relation does not exist" error (or
 * wrong counts) if the repository still queried {@code tenant_school}, and passes once the reads
 * are sourced from the dims.
 */
class ReportingDimensionReadIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbcClient;

    private ReportingReadRepository reporting;

    @BeforeAll
    static void setUpContainer() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping reporting dimension read-swap integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("reporting").defaultSchema("reporting")
                .locations("classpath:db/migration/reporting")
                .load().migrate();

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("owner");
        pool.setPassword("owner");
        pool.setMaximumPoolSize(2);
        dataSource = pool;
        jdbcClient = JdbcClient.create(dataSource);

        // Minimal supporting tables for the downstream (still cross-schema, out-of-scope-for-SP1)
        // joins that lowAttendanceSections() exercises once an active academic year is resolved.
        // Deliberately NOT creating tenant_school.schools / tenant_school.academic_years anywhere:
        // this proves the swapped reads no longer depend on those tables at all.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS tenant_school");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tenant_school.school_sections (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255),
                        school_id BIGINT
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS tenant_school.school_classes (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255)
                    )
                    """);
            st.execute("CREATE SCHEMA IF NOT EXISTS attendance");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS attendance.attendance_daily (
                        section_id VARCHAR(255),
                        school_class_id VARCHAR(255),
                        academic_year_id VARCHAR(255),
                        attendance_date DATE,
                        present_count INT,
                        total_enrolled INT
                    )
                    """);
            st.execute("CREATE SCHEMA IF NOT EXISTS student");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS student.students (
                        id BIGINT,
                        school_id BIGINT,
                        section_id VARCHAR(255),
                        attendance_percent NUMERIC
                    )
                    """);
        }
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        reporting = new ReportingReadRepository(jdbcClient);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE reporting.dim_school");
            st.execute("TRUNCATE reporting.dim_academic_year");
            st.execute("TRUNCATE attendance.attendance_daily");
            st.execute("TRUNCATE tenant_school.school_sections");
            st.execute("TRUNCATE tenant_school.school_classes");
            st.execute("TRUNCATE student.students");
        }
    }

    private void seedSchool(long id, String name, boolean active) {
        jdbcClient.sql("""
                        INSERT INTO reporting.dim_school (id, name, active, updated_at)
                        VALUES (:id, :name, :active, now())
                        """)
                .param("id", id)
                .param("name", name)
                .param("active", active)
                .update();
    }

    private void seedAcademicYear(String id, String label, boolean active) {
        jdbcClient.sql("""
                        INSERT INTO reporting.dim_academic_year (id, label, active, updated_at)
                        VALUES (:id, :label, :active, now())
                        """)
                .param("id", id)
                .param("label", label)
                .param("active", active)
                .update();
    }

    @SuppressWarnings("unchecked")
    @Test
    void commandCenterSummary_platformScope_activeSchoolsKpiReadsDimSchoolNotTenantSchool() {
        seedSchool(1L, "Greenwood High", true);
        seedSchool(2L, "Riverside Academy", false);

        Map<String, Object> summary = reporting.commandCenterSummary(null, true);

        List<Map<String, Object>> kpis = (List<Map<String, Object>>) summary.get("kpis");
        Map<String, Object> activeSchoolsKpi = kpis.stream()
                .filter(kpi -> "active_schools".equals(kpi.get("key")))
                .findFirst()
                .orElseThrow();
        // Preserves original "totalSchools" semantics: counts ALL rows in the dim, not just active ones.
        assertEquals("2", activeSchoolsKpi.get("value"));
    }

    @Test
    void lowAttendanceSections_resolvesActiveYearFromDimAcademicYear() throws Exception {
        seedAcademicYear("ay-dim-2026", "2025-26", true);
        seedAcademicYear("ay-dim-2025", "2024-25", false);

        LocalDate today = LocalDate.now();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO tenant_school.school_classes (id, name) VALUES ('class-1', 'Grade 5')");
            st.execute("INSERT INTO tenant_school.school_sections (id, name, school_id) VALUES ('sec-1', 'A', 100)");
        }
        jdbcClient.sql("""
                        INSERT INTO attendance.attendance_daily
                            (section_id, school_class_id, academic_year_id, attendance_date, present_count, total_enrolled)
                        VALUES ('sec-1', 'class-1', :yearId, :date, 5, 10)
                        """)
                .param("yearId", "ay-dim-2026")
                .param("date", today)
                .update();

        Map<String, Object> result = reporting.lowAttendanceSections(100L, today);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) result.get("sections");
        assertEquals(1, sections.size(), "expected the section to be found via the active year resolved from reporting.dim_academic_year");
        assertEquals("sec-1", sections.get(0).get("sectionId"));

        // Sanity: if the code fell back to a mismatched/other year (e.g. the inactive one), no rows would match.
        Map<String, Object> otherYearAttempt = reporting.lowAttendanceSections(999L, today);
        assertTrue(((List<?>) otherYearAttempt.get("sections")).isEmpty());
    }
}
