package com.custoking.ims.identityservice.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Small local HTTP + PostgreSQL baseline, not a Cloud Run capacity certification. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.jwt-secret=local-benchmark-secret-AAAABBBBCCCC1234", "identity.introspection-token=local-benchmark-internal",
    "identity.tenant-school.base-url=http://localhost:19999", "identity.tenant-school.token=local-test-only",
    "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "readiness.benchmark", matches = "true")
class AuthoritativeAuthLocalBenchmarkTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl); r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword); r.add("spring.flyway.url", PG::getJdbcUrl);
        r.add("spring.flyway.user", PG::getUsername); r.add("spring.flyway.password", PG::getPassword);
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired IdentityAuthService auth;
    // Isolate auth/database cost from an absent external telemetry collector.
    @MockitoBean BatchSpanProcessor spanProcessor;

    @Test @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void measuresAuthoritativeSessionPermissionAndSharedQuotaHttpRoundTrips() throws Exception {
        when(spanProcessor.forceFlush()).thenReturn(CompletableResultCode.ofSuccess());
        String email = "auth-baseline@example.invalid";
        jdbc.update("INSERT INTO identity.app_users (full_name,email,password_hash,role,branch_id,created_at) VALUES ('Synthetic Baseline',?,?,'ADMIN',7,now())",
                email, encoder.encode("synthetic-benchmark-password"));
        var login = auth.login(new IdentityAuthService.LoginRequest(email, "synthetic-benchmark-password"));
        String body = "{\"token\":\"" + login.authResponse().accessToken() + "\",\"method\":\"GET\",\"path\":\"/api/v1/students\"}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/introspect"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("X-Identity-Service-Token", "local-benchmark-internal").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            for (int i = 0; i < 10; i++) roundTrip(http, request);
            var samples = new ArrayList<Double>();
            long started = System.nanoTime();
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<Future<Double>>();
                for (int i = 0; i < 80; i++) futures.add(executor.submit(() -> roundTrip(http, request)));
                for (var future : futures) samples.add(future.get(30, TimeUnit.SECONDS));
            }
            double durationMs = (System.nanoTime() - started) / 1_000_000.0;
            Collections.sort(samples);
            assertThat(jdbc.queryForObject("SELECT used FROM identity.request_quotas WHERE quota_key='read:school:7'", Integer.class)).isEqualTo(90);
            System.out.printf(Locale.ROOT,
                    "IMS_LOCAL_AUTH_BASELINE|{\"requests\":80,\"warmup\":10,\"concurrency\":4,\"successes\":80,\"p50Ms\":%.2f,\"p95Ms\":%.2f,\"maxMs\":%.2f,\"durationMs\":%.2f,\"environment\":\"localhost HTTP + disposable PostgreSQL16; telemetry exporter excluded\"}%n",
                    samples.get(39), samples.get(75), samples.getLast(), durationMs);
        }
    }
    private double roundTrip(HttpClient http, HttpRequest request) throws Exception {
        long started = System.nanoTime();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"active\":true");
        return (System.nanoTime() - started) / 1_000_000.0;
    }
}
