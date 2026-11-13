package com.custoking.ims.operationsservice.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

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
 * Workflow schema V22 (item order workflows design v1, "Contract release: retiring the rollout bridges"): the overwritten
 * stage note of the notebook tracker is retired. V22 copies, once more, every authored note that is in no stage entry
 * (written since V21 with its trigger bypassed); stops, naming it and changing nothing, on a note it cannot copy (no recorded
 * author); and once every note is an entry drops V21's trigger, its function and the three columns.
 * <p>
 * The migrations run as a table owner that is neither superuser nor BYPASSRLS, as in production: without the migration's
 * bypass, forced row-level security would hide every step from the check, which would then pass and drop the notes.
 */
class StageNoteRetirementMigrationTest {

    static final String OWNER = "migration_owner";
    static PostgreSQLContainer<?> PG;
    static final Map<String, Long> INSTANCE = new HashMap<>();
    static String busyFailure;
    static long busySeconds;
    static String uncopiedFailure;
    static List<String> stateAfterFailures;
    static String versionAfterFailures;
    /** The message's remedy tried as the migration owner without the bypass it names: rows cleared, and the copy's refusal. */
    static int clearedWithoutBypass;
    static String copyWithoutBypass;

    @BeforeAll
    static void migrateToV21WriteNotesThenMigrateToV22() throws Exception {
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
        migrate("21");
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA workflow TO app_rt");
            tracker(st, "CK-401", 10);
            tracker(st, "CK-402", 10);
            tracker(st, "CK-403", 20);
            tracker(st, "CK-404", 20);
            // A note the tracker's revision saved after V21: V21's trigger copied it into an entry; it stays in the old column too.
            note(st, "CK-402", 1, "'Copied by V21'", "7", "'2026-11-12T05:00:00Z'");
            // A note somebody copied by hand although nobody is recorded as its author: it is an entry, so nothing is lost.
            note(st, "CK-404", 2, "'Copied by hand'", "NULL", "NULL");
            st.execute("INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order) "
                    + "VALUES (" + INSTANCE.get("CK-404") + ", 20, 3, 'Copied by hand', '2026-11-12T05:00:00Z', 2)");
            // Written with V21's trigger bypassed (session_replication_role = replica, a disabled trigger, a data load):
            st.execute("SET session_replication_role = replica");
            note(st, "CK-401", 2, "'Boards booked with Shree'", "8", "'2026-11-13T06:00:00Z'");     // authored: copied by V22
            note(st, "CK-403", 3, "'Call the printer on Friday'", "NULL", "'2026-11-13T07:00:00Z'"); // no author: stops V22
            note(st, "CK-404", 1, "'   '", "NULL", "NULL");                                         // blank: not a note
            st.execute("SET session_replication_role = origin");
        }

        // A reader holds workflow_instance_steps: V22 retries for 15 seconds, then stops having changed nothing.
        try (Connection reader = admin(); Statement st = reader.createStatement()) {
            reader.setAutoCommit(false);
            st.executeQuery("SELECT count(*) FROM workflow.workflow_instance_steps").close();
            long started = System.nanoTime();
            busyFailure = failure(() -> migrate("22"));
            busySeconds = (System.nanoTime() - started) / 1_000_000_000L;
            reader.rollback();
        }
        // The author-less note stops V22, which changes nothing either.
        uncopiedFailure = failure(() -> migrate("22"));
        stateAfterFailures = state();
        versionAfterFailures = rows("SELECT max(version::int) FROM workflow.flyway_schema_history WHERE success AND version IS NOT NULL").getFirst();

        // The operator gives the note its author as the message asks: as the migration owner, in a transaction that first turns
        // the bypass on. Without it, forced row-level security hides the step (a clear changes nothing) and refuses the copy.
        String copy = "INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, created_at, step_order) "
                + "VALUES (" + INSTANCE.get("CK-403") + ", 20, 9, 'Call the printer on Friday', '2026-11-13T07:00:00Z', 3)";
        try (Connection c = owner(); Statement st = c.createStatement()) {
            clearedWithoutBypass = st.executeUpdate("UPDATE workflow.workflow_instance_steps SET stage_note = NULL WHERE instance_id = "
                    + INSTANCE.get("CK-403") + " AND step_order = 3");
            try {
                st.execute(copy);
                copyWithoutBypass = "copied";
            } catch (SQLException refused) {
                copyWithoutBypass = refused.getMessage();
            }
            c.setAutoCommit(false);
            st.execute("SET LOCAL app.bypass_rls = 'on'");
            st.execute(copy);
            c.commit();
        }
        migrate("22");
    }

    @AfterAll
    static void stop() {
        if (PG != null) PG.stop();
    }

    @Test
    void aBusyStepsTableStopsTheMigrationWithinAboutFifteenSecondsHavingChangedNothing() {
        assertTrue(busyFailure.contains("V22: workflow_instance_steps stayed busy for 15 seconds; nothing was changed, re-run the migration job"),
                busyFailure);
        assertTrue(busySeconds >= 14 && busySeconds <= 30, "retried, not failed at once, and not queued for long: " + busySeconds + " s");
    }

    @Test
    void aNoteWithNoRecordedAuthorStopsTheMigrationNamedAndNothingIsChanged() throws Exception {
        assertTrue(uncopiedFailure.contains("V22: stage notes that are in no stage entry"), uncopiedFailure);
        assertTrue(uncopiedFailure.contains("instance " + INSTANCE.get("CK-403") + " (order CK-403) step 3"), uncopiedFailure);
        assertTrue(uncopiedFailure.contains("Nothing was changed"), uncopiedFailure);
        assertTrue(uncopiedFailure.contains("As the migration owner, in a transaction that first runs SET LOCAL app.bypass_rls = 'on'"),
                uncopiedFailure);
        for (String order : List.of("CK-401", "CK-402", "CK-404")) {
            assertFalse(uncopiedFailure.contains("(order " + order + ")"), order + " is copied or already an entry: " + uncopiedFailure);
        }
        assertEquals(List.of("columns=3", "trigger=1", "function=1", "CK-401 entries=0"), stateAfterFailures,
                "the copy rolled back with the rest");
        assertEquals("21", versionAfterFailures);
    }

    /** Why the message names the bypass: without it the owner's remedy silently changes nothing, or is refused. */
    @Test
    void theRemedyTheMessageGivesNeedsTheBypassItNames() {
        assertEquals(0, clearedWithoutBypass, "without the bypass the owner's clear finds no row");
        assertTrue(copyWithoutBypass.contains("row-level security"), copyWithoutBypass);
    }

    @Test
    void theTriggerItsFunctionAndTheColumnsAreGone() throws Exception {
        assertEquals(List.of("columns=0", "trigger=0", "function=0", "CK-401 entries=1"), state());
        assertEquals(List.of("22"), rows("SELECT max(version::int) FROM workflow.flyway_schema_history WHERE success AND version IS NOT NULL"));
    }

    @Test
    void everyNoteIsAStageEntryOnceWithItsAuthorAndTime() throws Exception {
        assertEquals(List.of(
                        "CK-401|2|8|Boards booked with Shree|2026-11-13 06:00",
                        "CK-402|1|7|Copied by V21|2026-11-12 05:00",
                        "CK-403|3|9|Call the printer on Friday|2026-11-13 07:00",
                        "CK-404|2|3|Copied by hand|2026-11-12 05:00"),
                rows("SELECT t.order_id, n.step_order, n.author_user_id, n.body, to_char(n.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') "
                        + "FROM workflow.workflow_internal_notes n JOIN workflow.workflow_notebook_trackers t ON t.instance_id = n.instance_id "
                        + "ORDER BY t.order_id, n.step_order"));
    }

    /** The serving revision's step reads and writes (explicit column lists, never stage_note) work on the new shape. */
    @Test
    void theServingRevisionsStepQueriesStillWorkAsTheRuntimeRole() {
        var dataSource = new SingleConnectionDataSource(PG.getJdbcUrl(), "app_rt", "app_rt", true);
        try {
            var jdbc = JdbcClient.create(dataSource);
            jdbc.sql("SELECT set_config('app.current_school_id', '10', false)").query().singleRow();
            var repository = new OrderWorkflowRepository(jdbc, JsonMapper.builder().build(), "workflow");
            long instance = repository.findByOrder("CK-401", false).orElseThrow().instanceId();
            assertEquals(INSTANCE.get("CK-401"), instance);
            var steps = repository.steps(instance);
            assertEquals(12, steps.size());
            assertEquals(1, repository.saveStepData(instance, 1, Map.of("note", "x"), 8L, 0L));
        } finally {
            dataSource.destroy();
        }
    }

    @Test
    void theDedicatedRuntimeRoleKeepsTheRuntimeRolesPrivilegesOnTheSteps() throws Exception {
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            assertEquals(rows("SELECT has_table_privilege('app_rt', 'workflow.workflow_instance_steps', '" + privilege + "')"),
                    rows("SELECT has_table_privilege('ims_operations_rt', 'workflow.workflow_instance_steps', '" + privilege + "')"), privilege);
        }
        assertEquals(List.of("true"), rows("SELECT has_table_privilege('ims_operations_rt', 'workflow.workflow_instance_steps', 'UPDATE')"));
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

    private static void note(Statement st, String order, int step, String body, String by, String at) throws SQLException {
        st.execute("UPDATE workflow.workflow_instance_steps SET stage_note = " + body + ", stage_note_saved_by = " + by
                + ", stage_note_saved_at = " + at + " WHERE instance_id = " + INSTANCE.get(order) + " AND step_order = " + step);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void migrate(String target) {
        Flyway.configure().dataSource(PG.getJdbcUrl(), OWNER, OWNER)
                .schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration/workflow")
                .target(target)
                .load().migrate();
    }

    private static String failure(Runnable migration) {
        try {
            migration.run();
            return "migrated";
        } catch (FlywayException failure) {
            return String.valueOf(failure.getMessage());
        }
    }

    /** The bridge's objects, and whether V22's copy of CK-401's note is there. */
    private static List<String> state() throws SQLException {
        return List.of(
                "columns=" + rows("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'workflow' "
                        + "AND table_name = 'workflow_instance_steps' AND column_name LIKE 'stage_note%'").getFirst(),
                "trigger=" + rows("SELECT count(*) FROM pg_trigger WHERE tgname = 'copy_retired_stage_note'").getFirst(),
                "function=" + rows("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'workflow' AND p.proname = 'copy_retired_stage_note'").getFirst(),
                "CK-401 entries=" + rows("SELECT count(*) FROM workflow.workflow_internal_notes WHERE instance_id = "
                        + INSTANCE.get("CK-401")).getFirst());
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), "admin", "admin");
    }

    /** The migration owner: neither superuser nor BYPASSRLS, so forced row-level security applies to it. */
    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), OWNER, OWNER);
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
}
