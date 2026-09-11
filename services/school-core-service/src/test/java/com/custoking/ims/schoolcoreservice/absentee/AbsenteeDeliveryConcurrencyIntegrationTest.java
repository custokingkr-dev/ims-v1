package com.custoking.ims.schoolcoreservice.absentee;

import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryTestSupport.ownerConnection;
import static com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryTestSupport.rowState;
import static com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryTestSupport.seedQueued;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claim-and-mark must be safe under concurrent drainer instances (Cloud Run can run several): two
 * workers draining the same queue at once must never hand the same row to the gateway twice, and a
 * row that failed transiently must only be re-claimed once its backoff has elapsed.
 */
class AbsenteeDeliveryConcurrencyIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static HikariDataSource pool;
    static PlatformTransactionManager txManager;
    static AbsenteeNotificationDeliveryRepository repository;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = AbsenteeDeliveryTestSupport.startPostgres();
        pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername(AbsenteeDeliveryTestSupport.OWNER);
        pool.setPassword(AbsenteeDeliveryTestSupport.OWNER);
        pool.setMaximumPoolSize(6);
        txManager = new DataSourceTransactionManager(pool);
        repository = new AbsenteeNotificationDeliveryRepository(JdbcClient.create(pool), "attendance");
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) pool.close();
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ownerConnection(PG); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.absentee_notifications");
        }
    }

    @Test
    void twoConcurrentDrainersNeverDeliverTheSameRowTwice() throws Exception {
        try (Connection c = ownerConnection(PG)) {
            for (int i = 1; i <= 6; i++) {
                seedQueued(c, "n" + i, 10, i, "2026-09-10T09:00:0" + i + "Z");
            }
        }
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        AbsenteeDeliveryGateway slowGateway = request -> {
            delivered.add(request.notificationId());
            sleep(150);
            return DeliveryOutcome.dryRun("test");
        };
        AbsenteeDeliveryWorker a = worker(slowGateway, Clock.systemUTC());
        AbsenteeDeliveryWorker b = worker(slowGateway, Clock.systemUTC());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> fa = executor.submit(() -> { start.await(); return a.drainBatch(); });
            Future<Integer> fb = executor.submit(() -> { start.await(); return b.drainBatch(); });
            start.countDown();
            int total = fa.get(30, TimeUnit.SECONDS) + fb.get(30, TimeUnit.SECONDS);
            assertThat(total).isEqualTo(6);
        } finally {
            executor.shutdownNow();
        }

        assertThat(delivered).hasSize(6);
        assertThat(delivered).doesNotHaveDuplicates();
        assertThat(delivered).containsExactlyInAnyOrder("n1", "n2", "n3", "n4", "n5", "n6");
        try (Connection c = ownerConnection(PG)) {
            for (int i = 1; i <= 6; i++) {
                Map<String, Object> state = rowState(c, "n" + i);
                assertThat(state.get("status")).isEqualTo(AbsenteeNotificationStatus.SENT_DRY_RUN);
                assertThat(state.get("attempts")).isEqualTo(1);
                assertThat(state.get("deliveredAt")).isNull();
            }
        }
    }

    @Test
    void transientFailureIsRetriedOnlyAfterItsBackoffElapses() throws Exception {
        try (Connection c = ownerConnection(PG)) {
            seedQueued(c, "n1", 10, 1, "2026-09-10T09:00:00Z");
        }
        Instant t0 = Instant.parse("2026-09-11T09:00:00Z");
        int[] calls = {0};
        AbsenteeDeliveryGateway flaky = request -> {
            calls[0]++;
            return calls[0] == 1 ? DeliveryOutcome.transientFailure("platform unavailable")
                    : DeliveryOutcome.delivered("msg91", "msg-42");
        };

        assertThat(worker(flaky, Clock.fixed(t0, ZoneOffset.UTC)).drainBatch()).isEqualTo(1);
        try (Connection c = ownerConnection(PG)) {
            Map<String, Object> state = rowState(c, "n1");
            assertThat(state.get("status")).isEqualTo(AbsenteeNotificationStatus.FAILED);
            assertThat(state.get("attempts")).isEqualTo(1);
            assertThat(state.get("lastError")).isEqualTo("platform unavailable");
            assertThat(state.get("nextAttemptAt")).isNotNull();
        }

        // Still inside the 30s backoff: nothing is claimable.
        assertThat(worker(flaky, Clock.fixed(t0.plusSeconds(10), ZoneOffset.UTC)).drainBatch()).isZero();
        assertThat(calls[0]).isEqualTo(1);

        // Backoff elapsed: the retry runs and the real provider id is recorded.
        assertThat(worker(flaky, Clock.fixed(t0.plusSeconds(31), ZoneOffset.UTC)).drainBatch()).isEqualTo(1);
        try (Connection c = ownerConnection(PG)) {
            Map<String, Object> state = rowState(c, "n1");
            assertThat(state.get("status")).isEqualTo(AbsenteeNotificationStatus.SENT);
            assertThat(state.get("attempts")).isEqualTo(2);
            assertThat(state.get("provider")).isEqualTo("msg91");
            assertThat(state.get("providerMessageId")).isEqualTo("msg-42");
            assertThat(state.get("deliveredAt")).isNotNull();
            assertThat(state.get("lastError")).isNull();
            assertThat(state.get("nextAttemptAt")).isNull();
        }
    }

    @Test
    void retryBudgetExhaustionDeadLettersAndStopsClaiming() throws Exception {
        try (Connection c = ownerConnection(PG)) {
            seedQueued(c, "n1", 10, 1, "2026-09-10T09:00:00Z");
        }
        int[] calls = {0};
        AbsenteeDeliveryGateway alwaysDown = request -> {
            calls[0]++;
            return DeliveryOutcome.transientFailure("still down");
        };
        Instant t0 = Instant.parse("2026-09-11T09:00:00Z");

        worker(alwaysDown, Clock.fixed(t0, ZoneOffset.UTC)).drainBatch();
        worker(alwaysDown, Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC)).drainBatch();
        worker(alwaysDown, Clock.fixed(t0.plusSeconds(200), ZoneOffset.UTC)).drainBatch();
        int afterBudget = worker(alwaysDown, Clock.fixed(t0.plusSeconds(10_000), ZoneOffset.UTC)).drainBatch();

        assertThat(calls[0]).isEqualTo(3);
        assertThat(afterBudget).isZero();
        try (Connection c = ownerConnection(PG)) {
            Map<String, Object> state = rowState(c, "n1");
            assertThat(state.get("status")).isEqualTo(AbsenteeNotificationStatus.DEAD_LETTER);
            assertThat(state.get("attempts")).isEqualTo(3);
            assertThat(state.get("deadLetteredAt")).isNotNull();
            assertThat(state.get("nextAttemptAt")).isNull();
        }
    }

    @Test
    void policyDenialAtDispatchSuppressesWithoutCallingTheGateway() throws Exception {
        try (Connection c = ownerConnection(PG)) {
            seedQueued(c, "n1", 10, 1, "2026-09-10T09:00:00Z");
        }
        int[] calls = {0};
        AbsenteeDispatchPolicy deny = (schoolId, studentId, channel, sourceEventId) ->
                DispatchDecision.denied("GUARDIAN_INACTIVE");
        AbsenteeDeliveryWorker worker = new AbsenteeDeliveryWorker(repository, deny, request -> {
            calls[0]++;
            return DeliveryOutcome.dryRun("test");
        }, txManager, new AbsenteeDeliveryStateMachine(3, Duration.ofSeconds(30), Duration.ofMinutes(5)),
                10, Clock.systemUTC());

        assertThat(worker.drainBatch()).isEqualTo(1);

        assertThat(calls[0]).isZero();
        try (Connection c = ownerConnection(PG)) {
            Map<String, Object> state = rowState(c, "n1");
            assertThat(state.get("status")).isEqualTo(AbsenteeNotificationStatus.SUPPRESSED);
            assertThat(state.get("lastError")).isEqualTo("GUARDIAN_INACTIVE");
        }
    }

    @Test
    void freshDecisionBoundToADifferentGuardianOrDestinationIsSuppressed() throws Exception {
        try (Connection c = ownerConnection(PG)) {
            seedQueued(c, "n1", 10, 1, "2026-09-10T09:00:00Z");
        }
        AbsenteeDispatchPolicy rebound = (schoolId, studentId, channel, sourceEventId) -> {
            var now = java.time.OffsetDateTime.now();
            return DispatchDecision.allowed("guardian-1", "918888888888", "consent-1", "notice-v1",
                    channel, schoolId, studentId, now, now.plusSeconds(90), sourceEventId);
        };
        int[] calls = {0};
        AbsenteeDeliveryWorker worker = new AbsenteeDeliveryWorker(repository, rebound, request -> {
            calls[0]++;
            return DeliveryOutcome.dryRun("test");
        }, txManager, new AbsenteeDeliveryStateMachine(3, Duration.ofSeconds(30), Duration.ofMinutes(5)),
                10, Clock.systemUTC());

        worker.drainBatch();

        assertThat(calls[0]).isZero();
        try (Connection c = ownerConnection(PG)) {
            assertThat(rowState(c, "n1").get("status")).isEqualTo(AbsenteeNotificationStatus.SUPPRESSED);
            assertThat(rowState(c, "n1").get("lastError")).isEqualTo("POLICY_BINDING_CHANGED");
        }
    }

    private static AbsenteeDeliveryWorker worker(AbsenteeDeliveryGateway gateway, Clock clock) {
        return new AbsenteeDeliveryWorker(repository, AbsenteeDeliveryTestSupport.allowAll(), gateway, txManager,
                new AbsenteeDeliveryStateMachine(3, Duration.ofSeconds(30), Duration.ofMinutes(5)), 10, clock);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
