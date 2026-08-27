package com.custoking.ims.schoolcoreservice.persistence;

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

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardianDataReviewRepositoryIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static GuardianDataReviewRepository repository;

    @BeforeAll
    static void setUpDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        postgres.start();
        for (String schema : new String[]{"tenant_school", "student"}) {
            Flyway.configure().dataSource(postgres.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema).load().migrate();
        }
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        repository = new GuardianDataReviewRepository(jdbc);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void seed() {
        jdbc.sql("DELETE FROM student.guardian_data_review_decisions").update();
        jdbc.sql("DELETE FROM student.student_guardians").update();
        jdbc.sql("DELETE FROM student.guardians").update();
        jdbc.sql("DELETE FROM student.students").update();
        jdbc.sql("DELETE FROM tenant_school.school_sections WHERE id = 's1'").update();
        jdbc.sql("DELETE FROM tenant_school.schools").update();
        jdbc.sql("DELETE FROM tenant_school.academic_years WHERE id = 'ay1'").update();
        jdbc.sql("INSERT INTO tenant_school.academic_years(id, label, active) VALUES ('ay1', '2026-27', true)").update();
        jdbc.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1, 'School', 'SCH', true, now())").update();
        jdbc.sql("INSERT INTO tenant_school.school_classes(id, name, sort_order) VALUES ('c1', 'Class 1', 1) ON CONFLICT (id) DO NOTHING").update();
        jdbc.sql("INSERT INTO tenant_school.school_sections(id, name, active, school_class_id, school_id) VALUES ('s1', 'A', true, 'c1', 1)").update();
        jdbc.sql("""
                INSERT INTO student.students
                    (id, admission_no, full_name, school_id, class_id, section_id,
                     academic_year_id, father_name, father_contact)
                VALUES (1, 'A1', 'Student One', 1, 'c1', 's1', 'ay1', 'Raj Rao', '9876543210')
                """).update();
        jdbc.sql("""
                INSERT INTO student.guardians
                    (id, school_id, full_name, phone, status)
                VALUES ('guardian-1', 1, 'RAJ RAO', '9876543210', 'ACTIVE')
                """).update();
        jdbc.sql("""
                INSERT INTO student.student_guardians
                    (id, school_id, student_id, guardian_id, relationship, is_primary)
                VALUES ('link-1', 1, 1, 'guardian-1', 'FATHER', true)
                """).update();
        TenantContext.set(new TenantContext(10L, "admin@example.com", "ADMIN", 1L,
                null, Set.of(), Set.of("student:read", "student:update")));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void classifiesAndTracksSnapshotBoundDecisionProgress() {
        Map<String, Object> summary = repository.summary(1L);
        assertThat(summary).containsEntry("totalCases", 1L)
                .containsEntry("distinctStudents", 1L);
        assertThat((java.math.BigDecimal) summary.get("progressPercent")).isEqualByComparingTo("0");

        Map<String, Object> page = repository.cases(1L, "CASE_ONLY", "PENDING", null, 0, 25);
        List<Map<String, Object>> cases = cases(page);
        assertThat(cases).singleElement().satisfies(reviewCase -> {
            assertThat(reviewCase).containsEntry("legacyValue", "Raj Rao")
                    .containsEntry("normalizedValue", "RAJ RAO")
                    .containsEntry("recommendedDecision", "ACCEPT_NORMALIZED");

            Map<String, Object> decided = repository.decide(1L,
                    String.valueOf(reviewCase.get("caseId")), Map.of(
                            "caseSnapshotSha256", reviewCase.get("caseSnapshotSha256"),
                            "decision", "ACCEPT_NORMALIZED",
                            "notes", "School register checked"), "request-1");
            assertThat(decided).containsEntry("reviewStatus", "DECIDED")
                    .containsEntry("decision", "ACCEPT_NORMALIZED");
        });

        assertThat((java.math.BigDecimal) repository.summary(1L).get("progressPercent"))
                .isEqualByComparingTo("100");
        assertThat(repository.cases(1L, null, "DECIDED", null, 0, 25))
                .containsEntry("totalElements", 1L);
    }

    @Test
    void rejectsDecisionWhenStudentSnapshotChanged() {
        Map<String, Object> reviewCase = cases(repository.cases(1L, null, null, null, 0, 25)).getFirst();
        jdbc.sql("UPDATE student.students SET father_name = 'Different', version = version + 1 WHERE id = 1").update();

        assertThatThrownBy(() -> repository.decide(1L,
                String.valueOf(reviewCase.get("caseId")), Map.of(
                        "caseSnapshotSha256", reviewCase.get("caseSnapshotSha256"),
                        "decision", "KEEP_LEGACY"), "request-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changed since it was loaded");
    }

    @Test
    void separatesSingletonIdentityCandidateFromRepeatedPlaceholderPhone() {
        jdbc.sql("DELETE FROM student.student_guardians").update();

        assertThat(repository.cases(1L, "IDENTITY_CANDIDATE", null, null, 0, 25))
                .containsEntry("totalElements", 2L);

        for (int index = 2; index <= 10; index++) {
            jdbc.sql("""
                    INSERT INTO student.guardians(id, school_id, full_name, phone, status)
                    VALUES (:id, 1, :name, '9876543210', 'ACTIVE')
                    """).param("id", "guardian-" + index)
                    .param("name", "Different Parent " + index).update();
        }

        assertThat(repository.cases(1L, "PLACEHOLDER_CANDIDATE", null, null, 0, 25))
                .containsEntry("totalElements", 2L);
    }

    @Test
    void separatesLargeLegacyClustersFromCandidatesWithoutMaskingIncompleteRelationships() {
        jdbc.sql("DELETE FROM student.student_guardians").update();
        jdbc.sql("DELETE FROM student.guardians").update();
        for (int index = 100; index < 110; index++) {
            jdbc.sql("""
                    INSERT INTO student.students
                        (id, admission_no, full_name, school_id, class_id, section_id,
                         academic_year_id, father_name, father_contact)
                    VALUES (:id, :admission, :studentName, 1, 'c1', 's1', 'ay1',
                            :fatherName, '1111111111')
                    """).param("id", index).param("admission", "R-" + index)
                    .param("studentName", "Repeated Student " + index)
                    .param("fatherName", "Repeated Parent " + index).update();
        }
        for (int index = 200; index < 300; index++) {
            jdbc.sql("""
                    INSERT INTO student.students
                        (id, admission_no, full_name, school_id, class_id, section_id,
                         academic_year_id, father_name, father_contact)
                    VALUES (:id, :admission, :studentName, 1, 'c1', 's1', 'ay1',
                            :fatherName, '2222222222')
                    """).param("id", index).param("admission", "L-" + index)
                    .param("studentName", "Large Cluster Student " + index)
                    .param("fatherName", "Large Cluster Parent " + index).update();
        }
        jdbc.sql("""
                INSERT INTO student.students
                    (id, admission_no, full_name, school_id, class_id, section_id,
                     academic_year_id, father_contact, mother_name)
                VALUES (110, 'I-110', 'Incomplete Student', 1, 'c1', 's1', 'ay1',
                        '1111111111', 'Incomplete Mother')
                """).update();

        assertThat(repository.cases(1L, "PLACEHOLDER_CANDIDATE", "PENDING", "R-", 0, 100))
                .containsEntry("totalElements", 20L);
        assertThat(repository.cases(1L, "PLACEHOLDER_CLUSTER", "PENDING", "L-", 0, 250))
                .containsEntry("totalElements", 200L);
        assertThat(repository.cases(1L, "MISSING_RELATIONSHIP", "PENDING", "I-110", 0, 100))
                .containsEntry("totalElements", 2L);
    }

    @Test
    void appliesProjectionAndCaseOnlyPrecedenceBeforeLinkedClusterRisk() {
        addNineStudentsLinkedToSeedGuardian();

        assertThat(repository.cases(1L, "CASE_ONLY", "PENDING", "A1", 0, 25))
                .containsEntry("totalElements", 1L);

        jdbc.sql("UPDATE student.students SET father_name = ' ' WHERE id = 1").update();
        assertThat(repository.cases(1L, "PROJECTION_MISSING", "PENDING", "A1", 0, 25))
                .containsEntry("totalElements", 1L);
    }

    @Test
    void keepsSubstantiveLinkedDifferenceOutOfPlaceholderCluster() {
        addNineStudentsLinkedToSeedGuardian();
        jdbc.sql("UPDATE student.students SET father_name = 'Different Parent' WHERE id = 1").update();

        assertThat(repository.cases(1L, "LINKED_CONFLICT", "PENDING", "A1", 0, 25))
                .containsEntry("totalElements", 1L);
        assertThat(repository.cases(1L, "PLACEHOLDER_CLUSTER", "PENDING", "A1", 0, 25))
                .containsEntry("totalElements", 0L);
    }

    @Test
    void recordsBulkDecisionsAtomicallyAndIdempotently() {
        Map<String, Object> reviewCase = cases(
                repository.cases(1L, "CASE_ONLY", "PENDING", null, 0, 25)).getFirst();
        var requested = List.of(new GuardianDataReviewRepository.BulkDecisionCase(
                1L, String.valueOf(reviewCase.get("caseId")),
                String.valueOf(reviewCase.get("caseSnapshotSha256"))));

        assertThat(repository.decideBulk(requested, "KEEP_LEGACY",
                "School confirmed legacy", "bulk-request-1"))
                .containsEntry("processed", 1)
                .containsEntry("recordsMutated", 0);
        assertThat(repository.cases(1L, "CASE_ONLY", "DECIDED", null, 0, 25))
                .containsEntry("totalElements", 1L);

        repository.decideBulk(requested, "KEEP_LEGACY",
                "School confirmed legacy", "bulk-request-1");
        assertThat(jdbc.sql("SELECT count(*) FROM student.guardian_data_review_decisions")
                .query(Long.class).single()).isEqualTo(1L);
    }

    private static void addNineStudentsLinkedToSeedGuardian() {
        for (int index = 2; index <= 10; index++) {
            jdbc.sql("""
                    INSERT INTO student.students
                        (id, admission_no, full_name, school_id, class_id, section_id,
                         academic_year_id, father_name, father_contact)
                    VALUES (:id, :admission, :studentName, 1, 'c1', 's1', 'ay1',
                            'RAJ RAO', '9876543210')
                    """).param("id", index).param("admission", "S-" + index)
                    .param("studentName", "Shared Student " + index).update();
            jdbc.sql("""
                    INSERT INTO student.student_guardians
                        (id, school_id, student_id, guardian_id, relationship, is_primary)
                    VALUES (:id, 1, :studentId, 'guardian-1', 'FATHER', true)
                    """).param("id", "link-" + index).param("studentId", index).update();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }
}
