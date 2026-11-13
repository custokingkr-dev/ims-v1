package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.OrderWorkflowService.ContactsChange;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.CreateWorkflow;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ImportedStep;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ListQuery;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.TimeSlotRow;
import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource;
import com.custoking.ims.operationsservice.application.orderworkflow.CallbackSource.OpenCallback;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.GroupLink;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.NoteEntry;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowListItem;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowPage;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.StepView;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup;
import com.custoking.ims.operationsservice.application.orderworkflow.VendorLookup.VendorSummary;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.GateFailure;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Gates;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.UpcomingActions;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.application.orderworkflow.template.ChecklistSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.ClasspathWorkflowTemplates;
import com.custoking.ims.operationsservice.application.orderworkflow.template.FieldSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.GateSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.GateType;
import com.custoking.ims.operationsservice.application.orderworkflow.template.StepSpec;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplate;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplates;
import com.custoking.ims.operationsservice.infrastructure.CatalogOrderGateway;
import com.custoking.ims.operationsservice.infrastructure.OperatorEligibility;
import com.custoking.ims.operationsservice.infrastructure.StaffDirectory;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository;
import com.custoking.ims.operationsservice.persistence.TrackerStatusOutbox;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository;
import com.custoking.ims.operationsservice.security.TenantAwareDataSource;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The order workflow engine's real flows (design §3.5, D18-D23, D33, D36-D42) against a real PostgreSQL migrated to the
 * latest workflow schema, with row-level security and the runtime role, the shipped templates, and stubbed peers.
 */
class OrderWorkflowServiceIntegrationTest {
    /** What identity grants a school-wide operator (the OPERATIONS role's codes). */
    static final Set<String> OPERATIONS = Set.of("workspace:access", "order:read", "order:update", "workflow:read",
            "notebook-tracker:read", "notebook-tracker:act");
    /** An operator whose only assignments are item scopes holds exactly these (owner decision D2). */
    static final Set<String> ITEM_ONLY = Set.of("workspace:access", "notebook-tracker:read", "notebook-tracker:act", "order:read", "order:update");
    static final Set<String> SCHOOL_ADMIN_WORKFLOW = Set.of("workflow:read", "workflow:act");
    static final long SUPER = 1, OP_A = 10, OP_B = 11, OP_C = 12;
    static final String NB = WorkflowTemplates.NOTEBOOK, FE = WorkflowTemplates.FLIER, GENERAL = WorkflowTemplates.GENERAL_ITEM;
    static final OffsetDateTime PLACED = OffsetDateTime.parse("2026-10-19T09:30:00+05:30");
    static final String LINK = "https://drive.example.com/folder/abc";
    static final String CDR = "https://drive.example.com/cdr/v1";

    static PostgreSQLContainer<?> PG;
    static javax.sql.DataSource dataSource;
    static HikariDataSource pool;
    /** The database owner (a superuser, which row-level security does not apply to): for looking at rows directly. */
    static JdbcClient owner;
    static TransactionTemplate transactions;
    static WorkflowTemplates TEMPLATES;
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static OrderWorkflowService service;
    static TrackerStatusOutbox statuses;
    static volatile Instant now = Instant.parse("2026-10-19T04:00:00Z"); // Monday 09:30 in Kolkata
    static final AtomicInteger ORDERS = new AtomicInteger();
    static final Clock CLOCK = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };

    /** {@code user:school:CATEGORY} identity would call eligible. */
    static final Set<String> ELIGIBLE = ConcurrentHashMap.newKeySet();
    static volatile boolean identityDown = false;
    static final OperatorEligibility ELIGIBILITY = (userId, schoolId, category) -> {
        if (identityDown) throw new IllegalStateException("identity is down");
        return ELIGIBLE.contains(userId + ":" + schoolId + ":" + category);
    };

    /** Design files the catalogue would report for any order. */
    static final List<Long> DRAFT_IDS = new CopyOnWriteArrayList<>();
    static final List<Boolean> DRAFT_FETCH_IN_TRANSACTION = new CopyOnWriteArrayList<>();
    static final List<String> PUSHED = new CopyOnWriteArrayList<>();
    static final List<Long> PUSHED_VERSIONS = new CopyOnWriteArrayList<>();
    static final List<CatalogOrderGateway.WorkflowStep> PUSHED_STEPS = new CopyOnWriteArrayList<>();
    static volatile boolean catalogDown = false;
    static volatile boolean catalogClosed = false;
    /** The catalogue answers that the order does not exist (school-core's 404, e.g. after a data cleanup). */
    static volatile boolean catalogGone = false;
    /** Runs once, inside the next status push, before it reaches the catalogue: lets a test act while a push is in flight. */
    static final AtomicReference<Runnable> DURING_PUSH = new AtomicReference<>();
    static CatalogOrderGateway catalog;

    /** The vendor directory as the workflow sees it (WP3 implements it over the database). */
    record Vendor(long id, String name, String category, boolean active) { }
    static final Map<Long, Vendor> VENDORS = Map.of(
            1L, new Vendor(1, "Shree Boards", "BOARD_SUPPLIERS", true),
            2L, new Vendor(2, "Gloss Lamination", "LAMINATION_VENDORS", true),
            3L, new Vendor(3, "City Binders", "BINDERS", true),
            4L, new Vendor(4, "Offset One", "OFFSET_PRINTERS", true),
            5L, new Vendor(5, "Digital Hub", "DIGITAL_PRINTERS", true),
            6L, new Vendor(6, "Flex World", "FLEX_PRINTERS", true),
            7L, new Vendor(7, "Old Offset", "OFFSET_PRINTERS", false));
    /** Categories superadmin has renamed in the directory (D31), by code; every other category keeps its SOP name. */
    static final Map<String, String> RENAMED_CATEGORIES = new ConcurrentHashMap<>();
    static final VendorLookup VENDOR_LOOKUP = new VendorLookup() {
        @Override public boolean inCategory(long vendorId, String category) {
            Vendor vendor = VENDORS.get(vendorId);
            return vendor != null && vendor.category().equals(category);
        }
        @Override public List<VendorSummary> vendors(java.util.Collection<Long> ids) {
            return ids.stream().distinct().map(VENDORS::get).filter(Objects::nonNull).sorted(Comparator.comparing(Vendor::name))
                    .map(v -> new VendorSummary(v.id(), v.name(), List.of(v.category()), null, "98450 0000" + v.id(), null, null, false, v.active()))
                    .toList();
        }
        @Override public Set<String> categoryCodes() { return Gates.SOP_CATEGORY_NAMES.keySet(); }
        @Override public Map<String, String> categoryNames() {
            Map<String, String> names = new HashMap<>(Gates.SOP_CATEGORY_NAMES);
            names.putAll(RENAMED_CATEGORIES);
            return names;
        }
    };
    static final List<Boolean> RESOLVED_IN_TRANSACTION = new CopyOnWriteArrayList<>();
    static final GroupLinkResolver GROUPS = (schoolId, category, vendorIds) -> {
        RESOLVED_IN_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
        return new ResolvedGroups(new GroupLink("https://chat.whatsapp.com/School" + schoolId + "Group01", GroupLinkResolver.Source.SCHOOL),
                GroupLink.none(), VENDOR_LOOKUP.vendors(vendorIds));
    };
    static final Map<Long, List<OpenCallback>> CALLBACKS = new ConcurrentHashMap<>();
    static final CallbackSource CALLBACK_SOURCE = instanceId -> CALLBACKS.getOrDefault(instanceId, List.of());

    /** Who identity knows as staff, by id. */
    static final Map<Long, String> STAFF = new ConcurrentHashMap<>();
    static final List<String> NAME_CALLS = new CopyOnWriteArrayList<>();
    static final StaffDirectory DIRECTORY = ids -> {
        NAME_CALLS.add(ids.stream().sorted().toList() + " active=" + pool.getHikariPoolMXBean().getActiveConnections()
                + " transaction=" + TransactionSynchronizationManager.isActualTransactionActive());
        Map<Long, String> found = new HashMap<>();
        for (Long id : ids) if (STAFF.containsKey(id)) found.put(id, STAFF.get(id));
        return found;
    };

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        PG = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        PG.start();
        // The runtime role exists before the migrations, as in production, so it holds exactly the grants they leave it.
        try (var c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); var st = c.createStatement()) {
            st.execute("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS");
            st.execute("ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_rt");
            st.execute("ALTER DEFAULT PRIVILEGES GRANT USAGE, SELECT ON SEQUENCES TO app_rt");
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner").schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration/workflow").load().migrate();
        try (var c = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), "owner", "owner"); var st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA workflow TO app_rt");
        }
        pool = new HikariDataSource();
        pool.setJdbcUrl(PG.getJdbcUrl());
        pool.setUsername("app_rt");
        pool.setPassword("app_rt");
        pool.setMaximumPoolSize(3);
        dataSource = new TenantAwareDataSource(pool);
        owner = JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner"));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
        TEMPLATES = new ClasspathWorkflowTemplates();
        catalog = new CatalogOrderGateway() {
            @Override public List<DraftImage> draftImages(String orderId) {
                DRAFT_FETCH_IN_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
                if (catalogDown) throw new IllegalStateException("catalogue is down");
                return DRAFT_IDS.stream().map(id -> new DraftImage(id, "application/pdf", "design-" + id + ".pdf")).toList();
            }
            @Override public void pushStatus(String orderId, String status, long trackerVersion, WorkflowStep step) {
                Runnable during = DURING_PUSH.getAndSet(null);
                if (during != null) during.run();
                if (catalogDown) throw new IllegalStateException("catalogue is down");
                if (catalogClosed) throw new OrderClosedException("the order catalogue answered HTTP 409", null);
                if (catalogGone) throw new OrderClosedException("the order catalogue answered HTTP 404", 404, null);
                PUSHED.add(orderId + ":" + status);
                PUSHED_VERSIONS.add(trackerVersion);
                PUSHED_STEPS.add(step);
            }
        };
        DRAFT_IDS.add(501L);
        statuses = new TrackerStatusOutbox(JdbcClient.create(dataSource), catalog, TEMPLATES, manager, "workflow");
        // The shared service never finds a name, so only the name tests' own services call the directory stub.
        service = serviceWith(new StaffNames(ids -> Map.of(), CLOCK));
    }

    static OrderWorkflowService serviceWith(StaffNames names) {
        return serviceWith(new OrderWorkflowRepository(JdbcClient.create(dataSource), MAPPER, "workflow"), names);
    }

    static OrderWorkflowService serviceWith(OrderWorkflowRepository repo, StaffNames names) {
        return new OrderWorkflowService(repo, new OrderWorkflowAccess(repo), TEMPLATES, transactions, CLOCK, ELIGIBILITY, catalog,
                statuses, names, VENDOR_LOOKUP, GROUPS, CALLBACK_SOURCE, MAPPER);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (pool != null) pool.close();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void reset() {
        TenantContext.clear();
        now = Instant.parse("2026-10-19T04:00:00Z");
        identityDown = false;
        ELIGIBLE.clear();
        catalogDown = false;
        catalogClosed = false;
        catalogGone = false;
        DURING_PUSH.set(null);
        PUSHED.clear();
        PUSHED_VERSIONS.clear();
        PUSHED_STEPS.clear();
        DRAFT_FETCH_IN_TRANSACTION.clear();
        RESOLVED_IN_TRANSACTION.clear();
        CALLBACKS.clear();
        STAFF.clear();
        NAME_CALLS.clear();
        // Tests share the database; a status one test left queued must not be delivered by another test's drain.
        owner.sql("DELETE FROM workflow.workflow_tracker_status_outbox").update();
        DRAFT_IDS.clear();
        DRAFT_IDS.add(501L);
    }

    // ==== helpers ========================================================================================================

    static void asSuper() { TenantContext.set(new TenantContext(SUPER, "sa@x", "SUPERADMIN", null, null)); }

    /** A school-wide operator as the gateway forwards them: their branch (the first school) is their school id. */
    static void asOperator(long user, Long... schools) {
        Long branch = schools.length == 0 ? null : schools[0];
        TenantContext.set(new TenantContext(user, "op" + user + "@x", "OPERATIONS", branch, null,
                schools.length == 0 ? Set.of() : OPERATIONS, Set.of(schools)));
    }

    /** An operator with only item scopes such as "10:FLIERS": no school id. */
    static void asScopedOperator(long user, String... scopes) {
        TenantContext.set(new TenantContext(user, "op" + user + "@x", "OPERATIONS", null, null, ITEM_ONLY, Set.of(), Set.of(scopes)));
    }

    static void asSchoolAdmin(long school) {
        TenantContext.set(new TenantContext(5L, "admin@x", "ADMIN", school, null, SCHOOL_ADMIN_WORKFLOW));
    }

    static void asNobody() { TenantContext.clear(); }

    static <T> T as(Runnable login, Supplier<T> action) {
        login.run();
        try { return action.get(); } finally { TenantContext.clear(); }
    }

    record Item(String definitionId, String category, String code, String noun) { }
    static final Map<String, Item> ITEMS = Map.of(
            NB, new Item(NB, "NOTEBOOKS", "NB", "notebook"),
            FE, new Item(FE, "FLIERS", "FE", "flier"),
            GENERAL, new Item(GENERAL, "CERTIFICATES", "CF", "certificate"));

    /** School-core's machine create; it logs everyone out, so call it before logging in, never inside {@link #as}. */
    static String create(String definitionId, boolean customised, long school, Long assignee) {
        return create(definitionId, customised, school, assignee, null, null, List.of());
    }

    static String create(String definitionId, boolean customised, long school, Long assignee, String number, String schoolName,
                         List<ImportedStep> imported) {
        String order = "ORD-" + ORDERS.incrementAndGet();
        Item item = ITEMS.get(definitionId);
        asNobody();
        service.create(new CreateWorkflow(order, school, customised, 1000, PLACED, "Asia/Kolkata", 77L, assignee, schoolName,
                item.definitionId(), item.category(), item.code(), item.noun(), number, imported));
        return order;
    }

    static StepView step(OrderWorkflowView view, int number) {
        return view.steps().stream().filter(s -> s.step() == number).findFirst().orElseThrow();
    }

    static long version(String order, int step) {
        return step(service.view(order), step).version();
    }

    /** Saves into a stage as whoever is logged in, with the version the screen would have loaded. */
    static OrderWorkflowView save(String order, int step, Map<String, Object> data) {
        return service.saveStepData(order, step, data, version(order, step));
    }

    static OrderWorkflowView complete(String order, int step) {
        return service.completeStep(order, step, version(order, step));
    }

    /** The codes of every requirement Complete still lists on the stage (design D19). */
    static List<String> unmet(String order, int step) {
        WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> complete(order, step));
        assertEquals(WorkflowRuleViolation.GATE_UNMET, refused.code(), refused.getMessage());
        return refused.unmet().stream().map(GateFailure::code).toList();
    }

    static String codeOf(Runnable action) {
        return assertThrows(WorkflowRuleViolation.class, action::run).code();
    }

    static int http(Runnable action) {
        return assertThrows(ResponseStatusException.class, action::run).getStatusCode().value();
    }

    static long vendorIn(String category) {
        return VENDORS.values().stream().filter(v -> v.category().equals(category) && v.active()).map(Vendor::id).findFirst().orElseThrow();
    }

    static Object sample(FieldSpec field) {
        return switch (field.kind()) {
            case URL -> LINK;
            case TEXT -> "Done";
            case WORDS -> "Checked against the approved specification";
            case DATE -> "2026-10-25";
            case WHOLE -> 1000;
            case NUMBER -> 1;
            case CHOICE -> field.choiceValues().get(0);
        };
    }

    /** As superadmin, meets every gate of the active stage from its template (fields, checklists, vendors, approval, sign-off). */
    static void satisfy(String order, int n) {
        OrderWorkflowView view = service.view(order);
        StepSpec spec = TEMPLATES.get(view.definitionId()).step(n).orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        for (GateSpec gate : spec.gates()) {
            switch (gate.type()) {
                case FIELDS_PRESENT -> gate.fields().forEach(key -> data.put(key, key.equals("cdrLink") ? CDR : sample(spec.fields().get(key))));
                case ALL_CHECKED -> {
                    ChecklistSpec checklist = spec.checklists().get(gate.checklist());
                    List<Integer> all = new ArrayList<>();
                    for (int i = 0; i < checklist.size(); i++) if (!Objects.equals(checklist.signOffItem(), i)) all.add(i);
                    data.put(gate.checklist(), all);
                }
                case CHOICE_IN -> data.put(gate.field(), gate.values().get(0));
                case VENDORS_SELECTED -> {
                    Map<String, List<Long>> selections = new LinkedHashMap<>();
                    if (gate.categories() == null) selections.put("DIGITAL_PRINTERS", List.of(5L));
                    else for (String category : gate.categories()) selections.put(category, List.of(vendorIn(category)));
                    service.setVendorSelections(order, selections, service.view(order).version());
                }
                default -> { }
            }
        }
        if (!data.isEmpty()) save(order, n, data);
        if (spec.hasGate(GateType.HEAD_APPROVED)) service.approveDesign(order, "Approved as shown", version(order, n));
        if (spec.hasGate(GateType.SIGNED_OFF)) service.signOff(order, n, version(order, n));
    }

    /** Completes stages as superadmin, meeting each gate, up to and including {@code last}. */
    static OrderWorkflowView advance(String order, int last) {
        asSuper();
        try {
            OrderWorkflowView view = service.view(order);
            while (view.progress().currentStep() != null && view.progress().currentStep() <= last) {
                int n = view.progress().currentStep();
                satisfy(order, n);
                view = complete(order, n);
            }
            return view;
        } finally {
            TenantContext.clear();
        }
    }

    static List<String> actions(String order) {
        return owner.sql("SELECT a.action FROM workflow.workflow_actions a JOIN workflow.workflow_notebook_trackers t ON t.instance_id = a.instance_id "
                + "WHERE t.order_id = :order ORDER BY a.id").param("order", order).query(String.class).list();
    }

    static String actionNotes(String order, String action) {
        List<String> notes = owner.sql("SELECT a.notes FROM workflow.workflow_actions a JOIN workflow.workflow_notebook_trackers t "
                        + "ON t.instance_id = a.instance_id WHERE t.order_id = :order AND a.action = :action ORDER BY a.id DESC")
                .param("order", order).param("action", action).query(String.class).list();
        assertFalse(notes.isEmpty(), "no " + action + " recorded");
        return notes.get(0);
    }

    static long instanceId(String order) {
        return owner.sql("SELECT instance_id FROM workflow.workflow_notebook_trackers WHERE order_id = :order").param("order", order)
                .query(Long.class).single();
    }

    /** The order ids a caller finds by searching for one order id (other tests' orders share the schools). */
    static List<String> found(Runnable login, String orderId) {
        return as(login, () -> service.list(new ListQuery(null, null, null, null, null, null, orderId, true, null, 100, 0)))
                .items().stream().map(OrderWorkflowListItem::orderId).toList();
    }

    // ==== creation (design §3.5 machine create, D4, D6, D13) ============================================================

    @Test
    void aNotebookOrderStartsStepOneWithItsClockAsAnItemOrderAndShowsItsStep() {
        String order = create(NB, true, 10L, OP_A, "NB-DPS_DEL-26-10-19-0001", "Delhi Public School", List.of());
        OrderWorkflowView view = as(() -> asOperator(OP_A, 10L), () -> service.view(order));
        assertEquals(NB, view.definitionId());
        assertEquals("NOTEBOOKS", view.categoryCode());
        assertEquals("NB", view.itemCode());
        assertEquals("NB-DPS_DEL-26-10-19-0001", view.orderNumber());
        assertEquals(12, view.progress().total());
        assertEquals(1, view.progress().currentStep());
        assertEquals("ORDER_DETAILS", view.status());
        assertEquals("ACTIVE", step(view, 1).status());
        for (int n = 2; n <= 12; n++) assertEquals("LOCKED", step(view, n).status(), "step " + n);
        // Step 1's 2 hours run from placement: due 11:30 in Kolkata (D23).
        assertEquals(PLACED.plusHours(2).toInstant(), step(view, 1).dueAt().toInstant());
        assertEquals(new OrderWorkflowView.EffectiveSla(2, "HOURS", "DEFAULT"), step(view, 1).sla());
        assertEquals(OP_A, step(view, 1).assigneeUserId());
        assertEquals("ITEM_ORDER", owner.sql("SELECT entity_type FROM workflow.workflow_instances WHERE id = :id")
                .param("id", instanceId(order)).query(String.class).single(), "new workflows are item orders (D6)");
        // The creation shows the first step on the order at once, with the step report school-core keeps for lists.
        assertEquals(List.of(order + ":ORDER_DETAILS"), PUSHED);
        assertEquals(new CatalogOrderGateway.WorkflowStep(NB, 1, "Order Details", 12, 0), PUSHED_STEPS.get(0));
        assertEquals(List.of("TRACKER_CREATED"), actions(order));
    }

    @Test
    void creationIsIdempotentAndFillsAMissingOrderNumberOnce() {
        String order = create(FE, true, 10L, OP_A);
        assertNull(as(() -> asSuper(), () -> service.view(order)).orderNumber());
        long createdAt = PUSHED_VERSIONS.get(PUSHED_VERSIONS.size() - 1);
        PUSHED.clear();
        PUSHED_VERSIONS.clear();
        PUSHED_STEPS.clear();
        CreateWorkflow again = new CreateWorkflow(order, 10, true, 1000, PLACED, "Asia/Kolkata", 77L, OP_A, null, FE, "FLIERS", "FE",
                "flier", "FE-DPS_DEL-26-10-19-0007", List.of());
        asNobody();
        OrderWorkflowService.Created repeated = service.create(again);
        assertFalse(repeated.created());
        assertEquals(instanceId(order), repeated.instanceId());
        assertEquals("FE-DPS_DEL-26-10-19-0007", as(() -> asSuper(), () -> service.view(order)).orderNumber());
        // A workflow numbered late (catalog V38 re-queues the creates of workflows made before numbers) pushes its status
        // again at a newer version, now with the step report the catalogue's lists show.
        assertEquals(List.of(order + ":ORDER_DETAILS"), PUSHED);
        assertEquals(List.of(createdAt + 1), PUSHED_VERSIONS);
        assertEquals(List.of(new CatalogOrderGateway.WorkflowStep(FE, 1, "Order Details", 9, 0)), PUSHED_STEPS);
        assertEquals(0L, owner.sql("SELECT count(*) FROM workflow.workflow_tracker_status_outbox WHERE order_id = :o")
                .param("o", order).query(Long.class).single(), "delivered once the create committed");
        PUSHED.clear();

        // A number already set never changes, and a repeated create pushes nothing.
        asNobody();
        service.create(new CreateWorkflow(order, 10, true, 1000, PLACED, "Asia/Kolkata", 77L, OP_A, null, FE, "FLIERS", "FE", "flier",
                "FE-DPS_DEL-26-10-19-0008", List.of()));
        assertEquals("FE-DPS_DEL-26-10-19-0007", as(() -> asSuper(), () -> service.view(order)).orderNumber());
        asNobody();
        service.create(again);
        assertTrue(PUSHED.isEmpty(), "an existing workflow is not pushed again");

        // Another order cannot take it.
        String other = create(FE, true, 10L, OP_A);
        asNobody();
        assertEquals(409, http(() -> service.create(new CreateWorkflow(other, 10, true, 1000, PLACED, "Asia/Kolkata", 77L, OP_A, null,
                FE, "FLIERS", "FE", "flier", "FE-DPS_DEL-26-10-19-0007", List.of()))));
        // Nor is an order tracked under another school taken over.
        assertEquals(409, http(() -> service.create(new CreateWorkflow(order, 20, true, 1000, PLACED, "Asia/Kolkata", 77L, null))));
    }

    @Test
    void aWholesaleNotebookRunsNineStepsAndAFlierRunsItsOwnNine() {
        String wholesaleOrder = create(NB, false, 10L, OP_A);
        OrderWorkflowView wholesale = as(() -> asSuper(), () -> service.view(wholesaleOrder));
        assertEquals(List.of(1, 5, 6, 7, 8, 9, 10, 11, 12), wholesale.steps().stream().map(StepView::step).toList());
        assertFalse(wholesale.can().canManageDesignFiles());
        assertFalse(wholesale.can().canSetDesigner(), "a wholesale notebook has no design stage (D7)");

        String flierOrder = create(FE, true, 10L, OP_A);
        OrderWorkflowView flier = as(() -> asSuper(), () -> service.view(flierOrder));
        assertEquals(FE, flier.definitionId());
        assertEquals("flier", flier.itemNoun());
        assertEquals(List.of("ORDER_DETAILS", "DESIGNING_ACTIVITY", "CDR_CONVERSION", "HEAD_APPROVAL", "PRODUCTION_PLANNING",
                "PRE_PRINT_QUALITY_CHECK", "PRODUCTION_FOLLOW_UP", "FINAL_APPROVAL", "DELIVERY_INVOICING"),
                flier.steps().stream().map(StepView::code).toList());
        assertEquals(List.of("INTAKE", "INTAKE", "INTAKE", "INTAKE", "PLANNING", "PRODUCTION", "PRODUCTION", "VERIFICATION", "DELIVERY"),
                flier.steps().stream().map(StepView::phase).toList());
    }

    /** A school-core deployed before this service sends only the notebook fields (the alias path, D6). */
    @Test
    void aCreateThatNamesNoItemIsANotebookOrder() {
        String order = "ORD-" + ORDERS.incrementAndGet();
        asNobody();
        service.create(new CreateWorkflow(order, 10, true, 1000, PLACED, "Asia/Kolkata", 77L, OP_A));
        OrderWorkflowView view = as(() -> asSuper(), () -> service.view(order));
        assertEquals(NB, view.definitionId());
        assertEquals("NOTEBOOKS", view.categoryCode());
        assertEquals("NB", view.itemCode());
        assertEquals("notebook", view.itemNoun());
        assertEquals(12, view.progress().total());
    }

    @Test
    void creationValidatesItsInput() {
        asNobody();
        assertEquals(400, http(() -> service.create(new CreateWorkflow("", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("CK 1", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 0, true, 1000, PLACED, "Asia/Kolkata", 1L, null))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 0, PLACED, "Asia/Kolkata", 1L, null))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 1000, PLACED, "Mars/Olympus", 1L, null))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null, null,
                "UNKNOWN_WORKFLOW", "FLIERS", "FE", "flier", null, List.of()))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null, null,
                FE, "FLIERS", null, "flier", null, List.of()))), "an item without its order code");
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null, null,
                FE, null, "FE", "flier", null, List.of()))), "an item without its category");
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null, null,
                FE, "FLIERS", "FE", "flier", "NB DPS 0001", List.of()))));
        assertEquals(400, http(() -> service.create(new CreateWorkflow("X", 10, true, 1000, PLACED, "Asia/Kolkata", 1L, null, null,
                FE, "FLIERS", "FE", "flier", null, List.of(new ImportedStep("ORDER_DETAILS", "ORDER_DETAILS", 1, "LOCKED", null, null, null, null))))));
    }

    /** Design D4: a retired production workflow resumes at the same stage, its stages imported without gates, its notes kept. */
    @Test
    void anImportedOrderResumesAtTheSameStageWithItsNotesAndHistory() {
        OffsetDateTime done1 = OffsetDateTime.parse("2026-10-12T10:00:00Z"), done2 = OffsetDateTime.parse("2026-10-14T10:00:00Z"),
                done3 = OffsetDateTime.parse("2026-10-15T10:00:00Z");
        String order = create(FE, true, 10L, OP_A, "FE-DPS_DEL-26-10-11-0003", null, List.of(
                new ImportedStep("ORDER_DETAILS", "ORDER_DETAILS", 1, "COMPLETED", done1, 21L, null, null),
                new ImportedStep("DESIGNING_ACTIVITY", "DESIGN_ACTIVITY", 2, "COMPLETED", done2, 22L, "Three drafts shared", "School liked v2"),
                new ImportedStep("CDR_CONVERSION", "CDR_CONVERSION", 3, "COMPLETED", done3, 22L, null, null),
                new ImportedStep("HEAD_APPROVAL", "HEAD_APPROVAL", 4, "ACTIVE", null, 23L, "Waiting for the principal", null),
                new ImportedStep("NOT_A_STAGE", "NOT_A_STAGE", 10, "COMPLETED", done3, 22L, null, null)));
        OrderWorkflowView view = as(() -> asSuper(), () -> service.view(order));
        assertEquals(4, view.progress().currentStep(), "it resumes at Head Approval");
        assertEquals("HEAD_APPROVAL", view.status());
        for (int n = 1; n <= 3; n++) assertEquals("COMPLETED", step(view, n).status());
        assertEquals(done2.toInstant(), step(view, 2).completedAt().toInstant());
        assertEquals(22L, step(view, 2).completedBy());
        assertEquals(now, step(view, 4).startedAt().toInstant(), "the resumed stage's clock starts on the engine");
        assertEquals(List.of("Production update: Three drafts shared", "School liked v2"),
                step(view, 2).notes().stream().map(NoteEntry::body).sorted().toList());
        assertEquals(List.of("Production update: Waiting for the principal"), step(view, 4).notes().stream().map(NoteEntry::body).toList());
        assertEquals(List.of("TRACKER_CREATED", "STEP_IMPORTED", "STEP_IMPORTED", "STEP_IMPORTED"), actions(order));
        assertTrue(actionNotes(order, "STEP_IMPORTED").contains("PRODUCT_WORKFLOW_V36"));
        assertEquals("PRODUCT_WORKFLOW_V36", owner.sql("SELECT imported_from FROM workflow.workflow_notebook_trackers WHERE order_id = :o")
                .param("o", order).query(String.class).single());
        assertEquals(List.of(order + ":HEAD_APPROVAL"), PUSHED);
        assertEquals(new CatalogOrderGateway.WorkflowStep(FE, 4, "Head Approval", 9, 3), PUSHED_STEPS.get(0));

        // An import whose every stage was completed arrives finished.
        List<ImportedStep> all = new ArrayList<>();
        for (StepSpec spec : TEMPLATES.get(FE).steps()) all.add(new ImportedStep(spec.code(), spec.code(), spec.order(), "COMPLETED", done3, 22L, null, null));
        String finished = create(FE, true, 10L, OP_A, null, null, all);
        OrderWorkflowView closed = as(() -> asSuper(), () -> service.view(finished));
        assertTrue(closed.progress().finished());
        assertEquals("COMPLETED", closed.status());
        assertEquals("COMPLETED", closed.instanceStatus());
    }

    @Test
    void concurrentCreateReturnsTheExistingWorkflow() {
        String order = "ORD-RACE-" + ORDERS.incrementAndGet();
        CreateWorkflow placed = new CreateWorkflow(order, 10, true, 1000, PLACED, "Asia/Kolkata", 77L, OP_A, null, FE, "FLIERS", "FE",
                "flier", null, List.of());
        AtomicReference<OrderWorkflowService.Created> winner = new AtomicReference<>();
        // A second request for the same order commits after this one looked and found none, so this one's insert hits the keys.
        OrderWorkflowRepository racing = new OrderWorkflowRepository(JdbcClient.create(dataSource), MAPPER, "workflow") {
            @Override public long insertInstance(long schoolId, String orderId, String definitionId, int currentStep, String status, Long by) {
                if (winner.get() == null) winner.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> service.create(placed)).join());
                return super.insertInstance(schoolId, orderId, definitionId, currentStep, status, by);
            }
        };
        asNobody();
        OrderWorkflowService.Created result = serviceWith(racing, new StaffNames(DIRECTORY, CLOCK)).create(placed);
        assertTrue(winner.get().created());
        assertFalse(result.created(), "the loser reports the workflow that already exists");
        assertEquals(winner.get().instanceId(), result.instanceId());
        assertEquals(1L, owner.sql("SELECT count(*) FROM workflow.workflow_instances WHERE entity_id = :order").param("order", order)
                .query(Long.class).single(), "the losing attempt left nothing behind");
    }

    // ==== gates (D19) ===================================================================================================

    /** Every notebook gate through the service: Complete lists every unmet requirement until each is met. */
    @Test
    void everyNotebookGateRefusesCompleteListingEveryRequirement() {
        String order = create(NB, true, 10L, OP_A);
        asSuper();
        assertEquals(List.of("FIELDS_PRESENT", "ALL_CHECKED"), unmet(order, 1));
        save(order, 1, Map.of("orderPdfLink", LINK));
        assertEquals(List.of("ALL_CHECKED"), unmet(order, 1));
        save(order, 1, Map.of("introChecks", List.of(0)));
        complete(order, 1);

        DRAFT_IDS.clear();
        assertEquals(List.of("DESIGN_FILES_PRESENT"), unmet(order, 2));
        DRAFT_IDS.add(501L);
        complete(order, 2);
        assertTrue(DRAFT_FETCH_IN_TRANSACTION.stream().noneMatch(Boolean::booleanValue), "design files are counted with no lock held");

        assertEquals(List.of("FIELDS_PRESENT", "ALL_CHECKED"), unmet(order, 3));
        save(order, 3, Map.of("cdrLink", CDR, "cdrChecks", List.of(0, 1, 2)));
        complete(order, 3);

        assertEquals(List.of("HEAD_APPROVED"), unmet(order, 4));
        service.approveDesign(order, "Matches the school's sample", version(order, 4));
        complete(order, 4);

        assertEquals(List.of("ALL_CHECKED", "VENDORS_SELECTED", "VENDORS_SELECTED", "VENDORS_SELECTED", "VENDORS_SELECTED"), unmet(order, 5));
        service.setVendorSelections(order, Map.of("BOARD_SUPPLIERS", List.of(1L), "LAMINATION_VENDORS", List.of(2L),
                "BINDERS", List.of(3L)), service.view(order).version());
        assertEquals(List.of("ALL_CHECKED", "VENDORS_SELECTED"), unmet(order, 5));
        service.setVendorSelections(order, Map.of("BOARD_SUPPLIERS", List.of(1L), "LAMINATION_VENDORS", List.of(2L),
                "BINDERS", List.of(3L), "OFFSET_PRINTERS", List.of(4L)), service.view(order).version());
        save(order, 5, Map.of("planningChecks", List.of(0, 1, 2)));
        complete(order, 5);

        assertEquals(List.of("FIELDS_PRESENT"), unmet(order, 6));
        save(order, 6, Map.of("boardBillLink", LINK));
        complete(order, 6);

        assertEquals(List.of("ALL_CHECKED", "FIELDS_PRESENT", "CHOICE_IN", "SIGNED_OFF"), unmet(order, 7));
        save(order, 7, Map.of("checks", List.of(0, 1, 2, 3, 4, 5, 6), "sampleLink", LINK, "schoolVerification", "PENDING"));
        assertEquals(List.of("CHOICE_IN", "SIGNED_OFF"), unmet(order, 7));
        save(order, 7, Map.of("schoolVerification", "VERIFIED"));
        assertEquals(List.of("SIGNED_OFF"), unmet(order, 7));
        OrderWorkflowView signed = service.signOff(order, 7, version(order, 7));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), ((List<?>) step(signed, 7).data().get("checks")).stream()
                .map(value -> ((Number) value).intValue()).toList(), "the sign-off ticks item 8 (D20)");
        assertEquals(SUPER, step(signed, 7).signOff().by());
        complete(order, 7);

        for (int n : new int[]{8, 9, 10}) {
            assertEquals(List.of("ALL_CHECKED"), unmet(order, n), "step " + n);
            satisfy(order, n);
            complete(order, n);
        }

        assertEquals(List.of("FIELDS_PRESENT", "FIELDS_PRESENT", "FIELDS_PRESENT"), unmet(order, 11));
        save(order, 11, Map.of("varianceReportLink", LINK, "deliveryChallanLink", LINK, "billsLink", LINK,
                "producedCount", 1000, "acceptedCount", 990, "dispatchCount", 980));
        assertEquals(List.of("COUNTS_CONSISTENT"), unmet(order, 11), "mismatched counts need a discrepancy note");
        save(order, 11, Map.of("discrepancyNote", "Ten covers damaged in lamination"));
        complete(order, 11);

        assertEquals(List.of("FIELDS_PRESENT", "FIELDS_PRESENT", "FIELDS_PRESENT", "SIGNED_OFF"), unmet(order, 12));
        save(order, 12, Map.of("transporter", "VRL Logistics", "dispatchDate", "2026-10-30", "proofLink", LINK, "invoiceStatus", "INITIATED"));
        service.signOff(order, 12, version(order, 12));
        OrderWorkflowView finished = complete(order, 12);
        assertTrue(finished.progress().finished());
        assertEquals("COMPLETED", finished.status());
        assertEquals("COMPLETED", finished.instanceStatus());
        // Superadmin can still edit the invoice status after completion (§2.7 Step 12); the dispatch it signed off is unchanged,
        // so the finished stage keeps its consignment sign-off.
        OrderWorkflowView paid = save(order, 12, Map.of("invoiceStatus", "PAID", "invoiceNumber", "INV-2026-118"));
        assertEquals("PAID", step(paid, 12).data().get("invoiceStatus"));
        assertNotNull(step(paid, 12).signOff(), "the invoice moving on keeps the consignment sign-off");
        assertEquals(SUPER, step(paid, 12).signOff().by());
        assertFalse(actionNotes(order, "STEP_DATA_SAVED").contains("signOffVoided"));
        // Changed dispatch evidence is not what was signed off.
        OrderWorkflowView moved = save(order, 12, Map.of("proofLink", LINK + "-reissued"));
        assertNull(step(moved, 12).signOff(), "a changed proof link voids it");
        assertTrue(actionNotes(order, "STEP_DATA_SAVED").contains("\"signOffVoided\":true"));
        TenantContext.clear();
        assertEquals(order + ":COMPLETED", PUSHED.get(PUSHED.size() - 1));
        assertEquals(new CatalogOrderGateway.WorkflowStep(NB, 12, "Delivery & Invoicing", 12, 12), PUSHED_STEPS.get(PUSHED_STEPS.size() - 1));
    }

    @Test
    void everyFlierGateRefusesCompleteListingEveryRequirement() {
        String order = create(FE, true, 10L, OP_A);
        advance(order, 4);
        asSuper();
        assertEquals(List.of("VENDORS_SELECTED"), unmet(order, 5));
        // Any one category does for a nine-step item (D33).
        service.setVendorSelections(order, Map.of("FLEX_PRINTERS", List.of(6L)), service.view(order).version());
        complete(order, 5);
        assertEquals(List.of("ALL_CHECKED"), unmet(order, 6));
        satisfy(order, 6);
        complete(order, 6);
        assertEquals(List.of("ALL_CHECKED"), unmet(order, 7));
        satisfy(order, 7);
        complete(order, 7);
        assertEquals(List.of("ALL_CHECKED", "FIELDS_PRESENT", "SIGNED_OFF"), unmet(order, 8));
        assertEquals(WorkflowRuleViolation.INVALID_STEP_DATA,
                codeOf(() -> save(order, 8, Map.of("finalValidationNotes", "word ".repeat(101)))), "final validation notes are at most 100 words");
        satisfy(order, 8);
        complete(order, 8);
        assertEquals(List.of("FIELDS_PRESENT", "FIELDS_PRESENT", "FIELDS_PRESENT", "CHOICE_IN", "SIGNED_OFF"), unmet(order, 9));
        save(order, 9, Map.of("transporter", "Courier", "dispatchDate", "2026-10-30", "proofLink", LINK, "deliveryStatus", "PENDING"));
        service.signOff(order, 9, version(order, 9));
        assertEquals(List.of("CHOICE_IN"), unmet(order, 9), "the order must be delivered");
        // The change voids the sign-off, so it is recorded again after it.
        save(order, 9, Map.of("deliveryStatus", "DELIVERED"));
        assertEquals(List.of("SIGNED_OFF"), unmet(order, 9));
        service.signOff(order, 9, version(order, 9));
        assertTrue(complete(order, 9).progress().finished());
    }

    /**
     * Superadmin may rename a vendor category (D31, "Batch Makers" to "Badge Makers"). The vendor gate then names it as the
     * directory names it now, both in Complete's refusal and in the unmet list the stage shows under its disabled button.
     */
    @Test
    void theVendorGateNamesACategoryAsTheDirectoryNamesItNow() {
        String order = create(NB, true, 10L, OP_A);
        advance(order, 4);
        asSuper();
        RENAMED_CATEGORIES.put("BINDERS", "Binding Partners");
        try {
            WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class, () -> complete(order, 5));
            List<String> messages = refused.unmet().stream().map(GateFailure::message).toList();
            assertTrue(messages.contains("Select a vendor in Binding Partners"), messages.toString());
            assertTrue(messages.contains("Select a vendor in Board Suppliers"), "a category not renamed keeps its SOP name: " + messages);
            assertTrue(messages.stream().noneMatch(message -> message.contains("Binders")), messages.toString());

            List<String> shown = step(service.view(order), 5).unmet().stream().map(GateFailure::message).toList();
            assertTrue(shown.contains("Select a vendor in Binding Partners"), shown.toString());
        } finally {
            RENAMED_CATEGORIES.clear();
        }
    }

    // ==== editing and the order of stages (D18) =========================================================================

    /** "Bulk printing blocked": Printing Press checks cannot be ticked until Pre-Print Quality Check is complete. */
    @Test
    void printingPressCannotBeTickedWhilePrePrintQualityCheckIsActive() {
        String order = create(NB, true, 10L, OP_A);
        advance(order, 6);
        asOperator(OP_A, 10L);
        long printing = version(order, 8);
        WorkflowRuleViolation refused = assertThrows(WorkflowRuleViolation.class,
                () -> service.saveStepData(order, 8, Map.of("pressChecks", List.of(0, 1)), printing));
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE, refused.code());
        assertEquals("Opens for editing when Pre-Print Quality Check is completed", refused.getMessage());
        assertFalse(step(service.view(order), 8).can().canEdit());
        assertTrue(step(service.view(order), 7).can().canEdit());
        TenantContext.clear();
        // Nobody edits a locked stage, superadmin included.
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE,
                as(() -> asSuper(), () -> codeOf(() -> service.saveStepData(order, 8, Map.of("pressChecks", List.of(0)), printing))));
        // An operator never edits a completed stage; superadmin does.
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE, as(() -> asOperator(OP_A, 10L), () -> codeOf(() -> save(order, 6, Map.of("boardBillLink", CDR)))));
        assertEquals(CDR, step(as(() -> asSuper(), () -> save(order, 6, Map.of("boardBillLink", CDR))), 6).data().get("boardBillLink"));
    }

    @Test
    void aSaveIsPartialValidatedAndVersioned() {
        String order = create(NB, true, 10L, OP_A);
        String wholesale = create(NB, false, 10L, OP_A);
        asOperator(OP_A, 10L);
        long loaded = version(order, 1);
        service.saveStepData(order, 1, Map.of("orderPdfLink", LINK), loaded);
        assertEquals(OrderWorkflowService.VERSION_CONFLICT, codeOf(() -> service.saveStepData(order, 1, Map.of("introChecks", List.of(0)), loaded)),
                "a stale screen is refused");
        OrderWorkflowView ticked = save(order, 1, Map.of("introChecks", List.of(0)));
        assertEquals(LINK, step(ticked, 1).data().get("orderPdfLink"), "a save keeps what it does not name");
        assertEquals(WorkflowRuleViolation.INVALID_STEP_DATA, codeOf(() -> save(order, 1, Map.of("orderPdfLink", "http://insecure.example.com"))));
        assertEquals(WorkflowRuleViolation.INVALID_STEP_DATA, codeOf(() -> save(order, 1, Map.of("unknownField", "x"))));
        TenantContext.clear();
        assertEquals(WorkflowRuleViolation.STEP_NOT_ON_PATH, as(() -> asSuper(),
                () -> codeOf(() -> service.saveStepData(wholesale, 2, Map.of(), 0))), "wholesale has no step 2");
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("orderPdfLink", null);
        assertNull(step(as(() -> asOperator(OP_A, 10L), () -> save(order, 1, cleared)), 1).data().get("orderPdfLink"), "null clears");
    }

    // ==== Head Approval (D21, D22) =======================================================================================

    @Test
    void requestChangesReopensDesigningActivityAndLocksTheLaterStages() {
        String order = create(NB, true, 10L, OP_A);
        advance(order, 3);
        asSuper();
        assertEquals("SENT", service.view(order).design().state(), "the design reached Head Approval");
        assertEquals(WorkflowRuleViolation.NOTE_REQUIRED, codeOf(() -> service.requestDesignChanges(order, " ", version(order, 4))));
        now = now.plusSeconds(3600);
        OrderWorkflowView back = service.requestDesignChanges(order, "The logo is the old one", version(order, 4));
        assertEquals(2, back.progress().currentStep());
        assertEquals("ACTIVE", step(back, 2).status());
        assertEquals(now, step(back, 2).startedAt().toInstant(), "Designing Activity's clock restarts");
        assertEquals(now.plusSeconds(72 * 3600), step(back, 2).dueAt().toInstant());
        assertEquals("LOCKED", step(back, 3).status());
        assertEquals("LOCKED", step(back, 4).status());
        assertEquals(CDR, step(back, 3).data().get("cdrLink"), "saved data is kept");
        assertEquals("CHANGES_REQUESTED", back.design().state());
        assertEquals("The logo is the old one", back.design().comment());
        assertTrue(back.can().canManageDesignFiles());
        assertEquals(order + ":DESIGNING_ACTIVITY", PUSHED.get(PUSHED.size() - 1));
        assertTrue(actions(order).contains("CHANGES_REQUESTED"));
        TenantContext.clear();
        // The operator of Designing Activity may change the files again (D26).
        assertTrue(service.designFileAccess(order, OP_A, "OPERATIONS").allowed());
        // Completing Designing Activity and CDR Conversion again sends the design back for a decision.
        OrderWorkflowView again = advance(order, 3);
        assertEquals("SENT", again.design().state());
        assertEquals(4, again.progress().currentStep());
    }

    @Test
    void anApprovalBindsTheCdrLinkAndGoesStaleWhenItChanges() {
        String order = create(FE, true, 10L, OP_A);
        String bare = create(FE, true, 10L, OP_A);
        advance(order, 3);
        asSuper();
        long seen = version(order, 4);
        OrderWorkflowView approved = service.approveDesign(order, "Matches the school's brief", seen);
        assertEquals("APPROVED", approved.design().state());
        assertEquals(CDR, approved.design().approvedCdrLink());
        assertEquals(SUPER, approved.design().decidedBy());
        assertTrue(actions(order).contains("HEAD_APPROVED"));

        // Superadmin changes the link on the completed CDR stage: the approval no longer counts.
        save(order, 3, Map.of("cdrLink", "https://drive.example.com/cdr/v2"));
        WorkflowRuleViolation stale = assertThrows(WorkflowRuleViolation.class, () -> complete(order, 4));
        assertEquals(List.of(new GateFailure("HEAD_APPROVED", "The CDR link changed after approval; approve the current link")), stale.unmet());
        // A screen that loaded Head Approval before the link changed is refused, so nobody approves a link they did not see.
        assertEquals(WorkflowRuleViolation.HEAD_APPROVAL_STALE, codeOf(() -> service.approveDesign(order, "Approve again", seen)));
        OrderWorkflowView reapproved = service.approveDesign(order, "Approve the new link", version(order, 4));
        assertEquals("https://drive.example.com/cdr/v2", reapproved.design().approvedCdrLink());
        assertEquals(5, complete(order, 4).progress().currentStep());
        assertEquals(WorkflowRuleViolation.STEP_NOT_ACTIVE, codeOf(() -> service.approveDesign(order, "Again", version(order, 4))));

        // Approve is refused without a CDR link.
        TenantContext.clear();
        advance(bare, 3);
        asSuper();
        Map<String, Object> noLink = new HashMap<>();
        noLink.put("cdrLink", null);
        save(bare, 3, noLink);
        assertEquals(OrderWorkflowService.CDR_LINK_MISSING, codeOf(() -> service.approveDesign(bare, "Approve", version(bare, 4))));
        assertFalse(service.view(bare).can().canApproveDesign());
        // Only superadmin decides.
        TenantContext.clear();
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, 10L),
                () -> codeOf(() -> service.approveDesign(bare, "Approve", version(bare, 4)))));
    }

    /** D22: "Not verified - changes needed" lets superadmin send the order back to Designing Activity with a reason. */
    @Test
    void prePrintCanSendTheOrderBackToDesigningActivity() {
        String order = create(NB, true, 10L, OP_A);
        advance(order, 6);
        asSuper();
        save(order, 7, Map.of("schoolVerification", "CHANGES_REQUIRED"));
        assertEquals(WorkflowRuleViolation.NOTE_REQUIRED, codeOf(() -> service.reopenStep(order, 2, "", version(order, 2))));
        OrderWorkflowView back = service.reopenStep(order, 2, "The school wants the house colours changed", version(order, 2));
        assertEquals(2, back.progress().currentStep());
        for (int n = 3; n <= 12; n++) assertEquals("LOCKED", step(back, n).status());
        assertEquals("NOT_SENT", back.design().state(), "the approval is taken back");
        assertNull(back.design().approvedCdrLink());
        assertEquals("The school wants the house colours changed", actionNotes(order, "SENT_BACK"));
        // An operator cannot reopen.
        TenantContext.clear();
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, 10L), () -> codeOf(() -> service.reopenStep(order, 1, "x", version(order, 1)))));
    }

    @Test
    void aSignOffIsVoidedByAChangeAndAReopenTakesItBack() {
        String order = create(NB, true, 10L, OP_A);
        advance(order, 6);
        asSuper();
        save(order, 7, Map.of("checks", List.of(0, 1, 2, 3, 4, 5, 6), "sampleLink", LINK, "schoolVerification", "VERIFIED"));
        assertNotNull(step(service.signOff(order, 7, version(order, 7)), 7).signOff());
        OrderWorkflowView unchanged = save(order, 7, Map.of("schoolVerification", "VERIFIED"));
        assertNotNull(step(unchanged, 7).signOff(), "saving the same values keeps it");
        OrderWorkflowView changed = save(order, 7, Map.of("qcComments", "Margins re-checked"));
        assertNull(step(changed, 7).signOff(), "a change voids it (D20)");
        assertFalse(((List<?>) step(changed, 7).data().get("checks")).stream().anyMatch(value -> ((Number) value).intValue() == 7));
        assertTrue(actionNotes(order, "STEP_DATA_SAVED").contains("signOffVoided"));
        assertEquals(OrderWorkflowService.NO_SIGN_OFF, codeOf(() -> service.signOff(order, 6, version(order, 6))));
        service.signOff(order, 7, version(order, 7));
        complete(order, 7);
        OrderWorkflowView reopened = service.reopenStep(order, 7, "Wrong board GSM", version(order, 7));
        assertNull(step(reopened, 7).signOff(), "what the reopened stage signed off is taken back");
        TenantContext.clear();
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, 10L), () -> codeOf(() -> service.signOff(order, 7, version(order, 7)))));
    }

    // ==== who may do what (D42) ==========================================================================================

    @Test
    void operatorsWorkOnlyTheStagesAssignedToThem() {
        String order = create(FE, true, 10L, OP_A);
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, as(() -> asOperator(OP_B, 10L),
                () -> codeOf(() -> save(order, 1, Map.of("orderPdfLink", LINK)))));
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, as(() -> asOperator(OP_B, 10L), () -> codeOf(() -> complete(order, 1))));
        OrderWorkflowView seen = as(() -> asOperator(OP_B, 10L), () -> service.view(order));
        assertFalse(step(seen, 1).can().canEdit(), "another operator of the school may look but not work");
        assertTrue(seen.can().canAddNote(), "but may add notes and log calls (D35, D38)");
        assertTrue(as(() -> asOperator(OP_A, 10L), () -> step(save(order, 1, Map.of("orderPdfLink", LINK)), 1).can().canEdit()));
        // Head Approval is completed by superadmin only, even by its assignee.
        advance(order, 3);
        asSuper();
        service.approveDesign(order, "Fine", version(order, 4));
        TenantContext.clear();
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, 10L), () -> codeOf(() -> complete(order, 4))));
        // An operator who may only read cannot act.
        Runnable readOnly = () -> TenantContext.set(new TenantContext(OP_A, "a@x", "OPERATIONS", 10L, null,
                Set.of("notebook-tracker:read"), Set.of(10L)));
        assertNotNull(as(readOnly, () -> service.view(order)));
        assertFalse(as(readOnly, () -> service.view(order)).can().canAddNote());
        assertEquals(403, as(readOnly, () -> http(() -> service.addNote(order, "a note", null))));
    }

    @Test
    void anItemScopedOperatorSeesOnlyTheirItemsAtTheirSchools() {
        String notebook = create(NB, true, 10L, OP_C);
        String flier = create(FE, true, 10L, OP_C);
        String elsewhere = create(FE, true, 20L, OP_C);
        Runnable fliersAt10 = () -> asScopedOperator(OP_C, "10:FLIERS");
        assertEquals(FE, as(fliersAt10, () -> service.view(flier)).definitionId());
        assertEquals(404, as(fliersAt10, () -> http(() -> service.view(notebook))), "an FE scope does not open the school's notebooks");
        assertEquals(404, as(fliersAt10, () -> http(() -> service.view(elsewhere))));
        assertEquals(404, as(fliersAt10, () -> http(() -> service.saveStepData(notebook, 1, Map.of("orderPdfLink", LINK), 0))));
        assertTrue(found(fliersAt10, flier).contains(flier));
        assertFalse(found(fliersAt10, notebook).contains(notebook));
        assertFalse(found(fliersAt10, elsewhere).contains(elsewhere));
        OrderWorkflowPage page = as(fliersAt10, () -> service.list(new ListQuery(null, null, null, null, null, null, null, true, null, 100, 0)));
        assertFalse(page.items().isEmpty());
        assertTrue(page.items().stream().allMatch(item -> item.categoryCode().equals("FLIERS") && item.schoolId() == 10L));
        assertTrue(page.counts().open() >= 1);
        // A notebook scope at the same school is the other way round.
        assertEquals(404, as(() -> asScopedOperator(OP_C, "10:NOTEBOOKS"), () -> http(() -> service.view(flier))));
        assertEquals(NB, as(() -> asScopedOperator(OP_C, "10:NOTEBOOKS"), () -> service.view(notebook)).definitionId());
    }

    @Test
    void schoolAdminsAndAnonymousCallersAreRefused() {
        String order = create(NB, true, 10L, OP_A);
        for (Runnable login : List.<Runnable>of(() -> asSchoolAdmin(10L), OrderWorkflowServiceIntegrationTest::asNobody)) {
            assertEquals(403, as(login, () -> http(() -> service.view(order))));
            assertEquals(403, as(login, () -> http(() -> service.list(ListQuery.all()))));
            assertEquals(403, as(login, () -> http(() -> service.saveStepData(order, 1, Map.of(), 0))));
            assertEquals(403, as(login, () -> http(() -> service.addNote(order, "x", null))));
            assertEquals(403, as(login, () -> http(() -> service.templates())));
        }
    }

    @Test
    void rowLevelSecurityScopesOperatorsToTheirSchools() {
        String at10 = create(NB, true, 10L, OP_A);
        String at20 = create(NB, true, 20L, OP_B);
        assertTrue(found(() -> asOperator(OP_A, 10L), at10).contains(at10));
        assertFalse(found(() -> asOperator(OP_A, 10L), at20).contains(at20));
        assertTrue(as(() -> asOperator(OP_A, 10L), () -> service.list(new ListQuery(null, null, null, null, null, null, null, true, null, 100, 0)))
                .items().stream().allMatch(item -> item.schoolId() == 10L));
        assertEquals(404, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.view(at20))));
        // The request's own school id (an operator's branch) is admitted by row-level security, but the scope still decides.
        assertEquals(404, as(() -> TenantContext.set(new TenantContext(OP_A, "a@x", "OPERATIONS", 20L, null, OPERATIONS, Set.of(10L))),
                () -> http(() -> service.view(at20))));
        assertTrue(found(OrderWorkflowServiceIntegrationTest::asSuper, at10).contains(at10));
        assertTrue(found(OrderWorkflowServiceIntegrationTest::asSuper, at20).contains(at20));
    }

    // ==== assignment and claiming (D37) ==================================================================================

    @Test
    void anEligibleOperatorTakesAnUnassignedOrder() {
        String order = create(FE, true, 10L, null);
        String another = create(FE, true, 10L, null);
        assertTrue(as(() -> asOperator(OP_A, 10L), () -> service.view(order)).can().canClaim());
        assertEquals(403, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.claim(order))), "identity says not eligible");
        ELIGIBLE.add(OP_A + ":10:FLIERS");
        identityDown = true;
        assertEquals(503, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.claim(order))));
        identityDown = false;
        OrderWorkflowView claimed = as(() -> asOperator(OP_A, 10L), () -> service.claim(order));
        assertTrue(claimed.steps().stream().allMatch(s -> Objects.equals(OP_A, s.assigneeUserId())));
        assertFalse(claimed.can().canClaim());
        assertTrue(actions(order).contains("ORDER_CLAIMED"));
        ELIGIBLE.add(OP_B + ":10:FLIERS");
        assertEquals(OrderWorkflowService.ORDER_ASSIGNED, as(() -> asOperator(OP_B, 10L), () -> codeOf(() -> service.claim(order))));
        assertEquals(409, as(() -> asSuper(), () -> http(() -> service.claim(another))), "superadmin assigns instead");
    }

    @Test
    void superadminAssignsEligibleOperatorsPerOrderOrPerStage() {
        String order = create(FE, true, 10L, null);
        assertEquals(400, as(() -> asSuper(), () -> http(() -> service.assignOrder(order, OP_B))), "not eligible for FLIERS at school 10");
        ELIGIBLE.add(OP_B + ":10:FLIERS");
        OrderWorkflowView assigned = as(() -> asSuper(), () -> service.assignOrder(order, OP_B));
        assertTrue(assigned.steps().stream().allMatch(s -> Objects.equals(OP_B, s.assigneeUserId())));
        assertEquals(OP_B, assigned.assigneeUserId());
        ELIGIBLE.add(OP_C + ":10:FLIERS");
        assertEquals(OP_C, step(as(() -> asSuper(), () -> service.assignStep(order, 4, OP_C)), 4).assigneeUserId());
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_B, 10L), () -> codeOf(() -> service.assignOrder(order, OP_B))));
        advance(order, 1);
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE, as(() -> asSuper(), () -> codeOf(() -> service.assignStep(order, 1, OP_C))));
    }

    // ==== vendors, designer, contacts (D33, D36) ==========================================================================

    @Test
    void vendorsAreSelectedAtProductionPlanningByItsAssigneeOrBySuperadmin() {
        String order = create(NB, true, 10L, OP_A);
        Map<String, List<Long>> four = Map.of("BOARD_SUPPLIERS", List.of(1L), "LAMINATION_VENDORS", List.of(2L), "BINDERS", List.of(3L),
                "OFFSET_PRINTERS", List.of(4L));
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE, as(() -> asOperator(OP_A, 10L),
                () -> codeOf(() -> service.setVendorSelections(order, four, service.view(order).version()))), "not before Production Planning");
        advance(order, 4);
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, as(() -> asOperator(OP_B, 10L),
                () -> codeOf(() -> service.setVendorSelections(order, four, service.view(order).version()))));
        asOperator(OP_A, 10L);
        assertTrue(service.view(order).can().canSelectVendors());
        long version = service.view(order).version();
        assertEquals(400, http(() -> service.setVendorSelections(order, Map.of("DIGITAL_PRINTERS", List.of(5L)), version)),
                "a notebook order picks from its four categories");
        assertEquals(400, http(() -> service.setVendorSelections(order, Map.of("BINDERS", List.of(1L)), version)), "not a binder");
        assertEquals(400, http(() -> service.setVendorSelections(order, Map.of("OFFSET_PRINTERS", List.of(7L)), version)), "inactive");
        assertEquals(400, http(() -> service.setVendorSelections(order, Map.of("BINDERS", List.of(3L, 3L)), version)));
        OrderWorkflowView selected = service.setVendorSelections(order, four, version);
        assertEquals(List.of(1L), selected.vendorSelections().get("BOARD_SUPPLIERS"));
        assertEquals(List.of("City Binders", "Gloss Lamination", "Offset One", "Shree Boards"),
                selected.groups().vendors().stream().map(VendorSummary::name).toList(), "later stages show the selected vendors");
        assertEquals(OrderWorkflowService.VERSION_CONFLICT, codeOf(() -> service.setVendorSelections(order, four, version)));
        assertTrue(actionNotes(order, "VENDORS_SELECTED").contains("BOARD_SUPPLIERS"));
        TenantContext.clear();
        advance(order, 5);
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE, as(() -> asOperator(OP_A, 10L),
                () -> codeOf(() -> service.setVendorSelections(order, four, service.view(order).version()))), "only while it is active");
        // Superadmin may change them at any time, and an order keeps a vendor deactivated after it was chosen.
        owner.sql("UPDATE workflow.workflow_notebook_trackers SET vendor_selections = '{\"OFFSET_PRINTERS\":[7]}'::jsonb WHERE order_id = :o")
                .param("o", order).update();
        OrderWorkflowView kept = as(() -> asSuper(), () -> service.setVendorSelections(order,
                Map.of("OFFSET_PRINTERS", List.of(4L, 7L)), service.view(order).version()));
        assertEquals(List.of(4L, 7L), kept.vendorSelections().get("OFFSET_PRINTERS"));
        assertFalse(kept.groups().vendors().stream().filter(v -> v.id() == 7L).findFirst().orElseThrow().active());
        assertFalse(RESOLVED_IN_TRANSACTION.isEmpty());
    }

    @Test
    void theDesignerAndContactsAreSetByTheCurrentAssigneeOrSuperadmin() {
        String order = create(FE, true, 10L, OP_A);
        String fresh = create(FE, true, 10L, OP_A);
        String wholesale = create(NB, false, 10L, OP_A);
        String name = "Designer " + ORDERS.incrementAndGet();
        long designer = as(() -> asSuper(), () -> service.createDesigner(name, "98450 11111")).id();
        assertEquals(409, as(() -> asSuper(), () -> http(() -> service.createDesigner(name, "98450 11111"))));
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, as(() -> asOperator(OP_B, 10L), () -> codeOf(() -> service.setDesigner(order, designer))));
        OrderWorkflowView chosen = as(() -> asOperator(OP_A, 10L), () -> service.setDesigner(order, designer));
        assertEquals(name, chosen.designer().name());
        assertTrue(actionNotes(order, "DESIGNER_SET").contains(String.valueOf(designer)));
        as(() -> asSuper(), () -> service.updateDesigner(designer, name, "98450 11111", false));
        assertEquals(400, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.setDesigner(fresh, designer))),
                "an inactive designer is not chosen anew");
        assertFalse(as(() -> asOperator(OP_A, 10L), () -> service.designers()).stream().anyMatch(d -> d.id() == designer),
                "operators see active designers only");
        assertTrue(as(() -> asSuper(), () -> service.designers()).stream().anyMatch(d -> d.id() == designer));
        assertEquals(409, as(() -> asSuper(), () -> http(() -> service.setDesigner(wholesale, null))),
                "a wholesale notebook has no design stage");

        OrderWorkflowView contacts = as(() -> asOperator(OP_A, 10L), () -> service.setContacts(order,
                new ContactsChange(" Mrs Rao ", "+91 98450 12345", "Factory Head", "080-2345 6789")));
        assertEquals(new OrderWorkflowView.OrderContacts("Mrs Rao", "+91 98450 12345", "Factory Head", "080-2345 6789"), contacts.contacts());
        assertTrue(actionNotes(order, "CONTACTS_SAVED").contains("schoolContactName"));
        assertEquals(400, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.setContacts(order,
                new ContactsChange(null, null, "Factory Head", null)))), "a factory contact has a name and a phone");
        assertEquals(400, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.setContacts(order,
                new ContactsChange(null, "call me", null, null)))));
        assertEquals(WorkflowRuleViolation.NOT_ASSIGNEE, as(() -> asOperator(OP_B, 10L), () -> codeOf(() -> service.setContacts(order,
                new ContactsChange("x", null, null, null)))));
    }

    // ==== notes (D38) ===================================================================================================

    @Test
    void notesAreAppendOnlyEntriesForAnyoneWhoCanWorkTheOrder() {
        String order = create(FE, true, 10L, OP_A);
        NoteEntry internal = as(() -> asOperator(OP_B, 10L), () -> service.addNote(order, "  Principal prefers calls after 2  ", null));
        assertEquals("Principal prefers calls after 2", internal.body());
        assertNull(internal.stepOrder());
        assertEquals(OP_B, internal.authorUserId());
        NoteEntry stage = as(() -> asOperator(OP_B, 10L), () -> service.addNote(order, "Waiting for the PDF", 1));
        assertEquals(1, stage.stepOrder());
        assertEquals(WorkflowRuleViolation.STEP_NOT_EDITABLE, as(() -> asOperator(OP_A, 10L),
                () -> codeOf(() -> service.addNote(order, "Too early", 3))), "a stage not reached takes no notes");
        assertEquals(WorkflowRuleViolation.NOTE_TOO_LONG, as(() -> asOperator(OP_A, 10L),
                () -> codeOf(() -> service.addNote(order, "word ".repeat(101), null))));
        assertEquals(WorkflowRuleViolation.NOTE_REQUIRED, as(() -> asOperator(OP_A, 10L), () -> codeOf(() -> service.addNote(order, " ", null))));
        OrderWorkflowView view = as(() -> asSuper(), () -> service.view(order));
        assertEquals(List.of("Principal prefers calls after 2"), view.internalNotes().stream().map(NoteEntry::body).toList());
        assertEquals(List.of("Waiting for the PDF"), step(view, 1).notes().stream().map(NoteEntry::body).toList());
    }

    // ==== the list (design §4.2) =========================================================================================

    @Test
    void theListFiltersSearchesSortsPagesAndCounts() {
        long school = 30;
        String a = create(NB, true, school, OP_A, "NB-DPS_ROH-26-10-19-0001", "Delhi Public School Rohini", List.of());
        String b = create(FE, true, school, null, "FE-DPS_ROH-26-10-19-0001", "Delhi Public School Rohini", List.of());
        String c = create(FE, true, school, OP_A, "FE-DPS_ROH-26-10-19-0002", "Delhi Public School Rohini", List.of());
        String d = create(NB, false, school, OP_A, "NB-DPS_ROH-26-10-19-0002", "Delhi Public School Rohini", List.of());
        advance(c, 1);
        advance(d, 12);
        now = now.plusSeconds(3 * 3600); // 12:30 in Kolkata: steps 1 of a and b (due 11:30) are overdue.

        java.util.function.Function<ListQuery, List<String>> ids = query -> as(() -> asSuper(), () -> service.list(query)).items().stream()
                .map(OrderWorkflowListItem::orderId).toList();
        assertEquals(Set.of(a, b, c), Set.copyOf(ids.apply(new ListQuery(null, null, null, null, school, null, null, null, null, null, null))));
        assertEquals(Set.of(a, b, c, d), Set.copyOf(ids.apply(new ListQuery(null, null, null, null, school, null, null, true, null, null, null))));
        assertEquals(Set.of(b, c), Set.copyOf(ids.apply(new ListQuery("FE", null, null, null, school, null, null, null, null, null, null))));
        assertEquals(Set.of(a, b, c), Set.copyOf(ids.apply(new ListQuery("NB,FE", null, null, null, school, null, null, null, null, null, null))));
        assertEquals(List.of(c), ids.apply(new ListQuery(null, null, null, null, school, null, "dps_roh-26-10-19-0002", null, null, null, null)),
                "search is case-insensitive on the order number");
        assertEquals(List.of(a), ids.apply(new ListQuery(null, null, null, null, school, null, a.toLowerCase(), null, null, null, null)),
                "and on the CK id");
        assertEquals(Set.of(a, b, c), Set.copyOf(ids.apply(new ListQuery(null, null, null, null, school, null, "ROHINI", null, null, null, null))),
                "and on the school name");
        assertEquals(List.of(), ids.apply(new ListQuery(null, null, null, null, school, null, "100%_", null, null, null, null)),
                "wildcards are literal");
        assertEquals(List.of(c), ids.apply(new ListQuery(null, "DESIGNING_ACTIVITY", null, null, school, null, null, null, null, null, null)));
        assertEquals(Set.of(a, b, c), Set.copyOf(ids.apply(new ListQuery(null, null, "INTAKE", null, school, null, null, null, null, null, null))));
        assertEquals(List.of(), ids.apply(new ListQuery(null, null, "DELIVERY", null, school, null, null, null, null, null, null)));
        assertEquals(List.of(b), ids.apply(new ListQuery(null, null, null, "unassigned", school, null, null, null, null, null, null)));
        assertEquals(Set.of(a, c), Set.copyOf(as(() -> asOperator(OP_A, school), () -> service.list(new ListQuery(null, null, null, "me",
                school, null, null, null, null, null, null))).items().stream().map(OrderWorkflowListItem::orderId).toList()));
        assertEquals(403, as(() -> asOperator(OP_A, school), () -> http(() -> service.list(new ListQuery(null, null, null,
                String.valueOf(OP_B), school, null, null, null, null, null, null)))), "only superadmin filters by another operator");
        assertEquals(Set.of(a, b), Set.copyOf(ids.apply(new ListQuery(null, null, null, null, school, "OVERDUE", null, null, null, null, null))));
        assertEquals(List.of(c), ids.apply(new ListQuery(null, null, null, null, school, "ON_TRACK", null, null, null, null, null)));
        assertEquals(List.of(b, c, a), ids.apply(new ListQuery(null, null, null, null, school, null, null, null, "number", null, null)));

        OrderWorkflowPage first = as(() -> asSuper(), () -> service.list(new ListQuery(null, null, null, null, school, null, null, null, "number", 2, 0)));
        assertEquals(List.of(b, c), first.items().stream().map(OrderWorkflowListItem::orderId).toList());
        assertEquals(3, first.total());
        assertEquals(List.of(a), as(() -> asSuper(), () -> service.list(new ListQuery(null, null, null, null, school, null, null, null,
                "number", 2, 2))).items().stream().map(OrderWorkflowListItem::orderId).toList());
        assertEquals(new OrderWorkflowView.OrderWorkflowCounts(3, 2, 2, 1, 0), first.counts(),
                "open, overdue, due today (in the school's zone), unassigned, mine");
        assertEquals(2, as(() -> asOperator(OP_A, school), () -> service.list(new ListQuery(null, null, null, null, school, null, null, null,
                null, null, null))).counts().mine());

        OrderWorkflowListItem row = as(() -> asSuper(), () -> service.list(new ListQuery(null, null, null, null, school, null, null, null,
                null, 100, 0))).items().stream().filter(item -> item.orderId().equals(c)).findFirst().orElseThrow();
        assertEquals("DESIGNING_ACTIVITY", row.status());
        assertEquals("INTAKE", row.phase());
        assertEquals(2, row.currentStep());
        assertEquals("Designing Activity", row.currentTitle());
        assertEquals("ON_TRACK", row.slaState());
        assertEquals(1, row.completed());
        assertEquals(9, row.total());
        assertEquals("FE", row.itemCode());
        OrderWorkflowListItem finished = as(() -> asSuper(), () -> service.list(new ListQuery(null, null, null, null, school, null, null, true,
                null, 100, 0))).items().stream().filter(item -> item.orderId().equals(d)).findFirst().orElseThrow();
        assertEquals("COMPLETED", finished.status());
        assertEquals("DELIVERY", finished.phase());
        assertNull(finished.currentStep());
        assertEquals("NO_TIMER", finished.slaState());
        assertEquals(9, finished.completed());

        for (ListQuery bad : List.of(new ListQuery("nb", null, null, null, null, null, null, null, null, null, null),
                new ListQuery(null, "printing", null, null, null, null, null, null, null, null, null),
                new ListQuery(null, null, "LATER", null, null, null, null, null, null, null, null),
                new ListQuery(null, null, null, "someone", null, null, null, null, null, null, null),
                new ListQuery(null, null, null, null, null, "LATE", null, null, null, null, null),
                new ListQuery(null, null, null, null, null, null, null, null, "oldest", null, null),
                new ListQuery(null, null, null, null, null, null, null, null, null, 101, null),
                new ListQuery(null, null, null, null, null, null, null, null, null, null, -1))) {
            assertEquals(400, as(() -> asSuper(), () -> http(() -> service.list(bad))), bad.toString());
        }
    }

    /**
     * The "Due today" chip filters (§4.2): {@code sla=DUE_TODAY} lists exactly the orders the {@code dueToday} count counts,
     * an active step due on today's date in the order's own time zone, overdue or not.
     */
    @Test
    void dueTodayListsExactlyTheOrdersTheChipCountsInEachOrdersTimeZone() {
        long school = 33;
        String laterToday = create(FE, true, school, OP_A);
        String earlierToday = create(FE, true, school, null);
        String tomorrow = create(FE, true, school, OP_A);
        String yesterday = create(FE, true, school, OP_A);
        String noTimer = create(FE, true, school, OP_A);
        String westward = create(FE, true, school, OP_A);
        String finished = create(NB, false, school, OP_A);
        advance(finished, 12);
        // now: Monday 19 October, 09:30 in Kolkata (04:00 UTC); still Sunday 18 October, 21:00, in Los Angeles.
        activeStepDue(laterToday, "2026-10-19T17:00:00Z");   // Monday 22:30 in Kolkata: due today, not overdue yet
        activeStepDue(earlierToday, "2026-10-18T20:00:00Z"); // Monday 01:30 in Kolkata, Sunday in UTC: due today and overdue
        activeStepDue(tomorrow, "2026-10-19T19:00:00Z");     // Tuesday 00:30 in Kolkata, still Monday in UTC
        activeStepDue(yesterday, "2026-10-18T10:00:00Z");    // Sunday 15:30 in Kolkata: overdue since yesterday
        activeStepDue(noTimer, null);
        owner.sql("UPDATE workflow.workflow_notebook_trackers SET time_zone = 'America/Los_Angeles' WHERE order_id = :order")
                .param("order", westward).update();
        activeStepDue(westward, "2026-10-18T10:00:00Z");     // Sunday 03:00 in Los Angeles, where it is still Sunday: overdue

        java.util.function.Function<ListQuery, OrderWorkflowPage> superadmin = query -> as(() -> asSuper(), () -> service.list(query));
        java.util.function.Function<OrderWorkflowPage, Set<String>> ids = page -> page.items().stream()
                .map(OrderWorkflowListItem::orderId).collect(java.util.stream.Collectors.toSet());

        OrderWorkflowPage due = superadmin.apply(new ListQuery(null, null, null, null, school, "DUE_TODAY", null, null, null, null, null));
        assertEquals(Set.of(laterToday, earlierToday, westward), ids.apply(due));
        assertEquals(3, due.counts().dueToday());
        assertEquals(due.counts().dueToday(), due.total(), "the filter lists what the chip counts");
        assertEquals(Set.of(laterToday, earlierToday, westward), ids.apply(superadmin.apply(new ListQuery(null, null, null, null, school,
                "DUE_TODAY", null, true, null, null, null))), "a finished order is never due today");
        assertEquals(Set.of(earlierToday), ids.apply(superadmin.apply(new ListQuery(null, null, null, "unassigned", school, "DUE_TODAY",
                null, null, null, null, null))), "it narrows with the other filters");
        assertEquals(Set.of(laterToday, westward), ids.apply(as(() -> asOperator(OP_A, school), () -> service.list(new ListQuery(null,
                null, null, "me", school, "DUE_TODAY", null, null, null, null, null)))), "and for an operator");
        assertEquals(Set.of(earlierToday, yesterday, westward), ids.apply(superadmin.apply(new ListQuery(null, null, null, null, school,
                "OVERDUE", null, null, null, null, null))), "overdue is unchanged: past due, whatever the day");
    }

    /** Sets the due time of the order's active step directly (null: no timer), as the database owner. */
    static void activeStepDue(String order, String dueAt) {
        assertEquals(1, owner.sql("UPDATE workflow.workflow_instance_steps s SET due_at = CAST(:due AS timestamptz) "
                        + "FROM workflow.workflow_notebook_trackers t WHERE t.instance_id = s.instance_id AND t.order_id = :order "
                        + "AND s.status = 'ACTIVE'")
                .param("due", dueAt == null ? null : java.time.OffsetDateTime.parse(dueAt)).param("order", order).update(), order);
    }

    // ==== the order's status in the catalogue (§3.6) ====================================================================

    @Test
    void theStatusPushCarriesTheStepAndFollowsOnlyStageChanges() {
        String order = create(FE, true, 10L, OP_A);
        PUSHED.clear();
        PUSHED_STEPS.clear();
        asOperator(OP_A, 10L);
        save(order, 1, Map.of("orderPdfLink", LINK, "introChecks", List.of(0)));
        service.addNote(order, "Introduced myself", null);
        assertTrue(PUSHED.isEmpty(), "saves and notes do not move the stage");
        complete(order, 1);
        assertEquals(List.of(order + ":DESIGNING_ACTIVITY"), PUSHED);
        assertEquals(new CatalogOrderGateway.WorkflowStep(FE, 2, "Designing Activity", 9, 1), PUSHED_STEPS.get(0));
        asSuper();
        service.reopenStep(order, 1, "Wrong PDF", version(order, 1));
        assertEquals(order + ":ORDER_DETAILS", PUSHED.get(PUSHED.size() - 1));
        assertTrue(PUSHED_VERSIONS.get(1) > PUSHED_VERSIONS.get(0), "a reopen carries a newer version");
    }

    @Test
    void aFailedStatusPushNeverUndoesTheUsersActionAndTheRelayDeliversItLater() {
        String order = create(FE, true, 10L, OP_A);
        PUSHED.clear();
        PUSHED_STEPS.clear();
        catalogDown = true;
        asOperator(OP_A, 10L);
        save(order, 1, Map.of("orderPdfLink", LINK, "introChecks", List.of(0)));
        assertEquals(2, complete(order, 1).progress().currentStep(), "the user's action stands although the push failed");
        TenantContext.clear();
        assertTrue(PUSHED.isEmpty());
        catalogDown = false;
        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(List.of(order + ":DESIGNING_ACTIVITY"), PUSHED);
        assertEquals(new CatalogOrderGateway.WorkflowStep(FE, 2, "Designing Activity", 9, 1), PUSHED_STEPS.get(0),
                "the relay reports the step too, from the order's template");

        // A closed order's status is dropped, not retried.
        catalogClosed = true;
        advance(order, 2);
        assertTrue(queued(order).isEmpty());
    }

    // ==== design files (D26) and the live list (D39) =====================================================================

    @Test
    void designFileAccessFollowsTheDesignStageItsAssigneeAndTheChangeRequest() {
        String order = create(FE, true, 10L, OP_A);
        String wholesale = create(NB, false, 10L, OP_A);
        assertFalse(service.designFileAccess(order, OP_A, "OPERATIONS").allowed(), "Designing Activity is still locked");
        advance(order, 1);
        assertTrue(service.designFileAccess(order, OP_A, "OPERATIONS").allowed());
        assertEquals("Designing Activity is assigned to another operator", service.designFileAccess(order, OP_B, "OPERATIONS").reason());
        assertTrue(service.designFileAccess(order, SUPER, "SUPERADMIN").allowed());
        assertFalse(service.designFileAccess(order, 5L, "ADMIN").allowed());
        assertTrue(as(() -> asOperator(OP_A, 10L), () -> service.view(order)).can().canManageDesignFiles());
        advance(order, 2);
        assertFalse(service.designFileAccess(order, OP_A, "OPERATIONS").allowed(), "closed once Designing Activity is done");
        assertTrue(service.designFileAccess(order, SUPER, "SUPERADMIN").allowed(), "superadmin may still correct them");
        assertEquals("Design files do not apply to this order", service.designFileAccess(wholesale, SUPER, "SUPERADMIN").reason());
        assertEquals("This order has no workflow yet", service.designFileAccess("ORD-NONE", SUPER, "SUPERADMIN").reason());
    }

    @Test
    void theViewCountsDesignFilesAfterCommitAndDerivesTheLiveList() {
        String order = create(FE, true, 10L, OP_A);
        String elsewhere = create(FE, true, 10L, OP_A);
        advance(order, 1);
        DRAFT_IDS.clear();
        DRAFT_FETCH_IN_TRANSACTION.clear();
        CALLBACKS.put(instanceId(order), List.of(new OpenCallback(9, "SCHOOL", "Mrs Rao", 2, OffsetDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC))));
        OrderWorkflowView view = as(() -> asSuper(), () -> service.view(order));
        assertEquals(List.of(new GateFailure("DESIGN_FILES_PRESENT", "Upload at least one design file")), step(view, 2).unmet());
        // The ticked row first; then by due time: the callback (in an hour), the stage deadline and its gate (in 72 hours).
        assertEquals(List.of(UpcomingActions.Kind.LAST_COMPLETED, UpcomingActions.Kind.CALLBACK, UpcomingActions.Kind.STAGE_DEADLINE,
                        UpcomingActions.Kind.UNMET_GATE),
                view.upcomingActions().stream().map(UpcomingActions.UpcomingAction::kind).toList());
        assertEquals("Order Details completed", view.upcomingActions().get(0).title());
        assertEquals("Call back School · Mrs Rao", view.upcomingActions().get(1).title());
        assertEquals("Complete Designing Activity", view.upcomingActions().get(2).title());
        assertEquals("Upload at least one design file", view.upcomingActions().get(3).title());
        assertEquals(List.of(false), DRAFT_FETCH_IN_TRANSACTION, "counted once, after the read committed");

        DRAFT_IDS.add(501L);
        OrderWorkflowView uploaded = as(() -> asSuper(), () -> service.view(order));
        assertEquals(List.of(), step(uploaded, 2).unmet());
        assertTrue(uploaded.upcomingActions().stream().noneMatch(action -> action.kind() == UpcomingActions.Kind.UNMET_GATE));
        // A view on another stage does not ask the catalogue.
        DRAFT_FETCH_IN_TRANSACTION.clear();
        as(() -> asSuper(), () -> service.view(elsewhere));
        assertTrue(DRAFT_FETCH_IN_TRANSACTION.isEmpty());
        // An unanswered catalogue leaves the conservative answer and the view still loads.
        catalogDown = true;
        assertEquals(1, step(as(() -> asSuper(), () -> service.view(order)), 2).unmet().size());
    }

    // ==== templates and time slots (D2, D23) =============================================================================

    @Test
    void templatesCarryTheirCurrentDefaultSlasWhichSuperadminEdits() {
        List<WorkflowTemplate> all = as(() -> asOperator(OP_A, 10L), () -> service.templates());
        assertEquals(WorkflowTemplates.DEFINITION_IDS, all.stream().map(WorkflowTemplate::definitionId).toList());
        assertEquals(Sla.hours(72), as(() -> asOperator(OP_A, 10L), () -> service.template(FE)).step(2).orElseThrow().defaultSla());
        assertEquals(404, as(() -> asSuper(), () -> http(() -> service.template("NOPE"))));
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, 10L), () -> codeOf(() -> service.setDefaultSla(FE, 6, Sla.hours(30)))));
        try {
            WorkflowTemplate changed = as(() -> asSuper(), () -> service.setDefaultSla(FE, 6, Sla.hours(30)));
            assertEquals(Sla.hours(30), changed.step(6).orElseThrow().defaultSla());
            assertNull(as(() -> asSuper(), () -> service.setDefaultSla(FE, 7, null)).step(7).orElseThrow().defaultSla(), "no timer");
            assertEquals(Sla.hours(24), as(() -> asSuper(), () -> service.template(WorkflowTemplates.BILL_BOOK)).step(6).orElseThrow().defaultSla(),
                    "other templates keep theirs");
            assertEquals(404, as(() -> asSuper(), () -> http(() -> service.setDefaultSla(FE, 10, Sla.hours(3)))));
        } finally {
            as(() -> asSuper(), () -> service.setDefaultSla(FE, 6, Sla.hours(24)));
            as(() -> asSuper(), () -> service.setDefaultSla(FE, 7, Sla.hours(72)));
        }
    }

    @Test
    void timeSlotsArePerSchoolAndTemplateAndApplyToStagesThatStartAfterwards() {
        long school = 40;
        as(() -> asSuper(), () -> { service.setTimeSlot(school, FE, 2, Sla.hours(48)); return null; });
        try {
            String order = create(FE, true, school, OP_A);
            OrderWorkflowView next = advance(order, 1);
            assertEquals(now.plusSeconds(48 * 3600), step(next, 2).dueAt().toInstant());
            assertEquals(new OrderWorkflowView.EffectiveSla(48, "HOURS", "SCHOOL"), step(next, 2).sla());
            List<TimeSlotRow> fe = as(() -> asOperator(OP_A, school), () -> service.timeSlots(school, FE));
            assertEquals(new TimeSlotRow(2, "DESIGNING_ACTIVITY", "Designing Activity", Sla.hours(72),
                    new OrderWorkflowView.EffectiveSla(48, "HOURS", "SCHOOL")), fe.get(1));
            assertEquals(9, fe.size());
            List<TimeSlotRow> nb = as(() -> asOperator(OP_A, school), () -> service.timeSlots(school, NB));
            assertEquals(new OrderWorkflowView.EffectiveSla(72, "HOURS", "DEFAULT"), nb.get(1).effective(), "the notebook template is untouched");
            assertEquals(12, nb.size());
            assertEquals(403, as(() -> asOperator(OP_A, 10L), () -> http(() -> service.timeSlots(school, FE))));
            assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, school),
                    () -> codeOf(() -> service.setTimeSlot(school, FE, 2, Sla.hours(1)))));
            assertEquals(400, as(() -> asSuper(), () -> http(() -> service.setTimeSlot(school, FE, 10, Sla.hours(1)))));
            assertEquals(400, as(() -> asSuper(), () -> http(() -> service.timeSlots(school, "NOPE"))));
            as(() -> asSuper(), () -> { service.setTimeSlot(school, FE, 3, null); return null; });
            assertNull(as(() -> asSuper(), () -> service.timeSlots(school, FE)).get(2).effective(), "an empty override is no timer");
        } finally {
            as(() -> asSuper(), () -> { service.clearTimeSlot(school, FE, 2); service.clearTimeSlot(school, FE, 3); return null; });
        }
        assertEquals("DEFAULT", as(() -> asSuper(), () -> service.timeSlots(school, FE)).get(1).effective().source());
    }

    // ==== history (D40) and the generic workflow API ====================================================================

    @Test
    void everyChangeIsRecordedAndTheIntroductionNamesTheItem() {
        String order = create(GENERAL, true, 10L, OP_A);
        asOperator(OP_A, 10L);
        save(order, 1, Map.of("orderPdfLink", LINK));
        assertTrue(actionNotes(order, "STEP_DATA_SAVED").contains("\"fields\":[\"orderPdfLink\"]"), "the changed field names, never the values");
        assertFalse(actionNotes(order, "STEP_DATA_SAVED").contains(LINK));
        // The tick saves the message as the operator sent it (name filled in, edited by hand), and that is what is recorded (D28).
        String sent = "My name is Asha, and I will be handling your certificate order for the annual day. Please reach out any time.";
        save(order, 1, Map.of("introChecks", List.of(0), "introText", " " + sent + " "));
        assertTrue(actionNotes(order, "STEP_DATA_SAVED").contains("\"introChecks\":\"1/1\""));
        assertEquals(sent, actionNotes(order, "INTRO_SENT"));
        save(order, 1, Map.of("introChecks", List.of(0)));
        assertEquals(1, actions(order).stream().filter("INTRO_SENT"::equals).count(), "recorded once, when it is ticked");
        assertEquals(WorkflowRuleViolation.INVALID_STEP_DATA, codeOf(() -> save(order, 1, Map.of("introText", "x".repeat(1001)))),
                "the message as sent is capped at 1,000 characters");
        complete(order, 1);
        TenantContext.clear();
        assertEquals(List.of("TRACKER_CREATED", "STEP_DATA_SAVED", "STEP_DATA_SAVED", "INTRO_SENT", "STEP_COMPLETED"), actions(order),
                "a save that repeats the saved values records nothing");

        // An older screen that sends no text records the template's message, with the item named.
        String older = create(GENERAL, true, 10L, OP_A);
        asOperator(OP_A, 10L);
        save(older, 1, Map.of("introChecks", List.of(0)));
        assertEquals("My name is {Name}, and I will be handling your certificate order. Please reach out for any queries, updates, or follow-ups.",
                actionNotes(older, "INTRO_SENT"));
        TenantContext.clear();
    }

    /** School admins hold workflow:read for their school's ordinary workflows; the generic reads still never find an order workflow. */
    @Test
    void theGenericWorkflowApiNeverFindsAnOrderWorkflow() {
        String order = create(FE, true, 10L, OP_A);
        long id = instanceId(order);
        WorkflowReadRepository generic = new WorkflowReadRepository(JdbcClient.create(dataSource), "workflow");
        for (Runnable login : List.<Runnable>of(() -> asSchoolAdmin(10L), OrderWorkflowServiceIntegrationTest::asSuper)) {
            assertTrue(as(login, () -> generic.instance(id)).isEmpty());
            assertTrue(as(login, () -> generic.actions(id)).isEmpty());
            assertFalse(as(login, () -> generic.instances(10L, null, null, 500)).stream().anyMatch(row -> row.id().equals(id)));
            assertTrue(as(login, () -> generic.instances(10L, null, "ITEM_ORDER", 500)).isEmpty());
            assertEquals(404, as(login, () -> http(() -> generic.complete(id, Map.of("expectedVersion", 0L, "actorId", SUPER)))));
            assertEquals(404, as(login, () -> http(() -> generic.createOrGetInstance(Map.of("entityType", "ITEM_ORDER", "entityId", order,
                    "schoolId", 10L, "definitionId", FE)))));
        }
    }

    // ==== staff names (never asked while a transaction holds a connection) =============================================

    @Test
    void theViewCarriesStaffNamesLookedUpAfterCommit() {
        STAFF.put(OP_A, "Asha Operator");
        STAFF.put(SUPER, "Sam Super");
        OrderWorkflowService named = serviceWith(new StaffNames(DIRECTORY, CLOCK));
        String order = create(FE, true, 10L, OP_A);
        NoteEntry note = as(() -> asSuper(), () -> named.addNote(order, "Approved the vendor", null));
        assertEquals("Sam Super", note.authorName());
        advance(order, 1);
        OrderWorkflowView view = as(() -> asOperator(OP_A, 10L), () -> named.view(order));
        assertEquals("Asha Operator", view.assigneeName());
        assertEquals("Asha Operator", step(view, 2).assigneeName());
        assertEquals("Sam Super", step(view, 1).completedByName());
        assertEquals("Sam Super", step(view, 1).dataSavedByName());
        assertEquals(List.of("Sam Super"), view.internalNotes().stream().map(NoteEntry::authorName).toList());
        assertFalse(NAME_CALLS.isEmpty());
        assertTrue(NAME_CALLS.stream().allMatch(call -> call.endsWith(" active=0 transaction=false")), NAME_CALLS.toString());

        OrderWorkflowPage page = as(() -> asSuper(), () -> serviceWith(new StaffNames(DIRECTORY, CLOCK)).list(new ListQuery(null, null, null,
                null, 10L, null, order, null, null, 100, 0)));
        assertEquals("Asha Operator", page.items().get(0).assigneeName());
    }

    /** The school's own groups, with row-level security: superadmin keeps them, school-wide operators read them. */
    @Test
    void aSchoolsOwnGroupsAreKeptBySuperadminAndReadBySchoolWideOperators() {
        long school = 50;
        Map<String, String> saved = as(() -> asSuper(), () -> service.setSchoolWhatsAppGroupLink(school, "SCHOOL_GROUP",
                " HTTPS://chat.whatsapp.com/AbCdEf1234567890 "));
        assertEquals(Map.of("SCHOOL_GROUP", "https://chat.whatsapp.com/AbCdEf1234567890"), saved);
        assertEquals(saved, as(() -> asOperator(OP_A, school), () -> service.schoolWhatsAppGroupLinks(school)));
        assertEquals(403, as(() -> asScopedOperator(OP_A, school + ":FLIERS"), () -> http(() -> service.schoolWhatsAppGroupLinks(school))));
        assertEquals(WorkflowRuleViolation.SUPERADMIN_ONLY, as(() -> asOperator(OP_A, school),
                () -> codeOf(() -> service.setSchoolWhatsAppGroupLink(school, "SCHOOL_GROUP", ""))));
        assertEquals(WorkflowRuleViolation.INVALID_INVITE, as(() -> asSuper(),
                () -> codeOf(() -> service.setSchoolWhatsAppGroupLink(school, "SCHOOL_GROUP", "https://example.com/AbCdEf1234567890"))));
        assertEquals(400, as(() -> asSuper(), () -> http(() -> service.setSchoolWhatsAppGroupLink(school, "PRODUCT_VENDOR",
                "https://chat.whatsapp.com/AbCdEf1234567890"))));
        assertEquals(Map.of(), as(() -> asSuper(), () -> service.setSchoolWhatsAppGroupLink(school, "SCHOOL_GROUP", " ")));
    }

    // ==== status delivery: a retried, versioned outbox (§3.6) ===========================================================

    record Queued(String status, long trackerVersion, int attempts) { }

    static java.util.Optional<Queued> queued(String order) {
        return owner.sql("SELECT status, tracker_version, attempts FROM workflow.workflow_tracker_status_outbox WHERE order_id = :order")
                .param("order", order)
                .query((rs, n) -> new Queued(rs.getString("status"), rs.getLong("tracker_version"), rs.getInt("attempts"))).optional();
    }

    static long instanceVersion(String order) {
        return owner.sql("SELECT version FROM workflow.workflow_instances WHERE id = :id").param("id", instanceId(order)).query(Long.class).single();
    }

    /** Lets the backoff of a failed attempt elapse. */
    static void due(String order) {
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET next_attempt_at = now() - interval '1 second' WHERE order_id = :order")
                .param("order", order).update();
    }

    static boolean deadLettered(String order) {
        return owner.sql("SELECT dead_lettered_at IS NOT NULL FROM workflow.workflow_tracker_status_outbox WHERE order_id = :order")
                .param("order", order).query(Boolean.class).single();
    }

    static String lastError(String order) {
        return owner.sql("SELECT last_error FROM workflow.workflow_tracker_status_outbox WHERE order_id = :order")
                .param("order", order).query(String.class).single();
    }

    static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture(Class<?> type) {
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    static void release(Class<?> type, ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(type)).detachAppender(appender);
    }

    /** The event as the JSON encoder writes it to Cloud Logging, whose log metrics read these fields. */
    static tools.jackson.databind.JsonNode json(ch.qos.logback.classic.spi.ILoggingEvent event) {
        var encoder = new net.logstash.logback.encoder.LogstashEncoder();
        encoder.setContext((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            return MAPPER.readTree(new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            encoder.stop();
        }
    }

    /** A wholesale notebook order completed through Order Details by its operator: its status is Production Planning. */
    static String atPlanning(boolean catalogueDown) {
        String order = create(NB, false, 10L, OP_A);
        asSuper();
        satisfy(order, 1);
        TenantContext.clear();
        PUSHED.clear();
        PUSHED_VERSIONS.clear();
        catalogDown = catalogueDown;
        as(() -> asOperator(OP_A, 10L), () -> complete(order, 1));
        return order;
    }

    @Test
    void theStatusIsQueuedInTheStageChangesOwnTransaction() {
        String order = create(NB, false, 10L, OP_A);
        asSuper();
        satisfy(order, 1);
        TenantContext.clear();
        long before = instanceVersion(order);
        catalogDown = true;
        PUSHED.clear();
        OrderWorkflowView done = as(() -> asOperator(OP_A, 10L), () -> complete(order, 1));
        assertEquals(5, done.progress().currentStep(), "the user's action stands although the push failed");
        Queued queued = queued(order).orElseThrow(() -> new AssertionError("the stage change queued its status"));
        assertEquals("PRODUCTION_PLANNING", queued.status());
        assertEquals(instanceVersion(order), queued.trackerVersion(), "the instance version after the change");
        assertTrue(queued.trackerVersion() > before);
        assertEquals(1, queued.attempts(), "one push was tried after commit");

        // When the status cannot be queued, the stage does not move: they commit together.
        owner.sql("CREATE FUNCTION workflow.test_refuse_status() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''outbox refused''; END'").update();
        owner.sql("CREATE TRIGGER test_refuse_status BEFORE INSERT OR UPDATE ON workflow.workflow_tracker_status_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION workflow.test_refuse_status()").update();
        try {
            asSuper();
            satisfy(order, 5);
            long loaded = version(order, 5);
            assertThrows(RuntimeException.class, () -> service.completeStep(order, 5, loaded));
        } finally {
            TenantContext.clear();
            owner.sql("DROP TRIGGER test_refuse_status ON workflow.workflow_tracker_status_outbox").update();
            owner.sql("DROP FUNCTION workflow.test_refuse_status()").update();
        }
        assertEquals(5, as(() -> asSuper(), () -> service.view(order)).progress().currentStep(), "rolled back together with the status");
        assertEquals("PRODUCTION_PLANNING", queued(order).orElseThrow().status());
    }

    @Test
    void aNewerVersionReplacesTheQueuedStatusAndAnOlderOneNever() {
        String order = atPlanning(true);
        Queued first = queued(order).orElseThrow();
        due(order);
        statuses.drain(10);
        assertEquals(2, queued(order).orElseThrow().attempts(), "the relay retried it");
        advance(order, 5);
        Queued replaced = queued(order).orElseThrow();
        assertEquals("BOARD_PROCUREMENT", replaced.status());
        assertTrue(replaced.trackerVersion() > first.trackerVersion());
        assertEquals(1, replaced.attempts(), "the newer status was due at once and was tried after its own commit");
        as(OrderWorkflowServiceIntegrationTest::asSuper, () -> transactions.execute(status -> {
            statuses.record(order, 10L, "PRODUCTION_PLANNING", first.trackerVersion());
            return null;
        }));
        assertEquals(replaced, queued(order).orElseThrow());
        catalogDown = false;
        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(List.of(order + ":BOARD_PROCUREMENT"), PUSHED, "only the newest status is delivered");
        assertTrue(queued(order).isEmpty());
    }

    @Test
    void aRowSurvivesWhenANewerVersionArrivesWhileAnOlderOneIsInFlight() {
        String order = create(NB, false, 10L, OP_A);
        asSuper();
        satisfy(order, 1);
        TenantContext.clear();
        PUSHED.clear();
        // While Order Details' status is in flight, superadmin completes Production Planning, whose status cannot be delivered yet.
        DURING_PUSH.set(() -> {
            TenantContext operator = TenantContext.get();
            catalogDown = true;
            try {
                asSuper();
                satisfy(order, 5);
                complete(order, 5);
            } finally {
                catalogDown = false;
                TenantContext.set(operator);
            }
        });
        as(() -> asOperator(OP_A, 10L), () -> complete(order, 1));
        assertEquals(List.of(order + ":PRODUCTION_PLANNING"), PUSHED);
        Queued survivor = queued(order).orElseThrow(() -> new AssertionError("delivering the older version must not delete the newer one"));
        assertEquals("BOARD_PROCUREMENT", survivor.status());
        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(order + ":BOARD_PROCUREMENT", PUSHED.get(PUSHED.size() - 1));
        assertTrue(queued(order).isEmpty());
    }

    @Test
    void aStatusDeadLettersAfterItsLastAttemptAndANewerVersionRevivesIt() {
        String order = atPlanning(true);
        long version = queued(order).orElseThrow().trackerVersion();
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET attempts = 47 WHERE order_id = :order").param("order", order).update();
        due(order);
        var logs = capture(TrackerStatusOutbox.class);
        try {
            assertEquals(0, statuses.drain(10));
        } finally {
            release(TrackerStatusOutbox.class, logs);
        }
        assertEquals(48, queued(order).orElseThrow().attempts());
        assertTrue(deadLettered(order));
        assertEquals("catalogue is down", lastError(order));
        var errors = logs.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR).toList();
        assertEquals(1, errors.size(), logs.list.toString());
        var logged = json(errors.get(0));
        assertEquals("tracker_status_dead_lettered", logged.path("event").asString());
        assertEquals(order, logged.path("orderId").asString());
        assertEquals(version, logged.path("trackerVersion").asLong());

        catalogDown = false;
        due(order);
        assertEquals(0, statuses.drain(10), "the drain skips a dead-lettered row");
        assertFalse(statuses.attempt(order), "and so does a direct attempt");
        assertTrue(PUSHED.isEmpty());

        catalogDown = true;
        advance(order, 5);
        assertFalse(deadLettered(order), "a newer status revives the row");
        assertEquals("BOARD_PROCUREMENT", queued(order).orElseThrow().status());
        assertEquals(1, queued(order).orElseThrow().attempts());
        catalogDown = false;
        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(List.of(order + ":BOARD_PROCUREMENT"), PUSHED);
    }

    /** School-core's 404 (the order was deleted) can never be delivered: the row is dropped with a structured log line. */
    @Test
    void aDeletedOrdersStatusIsDroppedNotDeadLettered() {
        String order = create(NB, false, 10L, OP_A);
        asSuper();
        satisfy(order, 1);
        TenantContext.clear();
        PUSHED.clear();
        catalogGone = true;
        var logs = capture(TrackerStatusOutbox.class);
        try {
            as(() -> asOperator(OP_A, 10L), () -> complete(order, 1));
        } finally {
            release(TrackerStatusOutbox.class, logs);
        }
        assertTrue(queued(order).isEmpty(), "dropped, neither retried nor dead-lettered");
        assertTrue(PUSHED.isEmpty());
        var dropped = logs.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
        assertEquals(1, dropped.size(), logs.list.toString());
        var logged = json(dropped.get(0));
        assertEquals("tracker_status_dropped", logged.path("event").asString());
        assertEquals(404, logged.path("httpStatus").asInt());
    }

    @Test
    void theOutboxHealthReportCountsTheStatusOutbox() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner").schemas("firefighting").defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting").load().migrate();
        String waiting = atPlanning(true);
        String stuck = atPlanning(true);
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET created_at = now() - interval '2 hours', updated_at = now() "
                + "WHERE order_id = :order").param("order", waiting).update();
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET dead_lettered_at = now(), created_at = now() - interval '9 hours' "
                + "WHERE order_id = :order").param("order", stuck).update();
        long outboxPending = owner.sql("SELECT count(*) FROM firefighting.outbox_events WHERE published_at IS NULL AND dead_lettered_at IS NULL")
                .query(Long.class).single();
        long outboxDead = owner.sql("SELECT count(*) FROM firefighting.outbox_events WHERE dead_lettered_at IS NOT NULL").query(Long.class).single();
        var reporter = new com.custoking.ims.operationsservice.outbox.OutboxAsyncHealthReporter(owner, "firefighting");
        reporter.configureBacklogs(List.of(statuses));
        var logs = capture(com.custoking.ims.operationsservice.outbox.OutboxAsyncHealthReporter.class);
        try {
            reporter.report();
        } finally {
            release(com.custoking.ims.operationsservice.outbox.OutboxAsyncHealthReporter.class, logs);
        }
        assertEquals(1, logs.list.size(), logs.list.toString());
        var outbox = json(logs.list.get(0)).path("health").path("outbox");
        assertEquals(outboxPending + 1, outbox.path("pendingCount").asLong());
        assertEquals(outboxDead + 1, outbox.path("deadLetterCount").asLong());
        long age = outbox.path("oldestPendingAgeSeconds").asLong();
        assertTrue(age >= 7200 && age < 7300, "the oldest pending status has waited since it was first queued: " + age);
        assertEquals("workflow_tracker_status_outbox", statuses.queueName());
    }
}
