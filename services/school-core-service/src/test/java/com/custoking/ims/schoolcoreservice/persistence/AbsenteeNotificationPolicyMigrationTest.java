package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbsenteeNotificationPolicyMigrationTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        postgres.start();
        flyway("8").migrate();
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO attendance.absentee_notifications
                        (id, school_id, student_id, class_id, section_id, academic_year_id,
                         attendance_date, parent_contact, message, status)
                    VALUES ('legacy-queued', 10, 1, 'c1', 's1', 'y1', DATE '2026-08-24',
                            '919999999999', 'legacy', 'QUEUED')
                    """);
        }
        flyway(null).migrate();
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void migrationQuarantinesLegacyQueueAndRequiresBoundedCompleteEvidence() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("""
                    SELECT status FROM attendance.absentee_notifications WHERE id = 'legacy-queued'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("SUPPRESSED");
            }

            assertPolicyRejected(statement, "missing-evidence", 2, "");
            assertPolicyRejected(statement, "blank-identity", 3, """
                    , '', '', '', 'guardian-communications.v2', 'ALLOW',
                      repeat('a', 64), now(), now() + interval '2 minutes'
                    """);
            assertPolicyRejected(statement, "non-hex-hash", 4, """
                    , 'guardian-1', 'consent-1', 'notice-v1', 'guardian-communications.v2', 'ALLOW',
                      repeat('z', 64), now(), now() + interval '2 minutes'
                    """);
            assertPolicyRejected(statement, "overlong-window", 5, """
                    , 'guardian-1', 'consent-1', 'notice-v1', 'guardian-communications.v2', 'ALLOW',
                      repeat('a', 64), now(), now() + interval '121 seconds'
                    """);

            statement.execute(validInsert("valid-evidence", 6));
            try (ResultSet result = statement.executeQuery("""
                    SELECT count(*) FROM attendance.absentee_notifications WHERE id = 'valid-evidence'
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isOne();
            }
        }
    }

    private static void assertPolicyRejected(Statement statement, String id, long studentId,
                                             String evidenceValues) {
        String columns = evidenceValues.isEmpty() ? "" : """
                , guardian_id, consent_event_id, consent_notice_version, policy_version,
                  policy_decision, destination_sha256, policy_evaluated_at, policy_expires_at
                """;
        assertThatThrownBy(() -> statement.execute(baseInsert(id, studentId, columns, evidenceValues)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_absentee_notification_v2_policy");
    }

    private static String validInsert(String id, long studentId) {
        return baseInsert(id, studentId, """
                , guardian_id, consent_event_id, consent_notice_version, policy_version,
                  policy_decision, destination_sha256, policy_evaluated_at, policy_expires_at
                """, """
                , 'guardian-1', 'consent-1', 'notice-v1', 'guardian-communications.v2', 'ALLOW',
                  repeat('a', 64), now(), now() + interval '2 minutes'
                """);
    }

    private static String baseInsert(String id, long studentId, String evidenceColumns,
                                     String evidenceValues) {
        return """
                INSERT INTO attendance.absentee_notifications
                    (id, school_id, student_id, class_id, section_id, academic_year_id,
                     attendance_date, parent_contact, message, status %s)
                VALUES ('%s', 10, %d, 'c1', 's1', 'y1', DATE '2026-08-25',
                        '919999999999', 'message', 'QUEUED' %s)
                """.formatted(evidenceColumns, id, studentId, evidenceValues);
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                .schemas("attendance")
                .defaultSchema("attendance")
                .locations("classpath:db/migration/attendance");
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "owner", "owner");
    }
}
