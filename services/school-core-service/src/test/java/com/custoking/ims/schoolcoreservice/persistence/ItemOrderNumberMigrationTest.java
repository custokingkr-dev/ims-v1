package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.SchoolOrderCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tenant_school V31 and catalog V37 and V38 (item order workflows design §3.2, D4, D11, D12, D13) applied to a database that
 * already holds schools, orders and a PR #380 product workflow, as dev and prod do: school codes derived with collisions,
 * existing orders numbered in placement order per item code and calendar year in the school's time zone, reporting told,
 * the counters continuing from there, open product orders moved onto the order workflow engine and the retired tables made
 * read-only for the runtime role. Catalog stops at V39: V40 drops the old queue that the rollout-window tests here use.
 */
class ItemOrderNumberMigrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        // The runtime role exists before the migrations run, as in every environment, so their grants and revokes apply.
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        // The dedicated role a deployed school-core connects as; afterMigrate__dedicated_runtime_acl.sql gives it app_rt's set.
        owner.sql("CREATE ROLE ims_school_core_rt LOGIN PASSWORD 'ims_school_core_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        flyway("tenant_school", "30").migrate();
        flyway("catalog", "36").migrate();
        // scripts/create-app-rt-role.sql sets these default privileges in every environment, so a table the owner creates
        // afterwards hands app_rt full DML unless its migration takes some back.
        owner.sql("ALTER DEFAULT PRIVILEGES FOR ROLE owner IN SCHEMA catalog GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt").update();
        seed();
        flyway("tenant_school", null).migrate();
        flyway("catalog", "37").migrate();
        // V37 commits on its own, and the previous revision, which never mints, keeps placing until V38 locks the orders.
        order("CK-3001", 1, "FLIERS", "DRAFT", 2, "{}");
        order("CK-3002", 3, "TIES", "DRAFT", 2, "{}");
        order("CK-3003", 1, "UNIFORMS", "DRAFT", 1, "{}");
        for (String id : List.of("CK-3001", "CK-3002", "CK-3003")) placeAsThePreviousRevision(owner, id);
        // V38 alone, without afterMigrate: the moment it commits, the previous revision's writes resume as the dedicated role.
        flyway("catalog", "38", true).migrate();
        owner.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                    design_status, superadmin_approval_status, placed_at, created_at, form_version, attributes, version, order_number)
                VALUES ('CK-3004', 1, 'NOTEBOOKS', '{}', 0, 0, 0, 'PROCESSING', 'NOT_REQUIRED', 'PENDING', now(), now(), 2,
                    '{"trackerManaged": true}', 1, 'NB-DPS_GUR-26-11-13-0900')
                """).update();
        var dedicated = new SingleConnectionDataSource(pg.getJdbcUrl(), "ims_school_core_rt", "ims_school_core_rt", true);
        try {
            var client = JdbcClient.create(dedicated);
            client.sql("SELECT set_config('app.current_school_id', '1', false)").query().singleRow();
            // NotebookTrackerCreationQueue.enqueue at origin/dev, verbatim.
            client.sql("""
                    INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id) VALUES (:order, :school)
                    ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now(), attempts = 0, dead_lettered_at = NULL
                    """).param("order", "CK-3004").param("school", 1L).update();
            dedicatedEnqueueBeforeAfterMigrate = "forwarded";
        } catch (RuntimeException failure) {
            dedicatedEnqueueBeforeAfterMigrate = String.valueOf(failure.getMessage());
        } finally {
            dedicated.destroy();
        }
        // Pinned to the last version before catalog V40, which retired the old queue and its forwarding trigger: these tests
        // replay the rollout window V38 opened and that release closed (CreationQueueRetirementMigrationTest covers V40).
        flyway("catalog", "39").migrate();
        // What scripts/create-app-rt-role.sql grants in every environment, so the runtime role can act as a school-core does.
        owner.sql("GRANT USAGE ON SCHEMA catalog, tenant_school TO app_rt").update();
        owner.sql("GRANT SELECT, UPDATE ON catalog.catalog_orders, tenant_school.schools TO app_rt").update();
    }

    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    /** What the previous revision's enqueue did, as the dedicated role, between V38's commit and afterMigrate. */
    static String dedicatedEnqueueBeforeAfterMigrate;

    private static Flyway flyway(String schema, String target) {
        return flyway(schema, target, false);
    }

    private static Flyway flyway(String schema, String target, boolean withoutAfterMigrate) {
        var configuration = Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/" + schema).skipDefaultCallbacks(withoutAfterMigrate);
        if (target != null) configuration = configuration.target(target);
        return configuration.load();
    }

    private static void seed() {
        owner.sql("""
                INSERT INTO tenant_school.schools (id, name, short_code, city, state, active, created_at, time_zone) VALUES
                    (1, 'Delhi Public School', 'DPS1', 'Gurugram', 'Haryana', true, now(), 'Asia/Kolkata'),
                    (2, 'Delhi Public School', 'DPS2', 'Gurgaon', NULL, true, now(), 'Asia/Kolkata'),
                    (3, 'The Academy', 'ACAD', NULL, 'Karnataka', true, now(), 'Asia/Kolkata'),
                    (4, 'Night Owl School', 'NOS', 'London', NULL, true, now(), 'UTC')
                """).update();
        // id, school, category, status, placed_at, created_at, form_version, attributes
        Object[][] orders = {
                {"CK-1001", 1, "NOTEBOOKS", "PRINTING_PRESS", "2026-11-12T19:00:00Z", "2026-11-01T10:00:00Z", 2, "{\"trackerManaged\": true}"},
                {"CK-1002", 1, "NOTEBOOKS", "DELIVERED", "2026-03-01T06:00:00Z", "2026-02-28T10:00:00Z", 1, "{}"},
                {"CK-1003", 2, "NOTEBOOKS", "APPROVED", "2025-12-31T19:00:00Z", "2025-12-30T10:00:00Z", 1, "{}"},
                {"CK-1004", 4, "NOTEBOOKS", "PROCESSING", "2025-12-31T19:00:00Z", "2025-12-30T10:00:00Z", 1, "{}"},
                {"CK-1005", 1, "FLIERS", "PROCESSING", "2026-11-10T06:00:00Z", "2026-11-09T10:00:00Z", 2, "{}"},
                {"CK-1006", 1, "FLIERS", "DELIVERED", "2026-11-09T06:00:00Z", "2026-11-08T10:00:00Z", 2, "{}"},
                {"CK-1007", 1, "FLIERS", "DRAFT", null, "2026-11-11T10:00:00Z", 2, "{}"},
                {"CK-1008", 3, "TIES", "PROCESSING", "2026-11-11T06:00:00Z", "2026-11-10T10:00:00Z", 2, "{}"},
                {"CK-1009", 1, "UNIFORMS", "APPROVED", "2026-11-11T06:00:00Z", "2026-11-10T10:00:00Z", 1, "{}"},
                {"CK-1010", 3, "CERTIFICATES", "DESIGN_APPROVAL", null, "2026-11-12T10:00:00Z", 2, "{}"},
        };
        for (Object[] order : orders) {
            owner.sql("""
                    INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                        design_status, superadmin_approval_status, placed_at, created_at, form_version, attributes, version)
                    VALUES (:id, :school, :category, '{}', 0, 0, 0, :status, 'NOT_REQUIRED', 'PENDING',
                        CAST(:placed AS timestamptz), CAST(:created AS timestamptz), :form, CAST(:attributes AS jsonb), 3)
                    """).param("id", order[0]).param("school", order[1]).param("category", order[2]).param("status", order[3])
                    .param("placed", order[4]).param("created", order[5]).param("form", order[6]).param("attributes", order[7]).update();
        }
        // The previous revision had queued CK-1001's notebook tracker and was retrying it.
        owner.sql("""
                INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id, attempts, next_attempt_at, last_error)
                VALUES ('CK-1001', 1, 3, now() - interval '1 minute', 'order workflow engine answered HTTP 503')
                """).update();
        // CK-1005 was being worked on the PR #380 product workflow.
        owner.sql("""
                INSERT INTO catalog.product_order_workflows (order_id, school_id, category_code, current_stage, status)
                VALUES ('CK-1005', 1, 'FLIERS', 'CDR_CONVERSION', 'ACTIVE')
                """).update();
    }

    @Test
    void everySchoolGetsAUniqueDerivedOrderCodeOldestFirst() {
        assertThat(codes()).containsExactly(Map.entry(1L, "DPS_GUR"), Map.entry(2L, "DPS2_GUR"), Map.entry(3L, "ACA_KAR"),
                Map.entry(4L, "NOS_LON"));
        assertThat(owner.sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'tenant_school' AND table_name = 'schools' AND column_name = 'order_code'")
                .query(String.class).single()).isEqualTo("NO");
    }

    @Test
    void theDatabaseDerivesTheSameCodeAsTheApplication() {
        for (String[] school : new String[][]{{"Delhi Public School", "Gurugram", null}, {"St. Mary's Convent School", "New Delhi", null},
                {"The School of Arts and Science for Girls", "Pune", null}, {"Ryan & Sons Academy", "", "Maharashtra"},
                {"Kendriya", null, null}, {"X", "42", "1"}, {"A B C D E F G H I J", "Agra", null}}) {
            String database = owner.sql("SELECT tenant_school.school_order_code_base(:name, :city, :state)")
                    .param("name", school[0]).param("city", school[1]).param("state", school[2]).query(String.class).single();
            assertThat(database).as(school[0]).isEqualTo(SchoolOrderCode.derive(school[0], school[1], school[2]));
        }
    }

    @Test
    void aSchoolInsertedWithoutACodeGetsOneAndCodesAreValidatedAndUnique() {
        owner.sql("INSERT INTO tenant_school.schools (id, name, short_code, city, active, created_at) VALUES (50, 'Delhi Public School', 'DPS50', 'Gurugram', true, now())").update();
        assertThat(code(50)).as("a third DPS in Gurugram").isEqualTo("DPS3_GUR");
        owner.sql("INSERT INTO tenant_school.schools (id, name, short_code, order_code, active, created_at) VALUES (51, 'Lower', 'LOW', ' kvs_del ', true, now())").update();
        assertThat(code(51)).as("stored trimmed and upper case").isEqualTo("KVS_DEL");
        assertThatThrownBy(() -> owner.sql("INSERT INTO tenant_school.schools (id, name, short_code, order_code, active, created_at) VALUES (52, 'Twin', 'TWN', 'KVS_DEL', true, now())").update())
                .hasMessageContaining("uk_schools_order_code");
        assertThatThrownBy(() -> owner.sql("UPDATE tenant_school.schools SET order_code = 'KVS-DEL' WHERE id = 51").update())
                .hasMessageContaining("ck_schools_order_code");
        owner.sql("DELETE FROM tenant_school.schools WHERE id IN (50, 51)").update();
    }

    @Test
    void categoriesCarryTheirItemCodesNounsAndTemplates() {
        var seeded = owner.sql("SELECT code, order_code, item_noun, workflow_definition FROM catalog.product_categories WHERE order_code IS NOT NULL ORDER BY code")
                .query((rs, n) -> rs.getString(1) + "=" + rs.getString(2) + "/" + rs.getString(3) + "/" + rs.getString(4)).list();
        assertThat(seeded).containsExactly("BELTS=BLT/belt/GENERAL_ITEM_ORDER_WORKFLOW", "BILLBOOKS=BBK/bill book/BILL_BOOK_ORDER_WORKFLOW",
                "BROCHURES=BCH/brochure/GENERAL_ITEM_ORDER_WORKFLOW", "CERTIFICATES=CF/certificate/GENERAL_ITEM_ORDER_WORKFLOW",
                "FLEX=FX/flex/FLEX_ORDER_WORKFLOW", "FLIERS=FE/flier/FLIER_ORDER_WORKFLOW", "FLUTE_BOARDS=FB/flute board/GENERAL_ITEM_ORDER_WORKFLOW",
                "NOTEBOOKS=NB/notebook/NOTEBOOK_ORDER_TRACKER", "REPORT_CARDS=RC/report card/GENERAL_ITEM_ORDER_WORKFLOW",
                "STANDEES=STD/standee/GENERAL_ITEM_ORDER_WORKFLOW", "TIES=TIE/tie/null", "TROPHIES=TRP/trophy/null");
        assertThatThrownBy(() -> owner.sql("UPDATE catalog.product_categories SET order_code = NULL WHERE code = 'FLEX'").update())
                .as("a template needs an item code").hasMessageContaining("ck_product_categories_workflow_needs_code");
        assertThatThrownBy(() -> owner.sql("UPDATE catalog.product_categories SET order_code = 'FE' WHERE code = 'FLEX'").update())
                .hasMessageContaining("uk_product_categories_order_code");
        assertThatThrownBy(() -> owner.sql("UPDATE catalog.product_categories SET workflow_definition = 'OTHER' WHERE code = 'FLEX'").update())
                .hasMessageContaining("ck_product_categories_workflow_definition");
    }

    @Test
    void placedOrdersAreNumberedInPlacementOrderPerItemAndYearInTheSchoolsTimeZone() {
        assertThat(number("CK-1003")).as("00:30 IST on 1 January is 2026, the first placement of its year").isEqualTo("NB-DPS2_GUR-26-01-01-0001");
        assertThat(number("CK-1002")).isEqualTo("NB-DPS_GUR-26-03-01-0002");
        assertThat(number("CK-1001")).as("00:30 IST on the 13th is the 13th").isEqualTo("NB-DPS_GUR-26-11-13-0003");
        assertThat(number("CK-1004")).as("19:00 UTC on 31 December is 2025's in London").isEqualTo("NB-NOS_LON-25-12-31-0001");
        assertThat(number("CK-1006")).isEqualTo("FE-DPS_GUR-26-11-09-0001");
        assertThat(number("CK-1005")).isEqualTo("FE-DPS_GUR-26-11-10-0002");
        assertThat(number("CK-1008")).as("an item with a code but no template is numbered too").isEqualTo("TIE-ACA_KAR-26-11-11-0001");
        assertThat(number("CK-1010")).as("a placed order without a placement time is dated by its creation").isEqualTo("CF-ACA_KAR-26-11-12-0001");
        assertThat(number("CK-1007")).as("drafts get no number").isNull();
        assertThat(number("CK-1009")).as("a category without a code gets no number").isNull();
    }

    @Test
    void theCountersContinueFromTheBackfill() {
        assertThat(counters()).containsExactly("CF/2026=1", "FE/2026=2", "NB/2025=1", "NB/2026=3", "TIE/2026=1");
        assertThat(owner.sql("SELECT relrowsecurity FROM pg_class WHERE oid = 'catalog.order_number_counters'::regclass").query(Boolean.class).single())
                .as("global, like the id sequence").isFalse();
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE")) {
            assertThat(privilege("catalog.order_number_counters", privilege)).as(privilege).isTrue();
        }
        assertThat(privilege("catalog.order_number_counters", "DELETE")).isFalse();
    }

    @Test
    void aNumberNeverChanges() {
        assertThatThrownBy(() -> owner.sql("UPDATE catalog.catalog_orders SET order_number = 'FE-OTHER' WHERE id = 'CK-1005'").update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> owner.sql("UPDATE catalog.catalog_orders SET order_number = NULL WHERE id = 'CK-1005'").update())
                .hasMessageContaining("immutable");
        owner.sql("UPDATE catalog.catalog_orders SET notes = 'still editable' WHERE id = 'CK-1005'").update();
        assertThat(number("CK-1005")).isEqualTo("FE-DPS_GUR-26-11-10-0002");
    }

    @Test
    void reportingIsToldEveryBackfilledNumber() {
        var events = owner.sql("""
                SELECT aggregate_id, payload ->> 'orderNumber' AS number, payload ->> 'itemCode' AS item, payload ->> 'version' AS version,
                       payload ->> 'status' AS status
                FROM tenant_school.outbox_events WHERE event_type = 'catalog-order.upserted.v1' AND payload ->> 'orderNumber' IS NOT NULL ORDER BY aggregate_id
                """).query((rs, n) -> rs.getString(1) + " " + rs.getString(2) + " " + rs.getString(3) + " v" + rs.getString(4) + " " + rs.getString(5)).list();
        assertThat(events).containsExactly(
                "CK-1001 NB-DPS_GUR-26-11-13-0003 NB v3 PRINTING_PRESS",
                "CK-1002 NB-DPS_GUR-26-03-01-0002 NB v3 DELIVERED",
                "CK-1003 NB-DPS2_GUR-26-01-01-0001 NB v3 APPROVED",
                "CK-1004 NB-NOS_LON-25-12-31-0001 NB v3 PROCESSING",
                "CK-1005 FE-DPS_GUR-26-11-10-0002 FE v3 PROCESSING",
                "CK-1006 FE-DPS_GUR-26-11-09-0001 FE v3 DELIVERED",
                "CK-1008 TIE-ACA_KAR-26-11-11-0001 TIE v3 PROCESSING",
                "CK-1010 CF-ACA_KAR-26-11-12-0001 CF v3 DESIGN_APPROVAL");
    }

    @Test
    void openProductOrdersMoveOntoTheEngineAndOpenWorkflowOrdersAreQueued() {
        assertThat(attributes("CK-1005")).containsEntry("trackerManaged", "true").containsEntry("trackerPending", "true")
                .containsEntry("workflowDefinition", "FLIER_ORDER_WORKFLOW");
        assertThat(attributes("CK-1010")).as("an open certificate order without a V36 record moves too")
                .containsEntry("trackerManaged", "true").containsEntry("workflowDefinition", "GENERAL_ITEM_ORDER_WORKFLOW");
        assertThat(attributes("CK-1006")).as("a delivered order stays where it finished").doesNotContainKey("trackerManaged");
        assertThat(attributes("CK-1008")).as("ties have no template").doesNotContainKey("trackerManaged");
        assertThat(attributes("CK-1001")).as("an existing notebook tracker is not marked pending again")
                .containsEntry("trackerManaged", "true").doesNotContainKey("trackerPending");
        assertThat(owner.sql("SELECT order_id FROM catalog.order_workflow_creation_queue WHERE order_id LIKE 'CK-10%' ORDER BY order_id")
                .query(String.class).list())
                .as("the moved orders, and the open notebook tracker so operations fills in its number")
                .containsExactly("CK-1001", "CK-1005", "CK-1010");
        assertThat(owner.sql("SELECT attempts || ' ' || last_error FROM catalog.order_workflow_creation_queue WHERE order_id = 'CK-1001'")
                .query(String.class).single()).as("a row the previous revision had queued moves with its retries")
                .isEqualTo("3 order workflow engine answered HTTP 503");
    }

    /**
     * The rollout window: the previous school-core revision serves after this migration (until its successor is ready, and in
     * prod beside it through the canary). Its drain, verbatim from NotebookTrackerCreationQueue.claim at origin/dev, would
     * give any order it finds a notebook workflow; it must find nothing, now or later.
     */
    @Test
    void thePreviousRevisionsDrainFindsNothingToCreate() {
        assertThat(owner.sql("""
                SELECT order_id, school_id FROM catalog.notebook_tracker_creation_queue WHERE next_attempt_at <= now() AND dead_lettered_at IS NULL
                ORDER BY next_attempt_at, order_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query().listOfRows()).isEmpty();
        assertThat(owner.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue").query(Long.class).single()).isZero();
        assertThat(owner.sql("SELECT count(*) FROM catalog.order_workflow_creation_queue WHERE order_id IN ('CK-1005', 'CK-1010')").query(Long.class).single())
                .as("the fliers V38 moved wait in the new queue").isEqualTo(2L);
    }

    /**
     * Whatever the previous revision queues lands in the new queue with fresh retries, under its own row-level security, and
     * its own "done" (a status push) cannot delete the new row: it carries the order number to the workflow.
     */
    @Test
    void whatThePreviousRevisionQueuesIsForwardedAndItsDoneCannotLoseIt() {
        order("CK-2001", 1, "NOTEBOOKS", "PRINTING_PRESS", 2, "{\"trackerManaged\": true}");
        owner.sql("INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, attempts, dead_lettered_at) VALUES ('CK-2001', 1, 48, now())").update();
        var runtime = runtime(1);
        try {
            // NotebookTrackerCreationQueue.enqueue and .done at origin/dev, verbatim.
            runtime.client().sql("""
                    INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id) VALUES (:order, :school)
                    ON CONFLICT (order_id) DO UPDATE SET next_attempt_at = now(), attempts = 0, dead_lettered_at = NULL
                    """).param("order", "CK-2001").param("school", 1L).update();
            assertThat(runtime.client().sql("DELETE FROM catalog.notebook_tracker_creation_queue WHERE order_id = :order")
                    .param("order", "CK-2001").update()).isZero();
        } finally {
            runtime.close();
        }
        assertThat(owner.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue").query(Long.class).single()).isZero();
        assertThat(owner.sql("""
                SELECT attempts || ' ' || (dead_lettered_at IS NULL) || ' ' || (next_attempt_at <= now()) FROM catalog.order_workflow_creation_queue
                WHERE order_id = 'CK-2001'
                """).query(String.class).single()).as("revived, due now").isEqualTo("0 true true");
        var otherSchool = runtime(2);
        try {
            assertThatThrownBy(() -> otherSchool.client().sql("INSERT INTO catalog.notebook_tracker_creation_queue (order_id, school_id) VALUES ('CK-2001', 1)").update())
                    .as("another school's order is refused, as before").hasStackTraceContaining("row-level security");
        } finally {
            otherSchool.close();
        }
    }

    /**
     * An order the previous revision places has no number when its transaction commits (that revision never mints one), and
     * a flier is placed on approve, quote and deliver: it is queued for repair. A placement that mints in its own
     * transaction, as this release's does, is not; nor is an order of a category without a code, nor any other change.
     */
    @Test
    void aPlacementThatCommitsWithoutANumberIsQueuedForRepair() {
        order("CK-2101", 1, "FLIERS", "DRAFT", 2, "{}");
        order("CK-2102", 3, "TIES", "DRAFT", 2, "{}");
        order("CK-2103", 1, "UNIFORMS", "DRAFT", 1, "{}");
        order("CK-2104", 1, "FLIERS", "DRAFT", 2, "{}");
        order("CK-2105", 1, "FLIERS", "DRAFT", 2, "{}");
        var school1 = runtime(1);
        var school3 = runtime(3);
        try {
            // CatalogOrderFormService.transition at origin/dev, as the runtime role scoped to the order's school.
            for (String id : List.of("CK-2101", "CK-2103")) placeAsThePreviousRevision(school1.client(), id);
            placeAsThePreviousRevision(school3.client(), "CK-2102");
            school1.client().sql("""
                    DO $placed$ BEGIN
                        UPDATE catalog.catalog_orders SET status = 'ORDER_DETAILS', placed_at = now() WHERE id = 'CK-2104';
                        UPDATE catalog.catalog_orders SET order_number = 'FE-DPS_GUR-26-11-13-0999' WHERE id = 'CK-2104';
                    END $placed$
                    """).update();
            school1.client().sql("UPDATE catalog.catalog_orders SET notes = 'edited' WHERE id = 'CK-2105'").update();
        } finally {
            school1.close();
            school3.close();
        }
        assertThat(owner.sql("SELECT order_id || ' ' || repair FROM catalog.order_workflow_creation_queue WHERE order_id LIKE 'CK-21%' ORDER BY order_id")
                .query(String.class).list()).containsExactly("CK-2101 true", "CK-2102 true");
    }

    /** A repair that cannot be queued never fails the placement: the order is placed, and PostgreSQL logs a warning. */
    @Test
    void aRepairThatCannotBeQueuedNeverFailsThePlacement() {
        order("CK-2201", 1, "FLIERS", "DRAFT", 2, "{}");
        owner.sql("REVOKE INSERT ON catalog.order_workflow_creation_queue FROM app_rt").update();
        var runtime = runtime(1);
        try {
            placeAsThePreviousRevision(runtime.client(), "CK-2201");
        } finally {
            runtime.close();
            owner.sql("GRANT INSERT ON catalog.order_workflow_creation_queue TO app_rt").update();
        }
        assertThat(owner.sql("SELECT status FROM catalog.catalog_orders WHERE id = 'CK-2201'").query(String.class).single()).isEqualTo("PROCESSING");
        assertThat(owner.sql("SELECT count(*) FROM catalog.order_workflow_creation_queue WHERE order_id = 'CK-2201'").query(Long.class).single()).isZero();
    }

    /**
     * V37 commits on its own and the previous revision does not mint, so orders it placed before V38 took its locks have no
     * number. V38 queues each one of a coded category for repair (the claim mints it and moves an open flier), open or
     * closed, tracker-managed or not; a category without a code is left alone, as V37 leaves it.
     */
    @Test
    void ordersThePreviousRevisionPlacedBetweenV37AndV38AreQueuedForRepair() {
        assertThat(owner.sql("SELECT order_id || ' ' || repair FROM catalog.order_workflow_creation_queue WHERE order_id IN ('CK-3001', 'CK-3002', 'CK-3003') ORDER BY order_id")
                .query(String.class).list()).containsExactly("CK-3001 true", "CK-3002 true");
        assertThat(attributes("CK-3001")).as("an open flier moves onto the engine as step 1 moves the others")
                .containsEntry("trackerManaged", "true").containsEntry("workflowDefinition", "FLIER_ORDER_WORKFLOW");
        assertThat(number("CK-3001")).as("the claim's repair mints it, dated by its placement").isNull();
    }

    /**
     * The previous revision's writes resume the moment V38 commits, before afterMigrate gives the dedicated runtime role its
     * privileges on new tables: V38 grants the new queue to that role itself, so the forwarding trigger can write it.
     */
    @Test
    void thePreviousRevisionsEnqueueWorksAsTheDedicatedRoleTheMomentV38Commits() {
        assertThat(dedicatedEnqueueBeforeAfterMigrate).isEqualTo("forwarded");
        assertThat(owner.sql("SELECT repair FROM catalog.order_workflow_creation_queue WHERE order_id = 'CK-3004'").query(Boolean.class).single())
                .as("forwarded into the new queue, not a repair").isFalse();
        assertThat(owner.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue").query(Long.class).single()).isZero();
    }

    @Test
    void theNewQueueIsTenantIsolatedAndWritableByTheRuntimeRole() {
        assertThat(owner.sql("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'catalog.order_workflow_creation_queue'::regclass")
                .query(Boolean.class).single()).isTrue();
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            assertThat(privilege("catalog.order_workflow_creation_queue", privilege)).as(privilege).isTrue();
        }
    }

    private static void placeAsThePreviousRevision(JdbcClient runtime, String id) {
        runtime.sql("""
                UPDATE catalog.catalog_orders SET status = 'PROCESSING', design_status = 'NOT_REQUIRED', superadmin_approval_status = 'PENDING',
                    placed_at = now(), placed_by = 7, updated_by = '7', version = version + 1 WHERE id = :id
                """).param("id", id).update();
    }

    private static void order(String id, long school, String category, String status, int form, String attributes) {
        owner.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                    design_status, superadmin_approval_status, placed_at, created_at, form_version, attributes, version)
                VALUES (:id, :school, :category, '{}', 0, 0, 0, :status, 'NOT_REQUIRED', 'PENDING',
                    CASE WHEN :status <> 'DRAFT' THEN now() END, now(), :form, CAST(:attributes AS jsonb), 1)
                """).param("id", id).param("school", school).param("category", category).param("status", status)
                .param("form", form).param("attributes", attributes).update();
    }

    /** The runtime role on one connection, scoped to a school as TenantAwareDataSource scopes a school's request. */
    private record Runtime(SingleConnectionDataSource dataSource, JdbcClient client) {
        void close() { dataSource.destroy(); }
    }

    private static Runtime runtime(long school) {
        var dataSource = new SingleConnectionDataSource(pg.getJdbcUrl(), "app_rt", "app_rt", true);
        var client = JdbcClient.create(dataSource);
        client.sql("SELECT set_config('app.current_school_id', :school, false), set_config('app.bypass_rls', 'off', false)")
                .param("school", String.valueOf(school)).query().singleRow();
        return new Runtime(dataSource, client);
    }


    @Test
    void theRetiredTablesAreReadOnlyForTheRuntimeRole() {
        for (String table : List.of("catalog.product_order_workflows", "catalog.product_order_workflow_steps")) {
            assertThat(privilege(table, "SELECT")).as(table).isTrue();
            for (String write : List.of("INSERT", "UPDATE", "DELETE")) assertThat(privilege(table, write)).as(table + " " + write).isFalse();
        }
        assertThat(owner.sql("SELECT obj_description('catalog.product_order_workflows'::regclass)").query(String.class).single())
                .contains("Read-only since V38");
    }

    private static Map<Long, String> codes() {
        Map<Long, String> codes = new java.util.LinkedHashMap<>();
        owner.sql("SELECT id, order_code FROM tenant_school.schools WHERE id <= 4 ORDER BY id")
                .query((rs, n) -> codes.put(rs.getLong(1), rs.getString(2))).list();
        return codes;
    }

    private static String code(long school) {
        return owner.sql("SELECT order_code FROM tenant_school.schools WHERE id = :id").param("id", school).query(String.class).single();
    }

    private static String number(String order) {
        return owner.sql("SELECT order_number FROM catalog.catalog_orders WHERE id = :id").param("id", order).query(String.class).optional().orElse(null);
    }

    private static List<String> counters() {
        return owner.sql("SELECT item_code || '/' || year || '=' || last_value FROM catalog.order_number_counters ORDER BY item_code, year")
                .query(String.class).list();
    }

    private static boolean privilege(String table, String privilege) {
        return owner.sql("SELECT has_table_privilege('app_rt', CAST(:table AS regclass), :privilege)").param("table", table)
                .param("privilege", privilege).query(Boolean.class).single();
    }

    private static Map<String, Object> attributes(String order) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        owner.sql("SELECT key, value FROM catalog.catalog_orders, jsonb_each_text(attributes) WHERE id = :id").param("id", order)
                .query((rs, n) -> result.put(rs.getString(1), rs.getString(2))).list();
        return result;
    }
}
