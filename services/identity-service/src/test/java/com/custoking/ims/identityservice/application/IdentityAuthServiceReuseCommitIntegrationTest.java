package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test proving that on the reuse-detection path the family revoke
 * (UPDATE auth_sessions SET status='REVOKED') AND the audit INSERT into
 * rbac_audit_log are committed to the database even though
 * {@link IdentityAuthService#refresh} subsequently throws a 401
 * {@link ResponseStatusException}.
 *
 * <p><strong>Without Fix 1</strong> ({@code @Transactional(noRollbackFor =
 * ResponseStatusException.class)} on the {@code refresh} method), the
 * class-level {@code @Transactional} rolls back the entire transaction on the
 * thrown RuntimeException. Both the {@code revokeFamily} UPDATE and the
 * {@code rbac_audit_log} INSERT are then silently discarded, leaving the
 * stolen token family still partially ACTIVE in the database.
 */
@SpringBootTest(
    properties = {
        // Minimum required secrets (values are test-only, never production).
        "app.jwt-secret=integration-test-jwt-secret-AAAABBBBCCCC1234",
        "identity.introspection-token=it-token",
        "identity.tenant-school.base-url=http://localhost:19999",
        "identity.tenant-school.token=it-ts-token"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class IdentityAuthServiceReuseCommitIntegrationTest {

    // Container is started by the Testcontainers JUnit 5 extension BEFORE the
    // Spring ApplicationContext is initialised, so @DynamicPropertySource below
    // can safely reference it.
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

    @Autowired IdentityAuthService identityAuthService;
    @Autowired JwtService          jwtService;

    @Test
    void reuseDetection_throws401_andPersistsFamilyRevokeAndAudit() throws Exception {
        String familyId  = "family-"  + System.nanoTime();
        String userEmail = "reuse-it-" + System.nanoTime() + "@example.com";

        // Build a real refresh token signed with the test JWT secret.
        AuthenticatedUserSnapshot snap = new AuthenticatedUserSnapshot(
                0L, "IT User", userEmail, "ADMIN", null, null, null, null);
        String rawToken  = jwtService.generateRefreshToken(snap);
        String tokenHash = sha256Hex(rawToken);

        // ── Seed the database via raw JDBC (outside any Spring TX) ───────────
        long userId;
        try (Connection c = DriverManager.getConnection(
                PG.getJdbcUrl(), "owner", "owner")) {
            c.setAutoCommit(true);

            // Insert the user.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity.app_users" +
                    "  (full_name, email, password_hash, role, created_at)" +
                    "  VALUES ('IT User', ?, 'bcrypt-placeholder', 'ADMIN', now())" +
                    "  RETURNING id")) {
                ps.setString(1, userEmail);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    userId = rs.getLong(1);
                }
            }

            // Insert a ROTATED auth_sessions row.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity.auth_sessions" +
                    "  (id, user_id, access_token_hash, refresh_token_hash," +
                    "   family_id, status, created_at, expires_at)" +
                    "  VALUES (?, ?, ?, ?, ?, 'ROTATED', now(), now() + interval '7 days')")) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setLong(2,   userId);
                ps.setString(3, "access-" + UUID.randomUUID());
                ps.setString(4, tokenHash);
                ps.setString(5, familyId);
                ps.executeUpdate();
            }
        }

        // Call refresh — must throw 401.
        assertThatThrownBy(() -> identityAuthService.refresh(rawToken))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reuse detected");

        // Verify DB state via a FRESH connection.
        try (Connection c = DriverManager.getConnection(
                PG.getJdbcUrl(), "owner", "owner")) {

            // Every session in the family must be REVOKED.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM identity.auth_sessions" +
                    "  WHERE family_id = ? AND status != 'REVOKED'")) {
                ps.setString(1, familyId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("All sessions in family must be REVOKED after reuse detection")
                            .isEqualTo(0);
                }
            }

            // An audit row with correlation_id = familyId must be committed.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM identity.rbac_audit_log" +
                    "  WHERE event_type = 'REFRESH_TOKEN_REUSE_DETECTED'" +
                    "    AND correlation_id = ?")) {
                ps.setString(1, familyId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("Audit row REFRESH_TOKEN_REUSE_DETECTED must be committed")
                            .isGreaterThanOrEqualTo(1);
                }
            }
        }
    }

    private static String sha256Hex(String input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
