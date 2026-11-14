package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyCatalogConsolidationIntegrationTest {

    static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        postgres = new PostgreSQLContainer<>("postgres:16")
                .withUsername("owner")
                .withPassword("owner");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "owner", "owner")
                .schemas("catalog")
                .defaultSchema("catalog")
                .locations("classpath:db/migration/catalog")
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void onlyExplicitlyMappedRowsReachCanonicalTables() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO catalog.supply_orders
                        (code, title, category, items, amount, status, order_date, action_label)
                    VALUES ('SUP-1', 'Books', 'stationery', '10 boxes', 2500, 'delivered', current_date, 'View')
                    """);
            statement.execute("""
                    INSERT INTO catalog.annual_plan_entries
                        (id, term_name, category, status, quantity, amount)
                    VALUES (71, 'Term 1', 'notebooks', 'planned', '100', 9000)
                    """);
            statement.execute("""
                    INSERT INTO catalog.legacy_catalog_migration_map
                        (source_table, source_id, school_id, mapped_by, mapped_at)
                    VALUES ('supply_orders', 'SUP-1', 10, 'test', now())
                    """);
            statement.execute("""
                    INSERT INTO catalog.legacy_catalog_migration_map
                        (source_table, source_id, school_id, mapped_by, mapped_at)
                    VALUES ('annual_plan_entries', '71', 10, 'test', now())
                    """);

            statement.execute("SELECT * FROM catalog.apply_legacy_catalog_mappings()");

            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM catalog.catalog_orders
                    WHERE legacy_source = 'supply_orders' AND legacy_source_id = 'SUP-1'
                    """));
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM catalog.annual_plan_items
                    WHERE legacy_source = 'annual_plan_entries' AND legacy_source_id = '71'
                    """));
            assertEquals(1, scalar(statement, """
                    SELECT academic_year_mapping_required_rows
                    FROM catalog.legacy_catalog_migration_readiness
                    WHERE source_table = 'annual_plan_entries'
                    """));

            statement.execute("""
                    UPDATE catalog.legacy_catalog_migration_map
                    SET academic_year_id = 'AY-2026', mapped_at = now()
                    WHERE source_table = 'annual_plan_entries' AND source_id = '71'
                    """);
            statement.execute("SELECT * FROM catalog.apply_legacy_catalog_mappings()");

            assertEquals(1, scalar(statement, """
                    SELECT count(*) FROM catalog.annual_plan_items
                    WHERE legacy_source = 'annual_plan_entries' AND legacy_source_id = '71'
                      AND school_id = 10 AND academic_year_id = 'AY-2026'
                    """));
        }
    }

    /**
     * Catalog V41 (design D13): a legacy order the function migrates placed, of a category with an order code, would otherwise
     * stay placed without a number for good (V38's repair trigger sees only an update out of DRAFT). It is queued for repair,
     * so the creation queue numbers it; a draft, a category without a code and an order migrated already are not queued.
     */
    @Test
    void aPlacedOrderOfACodedCategoryIsQueuedForItsNumber() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO catalog.supply_orders (code, title, category, items, amount, status, order_date, action_label) VALUES
                        ('SUP-NB', 'Notebooks', 'Notebooks', '1200 books', 54000, 'Placed', DATE '2026-03-02', 'View'),
                        ('SUP-TIE', 'Ties', ' ties ', '300 ties', 9000, 'delivered', DATE '2026-03-03', 'View'),
                        ('SUP-NB-DRAFT', 'Notebooks', 'notebooks', '10 books', 500, 'draft', DATE '2026-03-04', 'View'),
                        ('SUP-ST', 'Pens', 'stationery', '10 boxes', 2500, 'placed', DATE '2026-03-05', 'View')
                    """);
            statement.execute("""
                    INSERT INTO catalog.legacy_catalog_migration_map (source_table, source_id, school_id, mapped_by, mapped_at)
                    SELECT 'supply_orders', code, 20, 'test', now() FROM catalog.supply_orders
                    WHERE code IN ('SUP-NB', 'SUP-TIE', 'SUP-NB-DRAFT', 'SUP-ST')
                    """);

            statement.execute("SELECT * FROM catalog.apply_legacy_catalog_mappings()");

            assertEquals(4, scalar(statement, """
                    SELECT count(*) FROM catalog.catalog_orders
                    WHERE legacy_source_id IN ('SUP-NB', 'SUP-TIE', 'SUP-NB-DRAFT', 'SUP-ST') AND school_id = 20 AND order_number IS NULL
                    """));
            assertEquals("SUP-NB NOTEBOOKS PLACED true, SUP-TIE TIES DELIVERED true", text(statement, """
                    SELECT string_agg(co.legacy_source_id || ' ' || co.category || ' ' || co.status || ' ' || q.repair, ', '
                                      ORDER BY co.legacy_source_id)
                    FROM catalog.order_workflow_creation_queue q JOIN catalog.catalog_orders co ON co.id = q.order_id
                    WHERE q.school_id = 20
                    """));

            // Idempotent: a re-run migrates nothing and queues nothing again.
            statement.execute("DELETE FROM catalog.order_workflow_creation_queue WHERE school_id = 20");
            statement.execute("SELECT * FROM catalog.apply_legacy_catalog_mappings()");
            assertEquals(0, scalar(statement, "SELECT count(*) FROM catalog.order_workflow_creation_queue WHERE school_id = 20"));
        }
    }

    private static String text(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "owner", "owner");
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
