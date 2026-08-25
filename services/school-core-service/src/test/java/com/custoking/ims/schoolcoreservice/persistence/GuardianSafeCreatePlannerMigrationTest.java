package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuardianSafeCreatePlannerMigrationTest {

    @Test
    void plannerIsVersionedDeterministicPrivateAndAggregateSafe() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner")) {
            postgres.start();
            var ownerDataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), "owner", "owner");
            JdbcClient jdbc = JdbcClient.create(ownerDataSource);
            jdbc.sql("CREATE ROLE app_rt LOGIN PASSWORD 'runtime-test' NOBYPASSRLS").update();

            for (String schema : new String[]{"tenant_school", "student"}) {
                Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration/" + schema)
                        .load()
                        .migrate();
            }

            seed(jdbc);

            Map<String, Object> contract = jdbc.sql("""
                            SELECT contract_version, contract_digest, contract_manifest
                            FROM student.guardian_safe_create_contract_v1()
                            """)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "version", rs.getString("contract_version"),
                            "digest", rs.getString("contract_digest"),
                            "manifest", rs.getString("contract_manifest")))
                    .single();
            assertThat(contract.get("version")).isEqualTo("guardian-safe-create-v1");
            assertThat(String.valueOf(contract.get("digest")))
                    .isEqualTo("fa0ca25fd6c2f2e63f9040cebeb3899481415540ca3cc61a331624836012b641");
            assertThat(String.valueOf(contract.get("manifest")))
                    .contains("create-only-no-merge-no-reactivation-no-update")
                    .contains("SERIALIZABLE")
                    .contains("SHARE MODE NOWAIT")
                    .contains("guardian-repair-ledger-v1")
                    .contains("preserve-verified-photo")
                    .contains("student.guardian.upserted.v1")
                    .contains("student-review-item.upserted.v1")
                    .doesNotContain("tenant_school.outbox_events");

            List<Map<String, Object>> firstPlan = plan(jdbc);
            List<Map<String, Object>> secondPlan = plan(jdbc);
            assertThat(secondPlan).isEqualTo(firstPlan);
            assertThat(firstPlan).hasSize(7);

            List<Map<String, Object>> safeRows = firstPlan.stream()
                    .filter(row -> "SAFE_CREATE_UNLINKED_STUDENT".equals(row.get("bucket")))
                    .toList();
            assertThat(safeRows).hasSize(3);
            assertThat(safeRows).extracting(row -> row.get("field"))
                    .containsExactlyInAnyOrder("name", "contact", "name");
            assertThat(safeRows).extracting(row -> row.get("guardianId"))
                    .containsExactlyInAnyOrder(
                            deterministicId("guardian-repair-v1-", "1:father"),
                            deterministicId("guardian-repair-v1-", "1:father"),
                            deterministicId("guardian-repair-v1-", "1:mother"));
            assertThat(safeRows).extracting(row -> row.get("linkId"))
                    .containsExactlyInAnyOrder(
                            deterministicId("guardian-link-repair-v1-", "1:father"),
                            deterministicId("guardian-link-repair-v1-", "1:father"),
                            deterministicId("guardian-link-repair-v1-", "1:mother"));

            List<String> aggregateFirst = summaries(jdbc);
            List<String> aggregateSecond = summaries(jdbc);
            assertThat(aggregateSecond).isEqualTo(aggregateFirst);
            assertThat(aggregateFirst).anyMatch(row -> row.contains(
                    "guardian-safe-create-v1|SAFE_CREATE_UNLINKED_STUDENT|FATHER|contact|1|1|3|2|1"));
            assertThat(String.join("\n", aggregateFirst))
                    .doesNotContain("Safe Father", "Safe Mother", "Cluster One", "919876543210");

            assertThat(jdbc.sql("""
                            SELECT has_function_privilege(
                                'app_rt', 'student.guardian_safe_create_contract_v1()', 'EXECUTE')
                               OR has_function_privilege(
                                'app_rt', 'student.guardian_safe_create_plan_v1()', 'EXECUTE')
                               OR has_function_privilege(
                                'app_rt', 'student.guardian_safe_create_summary_v1()', 'EXECUTE')
                            """).query(Boolean.class).single()).isFalse();
            assertThat(jdbc.sql("""
                            SELECT has_function_privilege(
                                'public', 'student.guardian_safe_create_plan_v1()', 'EXECUTE')
                               OR has_function_privilege(
                                'public', 'student.guardian_safe_create_summary_v1()', 'EXECUTE')
                            """).query(Boolean.class).single()).isFalse();
        }
    }

    private static void seed(JdbcClient jdbc) {
        jdbc.sql("""
                INSERT INTO tenant_school.academic_years(id, label, active)
                VALUES ('ay', '2026-27', true);
                INSERT INTO tenant_school.schools(id, name, short_code, active, created_at)
                VALUES (1, 'Planner School', 'PLAN', true, now());
                INSERT INTO tenant_school.school_classes(id, name, sort_order)
                VALUES ('c', 'Class', 1);
                INSERT INTO tenant_school.school_sections(id, name, active, school_class_id, school_id)
                VALUES ('s', 'A', true, 'c', 1);

                INSERT INTO student.students
                    (id, admission_no, full_name, school_id, class_id, section_id, academic_year_id,
                     father_name, father_contact, mother_name, updated_at)
                VALUES
                    (1, 'P1', 'Safe Student', 1, 'c', 's', 'ay',
                     'Safe Father', '+91 98765-43210', 'Safe Mother', '2026-08-26T00:00:00Z'),
                    (2, 'P2', 'Cluster Student One', 1, 'c', 's', 'ay',
                     'Cluster One', '9090909090', NULL, '2026-08-26T00:00:00Z'),
                    (3, 'P3', 'Cluster Student Two', 1, 'c', 's', 'ay',
                     'Cluster Two', '9090909090', NULL, '2026-08-26T00:00:00Z'),
                    (4, 'P4', 'Linked Student', 1, 'c', 's', 'ay',
                     'Already Linked', '8080808080', NULL, '2026-08-26T00:00:00Z');

                INSERT INTO student.guardians(id, school_id, full_name, phone, status)
                VALUES ('existing-linked', 1, 'Already Linked', '8080808080', 'ACTIVE');
                INSERT INTO student.student_guardians
                    (id, school_id, student_id, guardian_id, relationship, is_primary)
                VALUES ('existing-link', 1, 4, 'existing-linked', 'FATHER', true);
                """).update();
    }

    private static List<Map<String, Object>> plan(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT student_id, relationship, field_name, eligibility_bucket,
                               target_guardian_id, target_link_id, contract_digest,
                               fingerprint_record::text AS fingerprint_record
                        FROM student.guardian_safe_create_plan_v1()
                        ORDER BY student_id, relationship, field_name
                        """)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "studentId", rs.getLong("student_id"),
                        "relationship", rs.getString("relationship"),
                        "field", rs.getString("field_name"),
                        "bucket", rs.getString("eligibility_bucket"),
                        "guardianId", rs.getString("target_guardian_id"),
                        "linkId", rs.getString("target_link_id"),
                        "contractDigest", rs.getString("contract_digest"),
                        "fingerprint", rs.getString("fingerprint_record")))
                .list();
    }

    private static List<String> summaries(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT concat_ws('|', contract_version, eligibility_bucket, relationship,
                                         field_name, field_actions, distinct_students,
                                         safe_create_field_actions,
                                         safe_create_relationship_actions, safe_create_students,
                                         contract_digest, unlinked_plan_sha256,
                                         safe_create_plan_sha256)
                        FROM student.guardian_safe_create_summary_v1()
                        ORDER BY eligibility_bucket, relationship, field_name
                        """)
                .query(String.class)
                .list();
    }

    private static String deterministicId(String prefix, String seed) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(seed.getBytes(StandardCharsets.UTF_8));
        return prefix + HexFormat.of().formatHex(digest);
    }
}
