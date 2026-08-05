package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardianConsentRepositoryIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    static DataSource dataSource;
    static GuardianConsentRepository repository;

    @BeforeAll
    static void setUpDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        postgres.start();
        for (String schema : new String[]{"tenant_school", "student"}) {
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), "owner", "owner");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        repository = new GuardianConsentRepository(
                jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seed() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM student.student_consent_events").update();
        jdbc.sql("DELETE FROM student.student_guardians").update();
        jdbc.sql("DELETE FROM student.guardians").update();
        jdbc.sql("DELETE FROM student.students").update();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();
        jdbc.sql("DELETE FROM tenant_school.school_sections WHERE id = 's1'").update();
        jdbc.sql("DELETE FROM tenant_school.schools").update();
        jdbc.sql("DELETE FROM tenant_school.academic_years WHERE id = 'ay1'").update();
        jdbc.sql("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('ay1', '2026-27', true)").update();
        jdbc.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1, 'School', 'SCH', true, now())").update();
        jdbc.sql("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1', 'Class 1', 1) ON CONFLICT (id) DO NOTHING").update();
        jdbc.sql("INSERT INTO tenant_school.school_sections(id, name, active, school_class_id, school_id) VALUES ('s1', 'A', true, 'c1', 1)").update();
        jdbc.sql("INSERT INTO student.students(id, admission_no, full_name, school_id, class_id, section_id, academic_year_id) VALUES (1, 'A1', 'Student One', 1, 'c1', 's1', 'ay1'), (2, 'A2', 'Student Two', 1, 'c1', 's1', 'ay1')").update();
        TenantContext.set(new TenantContext(10L, "admin@example.com", "ADMIN", 1L,
                null, Set.of(), Set.of("student:read", "student:update")));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void changingParentRelationshipClearsLegacyParentColumns() {
        Map<String, Object> created = repository.addGuardian(1L, Map.of(
                "fullName", "Parent One", "phone", "9876543210", "relationship", "FATHER",
                "primary", true));
        Map<String, Object> guardian = guardians(created).getFirst();

        assertThat(studentValue("father_name")).isEqualTo("Parent One");
        repository.updateGuardian(1L, String.valueOf(guardian.get("id")), Map.of(
                "fullName", "Parent One", "phone", "9876543210", "relationship", "OTHER",
                "primary", true, "version", guardian.get("version")));

        assertThat(studentValue("father_name")).isNull();
        assertThat(studentValue("father_contact")).isNull();
    }

    @Test
    void expiredGrantIsReportedAsExpiredAndIdempotencyCannotCrossStudents() {
        Map<String, Object> consent = Map.of(
                "purpose", "STUDENT_PHOTO", "status", "GRANTED", "noticeVersion", "privacy-1",
                "evidenceSource", "SIGNED_FORM", "expiresAt", OffsetDateTime.now().minusDays(1).toString());

        Map<String, Object> overview = repository.recordConsent(1L, consent, "consent-key");
        assertThat(consents(overview).getFirst().get("status")).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> repository.recordConsent(2L, consent, "consent-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another student");
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> guardians(Map<String, Object> overview) {
        return (java.util.List<Map<String, Object>>) overview.get("guardians");
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> consents(Map<String, Object> overview) {
        return (java.util.List<Map<String, Object>>) overview.get("consents");
    }

    private static String studentValue(String column) {
        return JdbcClient.create(dataSource).sql("SELECT " + column + " FROM student.students WHERE id = 1")
                .query(String.class).optional().orElse(null);
    }
}
