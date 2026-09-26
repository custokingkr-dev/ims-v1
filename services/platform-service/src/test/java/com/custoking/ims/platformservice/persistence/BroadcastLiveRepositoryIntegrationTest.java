package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Prepared;
import com.custoking.ims.platformservice.application.BroadcastLiveProvider.Result;
import com.custoking.ims.platformservice.persistence.BroadcastDispatchRepository.QueuedRecipient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/RLS checks: fixtures use the owner; every repository call uses app_rt. */
class BroadcastLiveRepositoryIntegrationTest {
    private static PostgreSQLContainer<?> pg;
    private static JdbcClient owner;
    private static JdbcClient app;
    private static TransactionTemplate transaction;
    private static BroadcastLiveRepository live;
    private static BroadcastDispatchRepository queue;
    private static final String DESTINATION = "a".repeat(64);
    private static final String SENDER = "b".repeat(64);
    private static final String REQUEST = "c".repeat(64);

    @BeforeAll static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS NOINHERIT NOCREATEROLE NOCREATEDB").update();
        // Reproduce broad deployment defaults: V12 must remove them itself, not rely on this fixture.
        owner.sql("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO app_rt").update();
        Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("notification")
                .defaultSchema("notification").locations("classpath:db/migration/notification").load().migrate();
        owner.sql("GRANT USAGE ON SCHEMA notification TO app_rt").update();
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "test");
        app = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(10);
        live = new BroadcastLiveRepository(app);
        queue = new BroadcastDispatchRepository(app, new ObjectMapper());
    }

    @AfterAll static void close() { if (pg != null) pg.stop(); }

    @BeforeEach void clean() {
        owner.sql("DELETE FROM notification.broadcast_live_reports").update();
        owner.sql("DELETE FROM notification.broadcast_live_submissions").update();
        owner.sql("DELETE FROM notification.notification_broadcast_recipients").update();
        owner.sql("DELETE FROM notification.notification_broadcasts").update();
    }

    @Test void durableReservationSurvivesRollbackAndReplayCannotStartASecondAttempt() {
        var row = seed(10);
        var prepared = prepared(row);
        assertThat(inTx(() -> live.reserve(row, prepared))).isTrue();
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            live.finish(row, prepared, new Result("ACCEPTED", "provider-one", null));
            throw new IllegalStateException("simulated lost result transaction");
        })).hasMessageContaining("simulated lost result");
        assertThat(submission(row, "submission_status")).isEqualTo("SUBMITTING");
        assertThat(inTx(() -> live.reserve(row, prepared))).isFalse();
        assertThat(count("broadcast_live_submissions")).isEqualTo(1);
        assertThat(attempts(row)).isEqualTo(1);
        assertThat(inTx(() -> queue.claim(OffsetDateTime.now().plusDays(1), "LIVE"))).isEmpty();
    }

    @Test void concurrentReservationHasExactlyOneWinnerAndOneDurableAttempt() throws Exception {
        var row = seed(10);
        var prepared = prepared(row);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> reserve = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("workers did not start");
                return inTx(() -> live.reserve(row, prepared));
            };
            var first = workers.submit(reserve);
            var second = workers.submit(reserve);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(count("broadcast_live_submissions")).isEqualTo(1);
        assertThat(attempts(row)).isEqualTo(1);
    }

    @Test void changedRequestCannotReuseAnExistingReservation() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        var changed = new Prepared("EMAIL", prepared.correlationId(), DESTINATION, "d".repeat(64), SENDER, "different synthetic payload");
        assertThatThrownBy(() -> inTx(() -> live.reserve(row, changed))).isInstanceOf(IllegalStateException.class);
        assertThat(submission(row, "request_sha256")).isEqualTo(REQUEST);
        assertThat(attempts(row)).isEqualTo(1);
    }

    @Test void providerAcceptanceAndAmbiguousReplayNeverClaimDeliveryOrRegressEvidence() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        finish(row, prepared, "ACCEPTED", "provider-one");
        assertThat(outcomes(row)).containsEntry("status", "AWAITING_DELIVERY").containsEntry("delivered", 0);
        finish(row, prepared, "ACCEPTED", "provider-one");
        finish(row, prepared, "UNKNOWN", null);
        assertThat(submission(row, "submission_status")).isEqualTo("ACCEPTED");
        assertThat(recipientStatus(row)).isEqualTo("ACCEPTED");
        assertThat(attempts(row)).isEqualTo(1);
        assertThat(inTx(() -> live.reserve(row, prepared))).isFalse();
        assertThat(recipientStatus(row)).isEqualTo("ACCEPTED");
        assertThat(attempts(row)).isEqualTo(1);
    }

    @Test void authenticatedBoundReportBeforeProviderResultSurvivesLateUnknownAndReplay() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        assertThat(report(prepared, "provider-one", "DELIVERED", "1".repeat(64))).isTrue();
        assertThat(outcomes(row)).containsEntry("status", "COMPLETED").containsEntry("delivered", 1);
        finish(row, prepared, "UNKNOWN", null);
        finish(row, prepared, "ACCEPTED", "provider-one");
        assertThat(report(prepared, "provider-one", "DELIVERED", "1".repeat(64))).isTrue();
        assertThat(inTx(() -> live.reserve(row, prepared))).isFalse();
        assertThat(count("broadcast_live_reports")).isEqualTo(1);
        assertThat(submission(row, "provider_message_id")).isEqualTo("provider-one");
        assertThat(submission(row, "delivery_status")).isEqualTo("DELIVERED");
        assertThat(recipientStatus(row)).isEqualTo("DELIVERED");
        assertThat(attempts(row)).isEqualTo(1);
        assertThat(outcomes(row)).containsEntry("status", "COMPLETED").containsEntry("delivered", 1);
    }

    @Test void reportsRequireKnownCorrelationMatchingHashesAndPlausibleTime() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(inTx(() -> live.report("unknown", "provider-one", DESTINATION, SENDER, "DELIVERED", now, "1".repeat(64)))).isFalse();
        assertThat(inTx(() -> live.report(prepared.correlationId(), "provider-one", "d".repeat(64), SENDER, "DELIVERED", now, "2".repeat(64)))).isFalse();
        assertThat(inTx(() -> live.report(prepared.correlationId(), "provider-one", DESTINATION, "d".repeat(64), "DELIVERED", now, "3".repeat(64)))).isFalse();
        assertThat(inTx(() -> live.report(prepared.correlationId(), "provider-one", DESTINATION, SENDER, "DELIVERED", now.minusMinutes(10), "4".repeat(64)))).isFalse();
        assertThat(inTx(() -> live.report(prepared.correlationId(), "provider-one", DESTINATION, SENDER, "DELIVERED", now.plusMinutes(10), "5".repeat(64)))).isFalse();
        assertThat(count("broadcast_live_reports")).isZero();
        assertThat(submission(row, "provider_message_id")).isNull();
        assertThat(recipientStatus(row)).isEqualTo("SUBMITTING");
    }

    @Test void terminalReportsAreMonotonicAndConflictingEvidenceRequiresReconciliation() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        finish(row, prepared, "ACCEPTED", "provider-one");
        assertThat(report(prepared, "provider-one", "DELIVERED", "1".repeat(64))).isTrue();
        assertThat(report(prepared, "provider-one", "ACCEPTED", "2".repeat(64))).isTrue();
        assertThat(recipientStatus(row)).isEqualTo("DELIVERED");
        assertThat(report(prepared, "provider-one", "DELIVERY_FAILED", "3".repeat(64))).isTrue();
        assertThat(report(prepared, "provider-one", "DELIVERED", "4".repeat(64))).isTrue();
        assertThat(submission(row, "delivery_status")).isEqualTo("REPORT_CONFLICT");
        assertThat(outcomes(row)).containsEntry("status", "NEEDS_RECONCILIATION").containsEntry("delivered", 0);
        assertThat(count("broadcast_live_reports")).isEqualTo(4);
        assertThat(attempts(row)).isEqualTo(1);
    }

    @Test void aDifferentProviderIdCannotReplaceTheOriginalBinding() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        assertThat(report(prepared, "provider-first", "DELIVERED", "1".repeat(64))).isTrue();
        finish(row, prepared, "ACCEPTED", "provider-other");
        assertThat(report(prepared, "provider-third", "DELIVERED", "2".repeat(64))).isTrue();
        assertThat(submission(row, "provider_message_id")).isEqualTo("provider-first");
        assertThat(submission(row, "delivery_status")).isEqualTo("REPORT_CONFLICT");
        assertThat(count("broadcast_live_reports")).isEqualTo(2);
        assertThat(outcomes(row)).containsEntry("status", "NEEDS_RECONCILIATION").containsEntry("delivered", 0);
    }

    @Test void aReportAfterProviderRejectionRetainsTheConflictInsteadOfClaimingDelivery() {
        var row = seed(10);
        var prepared = prepared(row);
        inTx(() -> live.reserve(row, prepared));
        finish(row, prepared, "REJECTED", null);
        assertThat(outcomes(row)).containsEntry("status", "COMPLETED_WITH_FAILURES").containsEntry("delivered", 0);
        assertThat(report(prepared, "provider-one", "DELIVERED", "1".repeat(64))).isTrue();
        assertThat(recipientStatus(row)).isEqualTo("REPORT_CONFLICT");
        assertThat(outcomes(row)).containsEntry("status", "NEEDS_RECONCILIATION").containsEntry("delivered", 0);
    }

    @Test void aggregationDistinguishesPendingAcceptedFailedAndConfirmedDelivery() {
        var delivered = seed(10);
        var pending = seedRecipient(delivered.broadcastId(), 10, 2);
        var prepared = prepared(delivered);
        inTx(() -> live.reserve(delivered, prepared));
        finish(delivered, prepared, "ACCEPTED", "provider-one");
        assertThat(outcomes(delivered)).containsEntry("status", "QUEUED").containsEntry("delivered", 0);
        assertThat(report(prepared, "provider-one", "DELIVERED", "1".repeat(64))).isTrue();
        assertThat(outcomes(delivered)).containsEntry("status", "QUEUED").containsEntry("delivered", 1);
        var pendingPrepared = prepared(pending);
        inTx(() -> live.reserve(pending, pendingPrepared));
        assertThat(outcomes(delivered)).containsEntry("status", "NEEDS_RECONCILIATION");
        finish(pending, pendingPrepared, "ACCEPTED", "provider-two");
        assertThat(outcomes(delivered)).containsEntry("status", "AWAITING_DELIVERY").containsEntry("delivered", 1);
        assertThat(report(pendingPrepared, "provider-two", "DELIVERY_FAILED", "2".repeat(64))).isTrue();
        assertThat(outcomes(delivered)).containsEntry("status", "COMPLETED_WITH_FAILURES").containsEntry("delivered", 1).containsEntry("total", 2);
        assertThat(inTx(() -> { live.scope(10); return queue.retry(queue.find(delivered.broadcastId(), true)); })).isZero();
    }

    @Test void futureLiveBroadcastWaitsUntilItsScheduledTimeWithoutConsumingAnAttempt() {
        var row = seed(10);
        OffsetDateTime due = OffsetDateTime.now().plusHours(1).withNano(0);
        owner.sql("UPDATE notification.notification_broadcasts SET scheduled_at=:due WHERE id=:id")
                .param("due", due).param("id", row.broadcastId()).update();

        assertThat(inTx(() -> queue.claim(OffsetDateTime.now(), "LIVE"))).isEmpty();
        assertThat(inTx(() -> queue.claim(due.minusSeconds(1), "LIVE"))).isEmpty();
        assertThat(recipientStatus(row)).isEqualTo("QUEUED");
        assertThat(attempts(row)).isZero();
        assertThat(outcomes(row)).containsEntry("status", "QUEUED").containsEntry("delivered", 0);
        assertThat(count("broadcast_live_submissions")).isZero();

        // Becoming due does not allow the dry-run worker to claim a live recipient.
        assertThat(inTx(() -> queue.claim(due, "DRY_RUN"))).isEmpty();
        var claimed = inTx(() -> queue.claim(due, "LIVE")).orElseThrow();
        assertThat(claimed.id()).isEqualTo(row.id());
        assertThat(claimed.eventId()).isEqualTo(row.eventId());
        assertThat(claimed.mode()).isEqualTo("LIVE");
        assertThat(claimed.attempts()).isZero();
        assertThat(recipientStatus(row)).isEqualTo("QUEUED");
        assertThat(attempts(row)).isZero();
        assertThat(count("broadcast_live_submissions")).isZero();
    }

    @Test void reportDiscoveryScopesToItsSchoolAndRuntimeReadsRemainTenantIsolated() {
        var first = seed(10);
        var second = seed(20);
        inTx(() -> live.reserve(first, prepared(first)));
        inTx(() -> live.reserve(second, prepared(second)));
        inTx(() -> {
            live.scope(20);
            assertThat(live.report(prepared(first).correlationId(), "provider-one", DESTINATION, SENDER,
                    "DELIVERED", OffsetDateTime.now(), "1".repeat(64))).isTrue();
            assertThat(app.sql("SELECT current_setting('app.bypass_rls')").query(String.class).single()).isEqualTo("off");
            assertThat(app.sql("SELECT current_setting('app.current_school_id')").query(String.class).single()).isEqualTo("10");
            return null;
        });
        inTx(() -> {
            live.scope(20);
            assertThat(app.sql("SELECT count(*) FROM notification.broadcast_live_submissions").query(Long.class).single()).isEqualTo(1);
            assertThat(app.sql("SELECT count(*) FROM notification.broadcast_live_reports").query(Long.class).single()).isZero();
            assertThat(app.sql("UPDATE notification.broadcast_live_submissions SET reason='WRONG_SCHOOL' WHERE event_id=:event")
                    .param("event", first.eventId()).update()).isZero();
            return null;
        });
        assertThat(recipientStatus(first)).isEqualTo("DELIVERED");
        assertThat(recipientStatus(second)).isEqualTo("SUBMITTING");
    }

    @Test void v12RemovesBroadDefaultPrivilegesAndProtectsLedgerIdentityAndReports() {
        var row = seed(10);
        inTx(() -> live.reserve(row, prepared(row)));
        assertThat(report(prepared(row), "provider-one", "DELIVERED", "1".repeat(64))).isTrue();
        for (String table : List.of("broadcast_live_submissions", "broadcast_live_reports")) {
            for (String verb : List.of("DELETE FROM", "TRUNCATE")) assertDenied(verb + " notification." + table);
        }
        for (String column : List.of("event_id", "correlation_id", "request_sha256", "destination_sha256", "sender_sha256")) {
            assertDenied("UPDATE notification.broadcast_live_submissions SET " + column + "='forged'");
        }
        assertDenied("UPDATE notification.broadcast_live_submissions SET school_id=20");
        assertDenied("UPDATE notification.broadcast_live_reports SET status='DELIVERY_FAILED'");
        assertThat(owner.sql("SELECT NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit AND NOT rolcreaterole FROM pg_roles WHERE rolname='app_rt'")
                .query(Boolean.class).single()).isTrue();
        assertThat(count("broadcast_live_submissions")).isEqualTo(1);
        assertThat(count("broadcast_live_reports")).isEqualTo(1);
        assertThat(recipientStatus(row)).isEqualTo("DELIVERED");
    }

    @Test void liveEvidenceRequiresAnExplicitTransaction() {
        var row = seed(10);
        assertThatThrownBy(() -> live.reserve(row, prepared(row))).isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertThatThrownBy(() -> live.report("correlation", "provider", DESTINATION, SENDER, "DELIVERED", OffsetDateTime.now(), "1".repeat(64)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        assertThat(count("broadcast_live_submissions")).isZero();
    }

    private static <T> T inTx(Supplier<T> action) { return transaction.execute(tx -> action.get()); }
    private static void finish(QueuedRecipient row, Prepared prepared, String status, String provider) {
        transaction.executeWithoutResult(tx -> live.finish(row, prepared, new Result(status, provider, null)));
    }
    private static boolean report(Prepared prepared, String provider, String status, String hash) {
        return inTx(() -> live.report(prepared.correlationId(), provider, DESTINATION, SENDER, status, OffsetDateTime.now(), hash));
    }
    private static Map<String, Object> outcomes(QueuedRecipient row) {
        return inTx(() -> { live.scope(row.schoolId()); return queue.outcomes(queue.find(row.broadcastId(), false)); });
    }
    private static void assertDenied(String sql) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(tx -> {
            // Even the application's explicit background bypass must not confer destructive SQL privileges.
            app.sql("SELECT set_config('app.bypass_rls','on',true)").query(String.class).single();
            app.sql(sql).update();
        })).rootCause().isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    }
    private static long count(String table) { return owner.sql("SELECT count(*) FROM notification." + table).query(Long.class).single(); }
    private static String submission(QueuedRecipient row, String column) {
        return owner.sql("SELECT " + column + " FROM notification.broadcast_live_submissions WHERE event_id=:event")
                .param("event", row.eventId()).query((rs, index) -> rs.getString(1)).list().getFirst();
    }
    private static String recipientStatus(QueuedRecipient row) {
        return owner.sql("SELECT status FROM notification.notification_broadcast_recipients WHERE id=:id").param("id", row.id()).query(String.class).single();
    }
    private static int attempts(QueuedRecipient row) {
        return owner.sql("SELECT attempts FROM notification.notification_broadcast_recipients WHERE id=:id").param("id", row.id()).query(Integer.class).single();
    }
    private static Prepared prepared(QueuedRecipient row) {
        return new Prepared("EMAIL", "ims" + row.id().toString().replace("-", "") + "0".repeat(16), DESTINATION, REQUEST, SENDER, "synthetic in-memory body");
    }
    private static QueuedRecipient seed(long school) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                INSERT INTO notification.notification_broadcasts
                  (id,school_id,title,message,audience_type,channels,communication_category,status,dispatch_mode,approval_mode)
                VALUES (:id,:school,'Synthetic notice','Synthetic message','ALL_PARENTS','EMAIL','SCHOOL_NOTICE','QUEUED','LIVE','LIVE')
                """).param("id", id).param("school", school).update();
        return seedRecipient(id, school, 1);
    }
    private static QueuedRecipient seedRecipient(UUID broadcast, long school, long student) {
        UUID id = UUID.randomUUID();
        String event = "broadcast:" + broadcast + ":" + student + ":EMAIL";
        owner.sql("""
                INSERT INTO notification.notification_broadcast_recipients
                  (id,broadcast_id,school_id,student_id,channel,event_id,guardian_id,destination_sha256,status)
                VALUES (:id,:broadcast,:school,:student,'EMAIL',:event,'synthetic-guardian',:destination,'QUEUED')
                """).param("id", id).param("broadcast", broadcast).param("school", school).param("student", student)
                .param("event", event).param("destination", DESTINATION).update();
        return new QueuedRecipient(id, broadcast, school, student, "EMAIL", event, "synthetic-guardian", DESTINATION, 0,
                "Synthetic notice", "Synthetic message", "LIVE");
    }
}
