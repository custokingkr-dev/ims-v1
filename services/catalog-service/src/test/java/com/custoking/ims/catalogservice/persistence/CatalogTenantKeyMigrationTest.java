package com.custoking.ims.catalogservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTenantKeyMigrationTest {
    static PostgreSQLContainer<?> PG;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("catalog").defaultSchema("catalog").locations("classpath:db/migration").load().migrate();
    }

    @AfterAll static void tearDown() { if (PG != null) PG.stop(); }

    private boolean isNotNull(String table, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             PreparedStatement ps = c.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema='catalog' AND table_name=? AND column_name=?")) {
            ps.setString(1, table); ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return "NO".equals(rs.getString(1)); }
        }
    }

    @Test void catalogOrders_schoolId_isNotNull() throws Exception { assertTrue(isNotNull("catalog_orders", "school_id")); }
    @Test void annualPlanItems_schoolId_isNotNull() throws Exception { assertTrue(isNotNull("annual_plan_items", "school_id")); }
}
