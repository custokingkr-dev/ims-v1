package com.custoking.ims.billingservice.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that {@link OutboxRelay#publishBatch()}:
 *
 * <ul>
 *   <li>publishes every unpublished {@code billing.outbox_events} row as a
 *       canonical {@link EventEnvelope} (eventId = row id) via the injected
 *       {@link DomainEventPublisher};</li>
 *   <li>marks each row published (publish-then-mark, so at-least-once
 *       semantics hold on a crash between the two);</li>
 *   <li>does NOT re-publish already-published rows on a subsequent call.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayIntegrationTest {

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
        // billing-service runs with @EnableScheduling, so OutboxRelay.runScheduled()
        // is registered against the app's real fixed-delay timer inside this test's
        // Spring context too. On CI (different timing) that scheduled run can race
        // the test's manual publishBatch() call and publish a seeded row first,
        // making the manual call see fewer unpublished rows than expected. Push the
        // first scheduled firing far beyond this test's lifetime so ONLY the manual
        // publishBatch() calls below touch the outbox.
        r.add("billing.outbox.relay.fixed-delay-ms", () -> "3600000");
    }

    @TestConfiguration
    static class CapturingPublisherConfig {
        @Bean
        @Primary
        CapturingDomainEventPublisher domainEventPublisher() {
            return new CapturingDomainEventPublisher();
        }
    }

    static class CapturingDomainEventPublisher implements DomainEventPublisher {
        final List<EventEnvelope> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(EventEnvelope envelope) {
            published.add(envelope);
        }
    }

    @Autowired OutboxRelay relay;
    @Autowired CapturingDomainEventPublisher capturingPublisher;

    private String rowIdA;
    private String rowIdB;

    @BeforeEach
    void seedUnpublishedRows() throws Exception {
        capturingPublisher.published.clear();
        rowIdA = insertOutboxEvent("InvoiceUpserted:A-" + System.nanoTime(), 11L);
        rowIdB = insertOutboxEvent("InvoiceUpserted:B-" + System.nanoTime(), 22L);
    }

    @Test
    void publishBatch_publishesEnvelopesForBothRows_andMarksThemPublished() throws Exception {
        int published = relay.publishBatch();

        assertThat(published).isEqualTo(2);
        assertThat(capturingPublisher.published).hasSize(2);

        for (EventEnvelope envelope : capturingPublisher.published) {
            assertThat(envelope.schemaVersion()).isEqualTo("ims.event-envelope.v1");
            assertThat(envelope.eventType()).isEqualTo("billing.invoice-upserted.v1");
            assertThat(envelope.aggregateType()).isEqualTo("SuperadminInvoice");
            assertThat(envelope.eventId()).isIn(rowIdA, rowIdB);
            assertThat(envelope.payloadJson()).contains(envelope.aggregateId());
        }

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner")) {
            assertThat(publishedAt(c, rowIdA)).isNotNull();
            assertThat(publishedAt(c, rowIdB)).isNotNull();
        }
    }

    @Test
    void publishBatch_secondCall_publishesNothing_noDuplicates() {
        int firstRun = relay.publishBatch();
        assertThat(firstRun).isEqualTo(2);

        capturingPublisher.published.clear();

        int secondRun = relay.publishBatch();

        assertThat(secondRun).isEqualTo(0);
        assertThat(capturingPublisher.published).isEmpty();
    }

    private String insertOutboxEvent(String aggregateId, long schoolId) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO billing.outbox_events " +
                     "(event_key, event_type, aggregate_type, aggregate_id, school_id, payload) " +
                     "VALUES (?, 'billing.invoice-upserted.v1', 'SuperadminInvoice', ?, ?, ?::jsonb) " +
                     "RETURNING id::text")) {
            ps.setString(1, aggregateId);
            ps.setString(2, aggregateId);
            ps.setLong(3, schoolId);
            ps.setString(4, "{\"id\":\"" + aggregateId + "\"}");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private Timestamp publishedAt(Connection c, String id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT published_at FROM billing.outbox_events WHERE id = ?::uuid")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp("published_at");
            }
        }
    }
}
