package com.custoking.ims.billingservice.outbox;

import com.custoking.ims.billingservice.application.BillingInvoiceService;
import com.custoking.ims.billingservice.persistence.BillingInvoiceRepository.InvoiceRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that {@link OutboxWriter} appends {@code billing.outbox_events} rows
 * IN THE SAME transaction as the invoice write performed by
 * {@link BillingInvoiceService#create(Map)}:
 *
 * <ul>
 *   <li>on commit, both the invoice row AND exactly one outbox row exist;</li>
 *   <li>on a forced rollback of the enclosing transaction, NEITHER the
 *       invoice NOR the outbox row persist.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxWriterIntegrationTest {

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
        // Same scheduler race as OutboxRelayIntegrationTest: @EnableScheduling means
        // OutboxRelay.runScheduled() is live in this context too, and
        // create_commitsInvoiceAndOutboxEventAtomically() asserts published_at IS
        // NULL for a freshly-written outbox row. A scheduled relay firing between
        // the write and the assertion would publish the row and flip that
        // assertion.
        //
        // IMPORTANT: fixedDelay alone does NOT delay the FIRST firing (Spring's
        // initialDelay defaults to 0), so the scheduled task still fires almost
        // immediately at startup regardless of fixed-delay-ms. Set
        // initial-delay-ms far beyond this test's lifetime so it never fires
        // during the test; keep the large fixed-delay too as a second line of
        // defense.
        r.add("billing.outbox.relay.fixed-delay-ms", () -> "3600000");
        r.add("billing.outbox.relay.initial-delay-ms", () -> "3600000");
    }

    @Autowired BillingInvoiceService invoiceService;
    @Autowired OutboxWriter outboxWriter;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void create_commitsInvoiceAndOutboxEventAtomically() throws Exception {
        Map<String, Object> request = Map.of(
                "orderRef", "ORD-" + System.nanoTime(),
                "school", "Test School",
                "schoolId", 42L,
                "description", "Annual plan",
                "qty", 1,
                "rate", 1000L);

        InvoiceRow row = invoiceService.create(request);

        // Task 3: the minted invoice id must carry the CURRENT year (not a hardcoded
        // literal) so invoices minted in future years still read INV-<current-year>-...
        assertThat(row.id())
                .as("invoice id must be prefixed with the current year")
                .startsWith("INV-" + java.time.Year.now().getValue() + "-0");

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner")) {
            assertThat(countInvoices(c, row.id()))
                    .as("invoice row must exist after commit")
                    .isEqualTo(1);
            assertThat(countOutboxEvents(c, row.id()))
                    .as("exactly one outbox row must exist after commit")
                    .isEqualTo(1);

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT event_type, aggregate_type, published_at, payload::text AS payload_text " +
                    "FROM billing.outbox_events WHERE aggregate_id = ?")) {
                ps.setString(1, row.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("event_type")).isEqualTo("billing.invoice-upserted.v1");
                    assertThat(rs.getString("aggregate_type")).isEqualTo("SuperadminInvoice");
                    rs.getTimestamp("published_at");
                    assertThat(rs.wasNull()).as("published_at must be NULL for a fresh event").isTrue();

                    // Task 4b: the outbox payload must carry the FULL invoice (all 15 columns),
                    // not just {id, schoolId, status, total} — the reporting projection now
                    // needs the wider shape to back the invoices() list endpoint too.
                    // Postgres re-serializes jsonb text with a space after each colon, so match
                    // loosely on key/value presence rather than the exact Jackson-written form.
                    String payloadJson = rs.getString("payload_text");
                    assertThat(payloadJson)
                            .contains("\"id\"").contains(row.id())
                            .contains("\"orderRef\"").contains(row.orderRef())
                            .contains("\"school\"").contains(row.school())
                            .contains("\"schoolId\"").contains(String.valueOf(row.schoolId()))
                            .contains("\"description\"").contains(row.description())
                            .contains("\"qty\"").contains(String.valueOf(row.qty()))
                            .contains("\"rate\"").contains(String.valueOf(row.rate()))
                            .contains("\"amount\"").contains(String.valueOf(row.amount()))
                            .contains("\"gstAmount\"").contains(String.valueOf(row.gstAmount()))
                            .contains("\"total\"").contains(String.valueOf(row.total()))
                            .contains("\"status\"").contains(row.status())
                            .contains("\"issuedAt\"").contains(row.issuedAt())
                            .contains("\"dueAt\"").contains(row.dueAt())
                            .contains("\"createdAt\"");
                }
            }
        }
    }

    @Test
    void rollbackOfEnclosingTransaction_persistsNeitherInvoiceNorOutboxEvent() {
        Map<String, Object> request = Map.of(
                "orderRef", "ORD-ROLLBACK-" + System.nanoTime(),
                "school", "Rollback School",
                "schoolId", 7L,
                "description", "Should not persist",
                "qty", 1,
                "rate", 500L);

        AtomicReference<String> createdId = new AtomicReference<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            InvoiceRow row = invoiceService.create(request);
            createdId.set(row.id());
            throw new RuntimeException("force rollback");
        })).hasMessage("force rollback");

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner")) {
            assertThat(countInvoices(c, createdId.get()))
                    .as("invoice must NOT persist after rollback")
                    .isEqualTo(0);
            assertThat(countOutboxEvents(c, createdId.get()))
                    .as("outbox event must NOT persist after rollback")
                    .isEqualTo(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void outboxWriter_append_insertsRow_withinCallersTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        String aggregateId = "direct-" + System.nanoTime();

        tx.executeWithoutResult(status -> outboxWriter.append(
                "billing.invoice-upserted.v1",
                "InvoiceUpserted:" + aggregateId,
                "SuperadminInvoice",
                aggregateId,
                99L,
                "{\"id\":\"" + aggregateId + "\"}"));

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner")) {
            assertThat(countOutboxEvents(c, aggregateId)).isEqualTo(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int countInvoices(Connection c, String id) throws Exception {
        if (id == null) return 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM billing.superadmin_invoices WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countOutboxEvents(Connection c, String aggregateId) throws Exception {
        if (aggregateId == null) return 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM billing.outbox_events WHERE aggregate_id = ?")) {
            ps.setString(1, aggregateId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
