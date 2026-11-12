package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormController;
import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormExceptionHandler;
import com.custoking.ims.schoolcoreservice.api.CatalogProductFormExceptionHandler;
import com.custoking.ims.schoolcoreservice.api.ProductOrderWorkflowController;
import com.custoking.ims.schoolcoreservice.api.ValidationExceptionHandler;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The product production workflow end to end: its controller, the order form detail, the shared refusal handler and the
 * transactional services, against the runtime role with row-level security forced, as production runs them. Order
 * fixtures are placed through the real order flow; the owner connection only moves an order into a status the flow cannot
 * reach in a test (cancelled, delivered and the like) or into a category with no workflow.
 */
class ProductOrderWorkflowIntegrationTest {
    private static final String TOKEN = "test-token";
    private static final List<String> STAGES = List.of("ORDER_DETAILS", "DESIGN_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL",
            "PRODUCTION_PLANNING", "PRE_PRINT_QUALITY_CHECK", "PRODUCTION_FOLLOW_UP", "FINAL_VERIFICATION", "DELIVERY_INVOICING");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    static AnnotationConfigApplicationContext context;
    static CatalogReadRepository orders;
    static MockMvc mvc;
    @TempDir static Path assets;

    record Actor(String role, long userId, Long schoolId, String operatorSchools, String operatorScopes, String permissions) { }

    static final Actor SUPERADMIN = new Actor("SUPERADMIN", 99, null, null, null, null);
    static final Actor SCHOOL_ADMIN = new Actor("ADMIN", 7, 1L, null, null, "order:create,order:read,order:update");
    static final Actor OTHER_SCHOOL_ADMIN = new Actor("ADMIN", 17, 2L, null, null, "order:create,order:read,order:update");
    static final Actor DISABLED_SCHOOL_ADMIN = new Actor("ADMIN", 27, 3L, null, null, "order:create,order:read,order:update");
    static final Actor OPERATOR = new Actor("OPERATIONS", 8, null, "1", null, "order:create,order:read,order:update");
    static final Actor READ_ONLY_OPERATOR = new Actor("OPERATIONS", 9, null, "1", null, "order:read");
    static final Actor OTHER_SCHOOL_OPERATOR = new Actor("OPERATIONS", 10, null, "2", null, "order:read,order:update");
    static final Actor ITEM_ONLY_OPERATOR = new Actor("OPERATIONS", 11, null, null, "1:BELTS", "order:read,order:update");

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

        DataSource runtime = new TenantAwareDataSource(new RecordingDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt")));
        var jdbc = JdbcClient.create(runtime);
        var manager = new DataSourceTransactionManager(runtime);
        var outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionalTestConfiguration.class);
        context.registerBean("transactionManager", org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
        context.registerBean(ModuleEntitlementReadRepository.class, () -> new ModuleEntitlementReadRepository(jdbc));
        context.registerBean(ModuleEntitlementGuard.class, () -> new ModuleEntitlementGuard(context.getBean(ModuleEntitlementReadRepository.class)));
        context.registerBean(ProductCatalogRepository.class, () -> new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, true));
        context.registerBean(CatalogOrderFormService.class, () -> new CatalogOrderFormService(jdbc,
                context.getBean(ProductCatalogRepository.class), new ProductFormRuleEngine(),
                new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, context.getBean(ModuleEntitlementGuard.class)));
        context.registerBean(CatalogReadRepository.class, () -> new CatalogReadRepository(jdbc, outbox));
        context.registerBean(ProductOrderWorkflowService.class, () -> new ProductOrderWorkflowService(jdbc, context.getBean(CatalogOrderFormService.class)));
        context.refresh();
        orders = context.getBean(CatalogReadRepository.class);
        var workflows = context.getBean(ProductOrderWorkflowService.class);
        var formController = new CatalogOrderFormController(context.getBean(CatalogOrderFormService.class), orders, TOKEN);
        formController.configureWorkflows(workflows);
        mvc = MockMvcBuilders.standaloneSetup(new ProductOrderWorkflowController(workflows, TOKEN), formController)
                .setControllerAdvice(new CatalogOrderFormExceptionHandler(), new CatalogProductFormExceptionHandler(), new ValidationExceptionHandler())
                .addFilters(new TenantContextFilter()).build();
    }

    @BeforeEach
    void clear() {
        owner.sql("DELETE FROM catalog.catalog_orders").update();
    }

    @AfterEach void clearContext() { TenantContext.clear(); }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (pg != null) pg.stop();
    }

    @Test
    void superadminRunsNineFixedStagesUnderOptimisticVersions() throws Exception {
        String id = placedBelts();
        var first = read(view(id, SUPERADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id)).andExpect(jsonPath("$.category").value("BELTS"))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.currentStage").value("ORDER_DETAILS"))
                .andExpect(jsonPath("$.canEdit").value(true)).andExpect(jsonPath("$.canComplete").value(true))
                .andExpect(jsonPath("$.stages[0].status").value("ACTIVE")).andExpect(jsonPath("$.stages[1].status").value("LOCKED"))
                .andExpect(jsonPath("$.stages[3].title").value("Head Approval"))
                .andExpect(jsonPath("$.whatsappGroups").doesNotExist()));
        assertThat(codes(first)).isEqualTo(STAGES);
        assertThat(rows(id)).isEqualTo(9);

        var second = read(complete(id, "ORDER_DETAILS", version(first), SUPERADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("DESIGN_ACTIVITY"))
                .andExpect(jsonPath("$.stages[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.stages[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.stages[1].status").value("ACTIVE")));
        complete(id, "ORDER_DETAILS", version(second), SUPERADMIN).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only the active production stage can be completed"));
        // A stale version is refused with the reason in the body, which is what the screen shows.
        save(id, "DESIGN_ACTIVITY", "Artwork prepared", "Ready for review", version(first), SUPERADMIN)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Workflow changed; refresh and try again"));
        var saved = read(save(id, "DESIGN_ACTIVITY", "Artwork prepared", "Ready for review", version(second), SUPERADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[1].data.details").value("Artwork prepared"))
                .andExpect(jsonPath("$.stages[1].note").value("Ready for review")));
        assertThat(version(saved)).isGreaterThan(version(second));

        var current = saved;
        for (String stage : STAGES.subList(1, STAGES.size())) {
            current = read(complete(id, stage, version(current), SUPERADMIN).andExpect(status().isOk()));
        }
        assertThat(current.get("status")).isEqualTo("COMPLETED");
        assertThat(current.get("canEdit")).isEqualTo(false);
        assertThat(current.get("canComplete")).isEqualTo(false);
        // canEdit=false, so a write is a 403 (contract A3), at the current version as much as at a stale one.
        complete(id, "DELIVERY_INVOICING", version(current), SUPERADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Production workflow is already complete"));
        save(id, "DELIVERY_INVOICING", "late", null, version(current), SUPERADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Production workflow is already complete"));
        complete(id, "DELIVERY_INVOICING", version(saved), SUPERADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Production workflow is already complete"));
        assertThat(read(view(id, SUPERADMIN)).get("version")).isEqualTo(current.get("version"));
    }

    @Test
    void theSchoolFollowsProgressWithoutStageDetailsAndCannotRunStages() throws Exception {
        String id = placedBelts();
        var started = read(view(id, SUPERADMIN));
        save(id, "ORDER_DETAILS", "Vendor quote 12,000", "Internal: margin is thin", version(started), SUPERADMIN).andExpect(status().isOk());

        view(id, SCHOOL_ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.canEdit").value(false)).andExpect(jsonPath("$.canComplete").value(false))
                .andExpect(jsonPath("$.stages[0].title").value("Order Details")).andExpect(jsonPath("$.stages[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.stages[0].data").isEmpty()).andExpect(jsonPath("$.stages[0].note").value(nullValue()));
        var internal = read(view(id, OPERATOR).andExpect(jsonPath("$.stages[0].data.details").value("Vendor quote 12,000")));
        save(id, "ORDER_DETAILS", "Changed by the school", null, version(internal), SCHOOL_ADMIN)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Production stages are run by superadmin and operators"));
        complete(id, "ORDER_DETAILS", version(internal), SCHOOL_ADMIN)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Production stages are run by superadmin and operators"));
        view(id, OTHER_SCHOOL_ADMIN).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Order not found"));
        assertThat(read(view(id, SUPERADMIN)).get("version")).isEqualTo(internal.get("version"));
    }

    @Test
    void operatorsRunOrdinaryStagesButOnlySuperadminCompletesHeadApproval() throws Exception {
        String id = placedBelts();
        var current = read(view(id, OPERATOR).andExpect(jsonPath("$.canEdit").value(true)).andExpect(jsonPath("$.canComplete").value(true)));
        for (String stage : List.of("ORDER_DETAILS", "DESIGN_ACTIVITY", "CDR_CONVERSION")) {
            current = read(complete(id, stage, version(current), OPERATOR).andExpect(status().isOk()));
        }
        assertThat(current.get("currentStage")).isEqualTo("HEAD_APPROVAL");
        assertThat(current.get("canEdit")).isEqualTo(true);
        assertThat(current.get("canComplete")).isEqualTo(false);
        current = read(save(id, "HEAD_APPROVAL", "Proof sent for sign-off", null, version(current), OPERATOR).andExpect(status().isOk()));
        complete(id, "HEAD_APPROVAL", version(current), OPERATOR)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Only superadmin can complete Head Approval"));
        view(id, SUPERADMIN).andExpect(jsonPath("$.canComplete").value(true));
        current = read(complete(id, "HEAD_APPROVAL", version(current), SUPERADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStage").value("PRODUCTION_PLANNING")));
        complete(id, "PRODUCTION_PLANNING", version(current), OPERATOR).andExpect(status().isOk());

        // An operator without order:update follows along; one not assigned to the school, or only to an item there, does not
        // reach the order at all (an item scope is for the notebook tracker alone, owner decision D2).
        var readOnly = read(view(id, READ_ONLY_OPERATOR).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false)));
        complete(id, "PRE_PRINT_QUALITY_CHECK", version(readOnly), READ_ONLY_OPERATOR).andExpect(status().isForbidden());
        view(id, OTHER_SCHOOL_OPERATOR).andExpect(status().isNotFound());
        view(id, ITEM_ONLY_OPERATOR).andExpect(status().isNotFound());
    }

    @Test
    void aSchoolWithoutTheOrdersModuleIsRefused() throws Exception {
        TenantContext.set(context(SUPERADMIN));
        var created = orders.createOrder(Map.of("schoolId", 3, "category", "BELTS", "orderData", belts()));
        orders.placeOrder(created.id(), 99L);
        TenantContext.clear();
        view(created.id(), SUPERADMIN).andExpect(status().isOk());
        view(created.id(), DISABLED_SCHOOL_ADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("ORDERS module is not enabled for this school"));
        view(created.id(), new Actor("OPERATIONS", 12, null, "3", null, "order:read,order:update")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("ORDERS module is not enabled for this school"));
    }

    @Test
    void draftAndCancelledOrdersHaveNoWorkflow() throws Exception {
        String draft = draftBelts();
        view(draft, SUPERADMIN).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The production workflow starts once the order is placed"));
        complete(draft, "ORDER_DETAILS", 0, SUPERADMIN).andExpect(status().isConflict());
        form(draft, SCHOOL_ADMIN).andExpect(jsonPath("$.order.productionWorkflowAvailable").value(false));

        String cancelled = placedBelts();
        moveTo(cancelled, "CANCELLED");
        view(cancelled, SUPERADMIN).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A cancelled order has no production workflow"));
        assertThat(workflows()).isZero();
    }

    @Test
    void aFinishedOrderIsNeverGivenAWorkflowButKeepsItsOwnReadOnly() throws Exception {
        for (String finished : List.of("DELIVERED", "Rejected", "FULFILLED", "RETURNED", "COMPLETED")) {
            String id = placedBelts();
            moveTo(id, finished);
            view(id, SUPERADMIN).andExpect(status().isConflict()).andExpect(jsonPath("$.message", containsString("has no production workflow")));
            view(id, OPERATOR).andExpect(status().isConflict());
            form(id, SUPERADMIN).andExpect(jsonPath("$.order.productionWorkflowAvailable").value(false));
        }
        assertThat(workflows()).as("a read never starts a workflow for a finished order").isZero();

        String id = placedBelts();
        var started = read(view(id, OPERATOR));
        moveTo(id, "DELIVERED");
        view(id, OPERATOR).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false))
                .andExpect(jsonPath("$.canComplete").value(false)).andExpect(jsonPath("$.currentStage").value("ORDER_DETAILS"));
        // canEdit=false on a finished order, so a write is a 403 (contract A3) and changes nothing.
        complete(id, "ORDER_DETAILS", version(started), SUPERADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This order is delivered; its production workflow is read-only"));
        save(id, "ORDER_DETAILS", "late", null, version(started), OPERATOR).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This order is delivered; its production workflow is read-only"));
        assertThat(read(view(id, OPERATOR)).get("version")).isEqualTo(started.get("version"));
        form(id, SCHOOL_ADMIN).andExpect(jsonPath("$.order.productionWorkflowAvailable").value(true));
        assertThat(workflows()).isEqualTo(1);

        // A finished order that never had a workflow is refused on write too, and still gets none.
        String never = placedBelts();
        moveTo(never, "DELIVERED");
        complete(never, "ORDER_DETAILS", 0, SUPERADMIN).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This order is delivered; its production workflow is read-only"));
        save(never, "ORDER_DETAILS", "late", null, 0, OPERATOR).andExpect(status().isForbidden());
        assertThat(workflows()).isEqualTo(1);
    }

    @Test
    void onlyTheNineProductionCategoriesHaveAWorkflow() throws Exception {
        for (String category : List.of("TROPHIES", "TIES", "NOTEBOOKS", "UNIFORMS")) {
            String id = placedBelts();
            recategorise(id, category);
            view(id, SUPERADMIN).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("This order has no production workflow"));
            form(id, SUPERADMIN).andExpect(jsonPath("$.order.productionWorkflowAvailable").value(false));
        }
        assertThat(workflows()).isZero();
        for (String category : ProductOrderWorkflowService.CATEGORIES) {
            String id = placedBelts();
            recategorise(id, category);
            form(id, SCHOOL_ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.order.productionWorkflowAvailable").value(true))
                    .andExpect(jsonPath("$.order.schoolId").value(1)).andExpect(jsonPath("$.order.id").value(id))
                    .andExpect(jsonPath("$.order.category").value(category));
            view(id, SCHOOL_ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.category").value(category));
        }
        assertThat(ProductOrderWorkflowService.CATEGORIES).hasSize(9);
    }

    /** A repeat read of an existing workflow writes nothing and reads each workflow table once (it used to issue 10 inserts). */
    @Test
    void readingAnExistingWorkflowWritesNothing() throws Exception {
        String id = placedBelts();
        view(id, SCHOOL_ADMIN).andExpect(status().isOk());
        assertThat(rows(id)).isEqualTo(9);
        STATEMENTS.clear();
        view(id, SCHOOL_ADMIN).andExpect(status().isOk());
        List<String> issued = List.copyOf(STATEMENTS);
        assertThat(issued).noneMatch(sql -> sql.stripLeading().toUpperCase().startsWith("INSERT")
                || sql.toUpperCase().contains(" INSERT INTO") || sql.stripLeading().toUpperCase().startsWith("UPDATE"));
        assertThat(issued.stream().filter(sql -> sql.contains("catalog.product_order_workflows"))).hasSize(1);
        assertThat(issued.stream().filter(sql -> sql.contains("catalog.product_order_workflow_steps"))).hasSize(1);
    }

    @Test
    void anUnreadableBodyIsRefusedWithAMessage() throws Exception {
        String id = placedBelts();
        mvc.perform(as(put("/api/v1/catalog/orders/" + id + "/production-workflow/stages/ORDER_DETAILS"), SUPERADMIN)
                        .contentType("application/json").content("{\"data\":{},\"expectedVersion\":\"soon\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        save(id, "ORDER_DETAILS", "x", null, null, SUPERADMIN).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("expectedVersion is required"));
        complete(id, "NO_SUCH_STAGE", 0, SUPERADMIN).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Production stage not found"));
    }

    private static String placedBelts() {
        String id = draftBelts();
        TenantContext.set(context(SCHOOL_ADMIN));
        try {
            assertThat(orders.placeOrder(id, 7L).status()).isEqualTo("PROCESSING");
        } finally {
            TenantContext.clear();
        }
        return id;
    }

    private static String draftBelts() {
        TenantContext.set(context(SCHOOL_ADMIN));
        try {
            return orders.createOrder(Map.of("category", "BELTS", "orderData", belts())).id();
        } finally {
            TenantContext.clear();
        }
    }

    private static Map<String, Object> belts() {
        return Map.of("orderSelections", Map.of(), "lines", List.of(Map.of(
                "selections", Map.of("BELT_TYPE", "CLOTH", "BUCKLE_TYPE", "BRONZE", "LENGTH", "L70"), "bookCount", 10, "pageCount", 1)));
    }

    private static void moveTo(String id, String status) {
        owner.sql("UPDATE catalog.catalog_orders SET status = :status WHERE id = :id").param("status", status).param("id", id).update();
    }

    private static void recategorise(String id, String category) {
        owner.sql("UPDATE catalog.catalog_orders SET category = :category WHERE id = :id").param("category", category).param("id", id).update();
    }

    private static long workflows() {
        return owner.sql("SELECT count(*) FROM catalog.product_order_workflows").query(Long.class).single();
    }

    private static long rows(String id) {
        return owner.sql("SELECT count(*) FROM catalog.product_order_workflow_steps WHERE order_id = :id").param("id", id).query(Long.class).single();
    }

    private static ResultActions view(String id, Actor actor) throws Exception {
        return mvc.perform(as(get("/api/v1/catalog/orders/" + id + "/production-workflow"), actor));
    }

    private static ResultActions form(String id, Actor actor) throws Exception {
        return mvc.perform(as(get("/api/v1/supply/orders/" + id + "/form"), actor)).andExpect(status().isOk());
    }

    private static ResultActions save(String id, String stage, String details, String note, Number version, Actor actor) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("data", Map.of("details", details));
        body.put("note", note);
        body.put("expectedVersion", version);
        return mvc.perform(as(put("/api/v1/catalog/orders/" + id + "/production-workflow/stages/" + stage), actor)
                .contentType("application/json").content(JSON.writeValueAsString(body)));
    }

    private static ResultActions complete(String id, String stage, Number version, Actor actor) throws Exception {
        return mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/production-workflow/stages/" + stage + "/complete"), actor)
                .contentType("application/json").content(JSON.writeValueAsString(Map.of("expectedVersion", version))));
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
                actor.permissions() == null ? Set.of() : Set.of(actor.permissions().split(",")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(ResultActions result) throws Exception {
        return JSON.readValue(result.andReturn().getResponse().getContentAsString(), Map.class);
    }

    private static long version(Map<String, Object> view) {
        return ((Number) view.get("version")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> codes(Map<String, Object> view) {
        return ((List<Map<String, Object>>) view.get("stages")).stream().map(stage -> String.valueOf(stage.get("code"))).toList();
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
