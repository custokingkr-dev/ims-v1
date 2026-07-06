package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.outbox.OutboxWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that {@link FirefightingReadRepository}'s {@code @Transactional} mutation
 * methods append a {@code firefighting.outbox_events} row IN THE SAME transaction
 * as the domain write — the whole point of the outbox pattern (SP6). Mirrors the
 * shape of school-core-service's outbox-writer proofs, but exercises the real
 * repository/table rather than a synthetic insert.
 */
class FirefightingOutboxIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;

    FirefightingReadRepository repository;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("firefighting")
                .defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting")
                .load()
                .migrate();
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("owner");
        pool.setPassword("owner");
        pool.setSchema("firefighting");
        pool.setMaximumPoolSize(3);
        dataSource = pool;
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) {
            PG.stop();
        }
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM firefighting.outbox_events");
            st.execute("DELETE FROM firefighting.ff_quotations");
            st.execute("DELETE FROM firefighting.firefighting_requests");
        }
        OutboxWriter outboxWriter = new OutboxWriter(jdbc, new ObjectMapper(), "firefighting");
        repository = new FirefightingReadRepository(jdbc, outboxWriter);
    }

    private long countOutboxRows(String code) {
        return jdbc.sql("SELECT count(*) FROM firefighting.outbox_events WHERE aggregate_id = :code")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private String latestPayload(String code) {
        return jdbc.sql("""
                        SELECT payload::text FROM firefighting.outbox_events
                        WHERE aggregate_id = :code ORDER BY id DESC LIMIT 1
                        """)
                .param("code", code)
                .query(String.class)
                .single();
    }

    @Test
    void createRequest_emitsOutboxRow_inSameTransaction() {
        Map<String, Object> request = new HashMap<>();
        request.put("schoolId", 10L);
        request.put("title", "New fire extinguisher");
        request.put("category", "Other");
        request.put("urgency", "HIGH");
        request.put("estimatedBudget", 5000L);
        request.put("actorEmail", "admin@demo.custoking.com");

        Map<String, Object> created = repository.createRequest(request);
        String code = (String) created.get("code");

        assertEquals(1, countOutboxRows(code));
        String payload = latestPayload(code);
        assertTrue(payload.contains("\"status\": \"DRAFT\""), "payload should carry status: " + payload);
        assertTrue(payload.contains("\"schoolId\": 10"), "payload should carry schoolId: " + payload);

        long outboxCount = jdbc.sql("SELECT count(*) FROM firefighting.outbox_events WHERE event_type = 'firefighting-request.upserted.v1'")
                .query(Long.class)
                .single();
        assertEquals(1, outboxCount);
    }

    @Test
    void fullLifecycle_eachMutation_emitsAnAdditionalOutboxRow() {
        Map<String, Object> request = new HashMap<>();
        request.put("schoolId", 20L);
        request.put("title", "Playground repair");
        request.put("category", "Sports & playground");
        request.put("urgency", "MEDIUM");
        request.put("estimatedBudget", 8000L);
        Map<String, Object> created = repository.createRequest(request);
        String code = (String) created.get("code");
        assertEquals(1, countOutboxRows(code));

        repository.submit(code);
        assertEquals(2, countOutboxRows(code));

        repository.approveBursar(code, Map.of("note", "ok"));
        assertEquals(3, countOutboxRows(code));

        repository.approvePrincipal(code, Map.of());
        assertEquals(4, countOutboxRows(code));

        repository.approveCustoking(code);
        assertEquals(5, countOutboxRows(code));

        Map<String, Object> paidRequest = new HashMap<>();
        paidRequest.put("notes", "paid via bank transfer");
        repository.markVendorPaid(code, paidRequest);
        assertEquals(6, countOutboxRows(code));

        String payload = latestPayload(code);
        assertTrue(payload.contains("\"code\": \"" + code + "\""), "payload should carry code: " + payload);
    }
}
