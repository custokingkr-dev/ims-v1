package com.custoking.ims.schoolcoreservice.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog V40 (item order workflows design v1, "Contract release: retiring the rollout bridges"): the old creation queue
 * {@code catalog.notebook_tracker_creation_queue}, its forwarding trigger and function go; a row a writer left in it with
 * the trigger bypassed is forwarded first, exactly as the trigger would have forwarded it; V38's placement trigger stays.
 * <p>
 * The migrations run as a table owner that is neither superuser nor BYPASSRLS, as in every deployed environment, so the
 * forced row-level security on the queues hides every row from it unless the migration turns the bypass on. The runtime
 * writes run as the roles a deployed school-core connects as.
 */
class CreationQueueRetirementMigrationTest {
    static final String OWNER = "migration_owner";
    static PostgreSQLContainer<?> pg;
    static JdbcClient admin;
    /** V40's first attempt, while a reader held catalog_orders: its failure, and what was left as it was. */
    static String busyFailure;
    static long busySeconds;
    static long oldRowsAfterBusyFailure;
    static Integer versionAfterBusyFailure;

    @BeforeAll
    static void migrate() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("admin").withPassword("admin");
        pg.start();
        admin = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "admin", "admin"));
        admin.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        admin.sql("CREATE ROLE ims_school_core_rt LOGIN PASSWORD 'ims_school_core_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        admin.sql("CREATE ROLE " + OWNER + " LOGIN PASSWORD '" + OWNER + "' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB").update();
        admin.sql("GRANT CREATE ON DATABASE " + pg.getDatabaseName() + " TO " + OWNER).update();
        // scripts/create-app-rt-role.sql: every table the owner creates hands app_rt DML unless its migration takes some back.
        admin.sql("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt").update();
        admin.sql("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT USAGE, SELECT ON SEQUENCES TO app_rt").update();
        flyway("tenant_school", null).migrate();
        flyway("catalog", "39").migrate();
        admin.sql("GRANT USAGE ON SCHEMA catalog, tenant_school TO app_rt").update();
        seed();

        // A reader holds catalog_orders: V40 retries for 15 seconds, then stops having changed nothing.
        try (Connection reader = DriverManager.getConnection(pg.getJdbcUrl(), "admin", "admin"); Statement st = reader.createStatement()) {
            reader.setAutoCommit(false);
            st.executeQuery("SELECT count(*) FROM catalog.catalog_orders").close();
            long started = System.nanoTime();
            try {
                flyway("catalog", "40").migrate();
                busyFailure = "migrated";
            } catch (FlywayException failure) {
                busyFailure = String.valueOf(failure.getMessage());
            }
            busySeconds = (System.nanoTime() - started) / 1_000_000_000L;
            reader.rollback();
        }
        oldRowsAfterBusyFailure = admin.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue").query(Long.class).single();
        versionAfterBusyFailure = latestVersion();

        flyway("catalog", "40").migrate();
    }

    @AfterAll
    static void stop() {
        if (pg != null) pg.stop();
    }

    private static Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(pg.getJdbcUrl(), OWNER, OWNER).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/" + schema);
        if (target != null) configuration = configuration.target(target);
        return configuration.load();
    }

    private static void seed() throws Exception {
        admin.sql("""
                INSERT INTO tenant_school.schools (id, name, short_code, city, active, created_at, time_zone) VALUES
                    (1, 'Delhi Public School', 'DPS1', 'Gurugram', true, now(), 'Asia/Kolkata'),
                    (2, 'The Academy', 'ACAD', 'Bengaluru', true, now(), 'Asia/Kolkata')
                """).update();
        order("CK-501", 1, "FLIERS", "ORDER_DETAILS", "{\"trackerManaged\": true}", "FE-DPS_GUR-26-11-13-0001");
        order("CK-502", 2, "NOTEBOOKS", "PRINTING_PRESS", "{\"trackerManaged\": true}", "NB-TA_BEN-26-11-13-0001");
        order("CK-503", 1, "FLIERS", "DRAFT", "{}", null);
        order("CK-504", 1, "UNIFORMS", "DRAFT", "{}", null);
        order("CK-505", 1, "FLIERS", "DRAFT", "{}", null);
        order("CK-506", 1, "FLIERS", "DRAFT", "{}", null);
        // CK-502 was queued in the new queue, given up on after its retries, and is a repair row.
        admin.sql("""
                INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, attempts, next_attempt_at, last_error, created_at,
                    dead_lettered_at, repair)
                VALUES ('CK-502', 2, 48, now() + interval '1 hour', 'order workflow engine answered HTTP 503', '2020-01-01T05:00:00Z', now(), true)
                """).update();
        // Rows written to the old queue with its forwarding trigger bypassed (session_replication_role = replica): the only
        // way the old table can hold a row since V38.
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "admin", "admin"); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("""
                    INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id, attempts, next_attempt_at, last_error, created_at)
                    VALUES ('CK-501', 1, 7, now() + interval '1 day', 'stale error', '2020-06-01T05:00:00Z'),
                           ('CK-502', 2, 3, now() + interval '1 day', 'other stale error', '2020-06-01T05:00:00Z')
                    """);
            // Replica mode skips the old table's foreign key as well: rows for an order that does not exist, or not at that school.
            st.execute("""
                    INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id)
                    VALUES ('CK-999', 1), ('CK-506', 2)
                    """);
        }
        assertThat(admin.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue").query(Long.class).single()).isEqualTo(4L);
    }

    @Test
    void aBusyCatalogOrdersTableStopsTheMigrationWithinAboutFifteenSecondsHavingChangedNothing() {
        assertThat(busyFailure).contains("V40: catalog_orders and notebook_tracker_creation_queue stayed busy for 15 seconds; "
                + "nothing was changed, re-run the migration job");
        assertThat(busySeconds).as("retried, not failed at once, and not queued for long").isBetween(14L, 30L);
        assertThat(oldRowsAfterBusyFailure).as("the old queue and its rows were still there").isEqualTo(4L);
        assertThat(versionAfterBusyFailure).isEqualTo(39);
    }

    @Test
    void theOldQueueItsForwardingTriggerAndItsFunctionAreGone() {
        assertThat(latestVersion()).isEqualTo(40);
        assertThat(admin.sql("SELECT to_regclass('catalog.notebook_tracker_creation_queue') IS NULL").query(Boolean.class).single()).isTrue();
        assertThat(admin.sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "WHERE n.nspname = 'catalog' AND p.proname = 'forward_to_order_workflow_creation_queue'").query(Long.class).single()).isZero();
        assertThat(admin.sql("SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_notebook_tracker_creation_queue_forward'")
                .query(Long.class).single()).isZero();
    }

    /**
     * Each row is forwarded as V38's forwarding function forwards an insert: a new row with fresh retries, and a row already
     * queued due again now with fresh retries, keeping its repair flag. Rows of every school move: the bypass is on.
     */
    @Test
    void rowsLeftInTheOldQueueAreForwardedExactlyAsItsTriggerWouldHaveForwardedThem() {
        assertThat(admin.sql("""
                SELECT order_id || ' ' || school_id || ' ' || attempts || ' ' || (next_attempt_at <= now()) || ' ' || (dead_lettered_at IS NULL)
                       || ' ' || coalesce(last_error, '-') || ' ' || repair || ' ' || CASE WHEN created_at < '2021-01-01' THEN 'kept' ELSE 'new' END
                FROM catalog.order_workflow_creation_queue WHERE order_id IN ('CK-501', 'CK-502') ORDER BY order_id
                """).query(String.class).list()).containsExactly(
                "CK-501 1 0 true true - false new",
                "CK-502 2 0 true true order workflow engine answered HTTP 503 true kept");
    }

    /**
     * A leftover row whose order does not exist (or not at that school) could never have been forwarded: the new queue's
     * foreign key refuses it, as it refused the trigger. V40 drops it with the table instead of failing on it at every re-run.
     */
    @Test
    void rowsLeftInTheOldQueueForAnOrderThatDoesNotExistAreDroppedNotForwarded() {
        assertThat(admin.sql("SELECT count(*) FROM catalog.order_workflow_creation_queue WHERE order_id IN ('CK-999', 'CK-506')")
                .query(Long.class).single()).isZero();
        assertThat(admin.sql("SELECT count(*) FROM catalog.catalog_orders WHERE id = 'CK-506' AND school_id = 1 AND status = 'DRAFT'")
                .query(Long.class).single()).as("the order a mismatched row named is untouched").isEqualTo(1L);
    }

    /**
     * Prod's first release with the item order workflows still needs V38's placement trigger, for the orders origin/main's
     * school-core places without a number during the canary: it stays, enabled, and still queues such a placement for repair,
     * as the dedicated runtime role. A placement that mints, a category without a code and any other change are not queued.
     */
    @Test
    void thePlacementTriggerStaysAndStillQueuesAnUnnumberedPlacementForRepair() {
        assertThat(admin.sql("SELECT tgenabled FROM pg_trigger WHERE tgname = 'trg_catalog_orders_queue_unnumbered_placement'")
                .query(String.class).single()).isEqualTo("O");
        String comment = admin.sql("SELECT obj_description('catalog.queue_unnumbered_placement()'::regprocedure, 'pg_proc')")
                .query(String.class).single();
        assertThat(comment).contains("kept by V40").contains("CatalogReadRepository.updateOrderStatus").contains("canary")
                .contains("rollback target");
        assertThat(admin.sql("""
                SELECT obj_description(t.oid, 'pg_trigger') FROM pg_trigger t WHERE t.tgname = 'trg_catalog_orders_queue_unnumbered_placement'
                """).query(String.class).single()).contains("may be dropped");

        asRuntime("ims_school_core_rt", 1, client -> {
            // CatalogOrderFormService.transition at origin/main, verbatim in effect: placed, never numbered.
            client.sql("""
                    UPDATE catalog.catalog_orders SET status = 'PROCESSING', design_status = 'NOT_REQUIRED', superadmin_approval_status = 'PENDING',
                        placed_at = now(), placed_by = 7, version = version + 1 WHERE id IN ('CK-503', 'CK-504')
                    """).update();
            client.sql("""
                    DO $placed$ BEGIN
                        UPDATE catalog.catalog_orders SET status = 'PROCESSING', placed_at = now() WHERE id = 'CK-505';
                        UPDATE catalog.catalog_orders SET order_number = 'FE-DPS_GUR-26-11-14-0002' WHERE id = 'CK-505';
                    END $placed$
                    """).update();
        });
        assertThat(admin.sql("SELECT order_id || ' ' || repair FROM catalog.order_workflow_creation_queue WHERE order_id IN ('CK-503', 'CK-504', 'CK-505')")
                .query(String.class).list()).containsExactly("CK-503 true");
    }

    @Test
    void theNewQueueKeepsItsRowLevelSecurityAndTheRuntimeRolesKeepTheirPrivileges() {
        assertThat(admin.sql("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'catalog.order_workflow_creation_queue'::regclass")
                .query(Boolean.class).single()).isTrue();
        assertThat(admin.sql("SELECT count(*) FROM pg_policies WHERE schemaname = 'catalog' AND tablename = 'order_workflow_creation_queue' AND policyname = 'tenant_isolation'")
                .query(Long.class).single()).isEqualTo(1L);
        for (String role : List.of("app_rt", "ims_school_core_rt")) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(admin.sql("SELECT has_table_privilege(:role, 'catalog.order_workflow_creation_queue', :privilege)")
                        .param("role", role).param("privilege", privilege).query(Boolean.class).single()).as(role + " " + privilege).isTrue();
            }
            assertThat(admin.sql("SELECT has_function_privilege(:role, 'catalog.queue_unnumbered_placement()', 'EXECUTE')")
                    .param("role", role).query(Boolean.class).single()).as(role).isTrue();
        }
        // afterMigrate keeps the dedicated role at exactly the runtime role's table privileges, table by table.
        assertThat(admin.sql("""
                SELECT c.relname || ' ' || p.privilege FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) AS p(privilege)
                WHERE n.nspname = 'catalog' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                  AND has_table_privilege('app_rt', c.oid, p.privilege) <> has_table_privilege('ims_school_core_rt', c.oid, p.privilege)
                """).query(String.class).list()).isEmpty();
        asRuntime("ims_school_core_rt", 2, client -> assertThatThrownBy(() -> client.sql(
                "INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id) VALUES ('CK-505', 1)").update())
                .as("another school's order is refused").hasStackTraceContaining("row-level security"));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------

    private static void order(String id, long school, String category, String status, String attributes, String number) {
        admin.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                    design_status, superadmin_approval_status, placed_at, created_at, form_version, attributes, version, order_number)
                VALUES (:id, :school, :category, '{}', 0, 0, 0, :status, 'NOT_REQUIRED', 'PENDING',
                    CASE WHEN :status <> 'DRAFT' THEN now() END, now(), 2, CAST(:attributes AS jsonb), 1, :number)
                """).param("id", id).param("school", school).param("category", category).param("status", status)
                .param("attributes", attributes).param("number", number).update();
    }

    private static Integer latestVersion() {
        return admin.sql("SELECT max(version::int) FROM catalog.flyway_schema_history WHERE success AND version IS NOT NULL")
                .query(Integer.class).single();
    }

    /** One connection as a runtime role, scoped to a school as TenantAwareDataSource scopes a school's request. */
    private static void asRuntime(String role, long school, Consumer<JdbcClient> work) {
        var dataSource = new SingleConnectionDataSource(pg.getJdbcUrl(), role, role, true);
        try {
            var client = JdbcClient.create(dataSource);
            client.sql("SELECT set_config('app.current_school_id', :school, false)").param("school", String.valueOf(school)).query().singleRow();
            work.accept(client);
        } finally {
            dataSource.destroy();
        }
    }
}
