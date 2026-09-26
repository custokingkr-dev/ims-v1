package com.custoking.ims.identityservice.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that OpenTelemetry tracing (via the Micrometer Tracing OTel bridge)
 * is wired into the identity-service application context, and that starting
 * a span makes {@code traceId} available in the SLF4J MDC so the JSON logs
 * can be trace-correlated (Cloud Logging format).
 *
 * <p>Before the tracing dependencies/config were added, no {@link Tracer}
 * bean existed in the context and this test failed with a
 * {@code NoSuchBeanDefinitionException}.
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
class TracingAutoConfigTest {

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

    @Autowired
    Tracer tracer;

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SessionActivityHealthReporter activity;

    @Test
    void passwordResetAndDisabledSessionsAreNotReportedAsActive() {
        Long user = jdbc.queryForObject("INSERT INTO identity.app_users (full_name,email,password_hash,role,created_at,credential_version) VALUES ('Activity Test','activity@example.invalid','unused','ADMIN',now(),1) RETURNING id", Long.class);
        for (int version : new int[]{0, 1}) {
            jdbc.update("INSERT INTO identity.auth_sessions (id,user_id,access_token_hash,refresh_token_hash,family_id,status,created_at,expires_at,credential_version) VALUES (?,?,?,?,?,'ACTIVE',now(),now()+interval '1 day',?)",
                    "activity-" + version, user, "access-" + version, "refresh-" + version, "family-" + version, version);
        }
        assertThat(activity.snapshot().get("activeSessions")).isEqualTo(1L);
        assertThat(activity.snapshot().get("activeUsers")).isEqualTo(1L);
        jdbc.update("UPDATE identity.app_users SET deleted_at=now() WHERE id=?", user);
        assertThat(activity.snapshot().get("activeSessions")).isEqualTo(0L);
    }

    @Test
    void tracerBeanIsPresent_andSpanPropagatesTraceIdIntoMdc() {
        assertThat(tracer).isNotNull();

        Span span = tracer.nextSpan().name("tracing-autoconfig-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String traceIdFromMdc = MDC.get("traceId");
            assertThat(traceIdFromMdc).isNotNull().isNotBlank();
            assertThat(traceIdFromMdc).isEqualTo(span.context().traceId());
        } finally {
            span.end();
        }
    }
}
