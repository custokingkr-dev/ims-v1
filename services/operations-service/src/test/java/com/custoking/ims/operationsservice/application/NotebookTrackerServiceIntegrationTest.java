package com.custoking.ims.operationsservice.application;

import com.custoking.ims.operationsservice.application.NotebookTrackerService.CreateTracker;
import com.custoking.ims.operationsservice.application.tracker.Sla;
import com.custoking.ims.operationsservice.application.tracker.TrackerRuleViolation;
import com.custoking.ims.operationsservice.application.tracker.TrackerView;
import com.custoking.ims.operationsservice.persistence.NotebookTrackerRepository;
import com.custoking.ims.operationsservice.persistence.TrackerStatusOutbox;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository;
import com.custoking.ims.operationsservice.security.TenantAwareDataSource;
import com.custoking.ims.operationsservice.security.TenantContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.custoking.ims.operationsservice.outbox.OutboxAsyncHealthReporter;
import com.zaxxer.hikari.HikariDataSource;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The tracker's real flows against a real PostgreSQL with row-level security and the runtime role. */
class NotebookTrackerServiceIntegrationTest {
    /**
     * What identity really grants (V4, V5, V6 and V12): an operator with a school-wide assignment holds the OPERATIONS
     * role's codes, which carry {@code workflow:read} but never {@code workflow:act}.
     */
    static final Set<String> OPERATIONS = Set.of("workspace:access", "student:read", "student:create", "student:update",
            "attendance:read", "attendance:manage", "order:read", "order:create", "order:update", "firefighting:read",
            "firefighting:create", "firefighting:update", "timetable:read", "workflow:read", "student:photo-import",
            "student:export", "notebook-tracker:read", "notebook-tracker:act");
    /** An operator whose only assignments are item scopes holds exactly these (owner decision D2). */
    static final Set<String> ITEM_ONLY = Set.of("workspace:access", "notebook-tracker:read", "notebook-tracker:act",
            "order:read", "order:update");
    /** A school admin genuinely holds the generic workflow codes, but the tracker is not theirs. */
    static final Set<String> SCHOOL_ADMIN_WORKFLOW = Set.of("workflow:read", "workflow:act");
    static final long SUPER = 1, OP_A = 10, OP_C = 12, OP_B = 11;

    static PostgreSQLContainer<?> PG;
    static javax.sql.DataSource dataSource;
    /** The database owner, which row-level security does not apply to: for looking at the status outbox directly. */
    static JdbcClient owner;
    static TransactionTemplate transactions;
    static NotebookTrackerService service;
    static TrackerStatusOutbox statuses;
    static Instant now = Instant.parse("2026-10-19T04:00:00Z"); // Monday 09:30 in Kolkata
    static final AtomicInteger ORDERS = new AtomicInteger();
    /** Users identity would call eligible for school 10 notebooks; school 20 has no eligible operators. */
    static final Set<Long> ELIGIBLE_FOR_10 = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static volatile boolean identityDown = false;
    /** Drafts the catalogue would report for any order; default has two current drafts. */
    static final java.util.List<Long> DRAFT_IDS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.List<String> PUSHED = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The tracker version each delivered status in {@link #PUSHED} carried, in the same order. */
    static final java.util.List<Long> PUSHED_VERSIONS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile boolean catalogDown = false;
    /** Runs once, inside the next status push, before it reaches the catalogue: lets a test act while a push is in flight. */
    static final AtomicReference<Runnable> DURING_PUSH = new AtomicReference<>();
    /** Whether a transaction was open at each draft fetch, in order: a fetch inside one would hold the tracker row lock. */
    static final java.util.List<Boolean> DRAFT_FETCH_IN_TRANSACTION = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Runs once, inside the next draft fetch: lets a test act while a design send or approval waits for the catalogue. */
    static final AtomicReference<Runnable> DURING_DRAFT_FETCH = new AtomicReference<>();
    static final Clock CLOCK = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    static final com.custoking.ims.operationsservice.infrastructure.OperatorEligibility ELIGIBILITY = (userId, schoolId, category) -> {
        if (identityDown) throw new IllegalStateException("identity is down");
        return "NOTEBOOKS".equals(category) && schoolId == 10 && ELIGIBLE_FOR_10.contains(userId);
    };
    static com.custoking.ims.operationsservice.infrastructure.CatalogOrderGateway catalog;
    /** The runtime pool, so a peer stub can see how many connections are checked out while it is called. */
    static HikariDataSource pool;
    /** Who identity knows as staff (an active OPERATIONS or SUPERADMIN user), by id. */
    static final Map<Long, String> STAFF = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile boolean directoryDown = false;
    /** One entry per names call: the ids asked about, the pool's active connections and whether a transaction was open. */
    static final List<String> NAME_CALLS = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final com.custoking.ims.operationsservice.infrastructure.StaffDirectory DIRECTORY = ids -> {
        NAME_CALLS.add(ids.stream().sorted().toList() + " active=" + pool.getHikariPoolMXBean().getActiveConnections()
                + " transaction=" + TransactionSynchronizationManager.isActualTransactionActive());
        if (directoryDown) throw new IllegalStateException("identity is down");
        Map<Long, String> found = new java.util.HashMap<>();
        for (Long id : ids) if (STAFF.containsKey(id)) found.put(id, STAFF.get(id));
        return found;
    };
    /** The catalogue answers that the order is closed (school-core's 409 for a cancelled order). */
    static volatile boolean catalogClosed = false;

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
        TenantAwareDataSource ds = new TenantAwareDataSource(pool);
        dataSource = ds;
        DRAFT_IDS.addAll(java.util.List.of(501L, 777L));
        owner = JdbcClient.create(new DriverManagerDataSource(PG.getJdbcUrl(), "owner", "owner"));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(ds);
        transactions = new TransactionTemplate(manager);
        catalog = new com.custoking.ims.operationsservice.infrastructure.CatalogOrderGateway() {
            @Override public java.util.List<DraftImage> draftImages(String orderId) {
                DRAFT_FETCH_IN_TRANSACTION.add(TransactionSynchronizationManager.isActualTransactionActive());
                Runnable during = DURING_DRAFT_FETCH.getAndSet(null);
                if (during != null) during.run();
                if (catalogDown) throw new IllegalStateException("catalogue is down");
                return DRAFT_IDS.stream().map(id -> new DraftImage(id, "image/png", "draft-" + id + ".png")).toList();
            }
            @Override public void pushStatus(String orderId, String status, long trackerVersion) {
                Runnable during = DURING_PUSH.getAndSet(null);
                if (during != null) during.run();
                if (catalogDown) throw new IllegalStateException("catalogue is down");
                if (catalogClosed) throw new OrderClosedException("the order catalogue answered HTTP 409", null);
                PUSHED.add(orderId + ":" + status);
                PUSHED_VERSIONS.add(trackerVersion);
            }
        };
        statuses = new TrackerStatusOutbox(JdbcClient.create(ds), catalog, manager, "workflow");
        // The shared service never finds a name, so only the name tests' own services call the directory stub.
        service = serviceWith(new StaffNames(ids -> Map.of(), CLOCK));
    }

    /** A service with its own name cache, so a test that looks at names starts with nothing cached. */
    static NotebookTrackerService serviceWith(StaffNames names) {
        return new NotebookTrackerService(new NotebookTrackerRepository(JdbcClient.create(dataSource), JsonMapper.builder().build(), "workflow"),
                transactions, CLOCK, ELIGIBILITY, catalog, statuses, names);
    }

    @AfterAll
    static void tearDown() {
        TenantContext.clear();
        if (PG != null) PG.stop();
    }

    @AfterEach
    void reset() {
        TenantContext.clear();
        now = Instant.parse("2026-10-19T04:00:00Z");
        identityDown = false;
        ELIGIBLE_FOR_10.clear();
        catalogDown = false;
        catalogClosed = false;
        STAFF.clear();
        directoryDown = false;
        NAME_CALLS.clear();
        PUSHED.clear();
        PUSHED_VERSIONS.clear();
        DURING_PUSH.set(null);
        DRAFT_FETCH_IN_TRANSACTION.clear();
        DURING_DRAFT_FETCH.set(null);
        // Tests share the database; a status one test left queued must not be delivered by another test's drain.
        owner.sql("DELETE FROM workflow.workflow_tracker_status_outbox").update();
        DRAFT_IDS.clear();
        DRAFT_IDS.addAll(java.util.List.of(501L, 777L));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static void asSuper() { TenantContext.set(new TenantContext(SUPER, "sa@x", "SUPERADMIN", null, null)); }

    /**
     * A school-wide operator as the gateway forwards them: their branch (the first school) is their school id and they
     * hold the OPERATIONS codes. An operator with no assignment at all holds no permission.
     */
    static void asOperator(long user, Long... schools) {
        Long branch = schools.length == 0 ? null : schools[0];
        TenantContext.set(new TenantContext(user, "op" + user + "@x", "OPERATIONS", branch, null,
                schools.length == 0 ? Set.of() : OPERATIONS, Set.of(schools)));
    }

    static void asNobody() { TenantContext.clear(); }

    /** An operator with no school-wide assignment, only item-specific scopes such as "10:NOTEBOOKS": no school id. */
    static void asScopedOperator(long user, String... scopes) {
        TenantContext.set(new TenantContext(user, "op" + user + "@x", "OPERATIONS", null, null, ITEM_ONLY, Set.of(), Set.of(scopes)));
    }

    static <T> T as(Runnable login, Supplier<T> action) {
        login.run();
        try { return action.get(); } finally { TenantContext.clear(); }
    }

    static String create(boolean customised, long school, Long assignee) {
        String order = "ORD-" + ORDERS.incrementAndGet();
        asNobody();
        service.create(new CreateTracker(order, school, customised, 1000, OffsetDateTime.parse("2026-10-19T09:30:00+05:30"),
                "Asia/Kolkata", 77L, assignee));
        return order;
    }

    static TrackerView.Step step(TrackerView view, int number) {
        return view.steps().stream().filter(s -> s.step() == number).findFirst().orElseThrow();
    }

    /** Completes steps as superadmin (who may act on any step), satisfying each gate, up to and including {@code last}. */
    static TrackerView advance(String order, int last) {
        asSuper();
        TrackerView view = service.view(order);
        while (view.progress().currentStep() != null && view.progress().currentStep() <= last) {
            int n = view.progress().currentStep();
            switch (n) {
                case 2 -> service.sendDesign(order);
                case 3 -> service.approveDesign(order, 501, "Looks good");
                case 8, 12 -> service.signOff(order, n, step(service.view(order), n).version());
                case 11 -> {
                    TrackerView.Step s = step(service.view(order), 11);
                    service.saveDraft(order, 11, Map.of("producedCount", 1000, "acceptedCount", 1000, "dispatchCount", 1000), s.version());
                }
                default -> { }
            }
            view = service.view(order);
            service.completeStep(order, n, step(view, n).version());
            view = service.view(order);
        }
        TenantContext.clear();
        return view;
    }

    static String codeOf(Runnable action) {
        TrackerRuleViolation violation = assertThrows(TrackerRuleViolation.class, action::run);
        return violation.code();
    }

    static HttpCode http(Runnable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action::run);
        return new HttpCode(ex.getStatusCode().value());
    }

    record HttpCode(int status) { }

    // ---- creation and visibility -----------------------------------------------------------------------------------

    @Test
    void creationIsIdempotentAndStartsOnlyStepOneWithItsClock() {
        String order = create(true, 10L, OP_A);
        asNobody();
        var again = service.create(new CreateTracker(order, 10, true, 1000, OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 77L, OP_A));
        assertFalse(again.created());

        TrackerView view = as(() -> asOperator(OP_A, 10L), () -> service.view(order));
        assertEquals(12, view.progress().total());
        assertEquals(1, view.progress().currentStep());
        assertEquals("ACTIVE", step(view, 1).status());
        for (int n = 2; n <= 12; n++) assertEquals("LOCKED", step(view, n).status(), "step " + n);
        // Step 1 default is 4 hours from placement on a Monday: due 13:30 Kolkata.
        assertEquals(OffsetDateTime.parse("2026-10-19T13:30:00+05:30").toInstant(), step(view, 1).dueAt().toInstant());
        assertEquals(OP_A, step(view, 1).assigneeUserId());
    }

    @Test
    void anOrderAlreadyTrackedUnderAnotherSchoolIsRejected() {
        String order = create(true, 10L, OP_A);
        asNobody();
        assertEquals(409, http(() -> service.create(new CreateTracker(order, 20, true, 1000,
                OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 77L, null))).status());
    }

    @Test
    void concurrentCreateReturnsExisting() {
        String order = "ORD-RACE-" + ORDERS.incrementAndGet();
        CreateTracker placed = new CreateTracker(order, 10, true, 1000, OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 77L, OP_A);
        AtomicReference<NotebookTrackerService.Created> winner = new AtomicReference<>();
        // A second request for the same order commits its tracker after this one looked and found none, so this one's
        // insert runs into the unique keys.
        NotebookTrackerRepository racing = new NotebookTrackerRepository(JdbcClient.create(dataSource), JsonMapper.builder().build(), "workflow") {
            @Override public long insertInstance(long schoolId, String orderId, int currentStep, Long initiatedBy) {
                if (winner.get() == null) winner.set(java.util.concurrent.CompletableFuture.supplyAsync(() -> service.create(placed)).join());
                return super.insertInstance(schoolId, orderId, currentStep, initiatedBy);
            }
        };
        asNobody();
        NotebookTrackerService.Created result = new NotebookTrackerService(racing, transactions, CLOCK, ELIGIBILITY, catalog, statuses,
                new StaffNames(DIRECTORY, CLOCK)).create(placed);

        assertTrue(winner.get().created());
        assertFalse(result.created(), "the loser reports the tracker that already exists");
        assertEquals(winner.get().instanceId(), result.instanceId());
        assertEquals(1L, owner.sql("SELECT count(*) FROM workflow.workflow_instances WHERE entity_id = :order").param("order", order)
                .query(Long.class).single(), "the losing attempt left nothing behind");
    }

    @Test
    void creationValidatesItsInput() {
        asNobody();
        OffsetDateTime placed = OffsetDateTime.parse("2026-10-19T09:30:00+05:30");
        assertEquals(400, http(() -> service.create(new CreateTracker("", 10, true, 1000, placed, "Asia/Kolkata", 1L, null))).status());
        assertEquals(400, http(() -> service.create(new CreateTracker("X", 0, true, 1000, placed, "Asia/Kolkata", 1L, null))).status());
        assertEquals(400, http(() -> service.create(new CreateTracker("X", 10, true, 0, placed, "Asia/Kolkata", 1L, null))).status());
        assertEquals(400, http(() -> service.create(new CreateTracker("X", 10, true, 1000, placed, "Mars/Olympus", 1L, null))).status());
    }

    @Test
    void anOperatorOutsideTheSchoolCannotSeeTheTrackerAndAnotherSchoolOperatorCanOnlyLookNotWork() {
        String order = create(true, 10L, OP_A);
        assertEquals(404, http(() -> as(() -> asOperator(OP_B, 20L), () -> service.view(order))).status());
        assertEquals(403, http(() -> as(() -> asOperator(OP_B), () -> service.view(order))).status(),
                "an operator with no assignment holds no tracker permission");

        TrackerView seen = as(() -> asOperator(OP_C, 10L), () -> service.view(order));
        assertFalse(step(seen, 1).can().canEditDetails());
        assertFalse(step(seen, 1).can().canComplete());
        long version = step(seen, 1).version();
        assertEquals(403, http(() -> as(() -> asOperator(OP_C, 10L), () -> service.saveDraft(order, 1, Map.of(), version))).status());
        assertEquals(403, http(() -> as(() -> asOperator(OP_C, 10L), () -> service.completeStep(order, 1, version))).status());
    }

    @Test
    void deployedOperatorCanWorkAssignedStep() {
        String order = create(true, 10L, OP_A);
        asOperator(OP_A, 10L); // school id 10 from the branch, the real OPERATIONS codes: no workflow:act
        TrackerView first = service.view(order);
        TrackerView drafted = service.saveDraft(order, 1, Map.of(), step(first, 1).version());
        TrackerView atTwo = service.completeStep(order, 1, step(drafted, 1).version());
        assertEquals(2, atTwo.progress().currentStep());
        TrackerView sent = service.sendDesign(order);
        assertEquals("SENT", sent.design().state());
        assertEquals(3, service.completeStep(order, 2, step(sent, 2).version()).progress().currentStep());

        asSuper();
        TrackerView approved = service.approveDesign(order, 501, null);
        service.completeStep(order, 3, step(approved, 3).version());

        asOperator(OP_A, 10L);
        TrackerView atFour = service.view(order);
        TrackerView saved = service.saveDraft(order, 4, Map.of("cdrFilename", "cover.cdr"), step(atFour, 4).version());
        assertEquals("cover.cdr", step(saved, 4).data().get("cdrFilename"));
    }

    @Test
    void operatorOutsideSchoolCannotViewTracker() {
        String order = create(true, 10L, OP_A);
        // Moved from school 10 to 20: identity leaves an operator without item scopes their old branch, so the school id
        // still says 10 and row-level security alone would admit this tracker.
        Runnable moved = () -> TenantContext.set(new TenantContext(OP_B, "op@x", "OPERATIONS", 10L, null, OPERATIONS, Set.of(20L)));
        assertEquals(403, http(() -> as(moved, () -> service.view(order))).status());
        assertEquals(403, http(() -> as(moved, () -> service.addInternalNote(order, "not my school"))).status());
        assertEquals(403, http(() -> as(moved, () -> service.list(false, 10L, true, 500))).status());
        assertTrue(as(moved, () -> service.list(false, null, true, 500)).stream().noneMatch(item -> item.schoolId() == 10L));
        assertEquals(404, http(() -> as(() -> asOperator(OP_B, 20L), () -> service.view(order))).status());
        assertTrue(as(() -> asSuper(), () -> service.view(order)).internalNotes().isEmpty(), "nothing was written");
    }

    @Test
    void schoolAdminsAndAnonymousCallersAreRefused() {
        String order = create(true, 10L, OP_A);
        TenantContext.set(new TenantContext(5L, "admin@x", "ADMIN", 10L, null, SCHOOL_ADMIN_WORKFLOW));
        assertEquals(403, http(() -> service.view(order)).status());
        TenantContext.clear();
        assertEquals(403, http(() -> service.view(order)).status());
    }

    // ---- sequence, drafts and gates --------------------------------------------------------------------------------

    @Test
    void stepsUnlockOneAtATimeAndTheNextClockStartsOnCompletion() {
        String order = create(true, 10L, OP_A);
        TrackerView first = as(() -> asOperator(OP_A, 10L), () -> service.view(order));
        assertEquals("STEP_NOT_ACTIVE", codeOf(() -> as(() -> asOperator(OP_A, 10L),
                () -> service.completeStep(order, 2, step(first, 2).version()))));

        now = Instant.parse("2026-10-19T05:00:00Z"); // 10:30 Kolkata
        TrackerView after = as(() -> asOperator(OP_A, 10L), () -> service.completeStep(order, 1, step(first, 1).version()));
        assertEquals("COMPLETED", step(after, 1).status());
        assertEquals("ACTIVE", step(after, 2).status());
        assertEquals(2, after.progress().currentStep());
        // Step 2 default is two working days from 10:30 Monday: Wednesday 10:30 Kolkata.
        assertEquals(OffsetDateTime.parse("2026-10-21T10:30:00+05:30").toInstant(), step(after, 2).dueAt().toInstant());
        // The operator is now locked out of the completed step.
        long v = step(after, 1).version();
        assertEquals(403, http(() -> as(() -> asOperator(OP_A, 10L), () -> service.saveDraft(order, 1, Map.of(), v))).status());
    }

    @Test
    void savingADraftValidatesFieldsChecksVersionAndKeepsTheLastDraft() {
        String order = create(false, 10L, OP_A);
        advance(order, 1);
        asOperator(OP_A, 10L);
        TrackerView view = service.view(order);
        assertEquals(5, view.progress().currentStep());
        long version = step(view, 5).version();

        assertEquals("INVALID_STEP_DATA", codeOf(() -> service.saveDraft(order, 5, Map.of("bogus", "x"), version)));
        assertEquals("INVALID_STEP_DATA", codeOf(() -> service.saveDraft(order, 5, Map.of("productionStart", "tomorrow"), version)));

        TrackerView saved = service.saveDraft(order, 5, Map.of("paperGsm", "70 GSM", "productionStart", "2026-11-01"), version);
        assertEquals("70 GSM", step(saved, 5).data().get("paperGsm"));
        assertEquals(409, http(() -> service.saveDraft(order, 5, Map.of("paperGsm", "80"), version)).status());

        TrackerView reloaded = service.view(order);
        assertEquals("2026-11-01", step(reloaded, 5).data().get("productionStart"));
    }

    @Test
    void aLockedStepRefusesWorkAndUnknownStepsAreNotFound() {
        String order = create(true, 10L, OP_A);
        asOperator(OP_A, 10L);
        assertEquals(409, http(() -> service.saveDraft(order, 4, Map.of("cdrFilename", "a.cdr"), 0)).status());
        assertEquals(404, http(() -> service.saveDraft(order, 13, Map.of(), 0)).status());
    }

    // ---- wholesale path --------------------------------------------------------------------------------------------

    @Test
    void aWholesaleOrderHasNineStepsAndSkipsTheDesignSteps() {
        String order = create(false, 10L, OP_A);
        TrackerView view = as(() -> asOperator(OP_A, 10L), () -> service.view(order));
        assertEquals(9, view.progress().total());
        assertNull(view.design());
        assertTrue(view.steps().stream().noneMatch(s -> s.step() >= 2 && s.step() <= 4));
        TrackerView after = as(() -> asOperator(OP_A, 10L), () -> service.completeStep(order, 1, step(view, 1).version()));
        assertEquals(5, after.progress().currentStep());
        // Step 2 does not exist on this path, so work on it is reported as not found.
        assertEquals(404, http(() -> as(() -> asOperator(OP_A, 10L), () -> service.saveDraft(order, 2, Map.of(), 0))).status());
    }

    @Test
    void designActionsAreRefusedOnAWholesaleOrder() {
        String order = create(false, 10L, OP_A);
        asSuper();
        assertEquals(400, http(() -> service.sendDesign(order)).status());
        assertEquals(400, http(() -> service.approveDesign(order, 1, null)).status());
        assertEquals(400, http(() -> service.setDesigner(order, 1)).status());
    }

    // ---- design approval -------------------------------------------------------------------------------------------

    @Test
    void designMustBeSentBeforeStepTwoCompletesAndOnlySuperadminApproves() {
        String order = create(true, 10L, OP_A);
        advance(order, 1);

        asOperator(OP_A, 10L);
        TrackerView atTwo = service.view(order);
        assertEquals("DESIGN_NOT_SENT", codeOf(() -> service.completeStep(order, 2, step(atTwo, 2).version())));
        assertTrue(atTwo.design().canManageImages());
        TrackerView sent = service.sendDesign(order);
        assertEquals("SENT", sent.design().state());
        TrackerView atThree = service.completeStep(order, 2, step(sent, 2).version());
        assertEquals(3, atThree.progress().currentStep());
        assertFalse(atThree.design().canManageImages());
        assertEquals(403, http(() -> service.approveDesign(order, 501, null)).status());
        assertEquals("SUPERADMIN_ONLY", codeOf(() -> service.completeStep(order, 3, step(atThree, 3).version())));
        assertFalse(step(atThree, 3).can().canComplete());

        asSuper();
        assertEquals("DESIGN_NOT_APPROVED", codeOf(() -> service.completeStep(order, 3, step(atThree, 3).version())));
        TrackerView approved = service.approveDesign(order, 501, "Approved with thanks");
        assertEquals("APPROVED", approved.design().state());
        assertEquals(501L, approved.design().approvedDraftRef());
        assertEquals("Approved with thanks", approved.design().comment());
        TrackerView atFour = service.completeStep(order, 3, step(approved, 3).version());
        assertEquals(4, atFour.progress().currentStep());
    }

    @Test
    void requestChangesLetsTheOperatorManageImagesAndResendWhileSuperadminCanStillApprove() {
        String order = create(true, 10L, OP_A);
        advance(order, 2);
        asSuper();
        TrackerView changes = service.requestChanges(order, "Make the logo bigger");
        assertEquals("CHANGES_REQUESTED", changes.design().state());
        assertEquals(3, changes.progress().currentStep());
        assertEquals("COMPLETED", step(changes, 2).status());

        asOperator(OP_A, 10L);
        TrackerView operatorView = service.view(order);
        assertTrue(operatorView.design().canManageImages(), "step 2 stays completed but the operator may manage images");
        assertFalse(step(operatorView, 2).can().canEditDetails());
        TrackerView resent = service.sendDesign(order);
        assertEquals("SENT", resent.design().state());
        assertEquals(409, http(() -> service.sendDesign(order)).status(), "already sent");

        // Superadmin could equally have approved the existing design while the request was pending.
        String second = create(true, 10L, OP_A);
        advance(second, 2);
        asSuper();
        service.requestChanges(second, null);
        assertEquals("APPROVED", service.approveDesign(second, 777, null).design().state());
    }

    @Test
    void designDecisionsNeedTheApprovalStepToBeActiveAndADesignToHaveBeenSent() {
        String order = create(true, 10L, OP_A);
        asSuper();
        assertEquals(409, http(() -> service.approveDesign(order, 1, null)).status(), "step 3 is not active yet");
        advance(order, 1);
        asSuper();
        assertEquals(409, http(() -> service.requestChanges(order, null)).status());
    }

    // ---- reopen, notes, sign-off, counts -----------------------------------------------------------------------------

    @Test
    void superadminReopenRestartsTheClockRevertsLaterStepsAndKeepsTheirData() {
        String order = create(false, 10L, OP_A);
        advance(order, 5);
        asOperator(OP_A, 10L);
        TrackerView atSix = service.view(order);
        long v = step(atSix, 6).version();
        service.saveDraft(order, 6, Map.of("totalTitleUnits", 1000, "piecesPerBoard", 20), v);

        assertEquals("SUPERADMIN_ONLY", codeOf(() -> service.reopenStep(order, 5)));

        now = Instant.parse("2026-10-20T06:00:00Z");
        asSuper();
        TrackerView reopened = service.reopenStep(order, 5);
        assertEquals("ACTIVE", step(reopened, 5).status());
        assertEquals(now, step(reopened, 5).startedAt().toInstant());
        assertEquals(5, reopened.progress().currentStep());
        assertEquals("LOCKED", step(reopened, 6).status());
        assertNull(step(reopened, 6).startedAt());
        assertEquals(1000L, ((Number) step(reopened, 6).data().get("totalTitleUnits")).longValue(), "later data is kept");
        assertEquals("IN_PROGRESS", reopened.instanceStatus());
        assertEquals("STEP_NOT_COMPLETED", codeOf(() -> service.reopenStep(order, 6)), "a step that is not completed cannot be reopened");
    }

    /** Reverting step 2 means the drafts may be replaced, so the old approval must not carry over (spec §3.4). */
    @Test
    void reopenDesigningResetsApproval() {
        String order = create(true, 10L, OP_A);
        advance(order, 3); // sent, draft 501 approved with a comment, step 4 active
        asSuper();
        TrackerView reopened = service.reopenStep(order, 2);
        assertEquals(2, reopened.progress().currentStep());
        assertEquals("NOT_SENT", reopened.design().state());
        assertNull(reopened.design().approvedDraftRef());
        assertNull(reopened.design().comment(), "the approval's comment went with it");
        assertEquals(SUPER, reopened.design().by());

        asOperator(OP_A, 10L);
        assertEquals("DESIGN_NOT_SENT", codeOf(() -> service.completeStep(order, 2, step(reopened, 2).version())));
        service.sendDesign(order);
        TrackerView atThree = service.completeStep(order, 2, step(service.view(order), 2).version());
        asSuper();
        assertEquals("DESIGN_NOT_APPROVED", codeOf(() -> service.completeStep(order, 3, step(atThree, 3).version())),
                "step 3 cannot complete until the design is approved again");
        TrackerView approved = service.approveDesign(order, 777, null);
        assertEquals(4, service.completeStep(order, 3, step(approved, 3).version()).progress().currentStep());

        // Reopening step 1 reverts step 2 as well, so it takes the same rule.
        String fromStart = create(true, 10L, OP_A);
        advance(fromStart, 3);
        asSuper();
        TrackerView restarted = service.reopenStep(fromStart, 1);
        assertEquals("NOT_SENT", restarted.design().state());
        assertNull(restarted.design().approvedDraftRef());
    }

    @Test
    void reopenHeadApprovalRequiresReapproval() {
        String order = create(true, 10L, OP_A);
        advance(order, 4);
        asSuper();
        // A step after the design steps leaves the approval alone.
        TrackerView atFour = service.reopenStep(order, 4);
        assertEquals("APPROVED", atFour.design().state());
        assertEquals(501L, atFour.design().approvedDraftRef());

        TrackerView reopened = service.reopenStep(order, 3);
        assertEquals(3, reopened.progress().currentStep());
        assertEquals("SENT", reopened.design().state(), "the sent design waits for a decision again");
        assertNull(reopened.design().approvedDraftRef());
        assertEquals("DESIGN_NOT_APPROVED", codeOf(() -> service.completeStep(order, 3, step(reopened, 3).version())));

        TrackerView approved = service.approveDesign(order, 777, "Second draft is better");
        assertEquals(777L, approved.design().approvedDraftRef());
        assertEquals(4, service.completeStep(order, 3, step(approved, 3).version()).progress().currentStep());
    }

    @Test
    void stageNotesBelongToTheOperatorWhileTheStepIsActiveAndLockOnCompletion() {
        String order = create(false, 10L, OP_A);
        asOperator(OP_A, 10L);
        TrackerView view = service.view(order);
        long v = step(view, 1).version();
        assertEquals(403, http(() -> as(() -> asSuper(), () -> service.saveStageNote(order, 1, "from superadmin", v))).status());

        asOperator(OP_A, 10L);
        TrackerView noted = service.saveStageNote(order, 1, "Checked the specs", v);
        assertEquals("Checked the specs", step(noted, 1).stageNote());
        assertEquals("NOTE_TOO_LONG", codeOf(() -> service.saveStageNote(order, 1, "word ".repeat(101), step(noted, 1).version())));
        service.saveStageNote(order, 1, "word ".repeat(100), step(noted, 1).version());

        TrackerView done = service.completeStep(order, 1, step(service.view(order), 1).version());
        assertEquals(403, http(() -> service.saveStageNote(order, 1, "late", step(done, 1).version())).status());

        asSuper();
        service.reopenStep(order, 1);
        asOperator(OP_A, 10L);
        long reopenedVersion = step(service.view(order), 1).version();
        assertEquals("again", step(service.saveStageNote(order, 1, "again", reopenedVersion), 1).stageNote());
    }

    @Test
    void longStageNoteIs400() {
        String order = create(false, 10L, OP_A);
        asOperator(OP_A, 10L);
        long v = step(service.view(order), 1).version();
        // One long word passes the 100-word limit but not the 4000-character column.
        ResponseStatusException tooLong = assertThrows(ResponseStatusException.class, () -> service.saveStageNote(order, 1, "x".repeat(4001), v));
        assertEquals(400, tooLong.getStatusCode().value());
        assertEquals("A stage note is limited to 4000 characters", tooLong.getReason());
        assertEquals(4000, step(service.saveStageNote(order, 1, "x".repeat(4000), v), 1).stageNote().length());
    }

    @Test
    void qualityGateAndDeliveryEachNeedTheirOwnSuperadminSignOff() {
        String order = create(false, 10L, OP_A);
        advance(order, 7);
        asOperator(OP_A, 10L);
        TrackerView atEight = service.view(order);
        assertEquals(8, atEight.progress().currentStep());
        assertEquals("SIGN_OFF_REQUIRED", codeOf(() -> service.completeStep(order, 8, step(atEight, 8).version())));
        assertEquals(403, http(() -> service.signOff(order, 8, step(atEight, 8).version())).status());

        asSuper();
        TrackerView signed = service.signOff(order, 8, step(atEight, 8).version());
        assertNotNull(step(signed, 8).data().get("signOff"));
        assertEquals(400, http(() -> service.signOff(order, 7, step(signed, 7).version())).status(), "only steps 8 and 12 have a sign-off");
        TrackerView passed = service.completeStep(order, 8, step(signed, 8).version());
        assertEquals(9, passed.progress().currentStep());

        // The step-8 sign-off must not satisfy step 12.
        advance(order, 11);
        asOperator(OP_A, 10L);
        TrackerView atTwelve = service.view(order);
        assertEquals(12, atTwelve.progress().currentStep());
        assertEquals("SIGN_OFF_REQUIRED", codeOf(() -> service.completeStep(order, 12, step(atTwelve, 12).version())));
        asSuper();
        TrackerView signed12 = service.signOff(order, 12, step(atTwelve, 12).version());
        TrackerView finished = service.completeStep(order, 12, step(signed12, 12).version());
        assertTrue(finished.progress().finished());
        assertEquals("COMPLETED", finished.instanceStatus());
    }

    /** A sign-off approves the data it saw (spec §3.4): saving the same values keeps it, changed values take it back. */
    @Test
    void editingSignedStepClearsSignOff() {
        String order = create(false, 10L, OP_A);
        advance(order, 7);
        asSuper();
        TrackerView drafted = service.saveDraft(order, 8, Map.of("sampleLink", "https://example.test/sample", "checks", List.of(0, 2)),
                step(service.view(order), 8).version());
        TrackerView signed = service.signOff(order, 8, step(drafted, 8).version());

        // The form sends every value again; the same values, in any order and as whole numbers of any width, are not a change.
        TrackerView same = service.saveDraft(order, 8, Map.of("checks", List.of(2L, 0L), "sampleLink", " https://example.test/sample "),
                step(signed, 8).version());
        assertNotNull(step(same, 8).data().get("signOff"), "an unchanged save keeps the sign-off");
        assertEquals(step(drafted, 8).data().get("sampleLink"), step(same, 8).data().get("sampleLink"));

        TrackerView changed = service.saveDraft(order, 8, Map.of("sampleLink", "https://example.test/other", "checks", List.of(0, 2)),
                step(same, 8).version());
        assertNull(step(changed, 8).data().get("signOff"), "changed data needs a new sign-off");
        assertEquals("SIGN_OFF_REQUIRED", codeOf(() -> service.completeStep(order, 8, step(changed, 8).version())));
        TrackerView resigned = service.signOff(order, 8, step(changed, 8).version());
        assertEquals(9, service.completeStep(order, 8, step(resigned, 8).version()).progress().currentStep());
    }

    @Test
    void reopenClearsLaterSignOffs() {
        String order = create(false, 10L, OP_A);
        advance(order, 12); // steps 8 and 12 signed off, every step completed
        asSuper();
        TrackerView reopened = service.reopenStep(order, 7);
        assertNull(step(reopened, 8).data().get("signOff"));
        assertNull(step(reopened, 12).data().get("signOff"));
        assertEquals(1000L, ((Number) step(reopened, 11).data().get("producedCount")).longValue(), "the rest of the later data is kept");

        TrackerView atEight = service.completeStep(order, 7, step(reopened, 7).version());
        assertEquals("SIGN_OFF_REQUIRED", codeOf(() -> service.completeStep(order, 8, step(atEight, 8).version())),
                "a step reverted by the reopen is signed off again");

        // Reopening a signed step itself takes its own sign-off back too.
        TrackerView signed = service.signOff(order, 8, step(atEight, 8).version());
        service.completeStep(order, 8, step(signed, 8).version());
        TrackerView eightReopened = service.reopenStep(order, 8);
        assertEquals("ACTIVE", step(eightReopened, 8).status());
        assertNull(step(eightReopened, 8).data().get("signOff"));
    }

    @Test
    void validationCountNeedsAllCountsAndANoteOnMismatch() {
        String order = create(false, 10L, OP_A);
        advance(order, 10);
        asOperator(OP_A, 10L);
        TrackerView at = service.view(order);
        assertEquals(11, at.progress().currentStep());
        assertEquals("COUNTS_INCOMPLETE", codeOf(() -> service.completeStep(order, 11, step(at, 11).version())));

        TrackerView mismatch = service.saveDraft(order, 11, Map.of("producedCount", 990, "acceptedCount", 990, "dispatchCount", 980), step(at, 11).version());
        assertEquals("DISCREPANCY_NOTE_REQUIRED", codeOf(() -> service.completeStep(order, 11, step(mismatch, 11).version())));
        TrackerView noted = service.saveDraft(order, 11, Map.of("producedCount", 990, "acceptedCount", 990, "dispatchCount", 980,
                "discrepancyNote", "Ten books were damaged in transit"), step(mismatch, 11).version());
        assertEquals(12, service.completeStep(order, 11, step(noted, 11).version()).progress().currentStep());
    }

    @Test
    void followUpsAreRepeatedEntriesOnlyWhileStepTenIsActive() {
        String order = create(false, 10L, OP_A);
        advance(order, 9);
        asOperator(OP_A, 10L);
        assertEquals(10, service.view(order).progress().currentStep());
        service.addFollowUp(order, 40, java.time.LocalDate.parse("2026-11-05"), "+911111111111");
        TrackerView two = service.addFollowUp(order, 75, null, null);
        assertEquals(List.of(40, 75), two.followUps().stream().map(TrackerView.FollowUp::percentComplete).toList());
        assertEquals(400, http(() -> service.addFollowUp(order, 101, null, null)).status());

        String early = create(false, 10L, OP_A);
        assertEquals(409, http(() -> as(() -> asOperator(OP_A, 10L), () -> service.addFollowUp(early, 10, null, null))).status());
    }

    // ---- people, notes, time slots -----------------------------------------------------------------------------------

    @Test
    void onlySuperadminSetsTheVendorAndDesignerAndInternalNotesAreALog() {
        String order = create(true, 10L, OP_A);
        asOperator(OP_A, 10L);
        assertEquals(403, http(() -> service.setVendor(order, "Acme Print", "+910000000000")).status());

        asSuper();
        long designer = service.createDesigner("Priya Singh", "+911234567890").id();
        assertEquals(400, http(() -> service.setVendor(order, " ", "1")).status());
        service.setVendor(order, "Acme Print", "+910000000000");
        TrackerView view = service.setDesigner(order, designer);
        assertEquals("Acme Print", view.vendor().name());
        assertEquals("Priya Singh", view.designer().name());
        assertEquals(400, http(() -> service.setDesigner(order, 99999)).status());

        asOperator(OP_A, 10L);
        service.addInternalNote(order, "First call made");
        TrackerView two = service.addInternalNote(order, "Vendor confirmed");
        assertEquals(List.of("First call made", "Vendor confirmed"), two.internalNotes().stream().map(TrackerView.Note::body).toList());
        assertEquals("NOTE_TOO_LONG", codeOf(() -> service.addInternalNote(order, "word ".repeat(101))));
        assertEquals(400, http(() -> service.addInternalNote(order, "  ")).status());
        assertTrue(as(() -> asOperator(OP_A, 10L), () -> service.designers()).stream().anyMatch(d -> d.id() == designer), "an operator can read the designer list");
    }

    @Test
    void aDesignerWithTheSameNameAndPhoneIsRefusedAsAConflictNotAServerError() {
        asSuper();
        long first = service.createDesigner("Dup Designer", "+910000000111").id();
        long other = service.createDesigner("Dup Designer", "+910000000222").id();
        assertEquals(409, http(() -> service.createDesigner("  Dup Designer ", "+910000000111")).status());
        assertEquals(409, http(() -> service.updateDesigner(other, "Dup Designer", "+910000000111", true)).status());
        assertEquals("+910000000222", service.designers().stream().filter(d -> d.id() == other).findFirst().orElseThrow().phone());
        assertFalse(service.updateDesigner(first, "Dup Designer", "+910000000111", false).active(), "saving a designer under its own name and phone is not a duplicate");
    }

    @Test
    void timeSlotOverridesApplyToNewlyActivatedStepsAndOnlySuperadminSetsThem() {
        asOperator(OP_A, 10L);
        assertEquals(403, http(() -> service.setTimeSlot(30, 1, Sla.hours(8))).status());
        asSuper();
        service.setTimeSlot(30, 1, Sla.hours(8));
        service.setTimeSlot(30, 5, null);

        String order = "ORD-SLA-" + ORDERS.incrementAndGet();
        asNobody();
        service.create(new CreateTracker(order, 30, false, 1000, OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 1L, null));
        asSuper();
        TrackerView view = service.view(order);
        assertEquals(OffsetDateTime.parse("2026-10-19T17:30:00+05:30").toInstant(), step(view, 1).dueAt().toInstant());
        assertEquals("SCHOOL", step(view, 1).sla().source());

        TrackerView atFive = advance(order, 1);
        asSuper();
        assertNull(step(atFive, 5).dueAt(), "an explicit 'no timer' override means no deadline");
        assertNull(step(atFive, 5).sla());

        List<NotebookTrackerService.SlaRow> slots = service.timeSlots(30);
        assertEquals(12, slots.size());
        assertEquals("SCHOOL", slots.get(0).sla().source());
        assertEquals("DEFAULT", slots.get(1).sla().source());
        assertNull(slots.get(7).sla(), "step 8 has no default timer");

        service.clearTimeSlot(30, 1);
        assertEquals("DEFAULT", service.timeSlots(30).get(0).sla().source());
        assertEquals(403, http(() -> as(() -> asOperator(OP_B, 20L), () -> service.timeSlots(30))).status());
    }

    @Test
    void anActiveStepPastItsDeadlineIsReportedOverdueOnlyWhileOpen() {
        String order = create(false, 10L, OP_A);
        asOperator(OP_A, 10L);
        assertFalse(step(service.view(order), 1).overdue());
        now = Instant.parse("2026-10-19T09:00:00Z"); // 14:30 Kolkata, past the 13:30 deadline
        TrackerView late = service.view(order);
        assertTrue(step(late, 1).overdue());
        TrackerView done = service.completeStep(order, 1, step(late, 1).version());
        assertFalse(step(done, 1).overdue(), "a completed step is never overdue");
    }

    // ---- operator scopes and assignment (spec v4) -------------------------------------------------------------------

    @Test
    void anOperatorScopedToNotebooksSeesNotebookTrackersAtThatSchoolOnly() {
        String order = create(true, 10L, OP_A);
        assertEquals(order, as(() -> asScopedOperator(OP_C, "10:NOTEBOOKS"), () -> service.view(order)).orderId());
        assertEquals(404, http(() -> as(() -> asScopedOperator(OP_C, "10:TIES"), () -> service.view(order))).status(), "another item grants nothing");
        assertEquals(404, http(() -> as(() -> asScopedOperator(OP_C, "20:NOTEBOOKS"), () -> service.view(order))).status(), "another school grants nothing");
        assertEquals(404, http(() -> as(() -> asScopedOperator(OP_C, "10:NOTEBOOKS_X", "bogus", ":NOTEBOOKS", "x:NOTEBOOKS"), () -> service.view(order))).status(),
                "a malformed or look-alike scope grants nothing");
    }

    @Test
    void itemScopedAndSchoolWideAccessCombine() {
        String order = create(true, 10L, OP_A);
        TenantContext.set(new TenantContext(OP_C, "c@x", "OPERATIONS", 20L, null, OPERATIONS, Set.of(20L), Set.of("10:NOTEBOOKS")));
        assertEquals(order, service.view(order).orderId());
        TenantContext.set(new TenantContext(OP_C, "c@x", "OPERATIONS", 10L, null, OPERATIONS, Set.of(10L), Set.of("10:TIES")));
        assertEquals(order, service.view(order).orderId(), "a school-wide assignment covers every item");
    }

    @Test
    void timeSlotsAreReadableToAnItemScopedOperatorOfThatSchoolOnly() {
        assertEquals(12, as(() -> asScopedOperator(OP_C, "10:NOTEBOOKS"), () -> service.timeSlots(10)).size());
        assertEquals(403, http(() -> as(() -> asScopedOperator(OP_C, "10:TIES"), () -> service.timeSlots(10))).status());
    }

    @Test
    void superadminAssignsTheWholeOrderToAnEligibleOperatorWhoThenWorksItAndTheOldOneCannot() {
        String order = create(true, 10L, null);
        TrackerView unassigned = as(() -> asScopedOperator(OP_A, "10:NOTEBOOKS"), () -> service.view(order));
        assertNull(step(unassigned, 1).assigneeUserId());
        assertEquals(403, http(() -> as(() -> asScopedOperator(OP_A, "10:NOTEBOOKS"), () -> service.completeStep(order, 1, step(unassigned, 1).version()))).status());

        ELIGIBLE_FOR_10.add(OP_A);
        TrackerView assigned = as(() -> asSuper(), () -> service.assignOrder(order, OP_A));
        for (int n = 1; n <= 12; n++) assertEquals(OP_A, step(assigned, n).assigneeUserId(), "step " + n);
        assertEquals("COMPLETED", as(() -> asScopedOperator(OP_A, "10:NOTEBOOKS"), () -> service.completeStep(order, 1, step(assigned, 1).version())).steps().get(0).status());

        ELIGIBLE_FOR_10.add(OP_C);
        TrackerView moved = as(() -> asSuper(), () -> service.assignOrder(order, OP_C));
        assertEquals(OP_A, step(moved, 1).assigneeUserId(), "a completed step keeps its operator");
        for (int n = 2; n <= 12; n++) assertEquals(OP_C, step(moved, n).assigneeUserId(), "step " + n);
        assertEquals(403, http(() -> as(() -> asScopedOperator(OP_A, "10:NOTEBOOKS"), () -> service.saveDraft(order, 2, Map.of(), step(moved, 2).version()))).status());
    }

    @Test
    void aSingleStepCanBeReassignedUntilItIsCompleted() {
        String order = create(true, 10L, OP_A);
        ELIGIBLE_FOR_10.addAll(List.of(OP_A, OP_C));
        asSuper();
        TrackerView one = service.assignStep(order, 3, OP_C);
        assertEquals(OP_C, step(one, 3).assigneeUserId());
        assertEquals(OP_A, step(one, 2).assigneeUserId());
        assertEquals(OP_C, step(service.assignStep(order, 1, OP_C), 1).assigneeUserId(), "step 1 is still open, so it moves");
        advance(order, 1);
        asSuper();
        assertEquals(409, http(() -> service.assignStep(order, 1, OP_A)).status(), "a completed step cannot be reassigned");
        assertEquals(404, http(() -> service.assignStep(order, 13, OP_A)).status());
    }

    @Test
    void assignmentIsRefusedForIneligibleUsersNonSuperadminsAndAnUnreachableDirectory() {
        String order = create(true, 10L, OP_A);
        asSuper();
        assertEquals(400, http(() -> service.assignOrder(order, OP_B)).status(), "identity says this user is not an operator for the school");
        assertEquals(400, http(() -> service.assignOrder(order, 0)).status());
        assertEquals(404, http(() -> service.assignOrder("NO-SUCH-ORDER", OP_A)).status());

        ELIGIBLE_FOR_10.add(OP_A);
        assertEquals(403, http(() -> as(() -> asOperator(OP_A, 10L), () -> service.assignOrder(order, OP_A))).status());
        assertEquals(403, http(() -> as(() -> asOperator(OP_A, 10L), () -> service.assignStep(order, 1, OP_A))).status());

        identityDown = true;
        asSuper();
        assertEquals(503, http(() -> service.assignOrder(order, OP_A)).status(), "no answer from identity must not allow an unchecked assignment");
        assertEquals(OP_A, step(service.view(order), 1).assigneeUserId());
    }

    @Test
    void assigningAnOrderWithEveryStepCompletedIsRefused() {
        String order = create(false, 10L, OP_A);
        advance(order, 12);
        ELIGIBLE_FOR_10.add(OP_A);
        asSuper();
        assertEquals(409, http(() -> service.assignOrder(order, OP_A)).status());
    }

    // ---- design drafts, order status and image access (phase 4) ---------------------------------------------------------

    @Test
    void aDesignCanOnlyBeSentOrApprovedAgainstDraftsThatExist() {
        String order = create(true, 10L, OP_A);
        advance(order, 1);
        DRAFT_IDS.clear();
        asOperator(OP_A, 10L);
        assertEquals(400, http(() -> service.sendDesign(order)).status(), "no draft uploaded yet");

        DRAFT_IDS.add(501L);
        service.sendDesign(order);
        TrackerView atThree = service.completeStep(order, 2, step(service.view(order), 2).version());
        asSuper();
        assertEquals(400, http(() -> service.approveDesign(order, 999, null)).status(), "999 is not a current draft");
        assertEquals(501L, service.approveDesign(order, 501, null).design().approvedDraftRef());
        assertEquals(3, atThree.progress().currentStep());
    }

    @Test
    void designSendFetchesDraftsBeforeLock() {
        String order = create(true, 10L, OP_A);
        advance(order, 1);
        asOperator(OP_A, 10L);
        service.sendDesign(order);
        service.completeStep(order, 2, step(service.view(order), 2).version());
        asSuper();
        service.approveDesign(order, 501, null);
        assertEquals(List.of(false, false), DRAFT_FETCH_IN_TRANSACTION,
                "send and approval ask the catalogue with no transaction open, so no tracker row lock is held");
    }

    /** Runs {@code action} as superadmin while the next draft fetch is waiting, then restores the caller's identity. */
    static void meanwhileSuperadmin(Runnable action) {
        DURING_DRAFT_FETCH.set(() -> {
            TenantContext caller = TenantContext.get();
            try {
                asSuper();
                action.run();
            } finally {
                TenantContext.set(caller);
            }
        });
    }

    @Test
    void designChecksRunAgainUnderTheLock() {
        String order = create(true, 10L, OP_A);
        advance(order, 1);
        // Superadmin sends the design while the operator's own send waits for the catalogue.
        meanwhileSuperadmin(() -> service.sendDesign(order));
        asOperator(OP_A, 10L);
        assertEquals(409, http(() -> service.sendDesign(order)).status(), "the design was sent meanwhile");
        assertEquals(1L, owner.sql("SELECT count(*) FROM workflow.workflow_actions a JOIN workflow.workflow_notebook_trackers t "
                + "ON t.instance_id = a.instance_id WHERE t.order_id = :order AND a.action = 'DESIGN_SENT'").param("order", order)
                .query(Long.class).single(), "sent once");

        asOperator(OP_A, 10L);
        service.completeStep(order, 2, step(service.view(order), 2).version());
        // Superadmin reopens step 2 while an approval waits for the catalogue: Head Approval is no longer the active step.
        asSuper();
        meanwhileSuperadmin(() -> service.reopenStep(order, 2));
        assertEquals(409, http(() -> service.approveDesign(order, 501, null)).status());
        TrackerView after = service.view(order);
        assertEquals("NOT_SENT", after.design().state());
        assertNull(after.design().approvedDraftRef());
    }

    @Test
    void anUnreachableCatalogueStopsSendingAndApprovingInsteadOfGuessing() {
        String order = create(true, 10L, OP_A);
        advance(order, 1);
        catalogDown = true;
        asOperator(OP_A, 10L);
        assertEquals(503, http(() -> service.sendDesign(order)).status());
        assertEquals("NOT_SENT", service.view(order).design().state());
    }

    @Test
    void theOrderStatusFollowsTheCurrentStepAndOnlyWhenItChanges() {
        String order = create(false, 10L, OP_A);
        asOperator(OP_A, 10L);
        TrackerView view = service.view(order);
        assertTrue(PUSHED.isEmpty(), "reading pushes nothing");

        view = service.saveStageNote(order, 1, "checked", step(view, 1).version());
        service.addInternalNote(order, "a note");
        assertTrue(PUSHED.isEmpty(), "notes and drafts do not move the step");

        service.completeStep(order, 1, step(view, 1).version());
        assertEquals(List.of(order + ":PRODUCTION_PLANNING"), PUSHED);

        asSuper();
        service.reopenStep(order, 1);
        assertEquals(order + ":ORDER_DETAILS", PUSHED.get(PUSHED.size() - 1));
        assertTrue(PUSHED_VERSIONS.get(1) > PUSHED_VERSIONS.get(0), "a reopen carries a newer version than the completion before it");
    }

    @Test
    void finishingTheLastStepShowsCompleted() {
        String order = create(false, 10L, OP_A);
        advance(order, 12);
        assertEquals(order + ":COMPLETED", PUSHED.get(PUSHED.size() - 1));
        assertTrue(PUSHED.contains(order + ":PRE_PRINT_QUALITY_GATE"));
        assertTrue(PUSHED.contains(order + ":DELIVERY_INVOICING"));
        for (int i = 1; i < PUSHED_VERSIONS.size(); i++) {
            assertTrue(PUSHED_VERSIONS.get(i) > PUSHED_VERSIONS.get(i - 1), "every step change carries a newer version");
        }
    }

    @Test
    void aFailedStatusPushNeverUndoesTheUsersAction() {
        String order = create(false, 10L, OP_A);
        catalogDown = true;
        asOperator(OP_A, 10L);
        TrackerView done = service.completeStep(order, 1, step(service.view(order), 1).version());
        assertEquals("COMPLETED", step(done, 1).status());
        assertEquals(5, done.progress().currentStep());
        assertTrue(PUSHED.isEmpty());
    }

    // ---- status delivery: a retried, versioned outbox (review fix, spec §3.3) -------------------------------------------

    record Queued(String status, long trackerVersion, int attempts) { }

    static Optional<Queued> queued(String order) {
        return owner.sql("SELECT status, tracker_version, attempts FROM workflow.workflow_tracker_status_outbox WHERE order_id = :order")
                .param("order", order)
                .query((rs, n) -> new Queued(rs.getString("status"), rs.getLong("tracker_version"), rs.getInt("attempts"))).optional();
    }

    static long instanceVersion(String order) {
        return owner.sql("SELECT i.version FROM workflow.workflow_instances i JOIN workflow.workflow_notebook_trackers t ON t.instance_id = i.id "
                + "WHERE t.order_id = :order").param("order", order).query(Long.class).single();
    }

    /** Lets the backoff of a failed attempt elapse. */
    static void due(String order) {
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET next_attempt_at = now() - interval '1 second' WHERE order_id = :order")
                .param("order", order).update();
    }

    @Test
    void statusQueuedInSameTransaction() {
        String order = create(false, 10L, OP_A);
        catalogDown = true;
        asOperator(OP_A, 10L);
        long before = instanceVersion(order);
        TrackerView done = service.completeStep(order, 1, step(service.view(order), 1).version());
        assertEquals(5, done.progress().currentStep(), "the user's action stands although the push failed");
        Queued queued = queued(order).orElseThrow(() -> new AssertionError("the step change queued its status"));
        assertEquals("PRODUCTION_PLANNING", queued.status());
        assertEquals(instanceVersion(order), queued.trackerVersion(), "the instance version after the change");
        assertTrue(queued.trackerVersion() > before);
        assertEquals(1, queued.attempts(), "one push was tried after commit");
        assertTrue(PUSHED.isEmpty());

        // The status is written by the step change's own transaction: when it cannot be queued, the step does not move.
        owner.sql("CREATE FUNCTION workflow.test_refuse_status() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'outbox refused'; END $$").update();
        owner.sql("CREATE TRIGGER test_refuse_status BEFORE INSERT OR UPDATE ON workflow.workflow_tracker_status_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION workflow.test_refuse_status()").update();
        try {
            long v = step(service.view(order), 5).version();
            assertThrows(RuntimeException.class, () -> service.completeStep(order, 5, v));
        } finally {
            owner.sql("DROP TRIGGER test_refuse_status ON workflow.workflow_tracker_status_outbox").update();
            owner.sql("DROP FUNCTION workflow.test_refuse_status()").update();
        }
        assertEquals(5, service.view(order).progress().currentStep(), "rolled back together with the status");
        assertEquals("PRODUCTION_PLANNING", queued(order).orElseThrow().status());
    }

    @Test
    void drainDeliversAndDeletes() {
        String order = create(false, 10L, OP_A);
        catalogDown = true;
        asOperator(OP_A, 10L);
        service.completeStep(order, 1, step(service.view(order), 1).version());
        TenantContext.clear();
        catalogDown = false;
        long version = queued(order).orElseThrow().trackerVersion();
        assertEquals(0, statuses.drain(10), "a failed attempt waits out its backoff");

        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(List.of(order + ":PRODUCTION_PLANNING"), PUSHED);
        assertEquals(List.of(version), PUSHED_VERSIONS);
        assertTrue(queued(order).isEmpty(), "a delivered status leaves the outbox");
        assertEquals(0, statuses.drain(10), "and is delivered once");
    }

    @Test
    void newerVersionReplacesQueuedStatus() {
        String order = create(false, 10L, OP_A);
        catalogDown = true;
        asSuper();
        service.completeStep(order, 1, step(service.view(order), 1).version());
        Queued first = queued(order).orElseThrow();
        due(order);
        statuses.drain(10);
        assertEquals(2, queued(order).orElseThrow().attempts(), "the relay retried it");

        service.completeStep(order, 5, step(service.view(order), 5).version());
        Queued replaced = queued(order).orElseThrow();
        assertEquals("BOARD_PROCUREMENT", replaced.status());
        assertEquals(instanceVersion(order), replaced.trackerVersion());
        assertTrue(replaced.trackerVersion() > first.trackerVersion());
        assertEquals(1, replaced.attempts(), "the newer status was due at once and was tried after its own commit");

        // An older version never replaces a newer one.
        transactions.executeWithoutResult(status -> statuses.record(order, 10L, "PRODUCTION_PLANNING", first.trackerVersion()));
        assertEquals(replaced, queued(order).orElseThrow());

        catalogDown = false;
        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(List.of(order + ":BOARD_PROCUREMENT"), PUSHED, "only the newest status is delivered");
    }

    @Test
    void rowSurvivesWhenNewerVersionArrivesMidPush() {
        String order = create(false, 10L, OP_A);
        // While step 1's status is in flight, superadmin completes step 5 and that newer status cannot be delivered yet.
        DURING_PUSH.set(() -> {
            TenantContext operator = TenantContext.get();
            catalogDown = true;
            try {
                as(() -> asSuper(), () -> service.completeStep(order, 5, step(service.view(order), 5).version()));
            } finally {
                catalogDown = false;
                TenantContext.set(operator);
            }
        });
        asOperator(OP_A, 10L);
        service.completeStep(order, 1, step(service.view(order), 1).version());
        TenantContext.clear();

        assertEquals(List.of(order + ":PRODUCTION_PLANNING"), PUSHED, "step 1's status arrived");
        Queued survivor = queued(order).orElseThrow(() -> new AssertionError("delivering the older version must not delete the newer one"));
        assertEquals("BOARD_PROCUREMENT", survivor.status());
        assertEquals(instanceVersion(order), survivor.trackerVersion());

        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(order + ":BOARD_PROCUREMENT", PUSHED.get(PUSHED.size() - 1));
        assertEquals(survivor.trackerVersion(), PUSHED_VERSIONS.get(PUSHED_VERSIONS.size() - 1));
        assertTrue(queued(order).isEmpty());
    }

    @Test
    void imageAccessFollowsTheStepTheAssignmentAndTheChangeRequest() {
        String order = create(true, 10L, OP_A);
        assertFalse(service.designImageAccess(order, OP_A, "OPERATIONS").allowed(), "step 2 is still locked");
        assertFalse(service.designImageAccess(order, SUPER, "SUPERADMIN").allowed(), "locked for superadmin too");

        advance(order, 1);
        assertTrue(service.designImageAccess(order, OP_A, "OPERATIONS").allowed());
        assertEquals("This step is assigned to another operator", service.designImageAccess(order, OP_C, "OPERATIONS").reason());
        assertTrue(service.designImageAccess(order, SUPER, "superadmin").allowed());

        asSuper();
        service.sendDesign(order);
        service.completeStep(order, 2, step(service.view(order), 2).version());
        assertFalse(service.designImageAccess(order, OP_A, "OPERATIONS").allowed(), "completed, so the operator is locked out");
        assertTrue(service.designImageAccess(order, SUPER, "SUPERADMIN").allowed(), "superadmin can still re-edit");

        service.requestChanges(order, "again");
        assertTrue(service.designImageAccess(order, OP_A, "OPERATIONS").allowed(), "after a change request the operator may manage images");
        assertFalse(service.designImageAccess(order, OP_C, "OPERATIONS").allowed());
    }

    @Test
    void imageAccessIsRefusedForWholesaleUnknownOrdersAndOtherRoles() {
        String wholesale = create(false, 10L, OP_A);
        assertFalse(service.designImageAccess(wholesale, OP_A, "OPERATIONS").allowed());
        assertEquals("This order has no tracker yet", service.designImageAccess("NO-SUCH-ORDER", OP_A, "OPERATIONS").reason());
        String order = create(true, 10L, OP_A);
        advance(order, 1);
        for (String role : new String[]{"ADMIN", "SCHOOL_ADMIN", "", null}) {
            assertFalse(service.designImageAccess(order, OP_A, role).allowed(), String.valueOf(role));
        }
    }

    // ---- the worklist ------------------------------------------------------------------------------------------------------

    private static String createNamed(boolean customised, long school, Long assignee, String name, String placedAt) {
        String order = "ORD-" + ORDERS.incrementAndGet();
        asNobody();
        service.create(new CreateTracker(order, school, customised, 1000, OffsetDateTime.parse(placedAt), "Asia/Kolkata", 77L, assignee, name));
        return order;
    }

    @Test
    void theWorklistShowsMyActiveStepsSoonestDeadlineFirstWithTheSchoolName() {
        // Each worklist test uses schools of its own: the database is shared with every other test in this class.
        String later = createNamed(true, 41L, OP_A, "School Forty-One", "2026-10-19T12:00:00+05:30");
        String sooner = createNamed(true, 41L, OP_A, "School Forty-One", "2026-10-19T09:00:00+05:30");
        createNamed(true, 41L, OP_C, "School Forty-One", "2026-10-19T08:00:00+05:30");

        var mine = as(() -> asOperator(OP_A, 41L), () -> service.list(true, 41L, false, 100));
        assertEquals(List.of(sooner, later), mine.stream().map(TrackerView.ListItem::orderId).toList());
        var first = mine.get(0);
        assertEquals("School Forty-One", first.schoolName());
        assertEquals("ORDER_DETAILS", first.status());
        assertEquals(1, first.currentStep());
        assertEquals("Order Details", first.currentTitle());
        assertEquals(OP_A, first.assigneeUserId());
        assertEquals(0, first.completed());
        assertEquals(12, first.total());
        assertTrue(first.customised());

        var all = as(() -> asOperator(OP_A, 41L), () -> service.list(false, 41L, false, 100));
        assertEquals(3, all.size(), "scope=all shows every order the operator may see, not only their own");
    }

    @Test
    void theWorklistCarriesEachOrdersOwnSchoolTimeZone() {
        String kolkata = createNamed(true, 46L, OP_A, "Forty-Six", "2026-10-19T09:00:00+05:30");
        String dubai = "ORD-" + ORDERS.incrementAndGet();
        asNobody();
        service.create(new CreateTracker(dubai, 46L, true, 1000, OffsetDateTime.parse("2026-10-19T09:00:00+04:00"), "Asia/Dubai", 77L, OP_A, "Forty-Six"));

        var items = as(() -> asSuper(), () -> service.list(false, 46L, false, 100));
        assertEquals(Map.of(kolkata, "Asia/Kolkata", dubai, "Asia/Dubai"),
                items.stream().collect(java.util.stream.Collectors.toMap(TrackerView.ListItem::orderId, TrackerView.ListItem::timeZone)),
                "each worklist row carries the zone its deadlines were computed in");
    }

    @Test
    void theWorklistIsBoundedByTheCallersScopeAndFilters() {
        createNamed(true, 42L, OP_A, "Forty-Two", "2026-10-19T09:00:00+05:30");
        createNamed(true, 43L, OP_B, "Forty-Three", "2026-10-19T09:00:00+05:30");
        assertEquals(List.of(42L), as(() -> asOperator(OP_A, 42L), () -> service.list(false, null, false, 500)).stream()
                .map(TrackerView.ListItem::schoolId).filter(id -> id == 42L || id == 43L).toList());
        assertEquals(403, http(() -> as(() -> asOperator(OP_B), () -> service.list(false, null, false, 500))).status(),
                "no assignment, no tracker permission");
        assertEquals(1, as(() -> asSuper(), () -> service.list(false, 43L, false, 100)).size());
        assertEquals(2, as(() -> asSuper(), () -> service.list(false, null, false, 500)).stream()
                .filter(item -> item.schoolId() == 42L || item.schoolId() == 43L).count());
        assertEquals(0, as(() -> asScopedOperator(OP_C, "42:TIES"), () -> service.list(false, null, false, 500)).size());
        assertEquals(1, as(() -> asScopedOperator(OP_C, "42:NOTEBOOKS"), () -> service.list(false, null, false, 500)).size());
    }

    @Test
    void theWorklistHidesFinishedOrdersUnlessAskedAndFlagsOverdueOnes() {
        String order = createNamed(false, 44L, OP_A, "Forty-Four", "2026-10-19T09:30:00+05:30");
        asOperator(OP_A, 44L);
        assertFalse(service.list(true, 44L, false, 100).get(0).overdue());
        now = Instant.parse("2026-10-19T09:00:00Z");
        assertTrue(service.list(true, 44L, false, 100).get(0).overdue(), "past the active step's deadline");

        advance(order, 12);
        asOperator(OP_A, 44L);
        assertEquals(0, service.list(false, 44L, false, 100).size());
        var finished = service.list(false, 44L, true, 100);
        assertEquals(1, finished.size());
        assertEquals("COMPLETED", finished.get(0).status());
        assertNull(finished.get(0).currentStep());
        assertFalse(finished.get(0).overdue());
    }

    @Test
    void theWorklistRefusesSchoolAdminsAndAnonymousCallers() {
        createNamed(true, 10L, OP_A, "Ten", "2026-10-19T09:00:00+05:30");
        TenantContext.set(new TenantContext(5L, "admin@x", "ADMIN", 10L, null, SCHOOL_ADMIN_WORKFLOW));
        assertEquals(403, http(() -> service.list(false, null, false, 10)).status());
        TenantContext.clear();
        assertEquals(403, http(() -> service.list(false, null, false, 10)).status());
    }

    @Test
    void theTrackerViewCarriesTheSchoolName() {
        String order = createNamed(true, 10L, OP_A, "  Named School  ", "2026-10-19T09:00:00+05:30");
        assertEquals("Named School", as(() -> asSuper(), () -> service.view(order)).schoolName());
        String plain = create(true, 10L, OP_A);
        assertNull(as(() -> asSuper(), () -> service.view(plain)).schoolName());
        asNobody();
        assertEquals(400, http(() -> service.create(new CreateTracker("ORD-LONG", 10, true, 1000, OffsetDateTime.parse("2026-10-19T09:00:00+05:30"),
                "Asia/Kolkata", 1L, null, "x".repeat(201)))).status());
    }

    /**
     * The generic workflow mutations treat a tracker as not there, exactly like the generic reads: the same 404 and message
     * as an id that does not exist, for every caller including superadmin, so nobody learns a tracker exists from them.
     */
    @Test
    void genericMutationsAnswerNotFoundForTrackers() {
        String order = "ORD-GEN-" + ORDERS.incrementAndGet();
        asNobody();
        long instanceId = service.create(new CreateTracker(order, 10, true, 1000,
                OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 77L, OP_A)).instanceId();
        long missing = owner.sql("SELECT COALESCE(max(id), 0) + 1000 FROM workflow.workflow_instances").query(Long.class).single();
        WorkflowReadRepository generic = new WorkflowReadRepository(JdbcClient.create(dataSource), "workflow");
        Map<String, Object> action = Map.of("expectedVersion", 0L, "actorId", SUPER);
        Map<String, java.util.function.LongConsumer> mutations = new java.util.LinkedHashMap<>();
        mutations.put("submit", id -> generic.submit(id, action));
        mutations.put("approve", id -> generic.approve(id, action));
        mutations.put("reject", id -> generic.reject(id, action));
        mutations.put("cancel", id -> generic.cancel(id, action));
        mutations.put("complete", id -> generic.complete(id, action));

        Runnable schoolAdmin = () -> TenantContext.set(new TenantContext(5L, "admin@x", "ADMIN", 10L, null, SCHOOL_ADMIN_WORKFLOW));
        Runnable superadmin = NotebookTrackerServiceIntegrationTest::asSuper;
        for (Runnable login : List.of(schoolAdmin, superadmin)) {
            for (var mutation : mutations.entrySet()) {
                ResponseStatusException tracker = as(login, () -> assertThrows(ResponseStatusException.class,
                        () -> mutation.getValue().accept(instanceId), mutation.getKey()));
                ResponseStatusException unknown = as(login, () -> assertThrows(ResponseStatusException.class,
                        () -> mutation.getValue().accept(missing), mutation.getKey()));
                assertEquals(404, tracker.getStatusCode().value(), mutation.getKey() + " on a tracker");
                assertEquals(404, unknown.getStatusCode().value(), mutation.getKey() + " on an unknown id");
                assertEquals(unknown.getReason(), tracker.getReason(), "no hint that the tracker exists");
            }
            ResponseStatusException create = as(login, () -> assertThrows(ResponseStatusException.class, () -> generic.createOrGetInstance(
                    Map.of("entityType", "notebook_order", "entityId", order, "schoolId", 10L, "definitionId", "NOTEBOOK_ORDER_TRACKER"))));
            assertEquals(404, create.getStatusCode().value(), "create-or-get never hands out a tracker either");
        }
        assertEquals("IN_PROGRESS", as(() -> asSuper(), () -> service.view(order)).instanceStatus(), "the tracker is untouched");
        assertEquals(0L, owner.sql("SELECT count(*) FROM workflow.workflow_actions WHERE instance_id = :id AND action IN ('SUBMIT', 'APPROVE', 'REJECT', 'CANCEL', 'COMPLETE')")
                .param("id", instanceId).query(Long.class).single());
    }

    /**
     * Product spec acceptance criterion 1: school admins do not see the tracker. They genuinely hold {@code workflow:read}
     * for their school's ordinary workflows, so the generic reads must not find a tracker, its history or its notes either.
     */
    @Test
    void theGenericWorkflowReadsNeverFindATracker() {
        String order = "ORD-GEN-" + ORDERS.incrementAndGet();
        asNobody();
        long trackerId = service.create(new CreateTracker(order, 10, true, 1000,
                OffsetDateTime.parse("2026-10-19T09:30:00+05:30"), "Asia/Kolkata", 77L, OP_A)).instanceId();
        as(() -> asOperator(OP_A, 10L), () -> service.addInternalNote(order, "Vendor called back"));
        // An ordinary supply-order workflow at the same school, which the school admin does see.
        long ordinary = owner.sql("INSERT INTO workflow.workflow_instances (definition_id, entity_type, entity_id, school_id, status) "
                        + "VALUES ('SUPPLY_ORDER_DEFAULT', 'SUPPLY_ORDER', :entity, 10, 'PENDING') RETURNING id")
                .param("entity", "SO-" + order).query(Long.class).single();
        owner.sql("INSERT INTO workflow.workflow_actions (instance_id, step_order, action, school_id) VALUES (:id, 0, 'SUBMIT', 10)")
                .param("id", ordinary).update();
        assertTrue(owner.sql("SELECT count(*) FROM workflow.workflow_actions WHERE instance_id = :id").param("id", trackerId)
                .query(Long.class).single() > 0, "the tracker has a history to hide");
        WorkflowReadRepository generic = new WorkflowReadRepository(JdbcClient.create(dataSource), "workflow");

        Runnable schoolAdmin = () -> TenantContext.set(new TenantContext(5L, "admin@x", "ADMIN", 10L, null, SCHOOL_ADMIN_WORKFLOW));
        Runnable superadmin = NotebookTrackerServiceIntegrationTest::asSuper;
        for (Runnable login : List.of(schoolAdmin, superadmin)) {
            List<Long> listed = as(login, () -> generic.instances(10L, null, null, 500)).stream()
                    .map(WorkflowReadRepository.WorkflowInstanceRow::id).toList();
            assertTrue(listed.contains(ordinary), "the school's ordinary workflows are still listed");
            assertFalse(listed.contains(trackerId), "but never a tracker");
            assertTrue(as(login, () -> generic.instances(10L, null, "NOTEBOOK_ORDER", 500)).isEmpty(), "not even when asked for by type");
            assertTrue(as(login, () -> generic.instance(trackerId)).isEmpty(), "a tracker is not found by id");
            assertTrue(as(login, () -> generic.instance(ordinary)).isPresent());
            assertTrue(as(login, () -> generic.actions(trackerId)).isEmpty(), "nor is its history");
            assertFalse(as(login, () -> generic.actions(ordinary)).isEmpty());
        }
    }

    /** A sign-off approves the data superadmin saw (spec §3.4): data saved after their screen loaded is refused, like a stale draft save. */
    @Test
    void aSignOffIsRefusedWhenTheStepChangedSinceItWasLoaded() {
        String order = create(false, 10L, OP_A);
        advance(order, 7);
        long loaded = step(as(() -> asSuper(), () -> service.view(order)), 8).version();
        as(() -> asOperator(OP_A, 10L), () -> service.saveDraft(order, 8, Map.of("sampleLink", "https://example.test/late"), loaded));

        asSuper();
        assertEquals(409, http(() -> service.signOff(order, 8, loaded)).status());
        assertNull(step(service.view(order), 8).data().get("signOff"), "nothing was signed off");

        TrackerView signed = service.signOff(order, 8, step(service.view(order), 8).version());
        assertNotNull(step(signed, 8).data().get("signOff"));
        assertEquals("https://example.test/late", step(signed, 8).data().get("sampleLink"), "the data seen is the data signed");
    }

    // ---- status outbox: dead letter, revival, closed orders and health (review follow-ups) ------------------------------

    static boolean deadLettered(String order) {
        return owner.sql("SELECT dead_lettered_at IS NOT NULL FROM workflow.workflow_tracker_status_outbox WHERE order_id = :order")
                .param("order", order).query(Boolean.class).single();
    }

    static String lastError(String order) {
        return owner.sql("SELECT last_error FROM workflow.workflow_tracker_status_outbox WHERE order_id = :order")
                .param("order", order).query(String.class).single();
    }

    static ListAppender<ILoggingEvent> capture(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    static void release(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    }

    /** The event as the JSON encoder writes it to Cloud Logging, whose log metrics read these fields. */
    static JsonNode json(ILoggingEvent event) {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            return JsonMapper.builder().build().readTree(new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            encoder.stop();
        }
    }

    @Test
    void statusRowDeadLettersAfterMaxAttempts() {
        String order = create(false, 10L, OP_A);
        catalogDown = true;
        as(() -> asOperator(OP_A, 10L), () -> service.completeStep(order, 1, step(service.view(order), 1).version()));
        long version = queued(order).orElseThrow().trackerVersion();
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET attempts = 47 WHERE order_id = :order").param("order", order).update();
        due(order);

        ListAppender<ILoggingEvent> logs = capture(TrackerStatusOutbox.class);
        try {
            assertEquals(0, statuses.drain(10));
        } finally {
            release(TrackerStatusOutbox.class, logs);
        }
        assertEquals(48, queued(order).orElseThrow().attempts());
        assertTrue(deadLettered(order));
        assertEquals("catalogue is down", lastError(order));
        List<ILoggingEvent> errors = logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), logs.list.toString());
        JsonNode logged = json(errors.get(0));
        assertEquals("tracker_status_dead_lettered", logged.path("event").asString());
        assertEquals(order, logged.path("orderId").asString());
        assertEquals(version, logged.path("trackerVersion").asLong());
        assertEquals(48, logged.path("attempts").asInt());
        assertEquals("catalogue is down", logged.path("lastError").asString());

        catalogDown = false;
        due(order);
        assertEquals(0, statuses.drain(10), "the drain skips a dead-lettered row");
        assertFalse(statuses.attempt(order), "and so does a direct attempt");
        assertTrue(PUSHED.isEmpty());
        assertEquals(48, queued(order).orElseThrow().attempts());
        assertTrue(deadLettered(order));
    }

    @Test
    void newerVersionRevivesDeadLetteredStatus() {
        String order = create(false, 10L, OP_A);
        catalogDown = true;
        asSuper();
        service.completeStep(order, 1, step(service.view(order), 1).version());
        Queued given = queued(order).orElseThrow();
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET attempts = 48, dead_lettered_at = now(), "
                + "next_attempt_at = now() + interval '1 hour' WHERE order_id = :order").param("order", order).update();

        // The same or an older version changes nothing: the row stays given up on.
        transactions.executeWithoutResult(status -> statuses.record(order, 10L, given.status(), given.trackerVersion()));
        assertTrue(deadLettered(order));
        assertEquals(48, queued(order).orElseThrow().attempts());

        service.completeStep(order, 5, step(service.view(order), 5).version());
        Queued revived = queued(order).orElseThrow();
        assertFalse(deadLettered(order), "a newer status revives the row");
        assertEquals("BOARD_PROCUREMENT", revived.status());
        assertTrue(revived.trackerVersion() > given.trackerVersion());
        assertEquals(1, revived.attempts(), "with fresh retries, of which the attempt after commit was the first");

        catalogDown = false;
        due(order);
        assertEquals(1, statuses.drain(10));
        assertEquals(List.of(order + ":BOARD_PROCUREMENT"), PUSHED);
        assertTrue(queued(order).isEmpty());
        TenantContext.clear();
    }

    /** School-core refuses any status for a closed order (409, e.g. cancelled): the row is dropped, not retried for two days. */
    @Test
    void aClosedOrdersStatusIsDroppedNotRetried() {
        String order = create(false, 10L, OP_A);
        catalogClosed = true;
        as(() -> asOperator(OP_A, 10L), () -> service.completeStep(order, 1, step(service.view(order), 1).version()));
        assertTrue(queued(order).isEmpty(), "the attempt after commit dropped it");
        assertTrue(PUSHED.isEmpty());

        // The same from the relay.
        catalogClosed = false;
        catalogDown = true;
        as(() -> asSuper(), () -> service.completeStep(order, 5, step(service.view(order), 5).version()));
        assertTrue(queued(order).isPresent());
        catalogDown = false;
        catalogClosed = true;
        due(order);
        assertEquals(0, statuses.drain(10));
        assertTrue(queued(order).isEmpty());
    }

    @Test
    void healthReportIncludesStatusOutbox() {
        Flyway.configure().dataSource(PG.getJdbcUrl(), "owner", "owner").schemas("firefighting").defaultSchema("firefighting")
                .locations("classpath:db/migration/firefighting").load().migrate();
        catalogDown = true;
        String waiting = create(false, 10L, OP_A);
        String stuck = create(false, 20L, OP_B);
        as(() -> asSuper(), () -> service.completeStep(waiting, 1, step(service.view(waiting), 1).version()));
        as(() -> asSuper(), () -> service.completeStep(stuck, 1, step(service.view(stuck), 1).version()));
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET created_at = now() - interval '2 hours', "
                + "updated_at = now() WHERE order_id = :order").param("order", waiting).update();
        owner.sql("UPDATE workflow.workflow_tracker_status_outbox SET dead_lettered_at = now(), created_at = now() - interval '9 hours' "
                + "WHERE order_id = :order").param("order", stuck).update();
        long outboxPending = owner.sql("SELECT count(*) FROM firefighting.outbox_events WHERE published_at IS NULL AND dead_lettered_at IS NULL")
                .query(Long.class).single();
        long outboxDead = owner.sql("SELECT count(*) FROM firefighting.outbox_events WHERE dead_lettered_at IS NOT NULL").query(Long.class).single();

        OutboxAsyncHealthReporter reporter = new OutboxAsyncHealthReporter(owner, "firefighting");
        reporter.configureBacklogs(List.of(statuses));
        ListAppender<ILoggingEvent> logs = capture(OutboxAsyncHealthReporter.class);
        try {
            reporter.report();
        } finally {
            release(OutboxAsyncHealthReporter.class, logs);
        }
        assertEquals(1, logs.list.size(), logs.list.toString());
        JsonNode outbox = json(logs.list.get(0)).path("health").path("outbox");
        assertEquals(outboxPending + 1, outbox.path("pendingCount").asLong());
        assertEquals(outboxDead + 1, outbox.path("deadLetterCount").asLong());
        long age = outbox.path("oldestPendingAgeSeconds").asLong();
        assertTrue(age >= 7200 && age < 7300, "the oldest pending status has waited since it was first queued, not since it was last tried: " + age);

        // A backlog that cannot be counted is skipped with a warning; the outbox figures are still reported.
        reporter.configureBacklogs(List.of(() -> { throw new IllegalStateException("cannot count"); }, statuses));
        ListAppender<ILoggingEvent> again = capture(OutboxAsyncHealthReporter.class);
        try {
            reporter.report();
        } finally {
            release(OutboxAsyncHealthReporter.class, again);
        }
        assertTrue(again.list.stream().anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("cannot count")), again.list.toString());
        JsonNode still = again.list.stream().filter(event -> event.getLevel() == Level.INFO).map(NotebookTrackerServiceIntegrationTest::json)
                .findFirst().orElseThrow().path("health").path("outbox");
        assertEquals(outboxPending + 1, still.path("pendingCount").asLong());
    }

    // ---- staff names (review follow-up: who is assigned, who wrote a note) ------------------------------------------------

    @Test
    void trackerViewCarriesAuthorAndAssigneeNames() {
        STAFF.put(OP_A, "Asha Operator");
        STAFF.put(SUPER, "Sam Super");
        NotebookTrackerService named = serviceWith(new StaffNames(DIRECTORY, CLOCK));
        String order = create(true, 10L, OP_A);
        as(() -> asOperator(OP_A, 10L), () -> named.addInternalNote(order, "Called the school"));
        // OP_C is not staff in identity's answer (a former operator, say): the name stays unknown.
        owner.sql("UPDATE workflow.workflow_instance_steps SET assignee_user_id = :user WHERE step_order = 2 AND instance_id = "
                + "(SELECT instance_id FROM workflow.workflow_notebook_trackers WHERE order_id = :order)").param("user", OP_C).param("order", order).update();
        TrackerView noted = as(() -> asSuper(), () -> named.addInternalNote(order, "Approved the vendor"));

        assertEquals(List.of("Asha Operator", "Sam Super"), noted.internalNotes().stream().map(TrackerView.Note::authorName).toList(),
                "a mutation's answer carries the names");
        TrackerView view = as(() -> asOperator(OP_A, 10L), () -> named.view(order));
        assertEquals("Asha Operator", step(view, 1).assigneeName());
        assertEquals(OP_C, step(view, 2).assigneeUserId());
        assertNull(step(view, 2).assigneeName());
        assertEquals(List.of("Asha Operator", "Sam Super"), view.internalNotes().stream().map(TrackerView.Note::authorName).toList());

        String unassigned = create(false, 10L, null);
        assertNull(step(as(() -> asSuper(), () -> named.view(unassigned)), 1).assigneeName());
        long asked = NAME_CALLS.size();
        as(() -> asSuper(), () -> named.view(order));
        assertEquals(asked, NAME_CALLS.size(), "names are cached");
    }

    @Test
    void namesFallBackToNullWhenIdentityIsDown() {
        STAFF.put(OP_A, "Asha Operator");
        NotebookTrackerService named = serviceWith(new StaffNames(DIRECTORY, CLOCK));
        String order = create(false, 10L, OP_A);
        directoryDown = true;
        TrackerView noted = as(() -> asOperator(OP_A, 10L), () -> named.addInternalNote(order, "Vendor called back"));
        assertNull(step(noted, 1).assigneeName());
        assertNull(noted.internalNotes().get(0).authorName());
        assertEquals("Vendor called back", noted.internalNotes().get(0).body(), "the change and its answer still go through");
        assertNull(step(as(() -> asOperator(OP_A, 10L), () -> named.view(order)), 1).assigneeName());

        directoryDown = false;
        TrackerView view = as(() -> asOperator(OP_A, 10L), () -> named.view(order));
        assertEquals("Asha Operator", step(view, 1).assigneeName(), "an outage is not remembered");
        assertEquals("Asha Operator", view.internalNotes().get(0).authorName());
    }

    /** The pool has five connections in production: identity is asked with none checked out and no transaction open. */
    @Test
    void nameLookupHoldsNoConnection() {
        STAFF.put(OP_A, "Asha Operator");
        STAFF.put(SUPER, "Sam Super");
        String order = create(false, 10L, OP_A);
        as(() -> asOperator(OP_A, 10L), () -> serviceWith(new StaffNames(DIRECTORY, CLOCK)).view(order));
        as(() -> asOperator(OP_A, 10L), () -> serviceWith(new StaffNames(DIRECTORY, CLOCK)).addInternalNote(order, "A note"));
        long loaded = step(as(() -> asSuper(), () -> service.view(order)), 1).version();
        as(() -> asSuper(), () -> serviceWith(new StaffNames(DIRECTORY, CLOCK)).completeStep(order, 1, loaded));
        assertEquals(3, NAME_CALLS.size(), NAME_CALLS.toString());
        assertTrue(NAME_CALLS.stream().allMatch(call -> call.endsWith(" active=0 transaction=false")), NAME_CALLS.toString());

        // Asked from inside a caller's transaction, the lookup does not call identity and the names stay unknown.
        NAME_CALLS.clear();
        TrackerView inside = as(() -> asSuper(), () -> transactions.execute(status -> serviceWith(new StaffNames(DIRECTORY, CLOCK)).view(order)));
        assertTrue(NAME_CALLS.isEmpty(), NAME_CALLS.toString());
        assertNull(step(inside, 1).assigneeName());
    }
}
