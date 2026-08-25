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
import java.util.List;
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
        jdbc.sql("DELETE FROM student.student_review_items").update();
        jdbc.sql("DELETE FROM student.student_review_campaigns").update();
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
                "primary", true, "version", guardian.get("version"),
                "linkVersion", guardian.get("linkVersion")));

        assertThat(studentValue("father_name")).isNull();
        assertThat(studentValue("father_contact")).isNull();
    }

    @Test
    void changingDestinationClearsPreviouslyVerifiedContactEvenWhenEditFormPostsTrue() {
        Map<String, Object> created = repository.addGuardian(1L, Map.of(
                "fullName", "Parent One", "phone", "9876543210", "relationship", "FATHER",
                "primary", true, "contactVerified", true));
        Map<String, Object> guardian = guardians(created).getFirst();
        assertThat(guardian.get("contactVerifiedAt")).isNotNull();

        Map<String, Object> updated = repository.updateGuardian(
                1L, String.valueOf(guardian.get("id")), Map.of(
                        "fullName", "Parent One", "phone", "9876543299", "relationship", "FATHER",
                        "primary", true, "contactVerified", true, "version", guardian.get("version"),
                        "linkVersion", guardian.get("linkVersion")));

        assertThat(guardians(updated).getFirst().get("contactVerifiedAt")).isNull();
    }

    @Test
    void staleLinkVersionCannotOverwriteGuardianPermissions() {
        Map<String, Object> created = repository.addGuardian(1L, Map.of(
                "fullName", "Parent One", "phone", "9876543210", "relationship", "FATHER",
                "primary", true));
        Map<String, Object> guardian = guardians(created).getFirst();
        String guardianId = String.valueOf(guardian.get("id"));
        JdbcClient.create(dataSource).sql("""
                        UPDATE student.student_guardians
                        SET receives_notifications = false, version = version + 1
                        WHERE student_id = 1 AND guardian_id = :guardianId
                        """).param("guardianId", guardianId).update();

        assertThatThrownBy(() -> repository.updateGuardian(1L, guardianId, Map.ofEntries(
                Map.entry("fullName", "Stale Edit"),
                Map.entry("phone", "9876543299"),
                Map.entry("relationship", "FATHER"),
                Map.entry("primary", true),
                Map.entry("receivesNotifications", true),
                Map.entry("version", guardian.get("version")),
                Map.entry("linkVersion", guardian.get("linkVersion")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permissions changed");

        assertThat(guardians(repository.overview(1L)).getFirst())
                .containsEntry("fullName", "Parent One")
                .containsEntry("receivesNotifications", false);
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

    @Test
    void updatingSharedGuardianFansOutLegacyParityWithoutRewritingConsentOrSiblingLink() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Map<String, Object> firstOverview = repository.addGuardian(1L, Map.ofEntries(
                Map.entry("fullName", "Shared Father"),
                Map.entry("phone", "9876543210"),
                Map.entry("email", "old@example.test"),
                Map.entry("relationship", "FATHER"),
                Map.entry("primary", true),
                Map.entry("contactVerified", true),
                Map.entry("receivesNotifications", true),
                Map.entry("canViewAcademic", true),
                Map.entry("canManageFees", false),
                Map.entry("pickupAuthorized", false)));
        Map<String, Object> sharedGuardian = guardians(firstOverview).getFirst();
        String guardianId = String.valueOf(sharedGuardian.get("id"));
        repository.addGuardian(2L, Map.ofEntries(
                Map.entry("guardianId", guardianId),
                Map.entry("relationship", "FATHER"),
                Map.entry("primary", true),
                Map.entry("receivesNotifications", false),
                Map.entry("canViewAcademic", false),
                Map.entry("canManageFees", true),
                Map.entry("pickupAuthorized", true)));

        repository.recordConsent(1L, Map.of(
                "guardianId", guardianId,
                "purpose", "SCHOOL_COMMUNICATIONS",
                "status", "GRANTED",
                "noticeVersion", "notice-1",
                "evidenceSource", "SIGNED_FORM"), "shared-consent-1");
        repository.recordConsent(2L, Map.of(
                "guardianId", guardianId,
                "purpose", "SCHOOL_COMMUNICATIONS",
                "status", "GRANTED",
                "noticeVersion", "notice-1",
                "evidenceSource", "SIGNED_FORM"), "shared-consent-2");

        jdbc.sql("""
                INSERT INTO student.student_review_campaigns
                    (id, school_id, academic_year_id, review_type, title, status)
                VALUES ('shared-review', 1, 'ay1', 'PROFILE_VERIFICATION', 'Shared review', 'ACTIVE')
                """).update();
        jdbc.sql("""
                INSERT INTO student.student_review_items
                    (id, campaign_id, student_id, school_id, status,
                     verified_full_name, verified_admission_no, verified_class_section,
                     verified_roll_no, verified_father_name, verified_father_contact,
                     verified_address, verified_blood_group, correction_requested,
                     correction_notes, current_full_name, suggested_full_name, completed_at)
                VALUES
                    ('shared-review-1', 'shared-review', 1, 1, 'COMPLETED',
                     true, true, true, true, true, true, true, true, true, 'old',
                     'Old Student One', 'Suggested One', now()),
                    ('shared-review-2', 'shared-review', 2, 1, 'COMPLETED',
                     true, true, true, true, true, true, true, true, true, 'old',
                     'Old Student Two', 'Suggested Two', now())
                """).update();

        Map<String, Object> firstLinkBefore = linkSnapshot(1L, guardianId);
        Map<String, Object> siblingLinkBefore = linkSnapshot(2L, guardianId);
        List<String> consentRowsBefore = consentRows(guardianId);
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();

        repository.updateGuardian(1L, guardianId, Map.ofEntries(
                Map.entry("fullName", "Updated Shared Father"),
                Map.entry("phone", "9876543299"),
                Map.entry("email", "new@example.test"),
                Map.entry("relationship", "FATHER"),
                Map.entry("primary", false),
                Map.entry("contactVerified", true),
                Map.entry("receivesNotifications", false),
                Map.entry("canViewAcademic", false),
                Map.entry("canManageFees", true),
                Map.entry("pickupAuthorized", true),
                Map.entry("version", sharedGuardian.get("version")),
                Map.entry("linkVersion", sharedGuardian.get("linkVersion"))));

        assertThat(jdbc.sql("""
                        SELECT father_name || '|' || father_contact
                        FROM student.students WHERE id IN (1, 2) ORDER BY id
                        """).query(String.class).list())
                .containsExactly("Updated Shared Father|9876543299", "Updated Shared Father|9876543299");
        assertThat(jdbc.sql("""
                        SELECT father_name_matches AND father_contact_matches AND mother_name_matches
                        FROM student.guardian_legacy_parity WHERE student_id IN (1, 2) ORDER BY student_id
                        """).query(Boolean.class).list()).containsExactly(true, true);
        assertThat(jdbc.sql("SELECT contact_verified_at FROM student.guardians WHERE id = :guardianId")
                .param("guardianId", guardianId).query(OffsetDateTime.class).optional()).isEmpty();

        Map<String, Object> firstLinkAfter = linkSnapshot(1L, guardianId);
        assertThat(firstLinkAfter)
                .containsEntry("id", firstLinkBefore.get("id"))
                .containsEntry("guardianId", guardianId)
                .containsEntry("relationship", "FATHER")
                .containsEntry("primary", false)
                .containsEntry("receivesNotifications", false)
                .containsEntry("canViewAcademic", false)
                .containsEntry("canManageFees", true)
                .containsEntry("pickupAuthorized", true);
        assertThat(linkSnapshot(2L, guardianId)).isEqualTo(siblingLinkBefore);
        assertThat(consentRows(guardianId)).isEqualTo(consentRowsBefore);

        assertThat(jdbc.sql("""
                        SELECT student_id || '|' || status || '|' || verified_father_name || '|' ||
                               verified_father_contact || '|' || correction_requested || '|' ||
                               COALESCE(correction_notes, '') || '|' || current_full_name || '|' ||
                               (suggested_full_name IS NULL) || '|' || (completed_at IS NULL)
                        FROM student.student_review_items ORDER BY student_id
                        """).query(String.class).list())
                .containsExactly("1|PENDING|false|false|false||Student One|true|true",
                        "2|PENDING|false|false|false||Student Two|true|true");
        assertThat(jdbc.sql("""
                        SELECT aggregate_id || '|' || (payload->>'status')
                        FROM tenant_school.outbox_events
                        WHERE event_type = 'student-review-item.upserted.v1'
                        ORDER BY aggregate_id
                        """).query(String.class).list())
                .containsExactly("shared-review-1|PENDING", "shared-review-2|PENDING");
        assertThat(jdbc.sql("""
                        SELECT aggregate_id || '|' || ((payload::jsonb)->>'fatherName') || '|' ||
                               ((payload::jsonb)->>'parentContact')
                        FROM tenant_school.outbox_events
                        WHERE event_type = 'student.upserted.v1'
                        ORDER BY aggregate_id
                        """).query(String.class).list())
                .containsExactly("1|Updated Shared Father|9876543299",
                        "2|Updated Shared Father|9876543299");
    }

    @Test
    void sharedIdentityUpdateEmitsProjectionOnlyForStudentsWhoseLegacyValuesChanged() {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        Map<String, Object> created = repository.addGuardian(1L, Map.of(
                "fullName", "Projection Father",
                "phone", "9876543210",
                "relationship", "FATHER",
                "primary", true));
        Map<String, Object> guardian = guardians(created).getFirst();
        String guardianId = String.valueOf(guardian.get("id"));
        repository.addGuardian(2L, Map.of(
                "guardianId", guardianId,
                "relationship", "OTHER",
                "primary", true));
        Map<String, Object> siblingLinkBefore = linkSnapshot(2L, guardianId);
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();

        repository.updateGuardian(1L, guardianId, Map.ofEntries(
                Map.entry("fullName", "Projection Father Updated"),
                Map.entry("phone", "9876543299"),
                Map.entry("relationship", "FATHER"),
                Map.entry("primary", true),
                Map.entry("receivesNotifications", true),
                Map.entry("canViewAcademic", true),
                Map.entry("canManageFees", false),
                Map.entry("pickupAuthorized", false),
                Map.entry("version", guardian.get("version")),
                Map.entry("linkVersion", guardian.get("linkVersion"))));

        assertThat(jdbc.sql("""
                        SELECT aggregate_id FROM tenant_school.outbox_events
                        WHERE event_type = 'student.upserted.v1' ORDER BY aggregate_id
                        """).query(String.class).list()).containsExactly("1");
        assertThat(jdbc.sql("SELECT father_name FROM student.students WHERE id = 2")
                .query(String.class).optional()).isEmpty();
        assertThat(linkSnapshot(2L, guardianId)).isEqualTo(siblingLinkBefore);
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

    private static Map<String, Object> linkSnapshot(Long studentId, String guardianId) {
        return JdbcClient.create(dataSource).sql("""
                        SELECT id, guardian_id, relationship, is_primary, receives_notifications,
                               can_view_academic, can_manage_fees, pickup_authorized, version
                        FROM student.student_guardians
                        WHERE student_id = :studentId AND guardian_id = :guardianId
                        """)
                .param("studentId", studentId)
                .param("guardianId", guardianId)
                .query((rs, n) -> Map.<String, Object>ofEntries(
                        Map.entry("id", rs.getString("id")),
                        Map.entry("guardianId", rs.getString("guardian_id")),
                        Map.entry("relationship", rs.getString("relationship")),
                        Map.entry("primary", rs.getBoolean("is_primary")),
                        Map.entry("receivesNotifications", rs.getBoolean("receives_notifications")),
                        Map.entry("canViewAcademic", rs.getBoolean("can_view_academic")),
                        Map.entry("canManageFees", rs.getBoolean("can_manage_fees")),
                        Map.entry("pickupAuthorized", rs.getBoolean("pickup_authorized")),
                        Map.entry("version", rs.getLong("version"))))
                .single();
    }

    private static List<String> consentRows(String guardianId) {
        return JdbcClient.create(dataSource).sql("""
                        SELECT id || '|' || school_id || '|' || student_id || '|' || guardian_id || '|' ||
                               purpose || '|' || status || '|' || lawful_basis || '|' || notice_version || '|' ||
                               evidence_source || '|' || COALESCE(evidence_reference, '') || '|' ||
                               COALESCE(notes, '') || '|' || effective_at || '|' || recorded_at || '|' ||
                               COALESCE(idempotency_key, '')
                        FROM student.student_consent_events
                        WHERE guardian_id = :guardianId ORDER BY id
                        """)
                .param("guardianId", guardianId)
                .query(String.class)
                .list();
    }
}
