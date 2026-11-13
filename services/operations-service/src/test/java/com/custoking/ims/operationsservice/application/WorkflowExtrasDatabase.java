package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.orderworkflow.AccessibleOrder;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderAccess;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.application.orderworkflow.template.ClasspathWorkflowTemplates;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplates;
import com.custoking.ims.operationsservice.persistence.ActivityRepository;
import com.custoking.ims.operationsservice.persistence.CallLogRepository;
import com.custoking.ims.operationsservice.persistence.VendorRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowRequestScope;
import com.custoking.ims.operationsservice.persistence.WorkflowSettingsRepository;
import com.custoking.ims.operationsservice.security.TenantAwareDataSource;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantScope;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One real PostgreSQL, migrated to the latest {@code workflow} schema, shared by the vendor directory, call log, activity
 * and group-link integration tests. As in production the runtime role {@code app_rt} exists before the migrations, so it
 * holds exactly the grants they leave it, and every service reads and writes through it, under row-level security, with
 * the tenant settings {@link TenantAwareDataSource} applies on each borrow. The owner connection is only for seeding and
 * for looking past row-level security.
 *
 * <p>The tests share the database, so each seeds orders with its own ids ({@link #order}) and leaves global rows (vendors,
 * categories, the internal approval group) as it found them.
 */
final class WorkflowExtrasDatabase {
    static final long SUPER = 1;
    static final String SCHEMA = "workflow";
    /** What an operator holds through the OPERATIONS role (identity V4-V12): both order workflow codes. */
    static final Set<String> OPERATOR_PERMISSIONS = Set.of("workspace:access", "order:read", "workflow:read",
            "notebook-tracker:read", "notebook-tracker:act");
    /** An operator who may look but not act. */
    static final Set<String> READ_ONLY_PERMISSIONS = Set.of("workspace:access", "notebook-tracker:read");

    private static WorkflowExtrasDatabase instance;

    final PostgreSQLContainer<?> pg;
    final HikariDataSource pool;
    /** The runtime role's pool, with the request's tenant settings applied on every borrow. */
    final TenantAwareDataSource dataSource;
    /** Runtime-role SQL; joins the transaction {@link #tx} opened. */
    final JdbcClient runtime;
    /** The database owner, a superuser that row-level security does not apply to. */
    final JdbcClient owner;
    final TransactionTemplate tx;
    final WorkflowTemplates templates = new ClasspathWorkflowTemplates();
    /** Staff names identity would return, by user id. */
    final Map<Long, String> staff = new ConcurrentHashMap<>();
    private final AtomicInteger orders = new AtomicInteger(7000);
    volatile Instant now = Instant.parse("2026-11-13T06:30:00Z");
    final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    static synchronized WorkflowExtrasDatabase get() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        if (instance == null) instance = new WorkflowExtrasDatabase();
        return instance;
    }

    private WorkflowExtrasDatabase() {
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "owner", "owner"); var st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt");
            st.execute("ALTER DEFAULT PRIVILEGES GRANT USAGE, SELECT ON SEQUENCES TO app_rt");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
        Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas(SCHEMA).defaultSchema(SCHEMA)
                .locations("classpath:db/migration/workflow").load().migrate();
        owner = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        owner.sql("GRANT USAGE ON SCHEMA workflow TO app_rt").update();
        pool = new HikariDataSource();
        pool.setJdbcUrl(pg.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(4);
        dataSource = new TenantAwareDataSource(pool);
        runtime = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    // ---- the services under test, wired as Spring would wire them ---------------------------------------------------

    VendorRepository vendorRepository() { return new VendorRepository(runtime, SCHEMA); }

    WorkflowSettingsRepository settingsRepository() { return new WorkflowSettingsRepository(runtime, SCHEMA); }

    WorkflowRequestScope scope() { return new WorkflowRequestScope(runtime); }

    OrderAccess access() { return new TestOrderAccess(runtime); }

    StaffNames names() { return new StaffNames(ids -> { var found = new java.util.HashMap<Long, String>(); ids.forEach(id -> { if (staff.containsKey(id)) found.put(id, staff.get(id)); }); return found; }, clock); }

    VendorDirectoryService vendorDirectory() { return new VendorDirectoryService(vendorRepository(), tx); }

    WorkflowSettingsService settings() { return new WorkflowSettingsService(settingsRepository(), tx); }

    GroupLinkService groups() { return new GroupLinkService(settingsRepository(), vendorRepository(), access(), scope(), tx); }

    CallLogService calls() {
        return new CallLogService(new CallLogRepository(runtime, SCHEMA), vendorRepository(), access(), scope(), tx, clock, names());
    }

    ActivityService activity() {
        return new ActivityService(new ActivityRepository(runtime, SCHEMA), access(), scope(), tx, templates, names(),
                JsonMapper.builder().build());
    }

    // ---- who is calling ------------------------------------------------------------------------------------------------

    static void superadmin() {
        TenantContext.set(new TenantContext(SUPER, "super@custoking.test", "SUPERADMIN", null, null, Set.of()));
    }

    /** An operator with school-wide assignments at {@code schools} and item scopes such as {@code 20:FLIERS}. */
    static void operator(long userId, Set<Long> schools, Set<String> itemScopes, Set<String> permissions) {
        TenantContext.set(new TenantContext(userId, "op" + userId + "@custoking.test", "OPERATIONS", null, null, permissions,
                schools, itemScopes));
    }

    static void operator(long userId, long... schools) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (long school : schools) ids.add(school);
        operator(userId, ids, Set.of(), OPERATOR_PERMISSIONS);
    }

    /** A school administrator: they hold the generic workflow codes but keep only the order status view. */
    static void schoolAdmin(long schoolId) {
        TenantContext.set(new TenantContext(900 + schoolId, "admin@school.test", "SCHOOL_ADMIN", schoolId, null,
                Set.of("workflow:read", "workflow:act", "notebook-tracker:read", "notebook-tracker:act")));
    }

    // ---- seeding (as the owner) ------------------------------------------------------------------------------------------

    /** An order workflow as the tests see it. */
    record Order(String orderId, long instanceId, long schoolId, String categoryCode, String definitionId) { }

    /** A customised notebook order at {@code school}, active on {@code activeStep} (0 = finished), every stage assigned to {@code assignee}. */
    Order notebook(long school, int activeStep, Long assignee) {
        return order(school, WorkflowTemplates.NOTEBOOK, "NOTEBOOKS", "NB", "notebook", true, activeStep, assignee);
    }

    /** A flier order (nine stages) at {@code school}. */
    Order flier(long school, int activeStep, Long assignee) {
        return order(school, WorkflowTemplates.FLIER, "FLIERS", "FE", "flier", true, activeStep, assignee);
    }

    /**
     * Seeds an order workflow: its instance, tracker row and one stage row per stage on its path (a wholesale notebook has
     * no Steps 2-4). Stages before {@code activeStep} are completed, it is active, later ones locked; 0 finishes them all.
     */
    Order order(long school, String definitionId, String category, String itemCode, String noun, boolean customised, int activeStep,
                Long assignee) {
        String orderId = "CK-" + orders.incrementAndGet();
        List<Integer> stepOrders = owner.sql("SELECT step_order FROM workflow.workflow_steps WHERE definition_id = :d ORDER BY step_order")
                .param("d", definitionId).query(Integer.class).list();
        long instanceId = owner.sql("INSERT INTO workflow.workflow_instances (definition_id, entity_type, entity_id, school_id, "
                        + "current_step, status, completed_at) VALUES (:d, 'ITEM_ORDER', :order, :school, :step, :status, "
                        + (activeStep == 0 ? "now()" : "NULL") + ") RETURNING id")
                .param("d", definitionId).param("order", orderId).param("school", school).param("step", activeStep)
                .param("status", activeStep == 0 ? "COMPLETED" : "IN_PROGRESS").query(Long.class).single();
        owner.sql("INSERT INTO workflow.workflow_notebook_trackers (instance_id, school_id, order_id, customised, ordered_count, placed_at, "
                        + "time_zone, school_name, definition_id, category_code, item_code, item_noun) VALUES (:id, :school, :order, "
                        + ":customised, 500, timestamptz '2026-11-10T04:30:00Z', 'Asia/Kolkata', :schoolName, :d, :category, :item, :noun)")
                .param("id", instanceId).param("school", school).param("order", orderId).param("customised", customised)
                .param("schoolName", "School " + school).param("d", definitionId).param("category", category)
                .param("item", itemCode).param("noun", noun).update();
        for (int step : stepOrders) {
            if (!customised && WorkflowTemplates.NOTEBOOK.equals(definitionId) && step >= 2 && step <= 4) continue;
            String started = "timestamptz '2026-11-10T05:00:00Z' + make_interval(hours => " + step + ")";
            String values;
            if (activeStep == 0 || step < activeStep) values = "'COMPLETED', " + started + ", NULL, " + started + " + interval '30 minutes', 1";
            else if (step == activeStep) values = "'ACTIVE', " + started + ", " + started + " + interval '1 day', NULL, NULL";
            else values = "'LOCKED', NULL, NULL, NULL, NULL";
            owner.sql("INSERT INTO workflow.workflow_instance_steps (instance_id, school_id, definition_id, step_order, status, started_at, "
                            + "due_at, completed_at, completed_by, assignee_user_id) VALUES (:id, :school, :d, :step, " + values + ", :assignee)")
                    .param("id", instanceId).param("school", school).param("d", definitionId).param("step", step)
                    .param("assignee", assignee).update();
        }
        return new Order(orderId, instanceId, school, category, definitionId);
    }

    /** Stores the order's vendor selections, {@code {CATEGORY: [ids]}}. */
    void selectVendors(Order order, String selectionsJson) {
        owner.sql("UPDATE workflow.workflow_notebook_trackers SET vendor_selections = CAST(:json AS jsonb) WHERE instance_id = :id")
                .param("json", selectionsJson).param("id", order.instanceId()).update();
    }

    /** A real vendor in the directory, seeded as the owner; returns its id. */
    long vendor(String name, String phone, String invite, String... categories) {
        long id = owner.sql("INSERT INTO workflow.workflow_vendors (name, phone, whatsapp_group_url) VALUES (:name, :phone, :url) RETURNING id")
                .param("name", name).param("phone", phone).param("url", invite).query(Long.class).single();
        for (String category : categories) {
            owner.sql("INSERT INTO workflow.workflow_vendor_category_members (vendor_id, category_code) VALUES (:id, :c)")
                    .param("id", id).param("c", category).update();
        }
        return id;
    }

    long designer(String name, String phone) {
        return owner.sql("INSERT INTO workflow.workflow_designers (name, phone) VALUES (:name, :phone) RETURNING id")
                .param("name", name).param("phone", phone).query(Long.class).single();
    }

    // ---- OrderAccess ---------------------------------------------------------------------------------------------------

    /**
     * The read and act rules of design §3.5 and D42 as the {@link OrderAccess} seam documents them, in SQL against the
     * tracker tables: superadmin sees every order; an operator reads with {@code notebook-tracker:read} and acts with
     * {@code notebook-tracker:act}, only on orders whose (school, category) their assignments cover (404 otherwise, as for
     * an order with no workflow); everyone else is refused (403). The tracker row is read under row-level security with
     * the scope the caller applied, as the order workflow's own implementation reads it.
     */
    static final class TestOrderAccess implements OrderAccess {
        private final JdbcClient jdbc;

        TestOrderAccess(JdbcClient jdbc) { this.jdbc = jdbc; }

        @Override
        public AccessibleOrder require(String orderId, boolean write) {
            TenantContext ctx = TenantContext.get();
            if (ctx.userId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated user required");
            Role role;
            if (ctx.isSuperAdmin()) {
                role = Role.SUPERADMIN;
            } else if (ctx.isOperations()) {
                TenantScope.requirePermission(write ? "notebook-tracker:act" : "notebook-tracker:read");
                role = Role.OPERATOR;
            } else {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Order workflows are for superadmin and operators only");
            }
            AccessibleOrder order = jdbc.sql("SELECT t.instance_id, t.school_id, t.order_id, t.category_code, t.definition_id, "
                            + "(SELECT min(s.step_order) FROM workflow.workflow_instance_steps s WHERE s.instance_id = t.instance_id "
                            + "AND s.status = 'ACTIVE') AS active FROM workflow.workflow_notebook_trackers t WHERE t.order_id = :order")
                    .param("order", orderId)
                    .query((rs, n) -> new AccessibleOrder(rs.getLong("instance_id"), rs.getLong("school_id"), rs.getString("order_id"),
                            rs.getString("category_code"), rs.getString("definition_id"), rs.getObject("active", Integer.class), role,
                            ctx.userId()))
                    .optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order workflow not found"));
            if (role == Role.OPERATOR && !ctx.operatorMayWork(order.schoolId(), order.categoryCode())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order workflow not found");
            }
            return order;
        }
    }
}
