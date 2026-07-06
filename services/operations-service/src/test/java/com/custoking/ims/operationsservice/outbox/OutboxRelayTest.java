package com.custoking.ims.operationsservice.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@link OutboxRelay#publishBatch()}:
 *
 * <ul>
 *   <li>publishes every unpublished {@code firefighting.outbox_events} row as
 *       a canonical {@link EventEnvelope} (eventId = row id) via the injected
 *       {@link DomainEventPublisher};</li>
 *   <li>marks each row published (publish-then-mark, so at-least-once
 *       semantics hold on a crash between the two);</li>
 *   <li>does NOT re-publish already-published rows on a subsequent call.</li>
 * </ul>
 *
 * <p>Mirrors {@code school-core-service}'s {@code OutboxRelayTest} (itself mirroring
 * billing-service's {@code OutboxRelayIntegrationTest}): plain Testcontainers + Flyway
 * bootstrap, no full {@code @SpringBootTest} context.
 */
class OutboxRelayTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;

    static class CapturingDomainEventPublisher implements DomainEventPublisher {
        final List<EventEnvelope> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }

    CapturingDomainEventPublisher capturingPublisher;
    OutboxRelay relay;

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
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
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
        }
        capturingPublisher = new CapturingDomainEventPublisher();
        relay = new OutboxRelay(jdbc, capturingPublisher, "firefighting", 100);
    }

    @Test
    void publishBatch_publishesEnvelopesForBothRows_andMarksThemPublished() throws Exception {
        long idA = insertOutboxEvent("FF-" + System.nanoTime(), 11L);
        long idB = insertOutboxEvent("FF-" + System.nanoTime(), 22L);

        int published = relay.publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(capturingPublisher.published).hasSize(2);

        for (EventEnvelope envelope : capturingPublisher.published) {
            assertThat(envelope.schemaVersion()).isEqualTo("ims.event-envelope.v1");
            assertThat(envelope.eventId()).isIn("operations:" + idA, "operations:" + idB);
            assertThat(envelope.eventId()).startsWith("operations:");
            assertThat(envelope.payloadJson()).contains(envelope.aggregateId());
            assertThat(envelope.eventType()).isEqualTo("firefighting-request.upserted.v1");
        }

        assertThat(publishedAt(idA)).isNotNull();
        assertThat(publishedAt(idB)).isNotNull();
    }

    @Test
    void publishBatch_secondCall_publishesNothing_noDuplicates() throws Exception {
        insertOutboxEvent("FF-" + System.nanoTime(), 33L);
        insertOutboxEvent("FF-" + System.nanoTime(), 44L);

        int firstRun = relay.publishBatch();
        assertThat(firstRun).isEqualTo(2);

        capturingPublisher.published.clear();

        int secondRun = relay.publishBatch();

        assertThat(secondRun).isEqualTo(0);
        assertThat(capturingPublisher.published).isEmpty();
    }

    private long insertOutboxEvent(String code, long schoolId) throws Exception {
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO firefighting.outbox_events " +
                             "(event_key, event_type, aggregate_type, aggregate_id, school_id, payload) " +
                             "VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id")) {
            ps.setString(1, "FirefightingRequestUpserted:" + code);
            ps.setString(2, "firefighting-request.upserted.v1");
            ps.setString(3, "FirefightingRequest");
            ps.setString(4, code);
            ps.setLong(5, schoolId);
            ps.setString(6, "{\"code\":\"" + code + "\"}");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Timestamp publishedAt(long id) {
        return jdbc.sql("SELECT published_at FROM firefighting.outbox_events WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> rs.getTimestamp("published_at"))
                .single();
    }
}
