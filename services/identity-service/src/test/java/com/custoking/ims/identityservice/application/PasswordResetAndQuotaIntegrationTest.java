package com.custoking.ims.identityservice.application;

import com.custoking.ims.identityservice.persistence.PasswordResetRepository;
import com.custoking.ims.identityservice.persistence.SharedQuotaRepository;
import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "app.jwt-secret=integration-test-jwt-secret-AAAABBBBCCCC1234", "identity.introspection-token=it-token",
    "identity.tenant-school.base-url=http://localhost:19999", "identity.tenant-school.token=it-ts-token"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class PasswordResetAndQuotaIntegrationTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl); r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword); r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername); r.add("spring.flyway.password", PG::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired SharedQuotaRepository quotas;
    @Autowired PasswordResetRepository resets;
    @Autowired PasswordEncoder encoder;
    @Autowired IdentityAuthService auth;
    @Autowired AuthAbuseProtection abuse;
    @Autowired UserDirectoryReadRepository directory;

    private String user() {
        String email = "reset-" + UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO identity.app_users (full_name,email,password_hash,role,created_at) VALUES ('Reset Test',?,?,'ADMIN',now())",
                email, encoder.encode("original-password"));
        return email;
    }

    @Test void resetIsSingleUseAndInvalidatesAccessAndRefreshWithoutDependingOnSessionWriteTiming() {
        String email = user(), token = "A".repeat(43);
        var login = auth.login(new IdentityAuthService.LoginRequest(email, "original-password"));
        resets.issue(email, token);
        assertThat(auth.introspect(login.authResponse().accessToken()).active()).isTrue();
        assertThat(resets.confirm(token, "replacement-password", encoder)).isTrue();
        assertThat(resets.confirm(token, "another-password", encoder)).isFalse();
        assertThat(auth.introspect(login.authResponse().accessToken()).active()).isFalse();
        assertThatThrownBy(() -> auth.refresh(login.refreshToken())).hasMessageContaining("401");
        assertThatThrownBy(() -> auth.login(new IdentityAuthService.LoginRequest(email, "original-password"))).hasMessageContaining("401");
        assertThat(auth.login(new IdentityAuthService.LoginRequest(email, "replacement-password"))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.password_reset_tokens WHERE token_hash = ?", Integer.class, token)).isZero();
    }

    @Test void expiredAndDisabledAccountsCannotResetAndNewlyIssuedTokensForOldVersionCannotBeUsed() {
        String email = user(), token = "B".repeat(43);
        resets.issue(email, token);
        jdbc.update("UPDATE identity.password_reset_tokens SET expires_at=now()-interval '1 minute'");
        assertThat(resets.confirm(token, "replacement-password", encoder)).isFalse();
        String disabledToken = "C".repeat(43);
        resets.issue(email, disabledToken);
        jdbc.update("UPDATE identity.app_users SET deleted_at=now() WHERE email=?", email);
        assertThat(resets.confirm(disabledToken, "replacement-password", encoder)).isFalse();
        assertThat(resets.issue(email, "D".repeat(43))).isEmpty();
        assertThat(resets.issue("not-present@example.invalid", "E".repeat(43))).isEmpty();
    }

    @Test void concurrentDifferentLinksCanChangeAnAccountOnlyOnce() throws Exception {
        String email = user();
        String a = "F".repeat(43), b = "G".repeat(43);
        resets.issue(email, a); resets.issue(email, b);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return resets.confirm(a, "first-password-123", encoder); });
            var second = executor.submit(() -> { start.await(); return resets.confirm(b, "second-password-123", encoder); });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS) ^ second.get(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void sharedQuotaAllowsExactlyTheBudgetAcrossConcurrentCallersAndExpiryResetsIt() throws Exception {
        String key = "test:" + UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 40).mapToObj(i -> executor.submit(() -> quotas.consume(key, 7, 60))).toList();
            int allowed = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(7);
        }
        assertThat(quotas.consume(key, 7, 60)).isFalse();
        jdbc.update("UPDATE identity.request_quotas SET expires_at=now()-interval '1 second' WHERE quota_key=?", key);
        assertThat(quotas.consume(key, 7, 60)).isTrue();
    }

    @Test void loginFailuresSpendAnAccountBudgetIndependentOfCredentialsAndTokenRotation() {
        String email = user();
        for (int i = 0; i < 10; i++) {
            abuse.login(email);
            assertThatThrownBy(() -> auth.login(new IdentityAuthService.LoginRequest(email, "wrong-password"))).hasMessageContaining("401");
        }
        assertThatThrownBy(() -> abuse.login(email.toUpperCase())).isInstanceOf(AuthAbuseProtection.QuotaExceeded.class);
    }

    @Test void deliveryIntentSurvivesWorkerFailureAndOnlyOneWorkerClaimsIt() {
        String email = user();
        jdbc.update("DELETE FROM identity.password_reset_deliveries");
        resets.enqueue(email); resets.enqueue("absent@example.invalid");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.password_reset_deliveries", Integer.class)).isEqualTo(1);
        var job = resets.claim().orElseThrow();
        assertThat(resets.claim()).isEmpty();
        assertThat(resets.issueForDelivery(job, "H".repeat(43))).contains(email);
        resets.finishDelivery(job, false);
        assertThat(resets.claim()).isEmpty();
        jdbc.update("UPDATE identity.password_reset_deliveries SET next_attempt_at=now()-interval '1 second'");
        var retried = resets.claim().orElseThrow();
        resets.finishDelivery(retried, true);
        assertThat(resets.claim()).isEmpty();
    }

    @Test void administratorResetInvalidatesOutstandingLinksAndCredentialsIssuedAtAnOldVersion() {
        String email = user(), token = "I".repeat(43);
        long userId = jdbc.queryForObject("SELECT id FROM identity.app_users WHERE email=?", Long.class, email);
        var login = auth.login(new IdentityAuthService.LoginRequest(email, "original-password"));
        resets.issue(email, token);
        directory.resetPassword(userId, "administrator-new-password", null, "admin@example.invalid");
        assertThat(jdbc.queryForObject("SELECT credential_version FROM identity.app_users WHERE id=?", Long.class, userId)).isEqualTo(1L);
        assertThat(resets.confirm(token, "stale-link-password", encoder)).isFalse();
        assertThat(auth.introspect(login.authResponse().accessToken()).active()).isFalse();
        assertThatThrownBy(() -> auth.refresh(login.refreshToken())).hasMessageContaining("401");
        assertThat(auth.login(new IdentityAuthService.LoginRequest(email, "administrator-new-password"))).isNotNull();
    }

    @Test void emailTransferInvalidatesOldAddressLinksButUnchangedEmailAndNameEditsDoNot() {
        String email = user(), token = "J".repeat(43);
        long userId = jdbc.queryForObject("SELECT id FROM identity.app_users WHERE email=?", Long.class, email);
        resets.issue(email, token);
        directory.updateProfile(userId, "Updated Name", email.toUpperCase(), null, "admin@example.invalid");
        assertThat(jdbc.queryForObject("SELECT credential_version FROM identity.app_users WHERE id=?", Long.class, userId)).isZero();
        directory.updateProfile(userId, "Another Name", null, null, "admin@example.invalid");
        assertThat(jdbc.queryForObject("SELECT credential_version FROM identity.app_users WHERE id=?", Long.class, userId)).isZero();
        directory.updateProfile(userId, null, "new-" + email, null, "admin@example.invalid");
        assertThat(jdbc.queryForObject("SELECT credential_version FROM identity.app_users WHERE id=?", Long.class, userId)).isEqualTo(1L);
        assertThat(resets.confirm(token, "stale-address-password", encoder)).isFalse();
    }

    @Test void reclaimedLeaseFencesTheOldWorkerFromIssuingTokensOrOverwritingTheNewWorker() {
        jdbc.update("DELETE FROM identity.password_reset_deliveries");
        String email = user(); resets.enqueue(email);
        var first = resets.claim().orElseThrow();
        jdbc.update("UPDATE identity.password_reset_deliveries SET lease_until=now()-interval '1 second' WHERE id=?", first.id());
        var second = resets.claim().orElseThrow();
        assertThat(second.attempt()).isGreaterThan(first.attempt());
        assertThat(resets.issueForDelivery(first, "K".repeat(43))).isEmpty();
        resets.finishDelivery(first, false);
        assertThat(resets.claim()).isEmpty(); // The current claim remains leased.
        assertThat(resets.issueForDelivery(second, "L".repeat(43))).contains(email);
        resets.finishDelivery(second, true);
        resets.finishDelivery(first, false);
        assertThat(jdbc.queryForObject("SELECT status FROM identity.password_reset_deliveries WHERE id=?", String.class, first.id())).isEqualTo("SENT");
        assertThat(resets.issueForDelivery(second, "M".repeat(43))).isEmpty();
    }

    @Test void expiredIntentOrLeaseCannotMintNewTokensOrAcknowledgeDelivery() {
        jdbc.update("DELETE FROM identity.password_reset_deliveries");
        String email = user(); resets.enqueue(email);
        var job = resets.claim().orElseThrow();
        jdbc.update("UPDATE identity.password_reset_deliveries SET created_at=now()-interval '31 minutes' WHERE id=?", job.id());
        assertThat(resets.issueForDelivery(job, "N".repeat(43))).isEmpty();
        resets.finishDelivery(job, true);
        assertThat(jdbc.queryForObject("SELECT status FROM identity.password_reset_deliveries WHERE id=?", String.class, job.id())).isEqualTo("PENDING");
        jdbc.update("UPDATE identity.password_reset_deliveries SET created_at=now(), lease_until=now()-interval '1 second' WHERE id=?", job.id());
        assertThat(resets.issueForDelivery(job, "O".repeat(43))).isEmpty();
        resets.finishDelivery(job, true);
        assertThat(jdbc.queryForObject("SELECT status FROM identity.password_reset_deliveries WHERE id=?", String.class, job.id())).isEqualTo("PENDING");
    }
}
