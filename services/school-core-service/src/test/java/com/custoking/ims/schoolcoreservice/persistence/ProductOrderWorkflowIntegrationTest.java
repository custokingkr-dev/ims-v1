package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormController;
import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormExceptionHandler;
import com.custoking.ims.schoolcoreservice.api.CatalogProductFormExceptionHandler;
import com.custoking.ims.schoolcoreservice.api.ProductOrderWorkflowController;
import com.custoking.ims.schoolcoreservice.api.ValidationExceptionHandler;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantAwareDataSource;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantContextFilter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product orders on the item order workflows (design D3, D4, D5, D10, D11, D14) end to end: the order flow, the order form
 * controller, the retired product workflow's controller and the creation queue, against the runtime role with row-level
 * security forced, as production runs them. Placing an order of a category with a template starts its workflow and mints its
 * number; the PR #380 workflow is a read-only record whose writes answer WORKFLOW_MOVED, and its progress travels to the
 * engine with the creation call. The owner connection only seeds what the flow cannot reach in a test (a retired V36 record,
 * a finished status).
 */
class ProductOrderWorkflowIntegrationTest {
    private static final String TOKEN = "test-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());
    /** The categories with a template (catalog V37), their item codes and templates. */
    private static final Map<String, String[]> WORKFLOW_CATEGORIES = new LinkedHashMap<>();
    static {
        WORKFLOW_CATEGORIES.put("FLIERS", new String[]{"FE", "FLIER_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("FLEX", new String[]{"FX", "FLEX_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("BILLBOOKS", new String[]{"BBK", "BILL_BOOK_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("CERTIFICATES", new String[]{"CF", "GENERAL_ITEM_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("FLUTE_BOARDS", new String[]{"FB", "GENERAL_ITEM_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("REPORT_CARDS", new String[]{"RC", "GENERAL_ITEM_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("BROCHURES", new String[]{"BCH", "GENERAL_ITEM_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("BELTS", new String[]{"BLT", "GENERAL_ITEM_ORDER_WORKFLOW"});
        WORKFLOW_CATEGORIES.put("STANDEES", new String[]{"STD", "GENERAL_ITEM_ORDER_WORKFLOW"});
    }
    private static final List<String> V36_STAGES = List.of("ORDER_DETAILS", "DESIGN_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL",
            "PRODUCTION_PLANNING", "PRE_PRINT_QUALITY_CHECK", "PRODUCTION_FOLLOW_UP", "FINAL_VERIFICATION", "DELIVERY_INVOICING");

    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    static AnnotationConfigApplicationContext context;
    static CatalogReadRepository orders;
    static NotebookTrackerCreationQueue queue;
    static MockMvc mvc;
    @TempDir static Path assets;
    static final List<TrackerGateway.NewTracker> CREATED = Collections.synchronizedList(new ArrayList<>());
    static final List<String> RESOLVED_CATEGORIES = Collections.synchronizedList(new ArrayList<>());

    record Actor(String role, long userId, Long schoolId, String operatorSchools, String operatorScopes, String permissions) { }

    static final Actor SUPERADMIN = new Actor("SUPERADMIN", 99, null, null, null, null);
    static final Actor SCHOOL_ADMIN = new Actor("ADMIN", 7, 1L, null, null, "order:create,order:read,order:update");
    static final Actor OTHER_SCHOOL_ADMIN = new Actor("ADMIN", 17, 2L, null, null, "order:create,order:read,order:update");
    static final Actor DISABLED_SCHOOL_ADMIN = new Actor("ADMIN", 27, 3L, null, null, "order:create,order:read,order:update");
    static final Actor OPERATOR = new Actor("OPERATIONS", 8, null, "1", null, "order:create,order:read,order:update");
    static final Actor BELTS_OPERATOR = new Actor("OPERATIONS", 11, null, null, "1:BELTS", "order:read,order:update");
    static final Actor FLEX_OPERATOR = new Actor("OPERATIONS", 12, null, null, "1:FLEX", "order:read,order:update");

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        for (String schema : List.of("tenant_school", "catalog")) {
            Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema).load().migrate();
        }
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        owner.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1,'School A','SCA',true,now()),(2,'School B','SCB',true,now()),(3,'Disabled School','SCD',true,now())").update();
        owner.sql("INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled) VALUES (1,'ORDERS',true),(2,'ORDERS',true),(3,'ORDERS',false)").update();
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        owner.sql("GRANT USAGE ON SCHEMA catalog, tenant_school TO app_rt").update();
        owner.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog, tenant_school TO app_rt").update();
        owner.sql("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA catalog, tenant_school TO app_rt").update();
        // As catalog V38 leaves them: the retired workflow's tables are read-only for the runtime role.
        owner.sql("REVOKE INSERT, UPDATE, DELETE ON catalog.product_order_workflows, catalog.product_order_workflow_steps FROM app_rt").update();

        DataSource runtime = new TenantAwareDataSource(new RecordingDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt")));
        var jdbc = JdbcClient.create(runtime);
        var manager = new DataSourceTransactionManager(runtime);
        var outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        TrackerGateway tracker = new TrackerGateway() {
            @Override public void create(NewTracker created) { CREATED.add(created); }
            @Override public ImageAccess imageAccess(String orderId, long userId, String role) { return new ImageAccess(true, null); }
        };
        OperatorResolver operators = (schoolId, category) -> {
            RESOLVED_CATEGORIES.add(category);
            return new OperatorResolver.Resolution("NONE", null);
        };
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionalTestConfiguration.class);
        context.registerBean("transactionManager", org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
        context.registerBean(ModuleEntitlementReadRepository.class, () -> new ModuleEntitlementReadRepository(jdbc));
        context.registerBean(ModuleEntitlementGuard.class, () -> new ModuleEntitlementGuard(context.getBean(ModuleEntitlementReadRepository.class)));
        context.registerBean(ProductCatalogRepository.class, () -> new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, true));
        context.registerBean(NotebookTrackerCreationQueue.class, () -> new NotebookTrackerCreationQueue(jdbc, tracker, operators, manager, outbox));
        context.registerBean(CatalogOrderFormService.class, () -> {
            var forms = new CatalogOrderFormService(jdbc, context.getBean(ProductCatalogRepository.class), new ProductFormRuleEngine(),
                    new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, context.getBean(ModuleEntitlementGuard.class));
            forms.configureTracker(tracker, context.getBean(NotebookTrackerCreationQueue.class));
            forms.configureTransactions(manager);
            return forms;
        });
        context.registerBean(CatalogReadRepository.class, () -> new CatalogReadRepository(jdbc, outbox));
        context.registerBean(ProductOrderWorkflowService.class, () -> new ProductOrderWorkflowService(jdbc, context.getBean(CatalogOrderFormService.class)));
        context.refresh();
        orders = context.getBean(CatalogReadRepository.class);
        queue = context.getBean(NotebookTrackerCreationQueue.class);
        var workflows = context.getBean(ProductOrderWorkflowService.class);
        var formController = new CatalogOrderFormController(context.getBean(CatalogOrderFormService.class), orders, TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(new ProductOrderWorkflowController(workflows, TOKEN), formController)
                .setControllerAdvice(new CatalogOrderFormExceptionHandler(), new CatalogProductFormExceptionHandler(), new ValidationExceptionHandler())
                .addFilters(new TenantContextFilter()).build();
    }

    @BeforeEach
    void clear() {
        owner.sql("DELETE FROM catalog.catalog_orders").update();
        owner.sql("DELETE FROM catalog.order_number_counters").update();
        CREATED.clear();
        RESOLVED_CATEGORIES.clear();
    }

    @AfterEach void clearContext() { TenantContext.clear(); }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (pg != null) pg.stop();
    }

    // ---- placement: workflows and numbers ---------------------------------------------------------------------------------

    @Test
    void placingAnOrderOfEveryTemplatedCategoryStartsItsWorkflowAndMintsItsNumber() throws Exception {
        for (var entry : WORKFLOW_CATEGORIES.entrySet()) {
            String category = entry.getKey();
            String id = placed(category);
            Map<String, Object> row = row(id);
            assertThat(row.get("status")).as(category).isEqualTo("ORDER_DETAILS");
            assertThat(attribute(id, "trackerManaged")).as(category).isEqualTo("true");
            assertThat(attribute(id, "trackerPending")).as(category).isEqualTo("true");
            assertThat(attribute(id, "workflowDefinition")).as(category).isEqualTo(entry.getValue()[1]);
            assertThat(row.get("pricing_status")).as("other items keep their pricing (D5): " + category).isEqualTo("PENDING_PRICING");
            assertThat(String.valueOf(row.get("order_number"))).as(category)
                    .isEqualTo(entry.getValue()[0] + "-SA_IN-" + placementDay(id) + "-0001");
            assertThat(queued(id)).as(category).isTrue();
            form(id, SCHOOL_ADMIN).andExpect(jsonPath("$.order.orderNumber").value(String.valueOf(row.get("order_number"))))
                    .andExpect(jsonPath("$.order.itemCode").value(entry.getValue()[0]))
                    .andExpect(jsonPath("$.order.trackerManaged").value(true))
                    .andExpect(jsonPath("$.order.productionWorkflowAvailable").doesNotExist());
        }
        assertThat(workflows()).as("nothing creates a retired product workflow any more").isZero();
    }

    @Test
    void categoriesWithACodeButNoTemplateGetANumberAndKeepTheApprovalFlow() {
        for (String category : List.of("TIES", "TROPHIES")) {
            String id = placed(category);
            assertThat(row(id).get("status")).as(category).isEqualTo("PROCESSING");
            assertThat(attribute(id, "trackerManaged")).as(category).isNull();
            assertThat(queued(id)).as(category + ": numbered in the placing transaction, so not queued for repair").isFalse();
            assertThat(String.valueOf(row(id).get("order_number"))).as(category)
                    .matches(("TIES".equals(category) ? "TIE" : "TRP") + "-SA_IN-\\d\\d-\\d\\d-\\d\\d-0001");
        }
        String notebook = placed("NOTEBOOKS");
        assertThat(row(notebook).get("pricing_status")).as("notebooks are not quoted (owner D1)").isEqualTo("NOT_APPLICABLE");
        assertThat(String.valueOf(row(notebook).get("order_number"))).isEqualTo("NB-SA_IN-" + placementDay(notebook) + "-0001");
        String uniforms = placedLegacy("UNIFORMS");
        assertThat(row(uniforms).get("order_number")).as("a category without an order code gets no number").isNull();
        String draft = draft("FLIERS");
        assertThat(row(draft).get("order_number")).as("drafts get no number").isNull();
    }

    @Test
    void theRunningNumberCountsPerItemCodeAcrossSchoolsAndANumberNeverChanges() {
        String first = placed("FLIERS");
        String second = placed("FLIERS");
        String flex = placed("FLEX");
        String otherSchool = placedAt(2, "FLIERS");
        assertThat(String.valueOf(row(first).get("order_number"))).endsWith("-0001");
        assertThat(String.valueOf(row(second).get("order_number"))).endsWith("-0002");
        assertThat(String.valueOf(row(flex).get("order_number"))).startsWith("FX-SA_IN-").endsWith("-0001");
        assertThat(String.valueOf(row(otherSchool).get("order_number"))).startsWith("FE-SB_IN-").endsWith("-0003");
        assertThat(owner.sql("SELECT last_value FROM catalog.order_number_counters WHERE item_code = 'FE'").query(Integer.class).single()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.sql("UPDATE catalog.catalog_orders SET order_number = 'FE-X' WHERE id = :id")
                .param("id", first).update()).hasMessageContaining("immutable");
        // A cancelled order keeps its number.
        moveTo(second, "CANCELLED");
        assertThat(String.valueOf(row(second).get("order_number"))).endsWith("-0002");
    }

    /** Design §5.1: twenty placements at once take twenty distinct numbers, with no gap and no duplicate. */
    @Test
    void twentyParallelPlacementsTakeDistinctNumbers() throws Exception {
        List<String> drafts = new ArrayList<>();
        for (int i = 0; i < 20; i++) drafts.add(draft("FLIERS"));
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> placements = new ArrayList<>();
            for (String id : drafts) {
                placements.add(pool.submit((Callable<String>) () -> {
                    TenantContext.set(context(SCHOOL_ADMIN));
                    try {
                        start.await();
                        return orders.placeOrder(id, 7L).orderNumber();
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.countDown();
            Set<String> numbers = new HashSet<>();
            for (Future<String> placement : placements) numbers.add(placement.get(60, TimeUnit.SECONDS));
            assertThat(numbers).hasSize(20).allMatch(number -> number.matches("FE-SA_IN-\\d\\d-\\d\\d-\\d\\d-\\d{4}"));
            List<String> counters = numbers.stream().map(number -> number.substring(number.length() - 4)).sorted().toList();
            List<String> expected = new ArrayList<>();
            for (int n = 1; n <= 20; n++) expected.add("%04d".formatted(n));
            assertThat(counters).isEqualTo(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Superadmin's status override on an order of the older form (PATCH /orders/{id}/status, the All orders "Update status"
     * select) can take a draft out of DRAFT, which places it. It records the placement (when, and by superadmin) and mints the
     * number dated by it in that transaction, as placing does, and the event carries it, so catalog V38's commit-time trigger
     * has nothing to repair. A category without an order code gets no number; an order already numbered keeps its number and
     * takes no new one. An override that does not take a draft out of DRAFT numbers nothing: an order that left DRAFT earlier
     * without a number (here, placed before its category had an order code) keeps none, and takes no running number.
     */
    @Test
    void aLegacyStatusOverrideOutOfDraftMintsTheNumberInThatTransaction() {
        String tie = legacyDraft("TIES");
        String uniforms = legacyDraft("UNIFORMS");
        String notebook = legacyDraft("NOTEBOOKS");
        String placedBeforeItsCode = legacyPlacedWithoutNumber("TIES");
        owner.sql("DELETE FROM tenant_school.outbox_events").update();
        String placedNumber;
        TenantContext.set(context(SUPERADMIN));
        try {
            var overridden = orders.updateOrderStatus(tie, "in_progress");
            assertThat(overridden.status()).isEqualTo("IN_PROGRESS");
            assertThat(overridden.orderNumber()).as("returned with the number").matches("TIE-SA_IN-\\d\\d-\\d\\d-\\d\\d-0001");
            assertThat(orders.updateOrderStatus(uniforms, "IN_PROGRESS").orderNumber()).as("a category without an order code").isNull();
            assertThat(orders.updateOrderStatus(placedBeforeItsCode, "DELIVERED").orderNumber())
                    .as("an order that left DRAFT earlier is not numbered by a later override").isNull();
            placedNumber = orders.placeOrder(notebook, 7L).orderNumber();
            assertThat(placedNumber).matches("NB-SA_IN-\\d\\d-\\d\\d-\\d\\d-0001");
            assertThat(orders.updateOrderStatus(notebook, "APPROVED").orderNumber()).as("an order already numbered").isEqualTo(placedNumber);
        } finally {
            TenantContext.clear();
        }
        String tieNumber = String.valueOf(row(tie).get("order_number"));
        assertThat(row(tie).get("placed_at")).as("the override recorded the placement").isNotNull();
        assertThat(row(tie).get("placed_by")).as("by superadmin").isEqualTo(99L);
        assertThat(tieNumber).as("dated by the recorded placement").isEqualTo("TIE-SA_IN-" + placementDay(tie) + "-0001");
        assertThat(row(uniforms).get("order_number")).isNull();
        assertThat(row(uniforms).get("placed_at")).as("placed, though it takes no number").isNotNull();
        assertThat(row(placedBeforeItsCode).get("order_number")).isNull();
        assertThat(row(placedBeforeItsCode).get("placed_by")).as("its placement is left as it was").isEqualTo(7L);
        assertThat(row(notebook).get("order_number")).isEqualTo(placedNumber);
        for (String id : List.of(tie, uniforms, placedBeforeItsCode, notebook)) {
            assertThat(queued(id)).as(id + ": numbered before commit, or not leaving DRAFT, so V38's trigger queues no repair").isFalse();
        }
        assertThat(owner.sql("SELECT item_code || '=' || last_value FROM catalog.order_number_counters ORDER BY item_code")
                .query(String.class).list()).as("one number each, none taken twice").containsExactly("NB=1", "TIE=1");
        assertThat(owner.sql("""
                SELECT aggregate_id || ' ' || coalesce(payload ->> 'orderNumber', '-') || ' ' || (payload ->> 'status') FROM tenant_school.outbox_events
                WHERE event_type = 'catalog-order.upserted.v1' ORDER BY id
                """).query(String.class).list()).as("reporting is told each number with the status that carried it")
                .containsExactly(tie + " " + tieNumber + " IN_PROGRESS", uniforms + " - IN_PROGRESS", placedBeforeItsCode + " - DELIVERED",
                        notebook + " " + placedNumber + " DESIGN_APPROVAL", notebook + " " + placedNumber + " APPROVED");
    }

    /**
     * A category given an order code later (superadmin's Catalog manager, PATCH /api/v1/supply/product-catalog/categories/{code})
     * numbers only the orders placed afterwards (design D8, D11; ProductCatalogRepository: "Changing the code affects only
     * numbers minted afterwards"). Its orders placed before keep no number, and neither a status override nor anything else
     * numbers them later, so an item's running number never jumps back to an old placement.
     */
    @Test
    void aCategoryGivenAnOrderCodeNumbersOnlyItsLaterPlacements() {
        String before = placedLegacy("UNIFORMS");
        try {
            TenantContext.set(context(SUPERADMIN));
            context.getBean(ProductCatalogRepository.class).update("categories", "UNIFORMS", Map.of("orderCode", "UNF"));
            assertThat(orders.updateOrderStatus(before, "DELIVERED").orderNumber()).as("placed before the code").isNull();
            TenantContext.clear();
            String after = placedLegacy("UNIFORMS");

            assertThat(row(before).get("order_number")).isNull();
            assertThat(queued(before)).isFalse();
            assertThat(String.valueOf(row(after).get("order_number"))).as("the first number of the new code")
                    .isEqualTo("UNF-SA_IN-" + placementDay(after) + "-0001");
        } finally {
            TenantContext.clear();
            owner.sql("UPDATE catalog.product_categories SET order_code = NULL WHERE code = 'UNIFORMS'").update();
        }
    }

    // ---- the creation queue -------------------------------------------------------------------------------------------------

    /**
     * The rollout window (catalog V38): school-core's migration job runs while the previous revision still serves, and in
     * prod beside this one through the canary. That revision places orders without a number, and places a flier on approve,
     * quote and deliver. Its placement is queued for repair when it commits; the next drain numbers the order by its
     * placement, moves an open flier onto its template as V38 moved the open ones, tells reporting, and creates the workflow.
     * An order on no workflow only gets its number. A notebook placed tracker-managed without a number (as the notebook
     * tracker's revision placed it) is created here, with its number, under its own template. Catalog V40 dropped the old
     * queue that revision also wrote, so its forwarding is covered at catalog V39 by ItemOrderNumberMigrationTest.
     */
    @Test
    void anOrderThePreviousRevisionPlacedIsNumberedMovedOntoItsWorkflowAndCreated() {
        String flier = draft("FLIERS");
        String tie = draft("TIES");
        String notebook = draft("NOTEBOOKS");
        // The previous revision's placements, as its SQL wrote them (CatalogOrderFormService.transition at origin/dev).
        owner.sql("""
                UPDATE catalog.catalog_orders SET status = 'PROCESSING', design_status = 'NOT_REQUIRED', superadmin_approval_status = 'PENDING',
                    placed_at = now(), placed_by = 7, updated_by = '7', version = version + 1 WHERE id IN (:ids)
                """).param("ids", List.of(flier, tie)).update();
        owner.sql("""
                UPDATE catalog.catalog_orders SET status = 'ORDER_DETAILS', placed_at = now(), placed_by = 7, version = version + 1,
                    pricing_status = 'NOT_APPLICABLE', attributes = attributes || jsonb_build_object('trackerManaged', true, 'trackerPending', true)
                WHERE id = :id
                """).param("id", notebook).update();
        assertThat(owner.sql("SELECT to_regclass('catalog.notebook_tracker_creation_queue') IS NULL").query(Boolean.class).single())
                .as("catalog V40 dropped the old queue").isTrue();
        assertThat(owner.sql("SELECT order_id FROM catalog.order_workflow_creation_queue WHERE repair ORDER BY order_id").query(String.class).list())
                .as("V38's placement trigger, which V40 keeps, queued each for repair").containsExactlyInAnyOrder(flier, tie, notebook);
        owner.sql("DELETE FROM tenant_school.outbox_events").update();

        assertThat(queue.drain(10)).as("the flier and the notebook; the tie runs on no workflow").isEqualTo(2);

        assertThat(String.valueOf(row(flier).get("order_number"))).isEqualTo("FE-SA_IN-" + placementDay(flier) + "-0001");
        assertThat(String.valueOf(row(tie).get("order_number"))).isEqualTo("TIE-SA_IN-" + placementDay(tie) + "-0001");
        assertThat(String.valueOf(row(notebook).get("order_number"))).isEqualTo("NB-SA_IN-" + placementDay(notebook) + "-0001");
        assertThat(attribute(flier, "trackerManaged")).isEqualTo("true");
        assertThat(attribute(flier, "workflowDefinition")).isEqualTo("FLIER_ORDER_WORKFLOW");
        assertThat(row(flier).get("status")).as("left where the previous flow put it until the engine pushes").isEqualTo("PROCESSING");
        assertThat(attribute(tie, "trackerManaged")).as("ties have no template").isNull();
        assertThat(CREATED).extracting(TrackerGateway.NewTracker::orderId, TrackerGateway.NewTracker::definitionId, TrackerGateway.NewTracker::orderNumber)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(flier, "FLIER_ORDER_WORKFLOW", row(flier).get("order_number")),
                        org.assertj.core.groups.Tuple.tuple(notebook, "NOTEBOOK_ORDER_TRACKER", row(notebook).get("order_number")));
        for (String id : List.of(flier, tie, notebook)) assertThat(queued(id)).as(id).isFalse();
        assertThat(owner.sql("""
                SELECT aggregate_id || ' ' || (payload ->> 'orderNumber') FROM tenant_school.outbox_events
                WHERE event_type = 'catalog-order.upserted.v1' ORDER BY aggregate_id
                """).query(String.class).list()).as("reporting is told each number")
                .containsExactlyInAnyOrder(flier + " " + row(flier).get("order_number"), tie + " " + row(tie).get("order_number"),
                        notebook + " " + row(notebook).get("order_number"));
    }

    /** A repaired order, once numbered and moved, is not changed again: a second repair finds nothing to do. */
    @Test
    void aRepairIsDoneOnce() {
        String flier = draft("FLIERS");
        owner.sql("UPDATE catalog.catalog_orders SET status = 'PROCESSING', placed_at = now(), placed_by = 7 WHERE id = :id").param("id", flier).update();
        assertThat(queue.drain(10)).isEqualTo(1);
        Object number = row(flier).get("order_number");
        owner.sql("INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id, repair) VALUES (:id, 1, true)").param("id", flier).update();
        owner.sql("DELETE FROM tenant_school.outbox_events").update();
        assertThat(queue.drain(10)).isEqualTo(1);
        assertThat(row(flier).get("order_number")).isEqualTo(number);
        assertThat(owner.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single()).as("nothing changed, nothing told").isZero();
        assertThat(owner.sql("SELECT last_value FROM catalog.order_number_counters WHERE item_code = 'FE'").query(Integer.class).single()).isEqualTo(1);
    }

    /** A category label may be 80 characters, but the engine takes a noun of at most 40: a noun taken from the label is cut. */
    @Test
    void aNounTakenFromALongLabelFitsTheEngine() {
        owner.sql("UPDATE catalog.product_categories SET item_noun = NULL, label = :label WHERE code = 'FLIERS'")
                .param("label", "Customised Annual Day Invitation Cards Set For Every Class").update();
        try {
            placed("FLIERS");
            assertThat(queue.drain(10)).isEqualTo(1);
            assertThat(CREATED.getFirst().itemNoun()).hasSizeLessThanOrEqualTo(40).isEqualTo("customised annual day invitation cards s");
        } finally {
            owner.sql("UPDATE catalog.product_categories SET item_noun = 'flier', label = 'Fliers' WHERE code = 'FLIERS'").update();
        }
    }

    @Test
    void theCreationCallCarriesTheTemplateItemNumberAndQuantitiesAndResolvesTheOperatorForTheItem() {
        String id = placed("FLIERS");
        assertThat(queue.drain(10)).isEqualTo(1);
        var created = CREATED.getFirst();
        assertThat(created.orderId()).isEqualTo(id);
        assertThat(created.definitionId()).isEqualTo("FLIER_ORDER_WORKFLOW");
        assertThat(created.categoryCode()).isEqualTo("FLIERS");
        assertThat(created.itemCode()).isEqualTo("FE");
        assertThat(created.itemNoun()).isEqualTo("flier");
        assertThat(created.orderNumber()).isEqualTo(row(id).get("order_number"));
        assertThat(created.customised()).as("every item but a wholesale notebook takes the design steps").isTrue();
        assertThat(created.orderedCount()).isEqualTo(3000);
        assertThat(created.importedProgress()).isEmpty();
        assertThat(RESOLVED_CATEGORIES).containsExactly("FLIERS");
        assertThat(attribute(id, "trackerPending")).isEqualTo("false");
        assertThat(queued(id)).isFalse();
    }

    /**
     * Design D13: catalog V38 queues every open notebook order whose workflow already exists, so operations' idempotent create
     * fills in its number. Such an order was placed before templates were recorded on the order, so it falls back to its
     * category's template.
     */
    @Test
    void anExistingNotebookWorkflowQueuedAgainIsSentItsTemplateItemAndNumber() {
        String id = placed("NOTEBOOKS");
        assertThat(queue.drain(10)).isEqualTo(1);
        CREATED.clear();
        RESOLVED_CATEGORIES.clear();
        owner.sql("UPDATE catalog.catalog_orders SET attributes = attributes - 'workflowDefinition' WHERE id = :id").param("id", id).update();
        owner.sql("INSERT INTO catalog.order_workflow_creation_queue (order_id, school_id) VALUES (:id, 1)").param("id", id).update();

        assertThat(queue.drain(10)).isEqualTo(1);
        var created = CREATED.getFirst();
        assertThat(created.orderId()).isEqualTo(id);
        assertThat(created.definitionId()).isEqualTo("NOTEBOOK_ORDER_TRACKER");
        assertThat(created.categoryCode()).isEqualTo("NOTEBOOKS");
        assertThat(created.itemCode()).isEqualTo("NB");
        assertThat(created.itemNoun()).isEqualTo("notebook");
        assertThat(created.orderNumber()).isNotNull().isEqualTo(row(id).get("order_number"));
        assertThat(created.customised()).as("a wholesale notebook skips the design steps (D7)").isFalse();
        assertThat(created.importedProgress()).as("a notebook never had a retired product workflow").isEmpty();
        assertThat(RESOLVED_CATEGORIES).containsExactly("NOTEBOOKS");
        assertThat(queued(id)).isFalse();
    }

    /** Design §3.5, D25: the workflow header covers every item; title cards are a notebook's alone. */
    @SuppressWarnings("unchecked")
    @Test
    void theWorkflowSummaryCoversAFlierOrderWithItsQuantitiesAndNoTitleCards() throws Exception {
        String id = placed("FLIERS");
        String number = String.valueOf(row(id).get("order_number"));
        var summary = read(mvc.perform(as(get("/api/v1/catalog/orders/" + id + "/tracker-summary"), SUPERADMIN)).andExpect(status().isOk()));
        assertThat(summary).containsEntry("orderId", id).containsEntry("orderNumber", number).containsEntry("category", "FLIERS")
                .containsEntry("itemCode", "FE").containsEntry("status", "ORDER_DETAILS").containsEntry("customised", true)
                .containsEntry("schoolName", "School A").containsEntry("orderedCount", 3000).containsEntry("trackerPending", true);
        assertThat(summary.get("titleCards")).as("only a notebook order has title cards").isNull();
        assertThat(String.valueOf(summary.get("itemLabel"))).isNotBlank();
        var quantities = (Map<String, Object>) summary.get("quantities");
        assertThat(quantities).containsEntry("total", 3000);
        var lines = (List<Map<String, Object>>) quantities.get("lines");
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).containsEntry("lineNo", 1).containsEntry("quantity", 3000);
            assertThat(String.valueOf(line.get("label"))).as("the line's main selection, its size").contains("A4");
            assertThat(String.valueOf(line.get("details"))).as("the typed GSM is a detail").contains("130");
        });

        mvc.perform(as(get("/api/v1/catalog/orders/" + id + "/tracker-summary"), FLEX_OPERATOR)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/catalog/orders/" + id + "/tracker-summary"), SCHOOL_ADMIN)).andExpect(status().isForbidden());
        String ties = placed("TIES");
        mvc.perform(as(get("/api/v1/catalog/orders/" + ties + "/tracker-summary"), SUPERADMIN)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("order workflow")));
    }

    /** Design D5: a nine-step code is shown as the status, and the step report is kept for lists, newest version only. */
    @SuppressWarnings("unchecked")
    @Test
    void aFlierStatusPushShowsTheStepAndStoresItsPositionForLists() throws Exception {
        String id = placed("FLIERS");
        var forms = context.getBean(CatalogOrderFormService.class);
        forms.applyTrackerStatus(id, "pre_print_quality_check", 5L,
                new CatalogOrderFormService.WorkflowStepReport("FLIER_ORDER_WORKFLOW", 6, " Pre-Print Quality Check ", 9, 5));
        assertThat(row(id).get("status")).isEqualTo("PRE_PRINT_QUALITY_CHECK");
        assertThat(attribute(id, "trackerPending")).as("a push proves the workflow exists").isEqualTo("false");
        var step = JSON.readValue(attribute(id, "workflowStep"), Map.class);
        assertThat(step).containsEntry("order", 6).containsEntry("title", "Pre-Print Quality Check")
                .containsEntry("total", 9).containsEntry("completed", 5);

        // A late push of an older version changes nothing, step included.
        forms.applyTrackerStatus(id, "CDR_CONVERSION", 4L,
                new CatalogOrderFormService.WorkflowStepReport("FLIER_ORDER_WORKFLOW", 3, "CDR Conversion", 9, 2));
        assertThat(row(id).get("status")).isEqualTo("PRE_PRINT_QUALITY_CHECK");
        assertThat(JSON.readValue(attribute(id, "workflowStep"), Map.class)).containsEntry("order", 6);

        // A push without a step report keeps the last one; the order list carries it as an object.
        forms.applyTrackerStatus(id, "COMPLETED", 6L);
        assertThat(row(id).get("status")).isEqualTo("COMPLETED");
        TenantContext.set(context(SUPERADMIN));
        Map<String, Object> page = orders.ordersPage(null, null, id, 0, 20);
        var listed = ((List<CatalogReadRepository.CatalogOrderRow>) page.get("content")).getFirst();
        assertThat(listed.orderNumber()).isEqualTo(row(id).get("order_number"));
        assertThat(listed.itemCode()).isEqualTo("FE");
        assertThat(JSON.readValue(listed.workflowStep(), Map.class)).containsEntry("order", 6).containsEntry("total", 9);
        TenantContext.clear();

        assertThatThrownBy(() -> forms.applyTrackerStatus(id, "AWAITING_APPROVAL", 7L)).as("the approval flow's statuses are never a step")
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> assertThat(refused.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void anOrderMovedOffTheRetiredWorkflowCarriesItsProgressMappedOntoTheEngine() {
        String id = placed("CERTIFICATES");
        retiredWorkflow(id, 8, "Proof shared with the press", "Waiting for counts");
        assertThat(queue.drain(10)).isEqualTo(1);
        var imported = CREATED.getFirst().importedProgress();
        assertThat(imported).extracting(TrackerGateway.ImportedStep::stepCode).containsExactly("ORDER_DETAILS", "DESIGNING_ACTIVITY",
                "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING", "PRE_PRINT_QUALITY_CHECK", "PRODUCTION_FOLLOW_UP", "FINAL_APPROVAL");
        assertThat(imported).extracting(TrackerGateway.ImportedStep::sourceStageCode).contains("DESIGN_ACTIVITY", "FINAL_VERIFICATION");
        assertThat(imported.subList(0, 7)).allMatch(step -> "COMPLETED".equals(step.status()) && step.completedAt() != null
                && Long.valueOf(99).equals(step.completedBy()));
        var active = imported.getLast();
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.details()).isEqualTo("Proof shared with the press");
        assertThat(active.note()).isEqualTo("Waiting for counts");
        assertThat(active.completedAt()).isNull();
    }

    @Test
    void aStageInProgressWithNothingWrittenIsNotImported() {
        String id = placed("BELTS");
        retiredWorkflow(id, 2, null, null);
        queue.drain(10);
        assertThat(CREATED.getFirst().importedProgress()).extracting(TrackerGateway.ImportedStep::stepCode).containsExactly("ORDER_DETAILS");
    }

    // ---- the retired product workflow -------------------------------------------------------------------------------------

    @Test
    void aRetiredWorkflowStaysReadableAndEveryWriteAnswersWorkflowMoved() throws Exception {
        String id = placed("BELTS");
        retiredWorkflow(id, 3, "Artwork in CorelDRAW", "Internal: margin is thin");
        var record = read(view(id, SUPERADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id)).andExpect(jsonPath("$.category").value("BELTS"))
                .andExpect(jsonPath("$.currentStage").value("CDR_CONVERSION"))
                .andExpect(jsonPath("$.canEdit").value(false)).andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.stages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.stages[2].data.details").value("Artwork in CorelDRAW"))
                .andExpect(jsonPath("$.stages[2].note").value("Internal: margin is thin")));
        assertThat((List<?>) record.get("stages")).hasSize(9);

        save(id, "CDR_CONVERSION", SUPERADMIN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_MOVED"));
        complete(id, "CDR_CONVERSION", SUPERADMIN).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_MOVED"));
        complete(id, "CDR_CONVERSION", OPERATOR).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WORKFLOW_MOVED"));
        assertThat(read(view(id, SUPERADMIN)).get("version")).isEqualTo(record.get("version"));

        view(id, SCHOOL_ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.stages[2].data").isEmpty())
                .andExpect(jsonPath("$.stages[2].note").value(nullValue()));
        view(id, OTHER_SCHOOL_ADMIN).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Order not found"));
        // The order is on its workflow now, so an operator scoped to its item reads it; one scoped to another item does not.
        view(id, BELTS_OPERATOR).andExpect(status().isOk());
        view(id, FLEX_OPERATOR).andExpect(status().isNotFound());
    }

    @Test
    void anOrderThatNeverHadTheRetiredWorkflowHasNoRecordAndReadingCreatesNone() throws Exception {
        String id = placed("FLEX");
        view(id, SUPERADMIN).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("This order has no production workflow record"));
        String draft = draft("FLEX");
        view(draft, SUPERADMIN).andExpect(status().isNotFound());
        assertThat(workflows()).isZero();
    }

    /** Reading the record writes nothing and reads each table once. */
    @Test
    void readingTheRetiredRecordWritesNothing() throws Exception {
        String id = placed("BELTS");
        retiredWorkflow(id, 1, null, null);
        STATEMENTS.clear();
        view(id, SCHOOL_ADMIN).andExpect(status().isOk());
        List<String> issued = List.copyOf(STATEMENTS);
        assertThat(issued).noneMatch(sql -> sql.stripLeading().toUpperCase().startsWith("INSERT")
                || sql.toUpperCase().contains(" INSERT INTO") || sql.stripLeading().toUpperCase().startsWith("UPDATE"));
        assertThat(issued.stream().filter(sql -> sql.contains("catalog.product_order_workflows"))).hasSize(1);
        assertThat(issued.stream().filter(sql -> sql.contains("catalog.product_order_workflow_steps"))).hasSize(1);
    }

    @Test
    void aSchoolWithoutTheOrdersModuleIsRefused() throws Exception {
        TenantContext.set(context(SUPERADMIN));
        String id = orders.createOrder(Map.of("schoolId", 3, "category", "BELTS", "orderData", ProductOrderFixtures.orderData("BELTS"))).id();
        orders.placeOrder(id, 99L);
        TenantContext.clear();
        retiredWorkflow(id, 1, null, null);
        view(id, SUPERADMIN).andExpect(status().isOk());
        view(id, DISABLED_SCHOOL_ADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("ORDERS module is not enabled for this school"));
        view(id, new Actor("OPERATIONS", 13, null, "3", null, "order:read,order:update")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("ORDERS module is not enabled for this school"));
    }

    // ---- quote, search, lookup ----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void aWorkflowOrderOfAnotherItemMayStillBeQuotedUntilItsWorkflowCompletes() throws Exception {
        String id = placed("FLIERS");
        var detail = read(form(id, SUPERADMIN));
        long line = ((Number) ((List<Map<String, Object>>) detail.get("lines")).getFirst().get("id")).longValue();
        quote(id, line, ((Number) detail.get("version")).longValue()).andExpect(status().isOk())
                .andExpect(jsonPath("$.pricingStatus").value("QUOTED")).andExpect(jsonPath("$.order.status").value("ORDER_DETAILS"));

        moveTo(id, "COMPLETED");
        var finished = read(form(id, SUPERADMIN));
        quote(id, line, ((Number) finished.get("version")).longValue()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This action is unavailable while the order is COMPLETED"));

        String notebook = placed("NOTEBOOKS");
        var nb = read(form(notebook, SUPERADMIN));
        long nbLine = ((Number) ((List<Map<String, Object>>) nb.get("lines")).getFirst().get("id")).longValue();
        quote(notebook, nbLine, ((Number) nb.get("version")).longValue()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Notebook orders on their order workflow are not quoted"));
    }

    @Test
    void ordersAreSearchedByNumberIdAndSchoolName() {
        String flier = placed("FLIERS");
        String flex = placed("FLEX");
        String other = placedAt(2, "FLIERS");
        String number = String.valueOf(row(flier).get("order_number"));
        TenantContext.set(context(SUPERADMIN));
        assertThat(ids(orders.ordersPage(null, null, number.toLowerCase(), 0, 20))).containsExactly(flier);
        assertThat(ids(orders.ordersPage(null, null, "fx-", 0, 20))).containsExactly(flex);
        assertThat(ids(orders.ordersPage(null, null, flex.substring(3), 0, 20))).contains(flex);
        assertThat(ids(orders.ordersPage(null, null, "school b", 0, 20))).containsExactly(other);
        assertThat(ids(orders.ordersPage(null, null, "_", 0, 20))).as("a wildcard matches itself").containsExactlyInAnyOrder(flier, flex, other);
        assertThat(ids(orders.ordersPage(null, null, "%", 0, 20))).as("a wildcard matches itself").isEmpty();
        assertThat(orders.ordersPage(null, null, "SB_IN", 0, 20).get("totalElements")).isEqualTo(1L);
        TenantContext.set(context(SCHOOL_ADMIN));
        assertThat(ids(orders.ordersPage(1L, null, "FE-", 0, 20))).as("a school searches its own orders").containsExactly(flier);
    }

    @Test
    void aPastedNumberResolvesToTheOrderOnlyForThoseWhoMayReadIt() throws Exception {
        String id = placed("FLEX");
        String number = String.valueOf(row(id).get("order_number"));
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/" + number.toLowerCase()), SCHOOL_ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.orderNumber").value(number));
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/" + number), SUPERADMIN)).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/" + number), OTHER_SCHOOL_ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/" + number), FLEX_OPERATOR)).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/" + number), BELTS_OPERATOR)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/FX-NOPE-26-01-01-0001"), SUPERADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/catalog/orders/by-number/FX.1"), SUPERADMIN)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("order number")));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    private static String placed(String category) {
        return placedAt(1, category);
    }

    /** A draft of {@code category} saved from its seeded form by the school's admin, then placed by them. */
    private static String placedAt(long school, String category) {
        Actor admin = school == 1 ? SCHOOL_ADMIN : OTHER_SCHOOL_ADMIN;
        String id = draftAt(admin, category);
        TenantContext.set(context(admin));
        try {
            assertThat(orders.placeOrder(id, admin.userId()).status()).as(category).isNotEqualTo("DRAFT");
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private static String draft(String category) {
        return draftAt(SCHOOL_ADMIN, category);
    }

    private static String draftAt(Actor admin, String category) {
        TenantContext.set(context(admin));
        try {
            return orders.createOrder(Map.of("category", category, "orderData", ProductOrderFixtures.orderData(category))).id();
        } finally {
            TenantContext.clear();
        }
    }

    /** A draft on the older, unstructured order form (form_version 1), as orders created before the order forms are. */
    private static String legacyDraft(String category) {
        return owner.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                    design_status, superadmin_approval_status, created_at, form_version, version)
                VALUES ('CK-' || nextval('catalog.seq_catalog_order_id'), 1, :category, '{}', 0, 0, 0, 'DRAFT', 'NOT_REQUIRED',
                    'NOT_REQUIRED', now(), 1, 0)
                RETURNING id
                """).param("category", category).query(String.class).single();
    }

    /**
     * An order on the older form placed a month ago by the school's admin without a number, as one placed before its category
     * had an order code is.
     */
    private static String legacyPlacedWithoutNumber(String category) {
        return owner.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                    design_status, superadmin_approval_status, placed_at, placed_by, created_at, form_version, version)
                VALUES ('CK-' || nextval('catalog.seq_catalog_order_id'), 1, :category, '{}', 0, 0, 0, 'PROCESSING', 'NOT_REQUIRED',
                    'NOT_REQUIRED', now() - interval '30 days', 7, now() - interval '30 days', 1, 1)
                RETURNING id
                """).param("category", category).query(String.class).single();
    }

    /** An order of a category with no structured form, created and placed through the legacy order flow. */
    private static String placedLegacy(String category) {
        TenantContext.set(context(SCHOOL_ADMIN));
        try {
            String id = orders.createOrder(Map.of("schoolId", 1, "category", category, "totalAmount", 900, "status", "DRAFT")).id();
            assertThat(orders.placeOrder(id, 7L).status()).as(category).isNotEqualTo("DRAFT");
            return id;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * A PR #380 workflow as it stood when it was retired: stages before {@code current} completed by superadmin, the current
     * one active with what was written there, the rest locked.
     */
    private static void retiredWorkflow(String id, int current, String details, String note) {
        String category = String.valueOf(row(id).get("category"));
        long school = ((Number) row(id).get("school_id")).longValue();
        owner.sql("""
                INSERT INTO catalog.product_order_workflows (order_id, school_id, category_code, current_stage, status, version)
                VALUES (:id, :school, :category, :stage, 'ACTIVE', 7)
                """).param("id", id).param("school", school).param("category", category).param("stage", V36_STAGES.get(current - 1)).update();
        for (int n = 1; n <= V36_STAGES.size(); n++) {
            String status = n < current ? "COMPLETED" : n == current ? "ACTIVE" : "LOCKED";
            Map<String, Object> data = n == current && details != null ? Map.of("details", details) : Map.of();
            owner.sql("""
                    INSERT INTO catalog.product_order_workflow_steps (order_id, school_id, stage_code, stage_order, title, status,
                        data, stage_note, started_at, completed_at, updated_by)
                    VALUES (:id, :school, :code, :n, :title, :status, CAST(:data AS jsonb), :note,
                        CASE WHEN :status <> 'LOCKED' THEN now() END, CASE WHEN :status = 'COMPLETED' THEN now() END,
                        CASE WHEN :status = 'COMPLETED' THEN 99 END)
                    """).param("id", id).param("school", school).param("code", V36_STAGES.get(n - 1)).param("n", n).param("title", "Stage " + n)
                    .param("status", status).param("data", JSON.writeValueAsString(data)).param("note", n == current ? note : null).update();
        }
    }

    private static Map<String, Object> row(String id) {
        return owner.sql("SELECT * FROM catalog.catalog_orders WHERE id = :id").param("id", id).query().singleRow();
    }

    private static String attribute(String id, String key) {
        return owner.sql("SELECT attributes ->> :key FROM catalog.catalog_orders WHERE id = :id").param("key", key).param("id", id)
                .query(String.class).optional().orElse(null);
    }

    /** The order's placement date in the school's time zone, as an order number writes it. */
    private static String placementDay(String id) {
        return owner.sql("""
                SELECT to_char(co.placed_at AT TIME ZONE s.time_zone, 'YY-MM-DD') FROM catalog.catalog_orders co
                JOIN tenant_school.schools s ON s.id = co.school_id WHERE co.id = :id
                """).param("id", id).query(String.class).single();
    }

    private static boolean queued(String id) {
        return owner.sql("SELECT count(*) FROM catalog.order_workflow_creation_queue WHERE order_id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }

    private static void moveTo(String id, String status) {
        owner.sql("UPDATE catalog.catalog_orders SET status = :status WHERE id = :id").param("status", status).param("id", id).update();
    }

    private static long workflows() {
        return owner.sql("SELECT count(*) FROM catalog.product_order_workflows").query(Long.class).single();
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<String, Object> page) {
        return ((List<CatalogReadRepository.CatalogOrderRow>) page.get("content")).stream().map(CatalogReadRepository.CatalogOrderRow::id).toList();
    }

    private static ResultActions view(String id, Actor actor) throws Exception {
        return mvc.perform(as(get("/api/v1/catalog/orders/" + id + "/production-workflow"), actor));
    }

    private static ResultActions form(String id, Actor actor) throws Exception {
        return mvc.perform(as(get("/api/v1/supply/orders/" + id + "/form"), actor)).andExpect(status().isOk());
    }

    private static ResultActions quote(String id, long line, long version) throws Exception {
        var body = Map.of("version", version, "gstPaise", 1800, "lines", List.of(Map.of("id", line, "unitPricePaise", 350)));
        return mvc.perform(as(put("/api/v1/supply/orders/" + id + "/quote"), SUPERADMIN)
                .contentType("application/json").content(JSON.writeValueAsString(body)));
    }

    private static ResultActions save(String id, String stage, Actor actor) throws Exception {
        return mvc.perform(as(put("/api/v1/catalog/orders/" + id + "/production-workflow/stages/" + stage), actor)
                .contentType("application/json").content("{\"data\":{\"details\":\"late\"},\"expectedVersion\":7}"));
    }

    private static ResultActions complete(String id, String stage, Actor actor) throws Exception {
        return mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/production-workflow/stages/" + stage + "/complete"), actor)
                .contentType("application/json").content("{\"expectedVersion\":7}"));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Actor actor) {
        request.header("X-Catalog-Service-Token", TOKEN).header("X-Authenticated-Role", actor.role())
                .header("X-Authenticated-User-Id", String.valueOf(actor.userId()));
        if (actor.schoolId() != null) request.header("X-Authenticated-School-Id", String.valueOf(actor.schoolId()));
        if (actor.operatorSchools() != null) request.header("X-Authenticated-Operator-Schools", actor.operatorSchools());
        if (actor.operatorScopes() != null) request.header("X-Authenticated-Operator-Scopes", actor.operatorScopes());
        if (actor.permissions() != null) request.header("X-Authenticated-Permissions", actor.permissions());
        return request;
    }

    private static TenantContext context(Actor actor) {
        return new TenantContext(actor.userId(), actor.role().toLowerCase() + "@example.test", actor.role(), actor.schoolId(), null,
                actor.operatorSchools() == null ? Set.of() : Set.of(Long.parseLong(actor.operatorSchools())),
                actor.permissions() == null ? Set.of() : Set.of(actor.permissions().split(",")),
                actor.operatorScopes() == null ? Set.of() : Set.of(actor.operatorScopes().split(",")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(ResultActions result) throws Exception {
        return JSON.readValue(result.andReturn().getResponse().getContentAsString(), Map.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalTestConfiguration {}

    /** Records every SQL statement the runtime connections are given, beneath the tenant data source. */
    static final class RecordingDataSource extends DelegatingDataSource {
        RecordingDataSource(DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws SQLException {
            return record(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return record(super.getConnection(username, password));
        }

        private static Connection record(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement") && arguments != null && arguments[0] instanceof String sql) {
                            STATEMENTS.add(sql);
                        }
                        Object result = invoke(connection, method, arguments);
                        if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                    (inner, call, values) -> {
                                        if (call.getName().startsWith("execute") && values != null && values.length > 0
                                                && values[0] instanceof String sql) {
                                            STATEMENTS.add(sql);
                                        }
                                        return invoke(statement, call, values);
                                    });
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
