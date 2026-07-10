package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reporting Decoupling SP2 (fee): proves assignFeePlan/recordPayment emit
 * fee-assignment.upserted.v1 / payment.recorded.v1 rows into tenant_school.outbox_events
 * in the same transaction as the domain write, and that a failed mutation emits nothing
 * (mirrors ReferenceEventEmissionIntegrationTest's SP1 pattern).
 */
class FeeOutboxEmissionIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource dataSource;
    static JdbcClient jdbc;
    static FeeReadRepository repo;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        for (String schema : new String[] {"tenant_school", "student", "fee"}) {
            Flyway.configure()
                    .dataSource(PG.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        repo = new FeeReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
    }

    @AfterAll
    static void tearDown() {
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void resetData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM fee.payment_records");
            st.execute("DELETE FROM fee.fee_assignments");
            st.execute("DELETE FROM fee.fee_items");
            st.execute("DELETE FROM fee.fee_bands");
            st.execute("DELETE FROM tenant_school.outbox_events");
            st.execute("DELETE FROM student.students");
            st.execute("DELETE FROM tenant_school.academic_years");
            st.execute("INSERT INTO tenant_school.academic_years (id, label, active) VALUES ('ay1', '2025-26', true)");
            st.execute("INSERT INTO student.students (id, admission_no, full_name, school_id, class_id, section_id, academic_year_id) " +
                    "VALUES (1, 'A-1', 'Test Student', 10, 'c1', 's1', 'ay1')");
            st.execute("INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id, school_id) " +
                    "VALUES ('band-1', 'Band 1', 1, 5, 0.0, 'ay1', 10)");
            st.execute("INSERT INTO fee.fee_items(id, name, frequency, amount, band_id, school_id) " +
                    "VALUES ('item-1', 'Tuition', 'Annual', 500000, 'band-1', 10)");
        }
    }

    private long countOutbox(String eventType) {
        return jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events WHERE event_type = :type")
                .param("type", eventType)
                .query(Long.class)
                .single();
    }

    @Test
    void assignFeePlanEmitsFeeAssignmentUpsertedInSameTransaction() {
        Map<String, Object> result = repo.assignFeePlan(Map.of(
                "studentId", 1L, "bandId", "band-1", "schedule", "Annual"));
        String assignmentId = String.valueOf(((Map<?, ?>) result.get("assignment")).get("id"));

        var rows = jdbc.sql("""
                        SELECT event_type, school_id, payload
                        FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'FeeAssignment' AND aggregate_id = :id
                        """)
                .param("id", assignmentId)
                .query((rs, n) -> Map.of(
                        "eventType", rs.getString("event_type"),
                        "schoolId", rs.getLong("school_id"),
                        "payload", rs.getString("payload")))
                .list();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("eventType")).isEqualTo("fee-assignment.upserted.v1");
        assertThat(rows.get(0).get("schoolId")).isEqualTo(10L);
        String payload = String.valueOf(rows.get(0).get("payload"));
        assertThat(payload).contains("\"studentId\": 1").contains("\"netPayable\": 500000").contains("\"status\": \"Overdue\"");
    }

    @Test
    void recordPaymentEmitsPaymentRecordedAndUpdatedFeeAssignmentEvents() {
        repo.assignFeePlan(Map.of("studentId", 1L, "bandId", "band-1", "schedule", "Annual"));
        long beforeAssignmentEvents = countOutbox("fee-assignment.upserted.v1");

        Map<String, Object> payment = repo.recordPayment(Map.of("studentId", 1L, "amount", 500000L));
        String paymentId = String.valueOf(payment.get("paymentId"));

        assertThat(countOutbox("payment.recorded.v1")).isEqualTo(1);
        assertThat(countOutbox("fee-assignment.upserted.v1")).isEqualTo(beforeAssignmentEvents + 1);

        var paymentRows = jdbc.sql("""
                        SELECT payload FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'Payment' AND aggregate_id = :id
                        """)
                .param("id", paymentId)
                .query(String.class)
                .list();
        assertThat(paymentRows).hasSize(1);
        assertThat(paymentRows.get(0)).contains("\"amount\": 500000").contains("\"studentId\": 1");

        // The most recent fee-assignment event reflects the fully-paid state.
        String latestAssignmentPayload = jdbc.sql("""
                        SELECT payload FROM tenant_school.outbox_events
                        WHERE aggregate_type = 'FeeAssignment'
                        ORDER BY id DESC LIMIT 1
                        """)
                .query(String.class)
                .single();
        assertThat(latestAssignmentPayload).contains("\"status\": \"Paid\"").contains("\"dueAmount\": 0");
    }

    @Test
    void recordPaymentAcceptsExplicitAmountPaisePayload() {
        repo.assignFeePlan(Map.of("studentId", 1L, "bandId", "band-1", "schedule", "Annual"));

        Map<String, Object> payment = repo.recordPayment(Map.of("studentId", 1L, "amountPaise", 123456L));

        Long storedAmount = jdbc.sql("SELECT amount FROM fee.payment_records WHERE id = :id")
                .param("id", payment.get("paymentId"))
                .query(Long.class)
                .single();
        assertThat(storedAmount).isEqualTo(123456L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void feeReportReturnsDiscountAndSurchargeAsPaiseAmountsWithPercentFieldsSeparate() {
        repo.assignFeePlan(Map.of(
                "studentId", 1L,
                "bandId", "band-1",
                "schedule", "Monthly",
                "bandDiscount", 10.0,
                "manualDiscount", 5.0,
                "surcharge", 2.0));

        Map<String, Object> report = repo.feeReport("c1", "s1", null, 10L);
        Map<String, Object> row = ((java.util.List<Map<String, Object>>) report.get("content")).get(0);

        assertThat(row.get("totalAnnualFeePaise")).isEqualTo(500000L);
        assertThat(row.get("approvedDiscountPaise")).isEqualTo(75000L);
        assertThat(row.get("discounts")).isEqualTo(75000L);
        assertThat(row.get("discountPercent")).isEqualTo(15.0);
        assertThat(row.get("surchargeAmountPaise")).isEqualTo(10000L);
        assertThat(row.get("surchargePercent")).isEqualTo(2.0);
        assertThat(row.get("dueAmountPaise")).isEqualTo(435000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeFeeOperationsIgnoreSoftDeletedStudentsButReceiptsRemainReadable() {
        repo.assignFeePlan(Map.of("studentId", 1L, "bandId", "band-1", "schedule", "Annual"));
        Map<String, Object> payment = repo.recordPayment(Map.of("studentId", 1L, "amount", 125000L));
        jdbc.sql("UPDATE student.students SET deleted_at = now(), deleted_reason = 'Transferred' WHERE id = 1")
                .update();

        assertThat((java.util.List<Map<String, Object>>) repo.feeReport("c1", "s1", null, 10L).get("content"))
                .isEmpty();
        assertThat((java.util.List<Map<String, Object>>) repo.feeOverdue("c1", "s1", null, 10L).get("content"))
                .isEmpty();
        assertThat(((Number) repo.feeOverdueCount(null, 10L).get("count")).longValue()).isZero();
        assertThat((java.util.List<Map<String, Object>>) repo.feeReminderRequests("c1", "s1", null, 10L, 99L).get("content"))
                .isEmpty();

        Map<String, Object> module = repo.feesModule(null, 10L);
        Map<String, Object> summary = (Map<String, Object>) module.get("summary");
        assertThat(summary.get("collected")).isEqualTo(0L);
        assertThat(summary.get("target")).isEqualTo(0L);
        assertThat((java.util.List<Map<String, Object>>) module.get("records")).isEmpty();

        Map<String, Object> receipt = repo.receiptByPaymentId(String.valueOf(payment.get("paymentId")));
        assertThat(receipt.get("amount")).isEqualTo(125000L);

        assertThatThrownBy(() -> repo.recordPayment(Map.of("studentId", 1L, "amount", 1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Student not found");
        assertThatThrownBy(() -> repo.assignFeePlan(Map.of("studentId", 1L, "bandId", "band-1", "schedule", "Annual")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Student not found");
    }

    @Test
    void recordPaymentRejectsAmountGreaterThanRemainingDue() {
        repo.assignFeePlan(Map.of("studentId", 1L, "bandId", "band-1", "schedule", "Annual"));

        assertThatThrownBy(() -> repo.recordPayment(Map.of("studentId", 1L, "amount", 500001L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining due");
    }

    @Test
    void failedRecordPaymentEmitsNoEvent() {
        long before = countOutbox("payment.recorded.v1");
        assertThatThrownBy(() -> repo.recordPayment(Map.of("studentId", 1L, "amount", 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countOutbox("payment.recorded.v1")).isEqualTo(before);
    }
}
