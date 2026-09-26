package com.custoking.ims.identityservice.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Login clears expired sessions before issuing a new one. A derived delete makes Hibernate load
 * each expired row and delete it individually, so two logins racing over the same expired rows make
 * the slower one delete zero rows and fail with an optimistic-locking error — surfacing as HTTP 500
 * on a perfectly valid login. Observed on dev 2026-09-23.
 */
@SpringBootTest(
    properties = {
        "app.jwt-secret=integration-test-jwt-secret-AAAABBBBCCCC1234",
        "identity.introspection-token=it-token",
        "identity.tenant-school.base-url=http://localhost:19999",
        "identity.tenant-school.token=it-ts-token"
    }
)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class AuthSessionExpiryCleanupIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired AuthSessionRepository sessions;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;

    private long seedUser() {
        return jdbc.sql("""
                INSERT INTO identity.app_users (full_name, email, password_hash, role, created_at)
                VALUES ('Expiry Test', :email, 'x', 'ADMIN', now()) RETURNING id
                """).param("email", UUID.randomUUID() + "@expiry.test").query(Long.class).single();
    }

    private void seedSession(long userId, OffsetDateTime expiresAt) {
        jdbc.sql("""
                INSERT INTO identity.auth_sessions (id, user_id, family_id, refresh_token_hash,
                    access_token_hash, status, expires_at, created_at)
                VALUES (:id, :user, :family, :hash, :hash, 'ACTIVE', :expires, now())
                """)
                .param("id", UUID.randomUUID().toString())
                .param("user", userId)
                .param("family", UUID.randomUUID().toString())
                .param("hash", UUID.randomUUID().toString())
                .param("expires", expiresAt)
                .update();
    }

    @Test
    void concurrentLoginsClearingTheSameExpiredSessionsDoNotFail() throws Exception {
        long userId = seedUser();
        var past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
        var future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        for (int i = 0; i < 40; i++) seedSession(userId, past);
        seedSession(userId, future);

        // Two logins racing over the same expired rows, which is exactly what two Cloud Run
        // instances do when traffic resumes after an idle period.
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cutoff = OffsetDateTime.now(ZoneOffset.UTC);
            // Each login runs in its own transaction, as the real login path does.
            Callable<Object> clear = () -> {
                var template = new TransactionTemplate(txManager);
                try {
                    return template.execute(status -> sessions.deleteByExpiresAtBefore(cutoff));
                } catch (RuntimeException e) {
                    return e;
                }
            };
            var results = new ArrayList<Object>();
            for (var future2 : pool.invokeAll(java.util.List.of(clear, clear))) results.add(future2.get());
            assertTrue(results.stream().noneMatch(r -> r instanceof RuntimeException),
                    "clearing expired sessions concurrently must not fail login: " + results);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0L, jdbc.sql("SELECT count(*) FROM identity.auth_sessions WHERE user_id = :u AND expires_at < now()")
                .param("u", userId).query(Long.class).single(), "expired sessions are removed");
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM identity.auth_sessions WHERE user_id = :u AND expires_at > now()")
                .param("u", userId).query(Long.class).single(), "a live session is left alone");
    }
}
