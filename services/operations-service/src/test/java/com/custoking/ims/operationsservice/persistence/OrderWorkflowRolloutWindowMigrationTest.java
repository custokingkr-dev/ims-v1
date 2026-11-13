package com.custoking.ims.operationsservice.persistence;

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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow schema V21: the rollout window after V19 and V20. A release runs the migrations before the new revision serves,
 * so the notebook tracker's revision keeps writing for a few minutes: vendor selections in its own format and stage notes
 * into the column V20 retired. V21 repeats V19's remap and V20's copy for what it wrote since, refuses any later selection
 * the engine cannot read, and copies each later stage note into the stage's entries.
 * <p>
 * The migrations run as a table owner that is neither superuser nor BYPASSRLS, as in production; the old revision's writes
 * run as the runtime role {@code app_rt}, scoped to their school as the old revision scoped them.
 */
class OrderWorkflowRolloutWindowMigrationTest {

    static final String OWNER = "migration_owner";
    static PostgreSQLContainer<?> PG;
    static final Map<String, Long> INSTANCE = new HashMap<>();
    static Map<String, Long> versionsBefore;
    static Map<String, Long> versionsAfter;

    @BeforeAll
    static void migrateToV20WriteAsTheOldRevisionThenMigrateToV21() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("admin").withPassword("admin");
        PG.start();
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("CREATE ROLE ims_operations_rt NOLOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("CREATE ROLE " + OWNER + " LOGIN PASSWORD '" + OWNER + "' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB");
            st.execute("GRANT CREATE ON DATABASE " + PG.getDatabaseName() + " TO " + OWNER);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt");
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT USAGE, SELECT ON SEQUENCES TO app_rt");
        }
        migrate("20");
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA workflow TO app_rt");
            tracker(st, "CK-201", 10);   // the old revision saves a selection in its own format after V19
            tracker(st, "CK-202", 10);   // a selection V19 already remapped
            tracker(st, "CK-203", 20);   // the old revision clears every list after V19
            tracker(st, "CK-204", 20);   // never selected anything
            st.execute("UPDATE workflow.workflow_notebook_trackers SET vendor_selections = '{\"BOARD_SUPPLIERS\": " + ids("board_prime")
                    + "}' WHERE order_id = 'CK-202'");
            // A stage note V20 already copied, as V20 leaves it: the note stays in the old column too.
            st.execute("UPDATE workflow.workflow_instance_steps SET stage_note = 'Copied by V20', stage_note_saved_by = 7, "
                    + "stage_note_saved_at = '2026-11-12T05:00:00Z' WHERE instance_id = " + INSTANCE.get("CK-202") + " AND step_order = 1");
            st.execute("INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order) "
                    + "VALUES (" + INSTANCE.get("CK-202") + ", 10, 7, 'Copied by V20', '2026-11-12T05:00:00Z', 1)");
        }
        // The old revision, between V20 and V21 (its SQL, as the runtime role in its school's scope).
        oldRevision(10, "UPDATE workflow.workflow_notebook_trackers SET vendor_selections = CAST('{\"board\": [\"board_shree\"], "
                + "\"offset\": [\"offset_metro\", \"offset_cityline\"], \"binder\": []}' AS jsonb), version = version + 1 WHERE order_id = 'CK-201'");
        oldRevision(20, "UPDATE workflow.workflow_notebook_trackers SET vendor_selections = CAST('{\"board\": [], \"offset\": []}' AS jsonb), "
                + "version = version + 1 WHERE order_id = 'CK-203'");
        oldRevision(10, "UPDATE workflow.workflow_instance_steps SET stage_note = 'Boards booked with Shree', stage_note_saved_at = "
                + "'2026-11-13T06:00:00Z', stage_note_saved_by = 8, version = version + 1 WHERE instance_id = " + INSTANCE.get("CK-201")
                + " AND step_order = 2");
        versionsBefore = versions();

        migrate("21");
        versionsAfter = versions();
    }

    @AfterAll
    static void stop() {
        if (PG != null) PG.stop();
    }

    @Test
    void selectionsSavedInTheOldFormatAreRemappedAndOnlyThose() throws Exception {
        assertSelections("CK-201", "{\"BOARD_SUPPLIERS\": " + ids("board_shree") + ", \"OFFSET_PRINTERS\": "
                + ids("offset_metro", "offset_cityline") + "}");
        assertSelections("CK-202", "{\"BOARD_SUPPLIERS\": " + ids("board_prime") + "}");
        assertSelections("CK-203", "{}");
        assertSelections("CK-204", "{}");
        for (String order : List.of("CK-201", "CK-203")) assertEquals(versionsBefore.get(order) + 1, versionsAfter.get(order), order);
        for (String order : List.of("CK-202", "CK-204")) assertEquals(versionsBefore.get(order), versionsAfter.get(order), order + " is untouched");
    }

    @Test
    void stageNotesSavedSinceV20BecomeStageEntriesOnce() throws Exception {
        assertEquals(List.of("CK-201|2|8|Boards booked with Shree|2026-11-13 06:00", "CK-202|1|7|Copied by V20|2026-11-12 05:00"),
                rows("SELECT t.order_id, n.step_order, n.author_user_id, n.body, to_char(n.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') "
                        + "FROM workflow.workflow_internal_notes n JOIN workflow.workflow_notebook_trackers t ON t.instance_id = n.instance_id "
                        + "ORDER BY t.order_id, n.step_order"));
    }

    @Test
    void anOldRevisionsSelectionIsRefusedFromNowOnAndTheEnginesIsNot() throws Exception {
        String update = "UPDATE workflow.workflow_notebook_trackers SET vendor_selections = CAST('%s' AS jsonb), version = version + 1 "
                + "WHERE order_id = 'CK-204'";
        for (String refused : List.of("{\"board\": [\"board_shree\"]}", "{\"board\": []}", "{\"BOARD_SUPPLIERS\": [\"board_shree\"]}",
                "{\"BOARD_SUPPLIERS\": 3}", "[]", "{\"BOARD_SUPPLIERS\": [[3]]}")) {
            SQLException failure = assertThrows(SQLException.class, () -> oldRevision(20, update.formatted(refused)), refused);
            assertTrue(failure.getMessage().contains("ck_wnt_vendor_selections"), failure.getMessage());
        }
        long vendor = Long.parseLong(rows("SELECT id FROM workflow.workflow_vendors WHERE legacy_key = 'binder_metro'").getFirst());
        try {
            for (String accepted : List.of("{}", "{\"BINDERS\": []}", "{\"BINDERS\": [" + vendor + "], \"OFFSET_PRINTERS\": []}")) {
                oldRevision(20, update.formatted(accepted));
                assertSelections("CK-204", accepted);
            }
        } finally {
            oldRevision(20, update.formatted("{}"));
        }
    }

    @Test
    void aStageNoteTheOldRevisionStillSavesBecomesAnEntryOfThatStage() throws Exception {
        long instance = INSTANCE.get("CK-203");
        String note = "UPDATE workflow.workflow_instance_steps SET stage_note = %s, stage_note_saved_at = now(), stage_note_saved_by = %s, "
                + "version = version + 1 WHERE instance_id = " + instance + " AND step_order = 3";
        try {
            oldRevision(20, note.formatted("'Printer promised Friday'", "9"));
            oldRevision(20, note.formatted("'Printer promised Friday'", "9"));            // saved again unchanged
            oldRevision(20, note.formatted("'Printer promised Saturday'", "9"));          // edited: a second entry
            oldRevision(20, note.formatted("'   '", "9"));                                // blanked: no entry
            oldRevision(20, note.formatted("NULL", "NULL"));                              // cleared: no entry
            oldRevision(20, "UPDATE workflow.workflow_instance_steps SET data = '{\"x\": 1}'::jsonb, version = version + 1 "
                    + "WHERE instance_id = " + instance + " AND step_order = 3");          // any other change: no entry
            assertEquals(List.of("3|9|Printer promised Friday", "3|9|Printer promised Saturday"),
                    rows("SELECT step_order, author_user_id, body FROM workflow.workflow_internal_notes WHERE instance_id = " + instance
                            + " ORDER BY id"));
            assertEquals(List.of("20|20"), rows("SELECT min(school_id), max(school_id) FROM workflow.workflow_internal_notes "
                    + "WHERE instance_id = " + instance), "the entry belongs to the step's school");
        } finally {
            try (Connection c = admin(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM workflow.workflow_internal_notes WHERE instance_id = " + instance);
            }
        }
    }

    @Test
    void theRuntimeRolesMayRunTheCopyAndNothingElseChanged() throws Exception {
        for (String role : List.of("app_rt", "ims_operations_rt")) {
            assertEquals(List.of("true"), rows("SELECT has_function_privilege('" + role + "', 'workflow.copy_retired_stage_note()', 'EXECUTE')"),
                    role);
        }
        assertEquals(List.of("copy_retired_stage_note|false"), rows("SELECT p.proname, p.prosecdef FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'workflow'"), "runs with the saver's rights");
    }

    // ---- seed ------------------------------------------------------------------------------------------------------

    /** A customised notebook tracker at Step 1, with a row per step. */
    private static void tracker(Statement st, String order, long school) throws SQLException {
        long id;
        try (ResultSet rs = st.executeQuery("INSERT INTO workflow.workflow_instances (definition_id, entity_type, entity_id, school_id, "
                + "current_step, status) VALUES ('NOTEBOOK_ORDER_TRACKER', 'NOTEBOOK_ORDER', '" + order + "', " + school
                + ", 1, 'IN_PROGRESS') RETURNING id")) {
            rs.next();
            id = rs.getLong(1);
        }
        INSTANCE.put(order, id);
        st.execute("INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, "
                + "placed_at, time_zone, definition_id, category_code, item_code, item_noun) VALUES (" + id + ", " + school + ", '" + order
                + "', true, 1200, '2026-11-01T04:30:00Z', 'Asia/Kolkata', 'NOTEBOOK_ORDER_TRACKER', 'NOTEBOOKS', 'NB', 'notebook')");
        for (int step = 1; step <= 12; step++) {
            String values = step == 1 ? "'ACTIVE', timestamptz '2026-11-01T05:00:00Z'" : "'LOCKED', NULL";
            st.execute("INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, definition_id, step_order, status, started_at) "
                    + "VALUES (" + id + ", " + school + ", 'NOTEBOOK_ORDER_TRACKER', " + step + ", " + values + ")");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void migrate(String target) {
        Flyway.configure().dataSource(PG.getJdbcUrl(), OWNER, OWNER)
                .schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration/workflow")
                .target(target)
                .load().migrate();
    }

    /** One statement as the old revision ran it: the runtime role, scoped to the order's school. */
    private static void oldRevision(long school, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), "app_rt", "app_rt"); Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.current_school_id', '" + school + "', false)");
            assertEquals(1, st.executeUpdate(sql), sql);
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), "admin", "admin");
    }

    /** Rows as "a|b|c" strings, read by the superuser (which row-level security does not filter). */
    private static List<String> rows(String sql) throws SQLException {
        try (Connection c = admin(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String cell = rs.getString(i);
                    cells.add(rs.wasNull() ? "null" : "t".equals(cell) ? "true" : "f".equals(cell) ? "false" : cell);
                }
                rows.add(String.join("|", cells));
            }
            return rows;
        }
    }

    private static Map<String, Long> versions() throws SQLException {
        Map<String, Long> versions = new HashMap<>();
        for (String row : rows("SELECT order_id, version FROM workflow.workflow_notebook_trackers")) {
            String[] cells = row.split("\\|");
            versions.put(cells[0], Long.parseLong(cells[1]));
        }
        return versions;
    }

    /** The directory ids of the given sample vendors, ascending, as a JSON array. */
    private static String ids(String... legacyKeys) throws SQLException {
        List<String> quoted = new ArrayList<>();
        for (String key : legacyKeys) quoted.add("'" + key + "'");
        return "[" + String.join(", ", rows("SELECT id FROM workflow.workflow_vendors WHERE legacy_key IN (" + String.join(",", quoted)
                + ") ORDER BY id")) + "]";
    }

    private static void assertSelections(String order, String expectedJson) throws SQLException {
        assertEquals(List.of("true"), rows("SELECT vendor_selections = '" + expectedJson + "'::jsonb FROM workflow.workflow_notebook_trackers "
                + "WHERE order_id = '" + order + "'"), order + " expected " + expectedJson + " but was "
                + rows("SELECT vendor_selections FROM workflow.workflow_notebook_trackers WHERE order_id = '" + order + "'"));
    }
}
