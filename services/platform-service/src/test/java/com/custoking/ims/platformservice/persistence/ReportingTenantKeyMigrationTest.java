package com.custoking.ims.platformservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class ReportingTenantKeyMigrationTest {
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("reporting").defaultSchema("reporting").locations("classpath:db/migration/reporting").load().migrate();
    }

    @AfterAll static void tearDown() { if (PG != null) PG.stop(); }

    private String getNullable(String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema='reporting' AND table_name=? AND column_name=?")) {
            ps.setString(1, table); ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Column not found: " + table + "." + column);
                return rs.getString(1);
            }
        }
    }

    @Test
    void commandCenterActions_schoolId_isNotNull() throws Exception {
        assertEquals("NO", getNullable("command_center_actions", "school_id"),
                "command_center_actions.school_id must be NOT NULL after V7 migration");
    }

    @Test
    void commandCenterFeed_schoolId_remainsNullable() throws Exception {
        assertEquals("YES", getNullable("command_center_feed", "school_id"),
                "command_center_feed.school_id must remain nullable (NULL = platform-wide)");
    }

    @Test
    void reportingEventInbox_schoolId_remainsNullable() throws Exception {
        assertEquals("YES", getNullable("reporting_event_inbox", "school_id"),
                "reporting_event_inbox.school_id must remain nullable (cross-school events)");
    }
}
