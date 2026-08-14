package com.custoking.ims.schoolcoreservice.studentexport;

import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class StudentExportRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16")
            .withUsername("owner")
            .withPassword("owner");

    static HikariDataSource dataSource;
    static StudentExportRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("tenant_school").defaultSchema("tenant_school")
                .locations("classpath:db/migration/tenant_school")
                .load().migrate();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("student").defaultSchema("student")
                .locations("classpath:db/migration/student")
                .load().migrate();

        try (Connection connection = java.sql.DriverManager.getConnection(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO tenant_school.schools
                        (id, name, short_code, active, created_at, configured_class_count, configured_section_count)
                    VALUES
                        (101, 'Assigned School', 'ASG', true, now(), 12, 1),
                        (102, 'Unassigned School', 'UNA', true, now(), 12, 1)
                    """);
            sql.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay-2026', '2026-27', true)");
            sql.execute("""
                    INSERT INTO tenant_school.school_sections
                        (id, name, active, school_class_id, school_id)
                    VALUES
                        ('section-101', 'A', true, '9', 101),
                        ('section-102', 'A', true, '9', 102)
                    """);
            sql.execute("""
                    INSERT INTO student.students
                        (admission_no, full_name, school_id, class_id, section_id, academic_year_id, photo_url)
                    VALUES
                        ('ADM-101', 'Assigned Student', 101, '9', 'section-101', 'ay-2026', 'photo-101'),
                        ('ADM-102', 'Unassigned Student', 102, '9', 'section-102', 'ay-2026', 'photo-102')
                    """);
        }

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(PG.getJdbcUrl());
        dataSource.setUsername(PG.getUsername());
        dataSource.setPassword(PG.getPassword());
        dataSource.setMaximumPoolSize(2);
        repository = new StudentExportRepository(JdbcClient.create(dataSource));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @AfterAll
    static void closeDataSource() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void assignedSchoolFilterExportQueryAndAuditRoundTripUseCurrentSchema() {
        TenantContext.set(new TenantContext(22L, "ops@example.com", "OPERATIONS", null, null,
                Set.of(101L), Set.of("student:export")));

        assertThat(repository.allowedSchools())
                .extracting(StudentExportRepository.SchoolOption::id)
                .containsExactly(101L);

        var data = repository.load(101L);
        assertThat(data.school().shortCode()).isEqualTo("ASG");
        assertThat(data.students())
                .extracting(StudentExportRepository.Student::admissionNumber)
                .containsExactly("ADM-101");

        var auditId = repository.startAudit(101L, null, data.students().size());
        repository.finishAudit(auditId, 101L, "COMPLETED", 1, 0, null);

        JdbcClient jdbc = JdbcClient.create(dataSource);
        assertThat(jdbc.sql("SELECT status FROM student.student_export_audit WHERE id = :id")
                .param("id", auditId)
                .query(String.class)
                .single()).isEqualTo("COMPLETED");
    }
}
