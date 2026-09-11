package com.custoking.ims.schoolcoreservice.absentee;

import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository;
import com.custoking.ims.schoolcoreservice.persistence.AbsenteeNotificationDeliveryRepository.ClaimedNotification;
import com.custoking.ims.schoolcoreservice.security.TenantAwareDataSource;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import static com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryTestSupport.ownerConnection;
import static com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryTestSupport.rowState;
import static com.custoking.ims.schoolcoreservice.absentee.AbsenteeDeliveryTestSupport.seedQueued;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker runs on a scheduler thread with no {@link TenantContext}, so nothing sets
 * {@code app.current_school_id} from a JWT. {@code attendance.absentee_notifications} is under RLS.
 *
 * <p>This test connects as {@code app_rt} (NOBYPASSRLS) through {@link TenantAwareDataSource} — the
 * production runtime shape — because the Testcontainers owner role is RLS-exempt and would pass
 * these assertions trivially. It proves that the cross-tenant read bypass used to claim a row is
 * confined to the claim, that the transaction is then hard-scoped to the claimed row's school, and
 * that the status UPDATE therefore cannot touch any other school's row.
 */
class AbsenteeDeliveryTenantScopeIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static HikariDataSource pool;
    static DataSource appRt;
    static JdbcClient jdbc;
    static PlatformTransactionManager txManager;
    static AbsenteeNotificationDeliveryRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = AbsenteeDeliveryTestSupport.startPostgres();
        try (Connection c = ownerConnection(PG); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA attendance TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA attendance TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA attendance TO app_rt");
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(3);
        appRt = new TenantAwareDataSource(pool);
        jdbc = JdbcClient.create(appRt);
        txManager = new DataSourceTransactionManager(appRt);
        repository = new AbsenteeNotificationDeliveryRepository(jdbc, "attendance");
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (pool != null) pool.close();
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        TenantContext.clear();
        try (Connection c = ownerConnection(PG); Statement st = c.createStatement()) {
            st.execute("DELETE FROM attendance.absentee_notifications");
        }
        try (Connection c = ownerConnection(PG)) {
            seedQueued(c, "school10-row", 10, 1, "2026-09-10T09:00:00Z");
            seedQueued(c, "school20-row", 20, 2, "2026-09-10T09:00:01Z");
        }
    }

    @AfterEach
    void clearCtx() {
        TenantContext.clear();
    }

    @Test
    void runtimeRoleWithoutContextSeesNoRowsAtAll() {
        Long visible = jdbc.sql("SELECT count(*) FROM attendance.absentee_notifications").query(Long.class).single();

        assertThat(visible).isZero();
    }

    @Test
    void claimScopesTheTransactionToTheClaimedRowsSchoolAndDropsTheBypass() {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Optional<ClaimedNotification> claimed = repository.claimNextScoped(OffsetDateTime.now());

            assertThat(claimed).isPresent();
            assertThat(claimed.get().id()).isEqualTo("school10-row");
            assertThat(claimed.get().schoolId()).isEqualTo(10L);
            assertThat(jdbc.sql("SELECT current_setting('app.bypass_rls', true)").query(String.class).single())
                    .isEqualTo("off");
            assertThat(jdbc.sql("SELECT current_setting('app.current_school_id', true)").query(String.class).single())
                    .isEqualTo("10");
            // Only the claimed school's row is visible for the rest of the transaction.
            assertThat(jdbc.sql("SELECT count(*) FROM attendance.absentee_notifications").query(Long.class).single())
                    .isEqualTo(1L);
            status.setRollbackOnly();
        });
    }

    @Test
    void bypassUsedForTheClaimDoesNotLeakIntoWrites() throws Exception {
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            repository.claimNextScoped(OffsetDateTime.now());

            // A deliberately unscoped UPDATE inside the claimed transaction: under RLS it can only
            // reach the claimed school's row. If the bypass leaked, this would touch both rows.
            int touched = jdbc.sql("UPDATE attendance.absentee_notifications SET last_error = 'leak-probe'").update();

            assertThat(touched).isEqualTo(1);
        });
        try (Connection c = ownerConnection(PG)) {
            assertThat(rowState(c, "school10-row").get("lastError")).isEqualTo("leak-probe");
            assertThat(rowState(c, "school20-row").get("lastError")).isNull();
        }
    }

    @Test
    void statusUpdateForAnotherSchoolsRowAffectsNothing() throws Exception {
        AbsenteeDeliveryStateMachine machine = new AbsenteeDeliveryStateMachine(3, Duration.ofSeconds(30), Duration.ofMinutes(5));
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Optional<ClaimedNotification> claimed = repository.claimNextScoped(OffsetDateTime.now());
            assertThat(claimed.get().schoolId()).isEqualTo(10L);

            ClaimedNotification foreign = new ClaimedNotification("school20-row", 20L, 2L, "c1", "s1", "y1",
                    LocalDate.of(2026, 9, 10), "919999999999", "WHATSAPP", "msg", 0,
                    "guardian-2", DispatchDecision.destinationSha256("WHATSAPP", "919999999999"), "consent-1", "notice-v1");
            int updated = repository.markOutcome(foreign,
                    machine.apply(0, DeliveryOutcome.dryRun("test"), OffsetDateTime.now()),
                    DeliveryOutcome.dryRun("test"), null);

            assertThat(updated).isZero();
        });
        try (Connection c = ownerConnection(PG)) {
            assertThat(rowState(c, "school20-row").get("status")).isEqualTo(AbsenteeNotificationStatus.QUEUED);
            assertThat(rowState(c, "school20-row").get("attempts")).isEqualTo(0);
        }
    }

    @Test
    void workerDrainsEveryTenantAsTheRuntimeRoleAndNothingLeaksAfterwards() throws Exception {
        AbsenteeDeliveryWorker worker = new AbsenteeDeliveryWorker(repository, AbsenteeDeliveryTestSupport.allowAll(),
                request -> DeliveryOutcome.dryRun("test"), txManager,
                new AbsenteeDeliveryStateMachine(3, Duration.ofSeconds(30), Duration.ofMinutes(5)), 10, Clock.systemUTC());

        assertThat(worker.drainBatch()).isEqualTo(2);

        try (Connection c = ownerConnection(PG)) {
            assertThat(rowState(c, "school10-row").get("status")).isEqualTo(AbsenteeNotificationStatus.SENT_DRY_RUN);
            assertThat(rowState(c, "school20-row").get("status")).isEqualTo(AbsenteeNotificationStatus.SENT_DRY_RUN);
        }
        // The transaction-local GUCs are gone: a fresh, context-less statement sees nothing again.
        assertThat(jdbc.sql("SELECT count(*) FROM attendance.absentee_notifications").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT current_setting('app.bypass_rls', true)").query(String.class).single())
                .isEqualTo("off");
    }
}
