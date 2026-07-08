package com.custoking.ims.platformservice.security;

import com.custoking.ims.platformservice.persistence.NotificationLogCommandRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the RLS backstop added on the notification schema's tenant tables in
 * {@code notification/V8__enable_rls.sql}, and — critically — that
 * {@link NotificationLogCommandRepository#createRequestLog} still succeeds with NO TenantContext
 * because it calls {@link ProjectorRls#allow(JdbcClient)} as its first statement. Mirrors
 * {@code ReportingFactRlsIntegrationTest}'s Testcontainers + app_rt NOBYPASSRLS +
 * TenantAwareDataSource + TransactionTemplate harness.
 */
class NotificationRlsIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ownerDs; // owner pool: bypasses RLS, used to seed/verify
    static DataSource appRt;   // app_rt pool wrapped by TenantAwareDataSource: subject to RLS
    static JdbcClient appRtJdbc;
    // Mirrors the real @Transactional proxy: binds ONE connection to the thread for the duration
    // of the callback, so ProjectorRls.allow()'s transaction-local set_config and the subsequent
    // insert land on the same physical connection/transaction.
    static TransactionTemplate txTemplate;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping RLS integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();

        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("notification").defaultSchema("notification")
                .locations("classpath:db/migration/notification")
                .load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("GRANT USAGE ON SCHEMA notification TO app_rt");
            st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA notification TO app_rt");
            st.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA notification TO app_rt");

            // Seed: school 10 (A) x2, school 20 (B) x1 per tenant table under test.
            st.execute("INSERT INTO notification.notification_broadcasts " +
                    "(id, school_id, title, message, audience_type, channels, status) VALUES " +
                    "('b0000000-0000-0000-0000-000000000001', 10, 'A1', 'msg', 'ALL', 'SMS', 'DRAFT')," +
                    "('b0000000-0000-0000-0000-000000000002', 10, 'A2', 'msg', 'ALL', 'SMS', 'DRAFT')," +
                    "('b0000000-0000-0000-0000-000000000003', 20, 'B1', 'msg', 'ALL', 'SMS', 'DRAFT')");

            st.execute("INSERT INTO notification.notification_logs " +
                    "(id, school_id, channel, notification_type) VALUES " +
                    "('log-a1', 10, 'SMS', 'FEE'), ('log-a2', 10, 'SMS', 'FEE'), ('log-b1', 20, 'SMS', 'FEE')");

            st.execute("INSERT INTO notification.whatsapp_onboarding_sessions " +
                    "(id, school_id) VALUES " +
                    "('d0000000-0000-0000-0000-000000000001', 10)," +
                    "('d0000000-0000-0000-0000-000000000002', 10)," +
                    "('d0000000-0000-0000-0000-000000000003', 20)");
        }

        HikariDataSource ownerPool = new HikariDataSource();
        ownerPool.setJdbcUrl(PG.getJdbcUrl());
        ownerPool.setUsername("owner");
        ownerPool.setPassword("owner");
        ownerPool.setMaximumPoolSize(2);
        ownerDs = ownerPool;

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(4);
        appRt = new TenantAwareDataSource(pool);
        appRtJdbc = JdbcClient.create(appRt);
        txTemplate = new TransactionTemplate(new DataSourceTransactionManager(appRt));
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearCtx() { TenantContext.clear(); }

    private long count(String table, String idPrefix) throws SQLException {
        try (Connection c = appRt.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM notification." + table + " WHERE id::text LIKE '" + idPrefix + "%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countAsOwner(String sql) throws SQLException {
        try (Connection c = ownerDs.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // --- (1) isolation reads (broadcasts, logs, whatsapp_onboarding_sessions) ---

    @Test
    void notificationBroadcasts_schoolA_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, count("notification_broadcasts", "b0000000"));
    }

    @Test
    void notificationBroadcasts_schoolB_seesOnlyItsRows() throws Exception {
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, count("notification_broadcasts", "b0000000"));
    }

    @Test
    void notificationBroadcasts_superadmin_seesAll() throws Exception {
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, count("notification_broadcasts", "b0000000"));
    }

    @Test
    void notificationBroadcasts_noContext_seesNothing() throws Exception {
        TenantContext.clear();
        assertEquals(0, count("notification_broadcasts", "b0000000"));
    }

    @Test
    void notificationLogs_isolation() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, count("notification_logs", "log-"));
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, count("notification_logs", "log-"));
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, count("notification_logs", "log-"));
        TenantContext.clear();
        assertEquals(0, count("notification_logs", "log-"));
    }

    @Test
    void whatsappOnboardingSessions_isolation() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        assertEquals(2, count("whatsapp_onboarding_sessions", "d0000000"));
        TenantContext.set(new TenantContext(2L, "b@x", "ADMIN", 20L, null));
        assertEquals(1, count("whatsapp_onboarding_sessions", "d0000000"));
        TenantContext.set(new TenantContext(3L, "s@x", "SUPERADMIN", null, null));
        assertEquals(3, count("whatsapp_onboarding_sessions", "d0000000"));
        TenantContext.clear();
        assertEquals(0, count("whatsapp_onboarding_sessions", "d0000000"));
    }

    // --- (2) WITH CHECK blocks cross-tenant raw insert on the standard-policy tables ---

    @Test
    void withCheck_blocksCrossTenantInsert_notificationBroadcasts() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO notification.notification_broadcasts " +
                    "(id, school_id, title, message, audience_type, channels) VALUES " +
                    "('e0000000-0000-0000-0000-000000000001', 20, 'Mallory', 'msg', 'ALL', 'SMS')"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    @Test
    void withCheck_blocksCrossTenantInsert_notificationLogs() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO notification.notification_logs " +
                    "(id, school_id, channel, notification_type) VALUES " +
                    "('mallory-log', 20, 'SMS', 'FEE')"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    @Test
    void withCheck_blocksCrossTenantInsert_whatsappOnboardingSessions() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO notification.whatsapp_onboarding_sessions " +
                    "(id, school_id) VALUES " +
                    "('e0000000-0000-0000-0000-000000000003', 20)"));
            assertTrue(ex.getMessage().toLowerCase().contains("row-level security"), ex.getMessage());
        }
    }

    // --- (3) CRITICAL: the real createRequestLog writer succeeds with NO TenantContext ---
    // (proves ProjectorRls.allow() is wired into NotificationLogCommandRepository.createRequestLog;
    // without it, WITH CHECK would reject this context-less system-ingestion write.)

    @Test
    void createRequestLog_succeedsWithNoTenantContext() throws Exception {
        TenantContext.clear();
        NotificationLogCommandRepository repo = new NotificationLogCommandRepository(appRtJdbc);

        Map<String, Object> request = Map.of(
                "id", "proj-log-1",
                "schoolId", 10L,
                "channel", "SMS",
                "notificationType", "FEE_PAYMENT");

        txTemplate.executeWithoutResult(status -> repo.createRequestLog(request));

        assertEquals(1, countAsOwner(
                "SELECT count(*) FROM notification.notification_logs WHERE id = 'proj-log-1'"));
    }
}
