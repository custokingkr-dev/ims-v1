package com.custoking.ims.identityservice.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-integration coverage for {@link RbacCommandRepository#syncOperatorSchools}.
 *
 * <p>The controller-level {@code RbacAuthorizationTest} mocks the repository, so the
 * business guard that lives INSIDE the repository is never exercised there. That guard
 * — "the target user must be an OPERATIONS user, otherwise 400" — is the F3 footgun
 * protection from the operator school-scoping work: without it this endpoint would
 * grant OPERATIONS role_permissions to an arbitrary user. This test runs the REAL
 * repository against a real (Flyway-migrated) Postgres to prove:
 * <ol>
 *   <li>a non-OPERATIONS target is rejected with 400 and NOTHING is written, and</li>
 *   <li>an OPERATIONS target's school set is synced — the assign/revoke diff.</li>
 * </ol>
 */
@SpringBootTest(properties = {
    // Minimum required secrets (test-only values, never production).
    "app.jwt-secret=integration-test-jwt-secret-AAAABBBBCCCC1234",
    "identity.introspection-token=it-token",
    "identity.tenant-school.base-url=http://localhost:19999",
    "identity.tenant-school.token=it-ts-token"
})
@Testcontainers(disabledWithoutDocker = true)
class RbacOperatorSchoolsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16")
                    .withUsername("owner")
                    .withPassword("owner");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.flyway.url",      PG::getJdbcUrl);
        r.add("spring.flyway.user",     PG::getUsername);
        r.add("spring.flyway.password", PG::getPassword);
    }

    @Autowired RbacCommandRepository rbac;

    @Test
    void nonOperatorTarget_isRejectedWith400_andWritesNothing() throws Exception {
        long adminUserId = insertUser("admin-" + System.nanoTime() + "@example.com", "ADMIN");

        assertThatThrownBy(() -> rbac.syncOperatorSchools(adminUserId, List.of(1L, 2L), 999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("not an operator");

        // The guard rejects BEFORE any assignment is written.
        assertThat(activeOperatorSchoolIds(adminUserId)).isEmpty();
    }

    @Test
    void unknownUser_isRejectedWith404() {
        assertThatThrownBy(() -> rbac.syncOperatorSchools(9_999_999L, List.of(1L), 999L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void operatorTarget_syncsSchoolSet_assignThenRevokeDiff() throws Exception {
        ensureOperationsRole();
        long opUserId = insertUser("op-" + System.nanoTime() + "@example.com", "OPERATIONS");

        // Initial set {1,2} — both assigned.
        rbac.syncOperatorSchools(opUserId, List.of(1L, 2L), 999L);
        assertThat(activeOperatorSchoolIds(opUserId)).containsExactlyInAnyOrder(1L, 2L);

        // New set {2,3} — school 1 revoked, 2 kept, 3 added.
        rbac.syncOperatorSchools(opUserId, List.of(2L, 3L), 999L);
        assertThat(activeOperatorSchoolIds(opUserId)).containsExactlyInAnyOrder(2L, 3L);

        // Empty set — all revoked (fail-closed: no schools == no access).
        rbac.syncOperatorSchools(opUserId, List.of(), 999L);
        assertThat(activeOperatorSchoolIds(opUserId)).isEmpty();
    }

    // ── seeding / assertion helpers (raw JDBC, outside any Spring TX) ─────────

    private static long insertUser(String email, String role) throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity.app_users (full_name, email, password_hash, role, created_at)" +
                     " VALUES (?, ?, 'bcrypt-placeholder', ?, now()) RETURNING id")) {
            ps.setString(1, "IT " + role);
            ps.setString(2, email);
            ps.setString(3, role);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void ensureOperationsRole() throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO identity.roles (name) VALUES ('OPERATIONS') ON CONFLICT (name) DO NOTHING")) {
            ps.executeUpdate();
        }
    }

    private static List<Long> activeOperatorSchoolIds(long userId) throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ura.school_id" +
                     "  FROM identity.user_role_assignments ura" +
                     "  JOIN identity.roles r ON r.id = ura.role_id" +
                     " WHERE ura.user_id = ?" +
                     "   AND UPPER(r.name) = 'OPERATIONS'" +
                     "   AND ura.active = true" +
                     "   AND ura.revoked_at IS NULL" +
                     "   AND ura.school_id IS NOT NULL")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getLong(1));
                return out;
            }
        }
    }

    private static Connection conn() throws Exception {
        Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
        c.setAutoCommit(true);
        return c;
    }
}
