package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.TestDataSources;
import com.custoking.ims.platformservice.persistence.WhatsappOnboardingStateException.Reason;
import com.custoking.ims.platformservice.security.TenantAwareDataSource;
import com.custoking.ims.platformservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completing or failing a WhatsApp onboarding request: only a REQUESTED session of the named school
 * moves, and completion writes the school's sender profile in the same transaction as the session.
 * Runs as app_rt (NOBYPASSRLS) through TenantAwareDataSource with a superadmin context, the only caller
 * the controller admits, so the school predicate in the SQL is what keeps other schools' sessions out.
 */
class SenderProfileOnboardingIntegrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource ownerDs;
    static DataSource appRt;
    static JdbcClient owner;
    static SenderProfileRepository profiles;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping onboarding integration test");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("notification").defaultSchema("notification")
                .locations("classpath:db/migration/notification")
                .load().migrate();

        HikariDataSource ownerPool = new HikariDataSource();
        ownerPool.setJdbcUrl(PG.getJdbcUrl());
        ownerPool.setUsername("owner");
        ownerPool.setPassword("owner");
        ownerPool.setMaximumPoolSize(2);
        ownerDs = ownerPool;
        owner = JdbcClient.create(ownerDs);
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        owner.sql("GRANT USAGE ON SCHEMA notification TO app_rt").update();
        owner.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA notification TO app_rt").update();

        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(4);
        appRt = new TenantAwareDataSource(pool);
        profiles = new SenderProfileRepository(JdbcClient.create(appRt), new DataSourceTransactionManager(appRt));
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        TestDataSources.close(appRt);
        TestDataSources.close(ownerDs);
        if (PG != null) PG.stop();
    }

    @BeforeEach
    void reset() throws Exception {
        try (Connection c = ownerDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM notification.whatsapp_onboarding_sessions");
            st.execute("DELETE FROM notification.notification_sender_profiles WHERE school_id IS NOT NULL");
        }
        TenantContext.set(new TenantContext(1L, "root@x", "SUPERADMIN", null, null));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void completingARequestedSessionClosesItAndWritesTheSchoolProfile() {
        UUID session = seedSession(10L, "REQUESTED");

        Map<String, Object> result = profiles.completeWhatsappOnboarding(10L, session,
                Map.of("integratedNumber", "+91 98765 43210", "providerReference", "WABA-1"));

        assertEquals("COMPLETED", result.get("status"));
        assertEquals(10L, result.get("schoolId"));
        assertEquals("919876543210", result.get("integratedNumber"));
        assertEquals("COMPLETED", sessionStatus(session));
        assertEquals(Optional.of("919876543210"), profileNumber(10L));
    }

    @Test
    void completingAnUnknownSessionIsNotFoundAndWritesNoProfile() {
        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.completeWhatsappOnboarding(10L, UUID.randomUUID(), Map.of("integratedNumber", "911111111111")));

        assertEquals(Reason.NOT_FOUND, ex.reason());
        assertEquals("WhatsApp onboarding request not found", ex.getMessage());
        assertEquals(Optional.empty(), profileNumber(10L));
    }

    @Test
    void completingAnotherSchoolsSessionIsNotFoundAndChangesNeitherSchool() {
        UUID otherSchools = seedSession(20L, "REQUESTED");

        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.completeWhatsappOnboarding(10L, otherSchools, Map.of("integratedNumber", "911111111111")));

        assertEquals(Reason.NOT_FOUND, ex.reason());
        assertEquals("REQUESTED", sessionStatus(otherSchools));
        assertEquals(Optional.empty(), profileNumber(10L));
        assertEquals(Optional.empty(), profileNumber(20L));
    }

    @Test
    void completingACompletedSessionIsAConflictAndLeavesTheProfileAlone() {
        UUID session = seedSession(10L, "REQUESTED");
        profiles.completeWhatsappOnboarding(10L, session, Map.of("integratedNumber", "912222222222"));

        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.completeWhatsappOnboarding(10L, session, Map.of("integratedNumber", "913333333333")));

        assertEquals(Reason.ALREADY_COMPLETED, ex.reason());
        assertEquals("This WhatsApp onboarding request is already completed", ex.getMessage());
        assertEquals(Optional.of("912222222222"), profileNumber(10L));
    }

    @Test
    void completingAFailedSessionIsAConflictAndWritesNoProfile() {
        UUID session = seedSession(10L, "FAILED");

        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.completeWhatsappOnboarding(10L, session, Map.of("integratedNumber", "911111111111")));

        assertEquals(Reason.ALREADY_FAILED, ex.reason());
        assertEquals("This WhatsApp onboarding request is already failed", ex.getMessage());
        assertEquals("FAILED", sessionStatus(session));
        assertEquals(Optional.empty(), profileNumber(10L));
    }

    @Test
    void aProfileWriteFailureRollsBackTheSessionUpdate() {
        UUID session = seedSession(10L, "REQUESTED");

        // whatsapp_display_name is VARCHAR(160). The request DTO refuses a longer one with 400; calling the repository
        // directly gets past that, so the profile write fails after the session update has run.
        assertThrows(DataAccessException.class, () -> profiles.completeWhatsappOnboarding(10L, session,
                Map.of("integratedNumber", "911111111111", "whatsappDisplayName", "x".repeat(161))));

        assertEquals("REQUESTED", sessionStatus(session));
        assertEquals(Optional.empty(), profileNumber(10L));
    }

    @Test
    void twoWritersOfOneSchoolsProfileAtOnceBothLandOnOneProfile() throws Exception {
        // With no profile yet, two unserialised writers would each pick a new id, and the second insert would break the
        // one-active-profile-per-school index (a 500). Serialised, the second updates what the first inserted.
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<?>> writes = new java.util.ArrayList<>();
            for (String number : java.util.List.of("911111111111", "922222222222")) {
                writes.add(pool.submit(() -> {
                    TenantContext.set(new TenantContext(1L, "root@x", "SUPERADMIN", null, null));
                    try {
                        start.await();
                        return profiles.upsert(10L, Map.of("whatsappIntegratedNumber", number));
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> write : writes) write.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, owner.sql("SELECT count(*) FROM notification.notification_sender_profiles WHERE school_id = 10 AND active")
                .query(Integer.class).single());
        assertTrue(java.util.Set.of("911111111111", "922222222222").contains(profileNumber(10L).orElseThrow()));
    }

    @Test
    void failingARequestedSessionMarksItFailed() {
        UUID session = seedSession(10L, "REQUESTED");

        Map<String, Object> result = profiles.failWhatsappOnboarding(10L, session, Map.of("failureReason", "Number rejected"));

        assertEquals("FAILED", result.get("status"));
        assertEquals("Number rejected", result.get("failureReason"));
        assertEquals("FAILED", sessionStatus(session));
    }

    @Test
    void failingACompletedSessionIsAConflictAndKeepsItCompleted() {
        UUID session = seedSession(10L, "REQUESTED");
        profiles.completeWhatsappOnboarding(10L, session, Map.of("integratedNumber", "912222222222"));

        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.failWhatsappOnboarding(10L, session, Map.of()));

        assertEquals(Reason.ALREADY_COMPLETED, ex.reason());
        assertEquals("COMPLETED", sessionStatus(session));
        assertEquals(Optional.of("912222222222"), profileNumber(10L));
    }

    @Test
    void failingAFailedSessionIsAConflict() {
        UUID session = seedSession(10L, "FAILED");

        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.failWhatsappOnboarding(10L, session, Map.of("failureReason", "again")));

        assertEquals(Reason.ALREADY_FAILED, ex.reason());
    }

    @Test
    void failingAnotherSchoolsSessionIsNotFoundAndLeavesItRequested() {
        UUID otherSchools = seedSession(20L, "REQUESTED");

        WhatsappOnboardingStateException ex = assertThrows(WhatsappOnboardingStateException.class,
                () -> profiles.failWhatsappOnboarding(10L, otherSchools, Map.of()));

        assertEquals(Reason.NOT_FOUND, ex.reason());
        assertEquals("REQUESTED", sessionStatus(otherSchools));
    }

    @Test
    void aSchoolAdminRequestReadsBackItsOwnSession() {
        TenantContext.set(new TenantContext(7L, "admin@x", "ADMIN", 10L, null));

        Map<String, Object> result = profiles.requestWhatsappOnboarding(10L, 7L, Map.of("contactName", "Asha"));

        assertEquals("REQUESTED", result.get("status"));
        assertEquals(10L, result.get("schoolId"));
        assertEquals(7L, ((Number) result.get("requestedBy")).longValue());
        assertTrue(result.get("id") instanceof UUID);
    }

    private static UUID seedSession(long schoolId, String status) {
        UUID id = UUID.randomUUID();
        owner.sql("INSERT INTO notification.whatsapp_onboarding_sessions (id, school_id, status) VALUES (:id, :school, :status)")
                .param("id", id).param("school", schoolId).param("status", status).update();
        return id;
    }

    private static String sessionStatus(UUID id) {
        return owner.sql("SELECT status FROM notification.whatsapp_onboarding_sessions WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private static Optional<String> profileNumber(long schoolId) {
        return owner.sql("""
                        SELECT whatsapp_integrated_number FROM notification.notification_sender_profiles
                        WHERE school_id = :school AND active = TRUE
                        """)
                .param("school", schoolId).query(String.class).optional();
    }
}
