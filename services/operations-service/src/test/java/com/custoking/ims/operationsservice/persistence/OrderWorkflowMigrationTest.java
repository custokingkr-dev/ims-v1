package com.custoking.ims.operationsservice.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow schema V17-V20 (design v1 §3.1, D41): a V16 database holding notebook trackers (customised and wholesale; active
 * at Steps 1, 4, 7 and 10; finished; with stage notes, vendor selections and vendor-key group links) migrates to V20
 * without losing a tracker or any progress; selections point at numeric directory vendors; each stage note becomes one
 * stage entry; the old pre-print verification value becomes PENDING; the definitions and steps equal the JSON templates;
 * the new tenant tables have forced row-level security and grants; call logs are append-only for the runtime roles.
 * <p>
 * The migrations run as a table owner that is neither superuser nor BYPASSRLS, as in production, so forced RLS applies to
 * their backfills: a backfill that forgot V7's {@code SET LOCAL app.bypass_rls} would silently change nothing and fail here.
 */
class OrderWorkflowMigrationTest {

    static final String OWNER = "migration_owner";
    static final List<String> TEMPLATES = List.of("NOTEBOOK_ORDER_TRACKER", "FLIER_ORDER_WORKFLOW", "FLEX_ORDER_WORKFLOW",
            "BILL_BOOK_ORDER_WORKFLOW", "GENERAL_ITEM_ORDER_WORKFLOW");
    static final String SHREE_INVITE = "https://chat.whatsapp.com/ShreeBoards12345";
    static final String PERFECT_INVITE = "https://chat.whatsapp.com/PerfectBind9876";

    static PostgreSQLContainer<?> PG;
    static final Map<String, Long> INSTANCE = new HashMap<>();
    static List<String> trackersBefore;
    static List<String> stepsBefore;
    static Map<String, Long> stepVersionsBefore;
    static Map<String, Long> trackerVersionsBefore;

    @BeforeAll
    static void migrateASeededV16DatabaseToV20() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("admin").withPassword("admin");
        PG.start();
        try (Connection c = admin(); Statement st = c.createStatement()) {
            // The runtime roles exist before the migrations run, as in production (scripts/create-app-rt-role.sql), with the
            // owner's default privileges handing app_rt DML on new tables, so a migration must revoke what it denies.
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("CREATE ROLE ims_operations_rt NOLOGIN NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("CREATE ROLE " + OWNER + " LOGIN PASSWORD '" + OWNER + "' NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB");
            st.execute("GRANT CREATE ON DATABASE " + PG.getDatabaseName() + " TO " + OWNER);
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt");
            st.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + OWNER + " GRANT USAGE, SELECT ON SEQUENCES TO app_rt");
        }
        migrate(PG.getJdbcUrl(), "16");
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA workflow TO app_rt");
            seed(st);
        }
        trackersBefore = rows(TRACKERS);
        stepsBefore = rows(STEPS);
        stepVersionsBefore = versions("SELECT t.order_id || '/' || s.step_order, s.version FROM workflow.workflow_instance_steps s "
                + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id");
        trackerVersionsBefore = versions("SELECT order_id, version FROM workflow.workflow_notebook_trackers");

        migrate(PG.getJdbcUrl(), "20");
    }

    @AfterAll
    static void stop() {
        if (PG != null) PG.stop();
    }

    // ---- the existing trackers -------------------------------------------------------------------------------------

    @Test
    void noTrackerIsLostAndProgressIsUntouched() throws Exception {
        assertEquals(5, trackersBefore.size());
        assertEquals(trackersBefore, rows(TRACKERS));
        // Status, assignee, clock, completion and saved data of every step row (the verification value is checked below).
        assertEquals(stepsBefore, rows(STEPS));
    }

    @Test
    void existingTrackersAreNotebookWorkflowsWithStepsOfTheirOwnDefinition() throws Exception {
        assertEquals(List.of("NOTEBOOK_ORDER_TRACKER|NOTEBOOKS|NB|notebook|null|null|null|null|null|null"),
                rows("SELECT DISTINCT definition_id, category_code, item_code, item_noun, order_number, school_contact_name, "
                        + "school_contact_phone, approved_cdr_link, design_comment, imported_from FROM workflow.workflow_notebook_trackers"));
        // Template rows vs instance rows (§3.1): an instance's steps are its tracker's definition's steps.
        assertEquals(List.of("0"), rows("SELECT count(*) FROM workflow.workflow_instance_steps s "
                + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id WHERE s.definition_id <> t.definition_id"));
        // Each active step now has a code, which is the order's status.
        assertEquals(List.of("CK-101|ORDER_DETAILS", "CK-102|HEAD_APPROVAL", "CK-103|PRE_PRINT_QUALITY_GATE", "CK-104|PRODUCTION_FOLLOW_UP"),
                rows("SELECT t.order_id, d.step_code FROM workflow.workflow_instance_steps s "
                        + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id "
                        + "JOIN workflow.workflow_steps d ON d.definition_id = s.definition_id AND d.step_order = s.step_order "
                        + "WHERE s.status = 'ACTIVE' ORDER BY t.order_id"));
    }

    @Test
    void vendorSelectionsPointAtNumericDirectoryVendors() throws Exception {
        assertSelections("CK-103", "{\"BOARD_SUPPLIERS\": " + ids("board_shree") + ", \"LAMINATION_VENDORS\": "
                + ids("lam_glosspro", "lam_crystal") + ", \"BINDERS\": " + ids("binder_metro") + ", \"OFFSET_PRINTERS\": "
                + ids("offset_cityline") + "}");
        // An empty list is no selection.
        assertSelections("CK-104", "{\"BOARD_SUPPLIERS\": " + ids("board_prime") + ", \"OFFSET_PRINTERS\": "
                + ids("offset_metro", "offset_precision") + "}");
        for (String order : List.of("CK-101", "CK-102", "CK-105")) assertSelections(order, "{}");

        assertEquals(List.of("0"), rows("SELECT count(*) FROM workflow.workflow_notebook_trackers t "
                + "CROSS JOIN LATERAL jsonb_each(t.vendor_selections) sel(key, value) "
                + "CROSS JOIN LATERAL jsonb_array_elements(sel.value) item(value) "
                + "LEFT JOIN workflow.workflow_vendor_category_members m "
                + "  ON jsonb_typeof(item.value) = 'number' AND m.vendor_id = (item.value #>> '{}')::bigint AND m.category_code = sel.key "
                + "WHERE m.vendor_id IS NULL"), "every selected id is a number naming a vendor of that category");

        // A rewritten selection moves the tracker's version, so a browser holding the old one gets a conflict.
        Map<String, Long> after = versions("SELECT order_id, version FROM workflow.workflow_notebook_trackers");
        for (String order : List.of("CK-103", "CK-104")) assertEquals(trackerVersionsBefore.get(order) + 1, after.get(order), order);
        for (String order : List.of("CK-101", "CK-102", "CK-105")) assertEquals(trackerVersionsBefore.get(order), after.get(order), order);
    }

    @Test
    void eachSavedStageNoteBecomesOneStageEntry() throws Exception {
        assertEquals(List.of(
                        "CK-102|2|7|Designer asked for the crest in colour|true",
                        "CK-102|3|7|CDR sent to the head for approval|true",
                        "CK-103|5|8|Boards booked with Shree|true",
                        "CK-104|10|8|Binder promised Friday delivery|true",
                        "CK-105|12|7|Delivered to the front office|true"),
                rows("SELECT t.order_id, n.step_order, n.author_user_id, n.body, n.created_at = s.stage_note_saved_at "
                        + "FROM workflow.workflow_internal_notes n "
                        + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = n.instance_id "
                        + "JOIN workflow.workflow_instance_steps s ON s.instance_id = n.instance_id AND s.step_order = n.step_order "
                        + "WHERE n.step_order IS NOT NULL ORDER BY t.order_id, n.step_order"));
        // The blank note and the one with no recorded author are not copied; the order-level note stays order-level.
        assertEquals(List.of("CK-101|null|Called the school about the delivery date"),
                rows("SELECT t.order_id, n.step_order, n.body FROM workflow.workflow_internal_notes n "
                        + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = n.instance_id WHERE n.step_order IS NULL"));
        assertEquals(List.of("6"), rows("SELECT count(*) FROM workflow.workflow_internal_notes"));
    }

    @Test
    void theOldPrePrintVerificationValueBecomesPending() throws Exception {
        assertEquals(List.of("CK-101|null", "CK-102|null", "CK-103|PENDING", "CK-104|PENDING", "CK-105|VERIFIED"),
                rows("SELECT t.order_id, s.data ->> 'schoolVerification' FROM workflow.workflow_instance_steps s "
                        + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id WHERE s.step_order = 7 ORDER BY t.order_id"));
        assertEquals(List.of("0"), rows("SELECT count(*) FROM workflow.workflow_instance_steps WHERE data::text LIKE '%NOT_REQUIRED_OR_PENDING%'"));
        // The completed step's sign-off stays; completed steps are never re-gated (D41).
        assertEquals(List.of("true"), rows("SELECT s.data ? 'signOff' FROM workflow.workflow_instance_steps s "
                + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id WHERE t.order_id = 'CK-104' AND s.step_order = 7"));
        Map<String, Long> after = versions("SELECT t.order_id || '/' || s.step_order, s.version FROM workflow.workflow_instance_steps s "
                + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id");
        for (Map.Entry<String, Long> before : stepVersionsBefore.entrySet()) {
            boolean changed = before.getKey().equals("CK-103/7") || before.getKey().equals("CK-104/7");
            assertEquals(before.getValue() + (changed ? 1 : 0), after.get(before.getKey()), before.getKey());
        }
    }

    // ---- definitions and steps -------------------------------------------------------------------------------------

    @Test
    void definitionsAndStepsEqualTheJsonTemplates() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        for (String id : TEMPLATES) {
            Map<String, Object> template;
            try (InputStream in = OrderWorkflowMigrationTest.class.getResourceAsStream("/workflow-templates/" + id + ".json")) {
                assertNotNull(in, id);
                template = map(json.readValue(in, Map.class));
            }
            assertEquals(List.of(template.get("title") + "|true"),
                    rows("SELECT name, active FROM workflow.workflow_definitions WHERE id = '" + id + "'"), id);
            List<String> expected = new ArrayList<>();
            for (Object raw : (List<?>) template.get("steps")) {
                Map<String, Object> step = map(raw);
                Map<String, Object> sla = map(step.get("sla"));
                expected.add(step.get("order") + "|" + step.get("code") + "|" + step.get("title") + "|" + step.get("phase") + "|"
                        + sla.get("value") + "|" + sla.get("unit") + "|" + step.get("customisedOnly") + "|"
                        + (Boolean.TRUE.equals(step.get("superadminCompletes")) ? "SUPERADMIN" : "null"));
            }
            assertEquals(expected, rows("SELECT step_order, step_code, step_name, phase, default_sla_value, default_sla_unit, "
                    + "customised_only, required_role FROM workflow.workflow_steps WHERE definition_id = '" + id + "' ORDER BY step_order"), id);
        }
    }

    @Test
    void genericApprovalStepsNeedNoCodeButItemOrderStepsDo() throws Exception {
        assertEquals(List.of(
                        "FIREFIGHTING_DEFAULT|1|Bursar Review|null|null",
                        "FIREFIGHTING_DEFAULT|2|Principal Approval|null|null",
                        "FIREFIGHTING_DEFAULT|3|Custoking Fulfillment|null|null",
                        "SUPPLY_ORDER_DEFAULT|1|Bursar Review|null|null",
                        "SUPPLY_ORDER_DEFAULT|2|Principal Approval|null|null"),
                rows("SELECT definition_id, step_order, step_name, step_code, phase FROM workflow.workflow_steps "
                        + "WHERE definition_id NOT IN ('" + String.join("','", TEMPLATES) + "') ORDER BY definition_id, step_order"));
        try (Connection c = admin(); Statement st = c.createStatement()) {
            // The generic approval engine keeps adding steps the way it always has.
            st.execute("INSERT INTO workflow.workflow_definitions (id, name) VALUES ('MIGRATION_TEST', 'Migration test')");
            st.execute("INSERT INTO workflow.workflow_steps (definition_id, step_order, step_name) VALUES ('MIGRATION_TEST', 1, 'Approval')");
            st.execute("DELETE FROM workflow.workflow_definitions WHERE id = 'MIGRATION_TEST'");

            String step = "INSERT INTO workflow.workflow_steps (definition_id, step_order, step_name, step_code, phase) "
                    + "VALUES ('FLIER_ORDER_WORKFLOW', 10, 'Extra', %s, %s)";
            assertViolates(st, step.formatted("NULL", "'DELIVERY'"), "ck_wf_step_item_order_code");
            assertViolates(st, step.formatted("'EXTRA'", "NULL"), "ck_wf_step_item_order_code");
            assertViolates(st, step.formatted("'ORDER_DETAILS'", "'DELIVERY'"), "uk_wf_step_def_code");
            assertViolates(st, step.formatted("'extra'", "'DELIVERY'"), "ck_wf_step_code");
            assertViolates(st, step.formatted("'EXTRA'", "'DONE'"), "ck_wf_step_phase");
        }
    }

    // ---- the vendor directory --------------------------------------------------------------------------------------

    @Test
    void theDirectoryHoldsTheSopCategoriesAndThePrototypesSampleVendors() throws Exception {
        assertEquals(List.of(
                "1|BOARD_SUPPLIERS|Board Suppliers|true", "2|LAMINATION_VENDORS|Lamination Vendors|true", "3|BINDERS|Binders|true",
                "4|OFFSET_PRINTERS|Offset Printers|true", "5|DIGITAL_PRINTERS|Digital Printers|true", "6|FLEX_PRINTERS|Flex Printers|true",
                "7|PHOTO_PAPER_PRINTERS|Photo Paper Printers|true", "8|BILL_BOOK_MAKERS|Bill Book Makers|true",
                "9|BATCH_MAKERS|Batch Makers|true", "10|FLUTE_BOARD_MAKERS|Flute Board Makers|true",
                "11|TROPHIES_MEDALS|Trophies & Medals|true", "12|FILE_MAKERS|File Makers|true", "13|SASHES|Sashes|true",
                "14|DIARIES|Diaries|true", "15|ID_CARDS|ID Cards|true", "16|FLAPS|Flaps|true",
                "17|GIFTING|Gifting Item Vendors & Printers|true", "18|NON_WOVEN_BAGS|Non-Woven Bag Vendors|true"),
                rows("SELECT sort_order, code, name, active FROM workflow.workflow_vendor_categories ORDER BY sort_order"));
        // Vendor-key group links move onto the vendor only where every school that saved one saved the same invite.
        assertEquals(List.of(
                "board_shree|Shree Board Traders|BOARD_SUPPLIERS|true|null|true|" + SHREE_INVITE,
                "board_national|National Boards Co.|BOARD_SUPPLIERS|true|null|true|null",
                "board_prime|Prime Board Suppliers|BOARD_SUPPLIERS|true|null|true|null",
                "lam_glosspro|GlossPro Laminations|LAMINATION_VENDORS|true|null|true|null",
                "lam_crystal|Crystal Lamination Works|LAMINATION_VENDORS|true|null|true|null",
                "lam_brightfinish|BrightFinish Laminators|LAMINATION_VENDORS|true|null|true|null",
                "binder_perfect|Perfect Bindery|BINDERS|true|null|true|" + PERFECT_INVITE,
                "binder_classic|Classic Notebook Binding|BINDERS|true|null|true|null",
                "binder_metro|Metro Binding House|BINDERS|true|null|true|null",
                "offset_metro|Metro Offset Printers|OFFSET_PRINTERS|true|null|true|null",
                "offset_precision|Precision Print House|OFFSET_PRINTERS|true|null|true|null",
                "offset_cityline|Cityline Offset Works|OFFSET_PRINTERS|true|null|true|null"),
                rows("SELECT v.legacy_key, v.name, string_agg(m.category_code, ','), v.sample, v.phone, v.active, v.whatsapp_group_url "
                        + "FROM workflow.workflow_vendors v JOIN workflow.workflow_vendor_category_members m ON m.vendor_id = v.id "
                        + "GROUP BY v.id ORDER BY v.id"));
        // The source rows stay where they were.
        assertEquals(List.of("6"), rows("SELECT count(*) FROM workflow.workflow_school_whatsapp_group_links"));
        assertEquals(List.of("0"), rows("SELECT count(*) FROM workflow.workflow_settings"));
    }

    // ---- row-level security and grants -----------------------------------------------------------------------------

    @Test
    void theNewTenantTablesHaveForcedRowLevelSecurityAndTheGlobalOnesNone() throws Exception {
        assertEquals(List.of(
                        "workflow_call_logs|true|true|1", "workflow_school_item_group_links|true|true|1", "workflow_settings|false|false|0",
                        "workflow_vendor_categories|false|false|0", "workflow_vendor_category_members|false|false|0",
                        "workflow_vendors|false|false|0"),
                rows("SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
                        + "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'workflow' AND p.tablename = c.relname "
                        + "   AND p.policyname = 'tenant_isolation' AND p.qual LIKE '%app.operator_schools%' AND p.with_check LIKE '%app.bypass_rls%') "
                        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'workflow' AND c.relname IN "
                        + "('workflow_call_logs', 'workflow_school_item_group_links', 'workflow_settings', 'workflow_vendor_categories', "
                        + "'workflow_vendor_category_members', 'workflow_vendors') ORDER BY c.relname"));

        try (Connection c = admin(); Statement st = c.createStatement()) {
            for (String order : List.of("CK-101", "CK-103")) {
                long school = order.equals("CK-101") ? 10 : 20;
                st.execute("INSERT INTO workflow.workflow_call_logs (instance_id, school_id, step_order, contacted, outcome, created_by) "
                        + "VALUES (" + INSTANCE.get(order) + ", " + school + ", 1, 'SCHOOL', 'CONNECTED', 7)");
                st.execute("INSERT INTO workflow.workflow_school_item_group_links (school_id, category_code, invite_url, updated_by) "
                        + "VALUES (" + school + ", 'FLIERS', 'https://chat.whatsapp.com/FlierGroup" + school + "xx', 7)");
            }
        }
        try {
            for (String table : List.of("workflow_call_logs", "workflow_school_item_group_links")) {
                assertEquals(1, runtimeCount(table, "app.current_school_id", "10"), table + ": a school sees its own rows");
                assertEquals(0, runtimeCount(table, null, null), table + ": no scope fails closed");
                assertEquals(1, runtimeCount(table, "app.operator_schools", "20"), table + ": an operator sees their schools");
                assertEquals(2, runtimeCount(table, "app.operator_schools", "10,20"), table);
                assertEquals(0, runtimeCount(table, "app.operator_schools", "30"), table);
                assertEquals(2, runtimeCount(table, "app.bypass_rls", "on"), table + ": superadmin bypasses");
            }
            try (Connection c = runtime(); Statement st = c.createStatement()) {
                st.execute("SELECT set_config('app.current_school_id', '10', false)");
                assertViolates(st, "INSERT INTO workflow.workflow_call_logs (instance_id, school_id, contacted, outcome, created_by) "
                        + "VALUES (" + INSTANCE.get("CK-103") + ", 20, 'SCHOOL', 'BUSY', 7)", "row-level security");
                assertViolates(st, "INSERT INTO workflow.workflow_school_item_group_links (school_id, category_code, invite_url, updated_by) "
                        + "VALUES (20, 'FLEX', 'https://chat.whatsapp.com/FlexGroup20xxxx', 7)", "row-level security");
            }
        } finally {
            try (Connection c = admin(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM workflow.workflow_call_logs");
                st.execute("DELETE FROM workflow.workflow_school_item_group_links");
            }
        }
    }

    @Test
    void callLogsAreAppendOnlyForTheRuntimeRoles() throws Exception {
        try (Connection c = runtime(); Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.current_school_id', '10', false)");
            assertEquals(1, st.executeUpdate("INSERT INTO workflow.workflow_call_logs (instance_id, school_id, step_order, contacted, "
                    + "outcome, contact_label, notes, next_follow_up_at, created_by) VALUES (" + INSTANCE.get("CK-101") + ", 10, 1, 'SCHOOL', "
                    + "'CALLBACK_REQUESTED', 'Front office', 'Principal is out; call back tomorrow', now() + interval '1 day', 7)"));
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow.workflow_call_logs")) {
                rs.next();
                assertEquals(1, rs.getLong(1), "entries stay readable");
            }
            for (String change : List.of("UPDATE workflow.workflow_call_logs SET notes = 'rewritten'",
                    "DELETE FROM workflow.workflow_call_logs", "TRUNCATE workflow.workflow_call_logs")) {
                assertViolates(st, change, "permission denied");
            }
        } finally {
            try (Connection c = admin(); Statement st = c.createStatement()) {
                st.execute("DELETE FROM workflow.workflow_call_logs");
            }
        }
        // afterMigrate mirrors app_rt's grants onto the dedicated runtime role, so the revoke reaches it too.
        for (String role : List.of("app_rt", "ims_operations_rt")) {
            assertEquals(List.of("true|true|false|false|false|true"), rows("SELECT has_table_privilege('" + role
                    + "', 'workflow.workflow_call_logs', 'SELECT'), has_table_privilege('" + role + "', 'workflow.workflow_call_logs', 'INSERT'), "
                    + "has_any_column_privilege('" + role + "', 'workflow.workflow_call_logs', 'UPDATE'), "
                    + "has_table_privilege('" + role + "', 'workflow.workflow_call_logs', 'DELETE'), "
                    + "has_table_privilege('" + role + "', 'workflow.workflow_call_logs', 'TRUNCATE'), "
                    + "has_sequence_privilege('" + role + "', 'workflow.workflow_call_logs_id_seq', 'USAGE')"), role);
        }
    }

    @Test
    void theRuntimeRolesCanWorkTheDirectorySettingsGroupsAndDefaultSlas() throws Exception {
        for (String role : List.of("app_rt", "ims_operations_rt")) {
            for (String table : List.of("workflow_vendor_categories", "workflow_vendors", "workflow_vendor_category_members",
                    "workflow_settings", "workflow_school_item_group_links")) {
                assertEquals(List.of("true|true|true|true"), rows("SELECT has_table_privilege('" + role + "', 'workflow." + table
                        + "', 'SELECT'), has_table_privilege('" + role + "', 'workflow." + table + "', 'INSERT'), "
                        + "has_table_privilege('" + role + "', 'workflow." + table + "', 'UPDATE'), "
                        + "has_table_privilege('" + role + "', 'workflow." + table + "', 'DELETE')"), role + " " + table);
            }
            assertEquals(List.of("true|true|true"), rows("SELECT has_sequence_privilege('" + role + "', 'workflow.workflow_vendors_id_seq', 'USAGE'), "
                    + "has_column_privilege('" + role + "', 'workflow.workflow_steps', 'default_sla_value', 'UPDATE'), "
                    + "has_column_privilege('" + role + "', 'workflow.workflow_steps', 'updated_by', 'UPDATE')"), role);
        }
        // The directory is global: it needs no tenant scope.
        try (Connection c = runtime(); Statement st = c.createStatement()) {
            long vendor;
            try (ResultSet rs = st.executeQuery("INSERT INTO workflow.workflow_vendors (name, phone, whatsapp_group_url, created_by, updated_by) "
                    + "VALUES ('Sharma Flex House', '+919800000001', 'https://chat.whatsapp.com/SharmaFlex0001', 9, 9) RETURNING id")) {
                rs.next();
                vendor = rs.getLong(1);
            }
            try {
                st.execute("INSERT INTO workflow.workflow_vendor_category_members (vendor_id, category_code) VALUES (" + vendor + ", 'FLEX_PRINTERS')");
                assertEquals(1, st.executeUpdate("UPDATE workflow.workflow_vendors SET active = false, updated_at = now() WHERE id = " + vendor));
                assertEquals(1, st.executeUpdate("UPDATE workflow.workflow_vendor_categories SET name = 'Badge Makers', updated_by = 9 "
                        + "WHERE code = 'BATCH_MAKERS'"));
                assertEquals(1, st.executeUpdate("INSERT INTO workflow.workflow_settings (key, value, updated_by) "
                        + "VALUES ('INTERNAL_APPROVAL_GROUP_URL', 'https://chat.whatsapp.com/InternalReview01', 9)"));
                assertEquals(1, st.executeUpdate("UPDATE workflow.workflow_steps SET default_sla_value = 6, updated_by = 9, updated_at = now() "
                        + "WHERE definition_id = 'FLIER_ORDER_WORKFLOW' AND step_order = 1"));
            } finally {
                try (Connection a = admin(); Statement admin = a.createStatement()) {
                    admin.execute("DELETE FROM workflow.workflow_vendors WHERE id = " + vendor);
                    admin.execute("UPDATE workflow.workflow_vendor_categories SET name = 'Batch Makers', updated_by = NULL WHERE code = 'BATCH_MAKERS'");
                    admin.execute("DELETE FROM workflow.workflow_settings");
                    admin.execute("UPDATE workflow.workflow_steps SET default_sla_value = 2, updated_by = NULL, updated_at = NULL "
                            + "WHERE definition_id = 'FLIER_ORDER_WORKFLOW' AND step_order = 1");
                }
            }
        }
    }

    // ---- constraints -----------------------------------------------------------------------------------------------

    @Test
    void theNewColumnsAndTablesRejectInvalidValues() throws Exception {
        long ck101 = INSTANCE.get("CK-101");
        try (Connection c = admin(); Statement st = c.createStatement()) {
            // Vendors, categories and settings.
            String vendor = "INSERT INTO workflow.workflow_vendors (name, phone, whatsapp_group_url, sample) VALUES (%s, %s, %s, %s)";
            assertViolates(st, vendor.formatted("'X'", "'1'", "'https://example.com/AbCdEf1234567890'", "false"), "ck_wv_whatsapp_group_url");
            assertViolates(st, vendor.formatted("'X'", "'1'", "'https://chat.whatsapp.com/AbCdEf1234567890?x=1'", "false"), "ck_wv_whatsapp_group_url");
            assertViolates(st, vendor.formatted("'  '", "'1'", "NULL", "false"), "ck_wv_name");
            assertViolates(st, vendor.formatted("'X'", "NULL", "NULL", "false"), "ck_wv_phone_required");
            assertViolates(st, "INSERT INTO workflow.workflow_vendor_categories (code, name, sort_order) VALUES ('bad-code', 'Bad', 19)", "ck_wvc_code");
            assertViolates(st, "INSERT INTO workflow.workflow_vendor_category_members (vendor_id, category_code) "
                    + "SELECT id, 'NO_SUCH_CATEGORY' FROM workflow.workflow_vendors LIMIT 1", "foreign key");
            assertViolates(st, "INSERT INTO workflow.workflow_settings (key, value, updated_by) VALUES ('OTHER', 'x', 1)", "ck_ws_key");
            assertViolates(st, "INSERT INTO workflow.workflow_settings (key, value, updated_by) "
                    + "VALUES ('INTERNAL_APPROVAL_GROUP_URL', 'https://example.com/group', 1)", "ck_ws_internal_approval_group_url");

            // Item group links.
            String link = "INSERT INTO workflow.workflow_school_item_group_links (school_id, category_code, invite_url, updated_by) VALUES (10, %s, %s, 1)";
            assertViolates(st, link.formatted("'FLIERS'", "'https://chat.whatsapp.com/short'"), "ck_wsigl_invite_url");
            assertViolates(st, link.formatted("'fliers'", "'https://chat.whatsapp.com/AbCdEf1234567890'"), "ck_wsigl_category_code");

            // Call logs.
            String call = "INSERT INTO workflow.workflow_call_logs (instance_id, school_id, step_order, contacted, outcome, notes, "
                    + "next_follow_up_at, created_by) VALUES (" + ck101 + ", %s, %s, %s, %s, %s, %s, 7)";
            assertViolates(st, call.formatted("10", "1", "'VENDOR'", "'CONNECTED'", "NULL", "NULL"), "ck_wcl_contacted");
            assertViolates(st, call.formatted("10", "1", "'SCHOOL'", "'LATER'", "NULL", "NULL"), "ck_wcl_outcome");
            assertViolates(st, call.formatted("10", "1", "'SCHOOL'", "'CALLBACK_REQUESTED'", "'Call back'", "NULL"), "ck_wcl_callback_follow_up");
            assertViolates(st, call.formatted("10", "1", "'SCHOOL'", "'CONNECTED'", "repeat('x', 1001)", "NULL"), "ck_wcl_notes");
            assertViolates(st, call.formatted("10", "1", "'SCHOOL'", "'CONNECTED'", "'   '", "NULL"), "ck_wcl_notes");
            assertViolates(st, call.formatted("10", "0", "'SCHOOL'", "'CONNECTED'", "NULL", "NULL"), "ck_wcl_step_order");
            assertViolates(st, call.formatted("20", "1", "'SCHOOL'", "'CONNECTED'", "NULL", "NULL"), "fk_wcl_instance");
            assertViolates(st, "INSERT INTO workflow.workflow_call_logs (instance_id, school_id, contacted, outcome, vendor_id, created_by) "
                    + "VALUES (" + ck101 + ", 10, 'PRINTING_PRESS', 'BUSY', 999999, 7)", "foreign key");

            // Stage entries.
            assertViolates(st, "INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body, step_order) "
                    + "VALUES (" + ck101 + ", 10, 7, 'note', 100)", "ck_win_step_order");

            // Trackers.
            String tracker = "UPDATE workflow.workflow_notebook_trackers SET %s WHERE order_id = 'CK-101'";
            assertViolates(st, tracker.formatted("item_code = 'nb'"), "ck_wnt_item_code");
            assertViolates(st, tracker.formatted("category_code = 'notebooks'"), "ck_wnt_category_code");
            assertViolates(st, tracker.formatted("item_noun = ' '"), "ck_wnt_item_noun");
            assertViolates(st, tracker.formatted("imported_from = 'SOMEWHERE'"), "ck_wnt_imported_from");
            assertViolates(st, tracker.formatted("definition_id = 'NO_SUCH_TEMPLATE'"), "fk_wnt_definition");
            assertViolates(st, tracker.formatted("approved_cdr_link = 'https://drive.example/cdr'"), "ck_wnt_approved_ref");
            try {
                st.execute(tracker.formatted("order_number = 'NB-DPS_GUR-26-11-13-0042'"));
                assertViolates(st, "UPDATE workflow.workflow_notebook_trackers SET order_number = 'NB-DPS_GUR-26-11-13-0042' "
                        + "WHERE order_id = 'CK-102'", "uk_wnt_order_number");
                // Approval binds a CDR link only while the design is approved.
                st.execute(tracker.formatted("design_state = 'APPROVED', approved_cdr_link = 'https://drive.example/cdr'"));
            } finally {
                st.execute(tracker.formatted("order_number = NULL, approved_cdr_link = NULL, design_state = 'NOT_SENT'"));
            }
        }
    }

    // ---- a selection the directory cannot place --------------------------------------------------------------------

    @Test
    void aSelectionNoDirectoryVendorMatchesStopsTheMigrationInsteadOfBeingDropped() throws Exception {
        String database = "unmappable";
        try (Connection c = admin(); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + database);
            st.execute("GRANT CREATE ON DATABASE " + database + " TO " + OWNER);
        }
        String url = "jdbc:postgresql://" + PG.getHost() + ":" + PG.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        migrate(url, "16");
        try (Connection c = DriverManager.getConnection(url, "admin", "admin"); Statement st = c.createStatement()) {
            long id = insertInstance(st, "CK-900", 10, 5);
            st.execute("INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, "
                    + "placed_at, time_zone, vendor_selections) VALUES (" + id + ", 10, 'CK-900', true, 100, now(), 'Asia/Kolkata', "
                    + "'{\"board\": [\"board_shree\"], \"offset\": [\"offset_gone\"]}')");

            FlywayException failure = assertThrows(FlywayException.class, () -> migrate(url, "20"));
            assertTrue(failure.getMessage().contains("CK-900") && failure.getMessage().contains("offset_gone"), failure.getMessage());
            try (ResultSet rs = st.executeQuery("SELECT vendor_selections::text, "
                    + "(SELECT max(version::int) FROM workflow.flyway_schema_history WHERE success AND version IS NOT NULL), "
                    + "to_regclass('workflow.workflow_vendors') IS NULL FROM workflow.workflow_notebook_trackers")) {
                rs.next();
                assertEquals("{\"board\": [\"board_shree\"], \"offset\": [\"offset_gone\"]}", rs.getString(1), "the selection is kept");
                assertEquals(18, rs.getInt(2), "V19 rolled back as a whole");
                assertTrue(rs.getBoolean(3), "no part of V19 remains");
            }
        }
    }

    // ---- seed ------------------------------------------------------------------------------------------------------

    /** Five V16 notebook trackers, their notes, selections and the schools' vendor-key group links. */
    private static void seed(Statement st) throws SQLException {
        tracker(st, "CK-101", 10, true, 1);   // customised, at Step 1
        tracker(st, "CK-102", 10, true, 4);   // customised, at Step 4 (Head Approval)
        tracker(st, "CK-103", 20, false, 7);  // wholesale (Steps 1, 5-12), at Step 7
        tracker(st, "CK-104", 20, true, 10);  // customised, at Step 10
        tracker(st, "CK-105", 10, true, 0);   // finished

        st.execute("UPDATE workflow.workflow_notebook_trackers SET design_state = 'SENT', design_state_at = now(), design_state_by = 7 "
                + "WHERE order_id = 'CK-102'");
        stageNote(st, "CK-102", 2, "'Designer asked for the crest in colour'", "7", "2026-11-10T10:00:00Z");
        stageNote(st, "CK-102", 3, "'CDR sent to the head for approval'", "7", "2026-11-11T09:30:00Z");
        stepData(st, "CK-102", 3, "{\"cdrFilename\": \"crest.cdr\", \"cdrLink\": \"https://drive.example/crest\"}");

        stepData(st, "CK-103", 7, "{\"checks\": [0, 1, 2], \"schoolVerification\": \"NOT_REQUIRED_OR_PENDING\", "
                + "\"sampleLink\": \"https://photos.example/sample\"}");
        stageNote(st, "CK-103", 5, "'Boards booked with Shree'", "8", "2026-11-12T05:00:00Z");
        stageNote(st, "CK-103", 6, "'   '", "8", "2026-11-12T06:00:00Z");
        stageNote(st, "CK-103", 8, "'A note nobody is recorded as writing'", "NULL", "NULL");
        st.execute("UPDATE workflow.workflow_notebook_trackers SET vendor_selections = '{\"board\": [\"board_shree\"], "
                + "\"lamination\": [\"lam_crystal\", \"lam_glosspro\"], \"binder\": [\"binder_metro\"], \"offset\": [\"offset_cityline\"]}' "
                + "WHERE order_id = 'CK-103'");

        stepData(st, "CK-104", 7, "{\"checks\": [0, 1, 2, 3, 4, 5, 6, 7], \"schoolVerification\": \"NOT_REQUIRED_OR_PENDING\", "
                + "\"signOff\": {\"by\": 9, \"at\": \"2026-11-08T10:00:00Z\"}}");
        stageNote(st, "CK-104", 10, "'Binder promised Friday delivery'", "8", "2026-11-12T11:15:00Z");
        st.execute("UPDATE workflow.workflow_notebook_trackers SET vendor_selections = '{\"board\": [\"board_prime\"], \"binder\": [], "
                + "\"offset\": [\"offset_precision\", \"offset_metro\"]}' WHERE order_id = 'CK-104'");

        stepData(st, "CK-105", 7, "{\"checks\": [0, 1, 2, 3, 4, 5, 6, 7], \"schoolVerification\": \"VERIFIED\"}");
        stageNote(st, "CK-105", 12, "'Delivered to the front office'", "7", "2026-11-09T12:00:00Z");

        st.execute("INSERT INTO workflow.workflow_internal_notes (instance_id, school_id, author_user_id, body) VALUES ("
                + INSTANCE.get("CK-101") + ", 10, 7, 'Called the school about the delivery date')");

        String link = "INSERT INTO workflow.workflow_school_whatsapp_group_links (school_id, target_key, invite_url, updated_by) "
                + "VALUES (%d, '%s', '%s', 9)";
        st.execute(link.formatted(10, "SCHOOL_GROUP", "https://chat.whatsapp.com/SchoolTen00001"));
        st.execute(link.formatted(10, "board_shree", SHREE_INVITE));
        st.execute(link.formatted(20, "board_shree", SHREE_INVITE));
        st.execute(link.formatted(10, "offset_metro", "https://chat.whatsapp.com/MetroOffsetA1234"));
        st.execute(link.formatted(20, "offset_metro", "https://chat.whatsapp.com/MetroOffsetB5678"));
        st.execute(link.formatted(20, "binder_perfect", PERFECT_INVITE));
    }

    /** A tracker with a row per step on its path; {@code active} 0 means finished. */
    private static void tracker(Statement st, String order, long school, boolean customised, int active) throws SQLException {
        long id = insertInstance(st, order, school, active);
        INSTANCE.put(order, id);
        st.execute("INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, "
                + "placed_at, time_zone, school_name) VALUES (" + id + ", " + school + ", '" + order + "', " + customised + ", 1200, "
                + "'2026-11-01T04:30:00Z', 'Asia/Kolkata', 'School " + school + "')");
        for (int step = 1; step <= 12; step++) {
            if (!customised && step >= 2 && step <= 4) continue;
            String started = "timestamptz '2026-11-01T05:00:00Z' + interval '" + step + " hours'";
            String values;
            if (active == 0 || step < active) {
                values = "'COMPLETED', " + started + ", NULL, " + started + " + interval '30 minutes', 7";
            } else if (step == active) {
                values = "'ACTIVE', " + started + ", " + started + " + interval '2 hours', NULL, NULL";
            } else {
                values = "'LOCKED', NULL, NULL, NULL, NULL";
            }
            st.execute("INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, step_order, status, started_at, due_at, "
                    + "completed_at, completed_by, assignee_user_id) VALUES (" + id + ", " + school + ", " + step + ", " + values + ", 7)");
        }
    }

    private static long insertInstance(Statement st, String order, long school, int active) throws SQLException {
        try (ResultSet rs = st.executeQuery("INSERT INTO workflow.workflow_instances (definition_id, entity_type, entity_id, school_id, "
                + "current_step, status, completed_at) VALUES ('NOTEBOOK_ORDER_TRACKER', 'NOTEBOOK_ORDER', '" + order + "', " + school + ", "
                + active + ", " + (active == 0 ? "'COMPLETED', now()" : "'IN_PROGRESS', NULL") + ") RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void stageNote(Statement st, String order, int step, String note, String by, String at) throws SQLException {
        st.execute("UPDATE workflow.workflow_instance_steps SET stage_note = " + note + ", stage_note_saved_by = " + by
                + ", stage_note_saved_at = " + (at.equals("NULL") ? "NULL" : "'" + at + "'") + " WHERE instance_id = "
                + INSTANCE.get(order) + " AND step_order = " + step);
    }

    private static void stepData(Statement st, String order, int step, String json) throws SQLException {
        st.execute("UPDATE workflow.workflow_instance_steps SET data = '" + json + "'::jsonb, version = version + 3 WHERE instance_id = "
                + INSTANCE.get(order) + " AND step_order = " + step);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static final String TRACKERS = "SELECT t.order_id, t.school_id, t.customised, t.ordered_count, t.placed_at, t.time_zone, "
            + "t.design_state, t.designer_id, t.vendor_name, t.approved_draft_ref, t.school_name, i.definition_id, i.entity_type, "
            + "i.status, i.current_step FROM workflow.workflow_notebook_trackers t JOIN workflow.workflow_instances i ON i.id = t.instance_id "
            + "ORDER BY t.order_id";
    static final String STEPS = "SELECT t.order_id, s.step_order, s.definition_id, s.status, s.assignee_user_id, s.started_at, s.due_at, "
            + "s.completed_at, s.completed_by, (s.data - 'schoolVerification')::text, s.stage_note, s.stage_note_saved_by, "
            + "s.stage_note_saved_at FROM workflow.workflow_instance_steps s "
            + "JOIN workflow.workflow_notebook_trackers t ON t.instance_id = s.instance_id ORDER BY t.order_id, s.step_order";

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, OWNER, OWNER)
                .schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration/workflow")
                .target(target)
                .load().migrate();
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), "admin", "admin");
    }

    private static Connection runtime() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), "app_rt", "app_rt");
    }

    /** Rows as "a|b|c" strings, read by the superuser (which row-level security does not filter). */
    private static List<String> rows(String sql) throws SQLException {
        try (Connection c = admin(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                List<String> cells = new ArrayList<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    boolean bool = meta.getColumnType(i) == Types.BOOLEAN || meta.getColumnType(i) == Types.BIT;
                    String cell = bool ? String.valueOf(rs.getBoolean(i)) : rs.getString(i);
                    cells.add(rs.wasNull() ? "null" : cell);
                }
                rows.add(String.join("|", cells));
            }
            return rows;
        }
    }

    private static Map<String, Long> versions(String sql) throws SQLException {
        try (Connection c = admin(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Map<String, Long> versions = new HashMap<>();
            while (rs.next()) versions.put(rs.getString(1), rs.getLong(2));
            return versions;
        }
    }

    /** What the runtime role sees in a table with one session setting (or none). */
    private static long runtimeCount(String table, String setting, String value) throws SQLException {
        try (Connection c = runtime(); Statement st = c.createStatement()) {
            if (setting != null) st.execute("SELECT set_config('" + setting + "', '" + value + "', false)");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM workflow." + table)) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The directory ids of the given sample vendors, ascending, as a JSON array. */
    private static String ids(String... legacyKeys) throws SQLException {
        String keys = java.util.Arrays.stream(legacyKeys).map(key -> "'" + key + "'").collect(Collectors.joining(","));
        return "[" + String.join(", ", rows("SELECT id FROM workflow.workflow_vendors WHERE legacy_key IN (" + keys + ") ORDER BY id")) + "]";
    }

    private static void assertSelections(String order, String expectedJson) throws SQLException {
        assertEquals(List.of("true"), rows("SELECT vendor_selections = '" + expectedJson + "'::jsonb FROM workflow.workflow_notebook_trackers "
                + "WHERE order_id = '" + order + "'"), order + " expected " + expectedJson + " but was "
                + rows("SELECT vendor_selections FROM workflow.workflow_notebook_trackers WHERE order_id = '" + order + "'"));
    }

    private static void assertViolates(Statement st, String sql, String expected) {
        SQLException refused = assertThrows(SQLException.class, () -> st.execute(sql), sql);
        assertTrue(refused.getMessage().contains(expected), expected + " expected, got: " + refused.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }
}
