package com.custoking.ims.operationsservice.persistence;

import com.custoking.ims.operationsservice.application.tracker.Sla;
import com.custoking.ims.operationsservice.application.tracker.TrackerStep;
import com.custoking.ims.operationsservice.security.TenantAwareDataSource;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V8 notebook tracker schema, the V10 status outbox and the V11 constraints and grants: seed, row-level security
 * (including the operator disjunct), tenant-safe keys, checks, append-only logs, one active step per tracker.
 */
class NotebookTrackerMigrationTest {

    static PostgreSQLContainer<?> PG;
    static DataSource appRt;
    static long instance10;
    static long instance20;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        // The runtime roles exist before the migrations run, as in production (scripts/create-app-rt-role.sql), so the
        // migrations' own grant and revoke blocks apply to app_rt and the afterMigrate callback mirrors them onto the
        // dedicated runtime role.
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt");
            st.execute("ALTER DEFAULT PRIVILEGES GRANT USAGE, SELECT ON SEQUENCES TO app_rt");
            st.execute("CREATE ROLE ims_operations_rt NOLOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner")
                .schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration/workflow").load().migrate();

        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA workflow TO app_rt");

            instance10 = instance(st, "ORD-10", 10);
            instance20 = instance(st, "ORD-20", 20);
            for (long[] row : new long[][]{{instance10, 10}, {instance20, 20}}) {
                st.execute("INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, placed_at, time_zone) "
                        + "VALUES (" + row[0] + "," + row[1] + ",'ORD-" + row[1] + "', true, 1000, now(), 'Asia/Kolkata')");
                st.execute("INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, step_order, status, started_at) "
                        + "VALUES (" + row[0] + "," + row[1] + ", 1, 'ACTIVE', now())");
                st.execute("INSERT INTO workflow.workflow_actions (instance_id, step_order, action, school_id) "
                        + "VALUES (" + row[0] + ", 1, 'STEP_ASSIGNED'," + row[1] + ")");
                st.execute("INSERT INTO workflow.workflow_tracker_status_outbox (order_id, school_id, status, tracker_version) "
                        + "VALUES ('ORD-" + row[1] + "'," + row[1] + ", 'PRODUCTION_PLANNING', 1)");
            }
        }
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(2);
        appRt = new TenantAwareDataSource(pool);
    }

    private static long instance(Statement st, String order, long school) throws SQLException {
        try (ResultSet rs = st.executeQuery("INSERT INTO workflow.workflow_instances (definition_id, entity_type, entity_id, school_id, status) "
                + "VALUES ('NOTEBOOK_ORDER_TRACKER','NOTEBOOK_ORDER','" + order + "'," + school + ",'IN_PROGRESS') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void clearContext() { TenantContext.clear(); }

    private static long count(String table, String operatorSchools) throws SQLException {
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            if (operatorSchools != null) st.execute("SELECT set_config('app.operator_schools', '" + operatorSchools + "', false)");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow." + table)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static final String[] TENANT_TABLES = {"workflow_notebook_trackers", "workflow_instance_steps", "workflow_instances", "workflow_actions",
            "workflow_tracker_status_outbox"};

    @Test
    void theSeededStepsMatchTheJavaCatalogue() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT step_order, step_name, required_role, customised_only, default_sla_value, default_sla_unit "
                     + "FROM workflow.workflow_steps WHERE definition_id='NOTEBOOK_ORDER_TRACKER' ORDER BY step_order")) {
            int rows = 0;
            while (rs.next()) {
                rows++;
                TrackerStep step = TrackerStep.ofNumber(rs.getInt("step_order")).orElseThrow();
                assertEquals(step.title(), rs.getString("step_name"));
                assertEquals(step.customisedOnly(), rs.getBoolean("customised_only"));
                assertEquals(step == TrackerStep.HEAD_APPROVAL ? "SUPERADMIN" : null, rs.getString("required_role"));
                Sla expected = step.defaultSla().orElse(null);
                if (expected == null) {
                    assertNull(rs.getObject("default_sla_value"));
                    assertNull(rs.getString("default_sla_unit"));
                } else {
                    assertEquals(expected.value(), rs.getInt("default_sla_value"));
                    assertEquals(expected.unit().name(), rs.getString("default_sla_unit"));
                }
            }
            assertEquals(12, rows);
        }
    }

    @Test
    void aSchoolUserSeesOnlyItsOwnTrackerRows() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        for (String table : TENANT_TABLES) assertEquals(1, count(table, null), table);
    }

    @Test
    void anOperatorSeesExactlyTheAssignedSchoolsAndNothingWhenUnassigned() throws Exception {
        TenantContext.set(new TenantContext(5L, "op@x", "OPERATIONS", null, null));
        for (String table : TENANT_TABLES) {
            assertEquals(0, count(table, null), table + " with no operator schools must fail closed");
            assertEquals(1, count(table, "20"), table);
            assertEquals(2, count(table, "10,20"), table);
            assertEquals(0, count(table, "30"), table);
        }
    }

    @Test
    void superadminSeesEverySchool() throws Exception {
        TenantContext.set(new TenantContext(9L, "sa@x", "SUPERADMIN", null, null));
        for (String table : TENANT_TABLES) assertEquals(2, count(table, null), table);
    }

    @Test
    void aSchoolUserCannotWriteAStepRowForAnotherSchool() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            SQLException blocked = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, step_order) VALUES (" + instance20 + ", 20, 2)"));
            assertTrue(blocked.getMessage().contains("row-level security"), blocked.getMessage());
        }
    }

    @Test
    void aChildRowCannotCarryADifferentSchoolFromItsInstance() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
            SQLException mismatch = assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body) VALUES (" + instance10 + ", 20, 1, 'x')"));
            assertTrue(mismatch.getMessage().contains("foreign key"), mismatch.getMessage());
        }
    }

    @Test
    void stepRowTimesMustMatchTheStatus() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
            String base = "INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, step_order, status, started_at, completed_at) VALUES (" + instance10 + ", 10, ";
            assertThrows(SQLException.class, () -> st.execute(base + "3, 'LOCKED', now(), NULL)"));
            assertThrows(SQLException.class, () -> st.execute(base + "3, 'ACTIVE', NULL, NULL)"));
            assertThrows(SQLException.class, () -> st.execute(base + "3, 'COMPLETED', now(), NULL)"));
            assertThrows(SQLException.class, () -> st.execute(base + "3, 'DONE', now(), NULL)"));
            assertThrows(SQLException.class, () -> st.execute(base + "99, 'LOCKED', NULL, NULL)"), "unknown step");
            st.execute(base + "3, 'LOCKED', NULL, NULL)");
            // The tracker rows are shared by the other tests, so remove what this one added.
            st.execute("DELETE FROM workflow.workflow_instance_steps WHERE instance_id = " + instance10 + " AND step_order = 3");
        }
    }

    @Test
    void trackerAndSlaChecksRejectIncompleteValues() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
            assertThrows(SQLException.class, () -> st.execute(
                    "UPDATE workflow.workflow_notebook_trackers SET vendor_name='V' WHERE instance_id=" + instance10));
            assertThrows(SQLException.class, () -> st.execute(
                    "UPDATE workflow.workflow_notebook_trackers SET approved_draft_ref=7 WHERE instance_id=" + instance10));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_school_step_sla (school_id, step_order, sla_value, sla_unit) VALUES (10, 1, 4, NULL)"));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_school_step_sla (school_id, step_order, sla_value, sla_unit) VALUES (10, 1, 0, 'HOURS')"));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_school_step_sla (school_id, step_order, sla_value, sla_unit) VALUES (10, 1, NULL, 'HOURS')"));
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_school_step_sla (school_id, step_order, sla_value, sla_unit) VALUES (10, 1, 6, 'MINUTES')"));
            assertThrows(SQLException.class, () -> st.execute(
                    "UPDATE workflow.workflow_steps SET default_sla_value = 4, default_sla_unit = NULL WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order = 8"));
            st.execute("INSERT INTO workflow.workflow_school_step_sla (school_id, step_order, sla_value, sla_unit) VALUES (10, 8, NULL, NULL)");
            st.execute("INSERT INTO workflow.workflow_school_step_sla (school_id, step_order, sla_value, sla_unit) VALUES (10, 1, 6, 'HOURS')");
            st.execute("DELETE FROM workflow.workflow_school_step_sla WHERE school_id = 10");
        }
    }

    @Test
    void theRuntimeRoleCanMaintainTheGlobalDesignerList() throws Exception {
        TenantContext.set(new TenantContext(9L, "sa@x", "SUPERADMIN", null, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate("INSERT INTO workflow.workflow_designers (name, phone) VALUES ('Priya Singh', '+911234567890')"));
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO workflow.workflow_designers (name, phone) VALUES ('Priya Singh', '+911234567890')"));
            assertThrows(SQLException.class, () -> st.execute("INSERT INTO workflow.workflow_designers (name, phone) VALUES ('  ', '1')"));
        }
    }

    @Test
    void aSecondTrackerForTheSameOrderIsRejected() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
            long sameSchool = instance(st, "ORD-10-DUP", 10);
            long otherSchool = instance(st, "ORD-10-DUP-20", 20);
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, placed_at, time_zone) "
                            + "VALUES (" + sameSchool + ", 10, 'ORD-10', true, 1000, now(), 'Asia/Kolkata')"));
            // Order ids are globally unique, so the same id under another school is rejected too.
            assertThrows(SQLException.class, () -> st.execute(
                    "INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, placed_at, time_zone) "
                            + "VALUES (" + otherSchool + ", 20, 'ORD-10', true, 1000, now(), 'Asia/Kolkata')"));
            st.execute("DELETE FROM workflow.workflow_instances WHERE id IN (" + sameSchool + "," + otherSchool + ")");
        }
    }

    @Test
    void appendOnlyTablesDenyUpdateAndDelete() throws Exception {
        TenantContext.set(new TenantContext(1L, "a@x", "ADMIN", 10L, null));
        try (Connection c = appRt.getConnection(); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate("INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body) "
                    + "VALUES (" + instance10 + ", 10, 1, 'Called the vendor')"));
            assertEquals(1, st.executeUpdate("INSERT INTO workflow.workflow_follow_ups (instance_id, school_id, percent_complete, created_by) "
                    + "VALUES (" + instance10 + ", 10, 40, 1)"));
            for (String table : new String[]{"workflow_internal_notes", "workflow_follow_ups"}) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow." + table)) {
                    rs.next();
                    assertEquals(1, rs.getLong(1), table + " entries stay readable");
                }
                for (String change : new String[]{"UPDATE workflow." + table + " SET created_at = now()", "DELETE FROM workflow." + table}) {
                    SQLException denied = assertThrows(SQLException.class, () -> st.execute(change), change);
                    assertTrue(denied.getMessage().contains("permission denied"), denied.getMessage());
                }
            }
        } finally {
            try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
                st.execute("DELETE FROM workflow.workflow_internal_notes WHERE instance_id = " + instance10);
                st.execute("DELETE FROM workflow.workflow_follow_ups WHERE instance_id = " + instance10);
            }
        }
        // The afterMigrate callback mirrors app_rt's grants onto the dedicated runtime role, so the revoke reaches it too.
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
            for (String table : new String[]{"workflow_internal_notes", "workflow_follow_ups"}) {
                try (ResultSet rs = st.executeQuery("SELECT has_table_privilege('ims_operations_rt', 'workflow." + table + "', 'SELECT'), "
                        + "has_table_privilege('ims_operations_rt', 'workflow." + table + "', 'INSERT'), "
                        + "has_any_column_privilege('ims_operations_rt', 'workflow." + table + "', 'UPDATE'), "
                        + "has_table_privilege('ims_operations_rt', 'workflow." + table + "', 'DELETE')")) {
                    rs.next();
                    assertTrue(rs.getBoolean(1), table + " SELECT");
                    assertTrue(rs.getBoolean(2), table + " INSERT");
                    assertFalse(rs.getBoolean(3), table + " UPDATE");
                    assertFalse(rs.getBoolean(4), table + " DELETE");
                }
            }
        }
    }

    @Test
    void oneActiveStepPerInstance() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement()) {
            String step = "INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, step_order, status, started_at) VALUES (" + instance10 + ", 10, ";
            try {
                // Step 1 of this tracker is already active.
                SQLException second = assertThrows(SQLException.class, () -> st.execute(step + "2, 'ACTIVE', now())"));
                assertTrue(second.getMessage().contains("uk_wis_one_active"), second.getMessage());

                st.execute(step + "2, 'LOCKED', NULL)");
                SQLException activated = assertThrows(SQLException.class, () -> st.execute("UPDATE workflow.workflow_instance_steps "
                        + "SET status = 'ACTIVE', started_at = now() WHERE instance_id = " + instance10 + " AND step_order = 2"));
                assertTrue(activated.getMessage().contains("uk_wis_one_active"), activated.getMessage());
            } finally {
                // The tracker rows are shared by the other tests, so remove what this one added.
                st.execute("DELETE FROM workflow.workflow_instance_steps WHERE instance_id = " + instance10 + " AND step_order = 2");
            }
        }
    }

    @Test
    void redundantAssigneeIndexDropped() throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'workflow' "
                     + "AND indexname IN ('idx_wis_assignee_active', 'idx_wis_active_assignee_due') ORDER BY indexname")) {
            java.util.List<String> names = new java.util.ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            assertEquals(java.util.List.of("idx_wis_active_assignee_due"), names, "V9's (assignee, due) index serves the dropped one's lookups");
        }
    }
}
