package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.api.FeeReadController;
import com.custoking.ims.schoolcoreservice.api.compat.FeePublicCompatibilityController;
import com.custoking.ims.schoolcoreservice.api.dto.RecordPaymentRequest;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class FeePaymentIntegrityIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    static FeeReadRepository fees;
    static TransactionTemplate tx;
    static String currentYear;

    @BeforeAll static void database() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        for (String schema : List.of("tenant_school", "student", "fee")) {
            Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner")
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration/" + schema)
                    .load().migrate();
        }
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        fees = new FeeReadRepository(jdbc, new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school"));
        currentYear = AcademicCalendar.currentAcademicYear(AcademicCalendar.DEFAULT_ACADEMIC_YEAR_START_MONTH).id();
    }

    @AfterAll static void stop() { if (pg != null) pg.stop(); }
    @AfterEach void clearActor() { TenantContext.clear(); }

    @BeforeEach void fixture() {
        jdbc.sql("DELETE FROM fee.payment_records").update();
        jdbc.sql("DELETE FROM fee.fee_assignments").update();
        jdbc.sql("DELETE FROM fee.fee_installments").update();
        jdbc.sql("DELETE FROM fee.fee_items").update();
        jdbc.sql("DELETE FROM fee.fee_bands").update();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();
        jdbc.sql("DELETE FROM student.students").update();
        jdbc.sql("""
            INSERT INTO student.students(id, admission_no, full_name, school_id, class_id, section_id, academic_year_id)
            VALUES (1, 'A1', 'First Student', 10, 'c1', 's1', :year), (2, 'A2', 'Second Student', 20, 'c1', 's1', :year)
            """).param("year", currentYear).update();
        for (long school : List.of(10L, 20L)) {
            jdbc.sql("""
                INSERT INTO fee.fee_bands(id, name, class_from, class_to, discount, academic_year_id, school_id)
                VALUES (:id, 'Tuition', 1, 5, 0, :year, :school)
                """).param("id", "band-" + school).param("year", currentYear).param("school", school).update();
            assignment("assignment-" + school, school / 10, school, currentYear, 10000);
        }
        actor(10L, 51L);
    }

    static void actor(long school, long user) {
        TenantContext.set(new TenantContext(user, "collector@example.test", "ADMIN", school, null,
                Set.of(), Set.of("fee:collect", "payment:create", "fee:assign")));
    }
    static void assignment(String id, long student, long school, String year, long total) {
        jdbc.sql("""
            INSERT INTO fee.fee_assignments(id, schedule, band_discount, manual_discount, surcharge,
                gross_fee, base_payable, net_payable, paid_amount, student_id, band_id, academic_year_id, school_id, assigned_at)
            VALUES (:id, 'Annual', 0, 0, 0, :total, :total, :total, 0, :student, :band, :year, :school, now())
            """).param("id", id).param("total", total).param("student", student)
                .param("band", "band-" + school).param("year", year).param("school", school).update();
    }
    static Map<String, Object> request(String key, long student, long amount) {
        return new HashMap<>(Map.of("studentId", student, "amount", amount, "idempotencyKey", key,
                "mode", "Cash", "paidAt", "2026-09-26T09:30:00Z"));
    }
    Map<String, Object> pay(Map<String, Object> request) { return tx.execute(ignored -> fees.recordPayment(request)); }
    long count(String table) { return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single(); }

    @Test void retryReturnsExactOriginalResultEvenAfterAnotherPayment() {
        var request = request("same-collection", 1, 1000);
        var original = pay(request);
        pay(request("second-collection", 1, 2000));
        assertThat(pay(request)).isEqualTo(original);
        assertThat(count("fee.payment_records")).isEqualTo(2);
        assertThat(count("tenant_school.outbox_events")).isEqualTo(4);
        assertThat(jdbc.sql("SELECT paid_amount FROM fee.fee_assignments WHERE id = 'assignment-10'").query(Long.class).single()).isEqualTo(3000);
    }

    @Test void changedAmountNotesOrAssignmentForSameKeyConflictsWithoutWriting() {
        var request = request("collision", 1, 1000);
        pay(request);
        for (var change : List.of(Map.entry("amount", (Object) 1001L), Map.entry("notes", (Object) "changed"), Map.entry("assignmentId", (Object) "assignment-10"))) {
            var changed = new HashMap<>(request);
            changed.put(change.getKey(), change.getValue());
            assertThatThrownBy(() -> pay(changed)).isInstanceOf(PaymentConflictException.class);
        }
        assertThat(count("fee.payment_records")).isEqualTo(1);
    }

    @Test void missingKeyIsRejected() {
        var request = request("unused", 1, 1000);
        request.remove("idempotencyKey");
        assertThatThrownBy(() -> pay(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotency");
        assertThat(count("fee.payment_records")).isZero();
    }

    @Test void sameKeyIsIndependentAcrossTenantsAndCrossTenantStudentIsDenied() {
        var first = pay(request("same-key", 1, 1000));
        assertThatThrownBy(() -> pay(request("cross-school", 2, 1000))).isInstanceOf(ResponseStatusException.class);
        actor(20, 52);
        var second = pay(request("same-key", 2, 1000));
        assertThat(second.get("paymentId")).isNotEqualTo(first.get("paymentId"));
        var crossAssignment = request("wrong-assignment", 2, 1000);
        crossAssignment.put("assignmentId", "assignment-10");
        assertThatThrownBy(() -> pay(crossAssignment)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("assignment not found");
        assertThat(count("fee.payment_records")).isEqualTo(2);
    }

    @Test void historicalSettlementPreservesYearAndCurrentStudentStatus() {
        assignment("old-assignment", 1, 10, "1999-2000", 9000);
        jdbc.sql("UPDATE student.students SET fee_status = 'Overdue' WHERE id = 1").update();
        var request = request("historic-payment", 1, 9000);
        request.put("assignmentId", "old-assignment");
        request.put("academicYearId", "1999-2000");
        assertThat(pay(request)).containsEntry("academicYearId", "1999-2000");
        assertThat(jdbc.sql("SELECT academic_year_id FROM fee.fee_assignments WHERE id = 'old-assignment'").query(String.class).single()).isEqualTo("1999-2000");
        assertThat(jdbc.sql("SELECT fee_status FROM student.students WHERE id = 1").query(String.class).single()).isEqualTo("Overdue");
        request.put("idempotencyKey", "wrong-year");
        request.put("academicYearId", currentYear);
        assertThatThrownBy(() -> pay(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different academic year");
    }

    @Test void missingCurrentAssignmentDoesNotFallBackToPreviousYear() {
        jdbc.sql("UPDATE fee.fee_assignments SET academic_year_id = '1999-2000' WHERE id = 'assignment-10'").update();
        assertThatThrownBy(() -> pay(request("no-fallback", 1, 1000))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("selected academic year");
        assertThat(count("fee.payment_records")).isZero();
    }

    @Test void simultaneousDuplicateRequestsProduceOneReceiptAndEventPair() throws Exception {
        var results = concurrently(request("double-click", 1, 1000), request("double-click", 1, 1000));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(count("fee.payment_records")).isEqualTo(1);
        assertThat(count("tenant_school.outbox_events")).isEqualTo(2);
    }

    @Test void simultaneousCollectionsCannotOverpay() throws Exception {
        var results = concurrently(request("collector-one", 1, 7000), request("collector-two", 1, 7000));
        assertThat(results.stream().filter(Map.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(IllegalArgumentException.class::isInstance)).hasSize(1);
        assertThat(count("fee.payment_records")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT paid_amount FROM fee.fee_assignments WHERE id = 'assignment-10'").query(Long.class).single()).isEqualTo(7000);
        assertThat(count("tenant_school.outbox_events")).isEqualTo(2);
    }

    @Test void simultaneousValidCollectionsHaveDistinctReceipts() throws Exception {
        var results = concurrently(request("collector-one", 1, 5000), request("collector-two", 1, 5000));
        assertThat(results).allMatch(Map.class::isInstance);
        assertThat(jdbc.sql("SELECT count(DISTINCT receipt_number) FROM fee.payment_records").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT paid_amount FROM fee.fee_assignments WHERE id = 'assignment-10'").query(Long.class).single()).isEqualTo(10000);
    }

    private List<Object> concurrently(Map<String, Object> first, Map<String, Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Object>>();
            for (var request : List.of(first, second)) futures.add(pool.submit(() -> {
                actor(10, 51);
                ready.countDown();
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Start timed out");
                    return pay(request);
                } catch (RuntimeException error) { return error; }
                finally { TenantContext.clear(); }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test void canonicalAndCompatibilityRoutesPersistTrustedActorAndReturnConflictStatus() {
        var canonical = new FeeReadController(fees, "token");
        var compatibility = new FeePublicCompatibilityController(fees, "token");
        tx.execute(ignored -> canonical.recordPayment("token", new RecordPaymentRequest(
                1L, 1000L, null, "Cash", "", 999L, "canonical-key", "assignment-10", currentYear)));
        var request = request("compatibility-key", 1, 1000);
        request.put("actorId", 999L);
        request.put("recordedBy", 999L);
        tx.execute(ignored -> compatibility.recordPayment("token", request));
        assertThat(jdbc.sql("SELECT DISTINCT recorded_by FROM fee.payment_records").query(Long.class).list()).containsExactly(51L);
        assertThat(jdbc.sql("SELECT updated_by FROM fee.fee_assignments WHERE id = 'assignment-10'").query(Long.class).single()).isEqualTo(51L);
        request.put("amount", 2000L);
        assertThatThrownBy(() -> tx.execute(ignored -> compatibility.recordPayment("token", request)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("fee.payment_records")).isEqualTo(2);
    }

    @Test void reportBatchesScheduleReadsAcrossManyStudentsAndTargetsReceiptByAssignment() {
        for (long id = 3; id <= 22; id++) {
            jdbc.sql("""
                INSERT INTO student.students(id, admission_no, full_name, school_id, class_id, section_id, academic_year_id)
                VALUES (:id, :admission, 'Additional student', 10, 'c1', 's1', :year)
                """).param("id", id).param("admission", "A" + id).param("year", currentYear).update();
            assignment("extra-" + id, id, 10, currentYear, 10000);
        }
        assignment("old-assignment", 1, 10, "1999-2000", 9000);
        var oldPayment = request("old-payment", 1, 1000);
        oldPayment.put("assignmentId", "old-assignment");
        pay(oldPayment);
        var observed = org.mockito.Mockito.spy(jdbc);
        var observedFees = new FeeReadRepository(observed, new OutboxWriter(observed, new ObjectMapper(), "tenant_school"));
        var report = tx.execute(ignored -> observedFees.feeReport("c1", "s1", currentYear, 10L));
        @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) report.get("content");
        assertThat(rows).hasSize(21);
        assertThat(rows.stream().filter(row -> ((Number) row.get("studentId")).longValue() == 1).findFirst().orElseThrow())
                .containsEntry("paymentId", "").containsEntry("academicYearId", currentYear);
        long selects = org.mockito.Mockito.mockingDetails(observed).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("sql"))
                .filter(call -> ((String) call.getArgument(0)).stripLeading().startsWith("SELECT"))
                .count();
        assertThat(selects).isEqualTo(4);
    }
}
