package com.custoking.ims.schoolcoreservice.persistence;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormController;
import com.custoking.ims.schoolcoreservice.api.CatalogReadController;
import com.custoking.ims.schoolcoreservice.api.StudentReadController;
import com.custoking.ims.schoolcoreservice.api.compat.CatalogPublicCompatibilityController;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.ImageUrlFetcher;
import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.OutboxAsyncHealthReporter;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.zaxxer.hikari.HikariDataSource;
import net.logstash.logback.encoder.LogstashEncoder;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import com.custoking.ims.schoolcoreservice.security.TenantAwareDataSource;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Notebook order tracker, school-core side (spec v4): placement starts the tracker, design drafts are capped and gated,
 * the email intent is recorded, and operators scoped to a catalogue item reach only that item. Runs against the real
 * migrated schema with the tracker and the operator directory replaced by controllable fakes.
 */
class NotebookTrackerIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    /** A second, independent connection: what other sessions see, so a peer call can be shown to follow the commit. */
    static JdbcClient observer;
    static DataSourceTransactionManager manager;
    static TransactionTemplate transaction;
    static NotebookTrackerCreationQueue queue;
    static CatalogReadRepository orders;
    static CatalogOrderFormService forms;
    @TempDir static Path assets;

    static final FakeTracker TRACKER = new FakeTracker();
    static final FakeOperators OPERATORS = new FakeOperators();

    static final class FakeTracker implements TrackerGateway {
        final List<NewTracker> created = new ArrayList<>();
        /** Every create call, failed or not: whether a transaction was open, and the order status other sessions saw. */
        final List<Boolean> createdInTransaction = new ArrayList<>();
        final List<String> statusOthersSaw = new ArrayList<>();
        boolean failCreate;
        boolean down;
        ImageAccess access = new ImageAccess(true, null);
        final List<String> accessChecks = new ArrayList<>();

        @Override public void create(NewTracker tracker) {
            createdInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            statusOthersSaw.add(observer.sql("SELECT status FROM catalog.catalog_orders WHERE id = :id").param("id", tracker.orderId())
                    .query(String.class).optional().orElse(null));
            if (failCreate) throw new IllegalStateException("tracker down");
            created.add(tracker);
        }
        @Override public ImageAccess imageAccess(String orderId, long userId, String role) {
            if (down) throw new IllegalStateException("tracker down");
            accessChecks.add(orderId + ":" + userId + ":" + role);
            return access;
        }
        int createCalls() { return createdInTransaction.size(); }
        void forget() { created.clear(); createdInTransaction.clear(); statusOthersSaw.clear(); }
        void reset() { forget(); accessChecks.clear(); failCreate = false; down = false; access = new ImageAccess(true, null); }
    }

    static final class FakeOperators implements OperatorResolver {
        String outcome = "NONE";
        Long userId;
        boolean down;
        /** A school whose resolution fails with an unexpected exception (not the client's IllegalStateException). */
        Long brokenSchool;
        final List<Boolean> resolvedInTransaction = new ArrayList<>();
        @Override public Resolution resolve(long schoolId, String catalogCategory) {
            resolvedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (down) throw new IllegalStateException("identity down");
            if (brokenSchool != null && brokenSchool == schoolId) throw new UnsupportedOperationException("unexpected resolver failure");
            return new Resolution(outcome, userId);
        }
        void reset() { outcome = "NONE"; userId = null; down = false; brokenSchool = null; resolvedInTransaction.clear(); }
    }

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        for (String schema : List.of("tenant_school", "catalog")) {
            Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/" + schema).load().migrate();
        }
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        observer = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner"));
        manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        var outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        var products = new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, true);
        forms = new CatalogOrderFormService(jdbc, products, new ProductFormRuleEngine(),
                new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, null);
        queue = new NotebookTrackerCreationQueue(jdbc, TRACKER, OPERATORS, manager);
        forms.configureTracker(TRACKER, queue);
        // As in the application: the form service opens and closes its own transactions around the tracker's calls.
        forms.configureTransactions(manager);
        orders = new CatalogReadRepository(jdbc, outbox);
        orders.configureProductForms(forms, products);
        jdbc.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at, time_zone, contact_email, contact_phone) VALUES (1,'School A','SCA',true,now(),'Asia/Kolkata','office@school-a.test','+91 98765 43210'),(2,'School B','SCB',true,now(),'Asia/Kolkata',NULL,NULL)").update();
        jdbc.sql("INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled) VALUES (1,'ORDERS',true),(2,'ORDERS',true)").update();
        jdbc.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        jdbc.sql("GRANT USAGE ON SCHEMA catalog, tenant_school TO app_rt").update();
        jdbc.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog, tenant_school TO app_rt").update();
        jdbc.sql("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA catalog, tenant_school TO app_rt").update();
    }

    @BeforeEach
    void clear() {
        tx(() -> {
            jdbc.sql("DELETE FROM catalog.catalog_orders").update();
            jdbc.sql("DELETE FROM tenant_school.outbox_events").update();
            return null;
        });
        TRACKER.reset();
        OPERATORS.reset();
        school(1);
    }

    @AfterEach void clearContext() { TenantContext.clear(); }
    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    // ---- placement starts the tracker ----------------------------------------------------------------------------------

    @Test
    void placementStartsTheTrackerWithTheOrdersFactsAndTheItemSpecificOperator() {
        OPERATORS.outcome = "ITEM_SPECIFIC";
        OPERATORS.userId = 42L;
        var order = notebook(true);
        var placed = place(order);

        assertThat(placed.status()).isEqualTo("ORDER_DETAILS");
        assertThat(TRACKER.created).hasSize(1);
        var created = TRACKER.created.getFirst();
        assertThat(created.orderId()).isEqualTo(order);
        assertThat(created.schoolId()).isEqualTo(1L);
        assertThat(created.customised()).isTrue();
        assertThat(created.orderedCount()).as("every ruling line, after the 1000 floor").isEqualTo(2000);
        assertThat(created.timeZone()).isEqualTo("Asia/Kolkata");
        assertThat(created.schoolName()).isEqualTo("School A");
        assertThat(created.placedBy()).isEqualTo(7L);
        assertThat(created.assigneeUserId()).isEqualTo(42L);
        assertThat(created.placedAt()).isNotNull();
        assertThat(pending(order)).isFalse();
    }

    @Test
    void aSchoolWideOperatorIsUsedWhenNoItemOperatorExistsAndAmbiguityLeavesTheOrderUnassigned() {
        OPERATORS.outcome = "SCHOOL_WIDE";
        OPERATORS.userId = 6L;
        place(notebook(false));
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isEqualTo(6L);
        assertThat(TRACKER.created.getFirst().customised()).isFalse();

        TRACKER.reset();
        OPERATORS.outcome = "AMBIGUOUS";
        OPERATORS.userId = null;
        place(notebook(true));
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isNull();

        TRACKER.reset();
        OPERATORS.outcome = "NONE";
        place(notebook(true));
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isNull();
    }

    /**
     * Without an answer from identity the order's operator is unknown, not absent: the order stays queued and a later attempt
     * assigns it. Only a definitive answer (no operator, or several) creates the tracker unassigned for superadmin.
     */
    @Test
    void identityUnavailableKeepsTheOrderQueued() {
        OPERATORS.down = true;
        var order = notebook(true);
        assertThat(place(order).status()).isEqualTo("ORDER_DETAILS");
        assertThat(TRACKER.createCalls()).as("no tracker until identity answers").isZero();
        assertThat(queued(order)).isEqualTo(1);
        assertThat(pending(order)).isTrue();
        assertThat(lastError(order)).isEqualTo("identity down");

        OPERATORS.down = false;
        OPERATORS.outcome = "ITEM_SPECIFIC";
        OPERATORS.userId = 42L;
        due(order);
        TenantContext.clear();
        assertThat(queue.drain(5)).isEqualTo(1);
        assertThat(TRACKER.created).singleElement().satisfies(created -> assertThat(created.assigneeUserId()).as("the retry finds the operator").isEqualTo(42L));
        assertThat(queued(order)).isZero();

        // An answer that cannot be read, or has no outcome at all, is no answer either; its reason is kept so it can dead-letter.
        for (String unreadable : java.util.Arrays.asList("SOMETHING_NEW", null)) {
            TRACKER.reset();
            OPERATORS.outcome = unreadable;
            OPERATORS.userId = null;
            var waiting = notebook(false);
            place(waiting);
            assertThat(TRACKER.createCalls()).as(String.valueOf(unreadable)).isZero();
            assertThat(queued(waiting)).isEqualTo(1);
            assertThat(lastError(waiting)).as(String.valueOf(unreadable)).isEqualTo("operator directory gave no usable answer");
        }

        for (String definitive : List.of("NONE", "AMBIGUOUS")) {
            TRACKER.reset();
            OPERATORS.outcome = definitive;
            var answered = notebook(false);
            place(answered);
            assertThat(TRACKER.created).as(definitive).singleElement().satisfies(created -> assertThat(created.assigneeUserId()).isNull());
            assertThat(queued(answered)).isZero();
            assertThat(pending(answered)).isFalse();
        }
    }

    /** A row that keeps failing stops being retried after MAX_ATTEMPTS and says so loudly, with fields a log metric can read. */
    @Test
    void creationRowDeadLettersAfterMaxAttempts() {
        TRACKER.failCreate = true;
        var order = notebook(false);
        place(order);
        TenantContext.clear();
        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET attempts = 47 WHERE order_id = :id").param("id", order).update();
        due(order);

        var logs = capture(NotebookTrackerCreationQueue.class);
        try {
            assertThat(queue.drain(5)).isZero();
        } finally {
            release(NotebookTrackerCreationQueue.class, logs);
        }
        assertThat(attempts(order)).isEqualTo(48);
        assertThat(deadLettered(order)).isTrue();
        assertThat(lastError(order)).isEqualTo("tracker down");
        assertThat(logs.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList()).singleElement().satisfies(event -> {
            JsonNode logged = json(event);
            assertThat(logged.path("event").asString()).isEqualTo("tracker_creation_dead_lettered");
            assertThat(logged.path("orderId").asString()).isEqualTo(order);
            assertThat(logged.path("attempts").asInt()).isEqualTo(48);
            assertThat(logged.path("lastError").asString()).isEqualTo("tracker down");
        });

        int calls = TRACKER.createCalls();
        due(order);
        assertThat(queue.drain(5)).as("the drain skips a dead-lettered row").isZero();
        assertThat(queue.attempt(order)).isFalse();
        assertThat(TRACKER.createCalls()).isEqualTo(calls);
        assertThat(attempts(order)).isEqualTo(48);
        assertThat(pending(order)).as("the order still waits for its tracker").isTrue();
    }

    @Test
    void ensureTrackerRevivesADeadLetteredRow() {
        TRACKER.failCreate = true;
        OPERATORS.outcome = "ITEM_SPECIFIC";
        OPERATORS.userId = 42L;
        var order = notebook(true);
        place(order);
        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET attempts = 48, dead_lettered_at = now(), next_attempt_at = now() + interval '1 hour' WHERE order_id = :id")
                .param("id", order).update();
        int calls = TRACKER.createCalls();

        superadmin();
        forms.ensureTracker(order);
        assertThat(TRACKER.createCalls()).as("retried at once").isEqualTo(calls + 1);
        assertThat(deadLettered(order)).as("back in the queue").isFalse();
        assertThat(attempts(order)).as("with fresh retries, of which this was the first").isEqualTo(1);
        assertThat(minutesToNextAttempt(order)).isCloseTo(2.0, within(0.2));

        TRACKER.failCreate = false;
        forms.ensureTracker(order);
        assertThat(TRACKER.created).singleElement().satisfies(created -> assertThat(created.assigneeUserId()).isEqualTo(42L));
        assertThat(queued(order)).isZero();
        assertThat(pending(order)).isFalse();
    }

    /**
     * A row whose order is closed has nothing left to create: the tracker's own COMPLETED push clears it (the push proves the
     * tracker exists), a drain clears a due one, and a closed order's row never counts toward the outbox alerts, even one
     * that was given up on (ensure refuses closed orders, so nothing else could clear it).
     */
    @Test
    void aClosedOrdersCreationRowIsClearedAndNotCounted() {
        TRACKER.failCreate = true;
        var completed = notebook(false);
        place(completed);
        var cancelled = notebook(false);
        place(cancelled);
        var delivered = notebook(false);
        place(delivered);
        var open = notebook(false);
        place(open);
        for (String order : List.of(completed, delivered, open)) {
            jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET attempts = 48, dead_lettered_at = now() WHERE order_id = :id").param("id", order).update();
        }
        TenantContext.clear();
        assertThat(queue.backlog().pendingCount()).isEqualTo(1);
        assertThat(queue.backlog().deadLetterCount()).isEqualTo(3);

        tx(() -> { forms.applyTrackerStatus(completed, "COMPLETED", 9L); return null; });
        assertThat(queued(completed)).as("the tracker exists, so its creation is done").isZero();
        assertThat(pending(completed)).isFalse();

        // No flow cancels or delivers a tracker order, but a data fix can.
        jdbc.sql("UPDATE catalog.catalog_orders SET status = 'CANCELLED' WHERE id = :id").param("id", cancelled).update();
        jdbc.sql("UPDATE catalog.catalog_orders SET status = 'DELIVERED' WHERE id = :id").param("id", delivered).update();
        assertThat(queue.backlog().pendingCount()).as("the cancelled order's row is not pending work").isZero();
        assertThat(queue.backlog().deadLetterCount()).as("only the open order's row").isEqualTo(1);

        int calls = TRACKER.createCalls();
        due(cancelled);
        assertThat(queue.drain(5)).isZero();
        assertThat(queued(cancelled)).as("cleared by the drain, not attempted").isZero();
        assertThat(TRACKER.createCalls()).isEqualTo(calls);
        assertThat(queued(open)).isEqualTo(1);
    }

    /** The queue's backlog is added into the outbox health figures, so the outbox pending, age and dead-letter alerts cover it. */
    @Test
    void healthReportIncludesTrackerQueue() {
        TRACKER.failCreate = true;
        var waiting = notebook(false);
        place(waiting);
        var stuck = notebook(false);
        place(stuck);
        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET created_at = now() - interval '2 hours' WHERE order_id = :id").param("id", waiting).update();
        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET dead_lettered_at = now() WHERE order_id = :id").param("id", stuck).update();
        long outboxPending = jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events WHERE published_at IS NULL AND dead_lettered_at IS NULL")
                .query(Long.class).single();
        long outboxDead = jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events WHERE dead_lettered_at IS NOT NULL").query(Long.class).single();
        TenantContext.clear();

        assertThat(queue.queueName()).as("named in the health log if it cannot be counted").isEqualTo("notebook_tracker_creation_queue");
        var reporter = new OutboxAsyncHealthReporter(jdbc, "tenant_school");
        reporter.configureBacklogs(List.of(queue));
        var logs = capture(OutboxAsyncHealthReporter.class);
        try {
            reporter.report();
        } finally {
            release(OutboxAsyncHealthReporter.class, logs);
        }
        assertThat(logs.list).singleElement().satisfies(event -> {
            JsonNode outbox = json(event).path("health").path("outbox");
            assertThat(outbox.path("pendingCount").asLong()).isEqualTo(outboxPending + 1);
            assertThat(outbox.path("deadLetterCount").asLong()).isEqualTo(outboxDead + 1);
            assertThat(outbox.path("oldestPendingAgeSeconds").asLong()).isBetween(7200L, 7300L);
        });
    }

    /** Called from inside a transaction, a peer call is refused rather than made while that transaction holds its connection. */
    @Test
    void aPeerCallInsideATransactionIsRefusedRatherThanMadeHoldingItsConnection() throws Exception {
        var order = notebook(true);
        tx(() -> orders.placeOrder(order, 7L));
        assertThat(tx(() -> queue.attempt(order))).as("an attempt inside a transaction leaves the order for the relay").isFalse();
        assertThatThrownBy(() -> tx(() -> queue.drain(5))).isInstanceOf(IllegalStateException.class);
        assertThat(TRACKER.createCalls()).isZero();
        assertThat(attempts(order)).isZero();

        superadmin();
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        assertThatThrownBy(() -> tx(() -> forms.upload(order, "DESIGN_DRAFT", png.toByteArray(), "draft.png"))).isInstanceOf(IllegalStateException.class);
        assertThat(TRACKER.accessChecks).isEmpty();
        assertThat(currentDrafts(order)).isZero();

        assertThat(queue.attempt(order)).as("outside one, it goes ahead").isTrue();
    }

    /**
     * The pool holds five connections and waits 30 s for one. A peer call made while holding one, plus the second a nested
     * step takes, let five concurrent requests stall it. So the placing request releases its connection before the tracker
     * is attempted, and the ensure attempt and the draft image-access check run with none checked out. Driven through the
     * real controllers and transaction proxies over a HikariCP pool, as deployed.
     */
    @Test
    void noPooledConnectionHeldDuringPeerCalls() throws Exception {
        List<String> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl(pg.getJdbcUrl());
            pool.setUsername("app_rt");
            pool.setPassword("app_rt");
            pool.setMaximumPoolSize(5);
            pool.setConnectionTimeout(5000);
            java.util.function.IntSupplier active = () -> pool.getHikariPoolMXBean().getActiveConnections();
            TrackerGateway peer = new TrackerGateway() {
                @Override public void create(NewTracker tracker) { observed.add("create " + active.getAsInt()); }
                @Override public ImageAccess imageAccess(String orderId, long userId, String role) {
                    observed.add("image-access " + active.getAsInt());
                    return new ImageAccess(true, null);
                }
            };
            OperatorResolver identity = (school, category) -> {
                observed.add("resolve " + active.getAsInt());
                return new OperatorResolver.Resolution("NONE", null);
            };
            var runtime = new TenantAwareDataSource(pool);
            var runtimeJdbc = JdbcClient.create(runtime);
            var runtimeManager = new DataSourceTransactionManager(runtime);
            var runtimeOutbox = new OutboxWriter(runtimeJdbc, new ObjectMapper(), "tenant_school");
            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(TransactionalTestConfiguration.class);
                context.registerBean("transactionManager", PlatformTransactionManager.class, () -> runtimeManager);
                context.registerBean(TrackerGateway.class, () -> peer);
                context.registerBean(NotebookTrackerCreationQueue.class, () -> new NotebookTrackerCreationQueue(runtimeJdbc, peer, identity, runtimeManager));
                context.registerBean(ProductCatalogRepository.class, () -> new ProductCatalogRepository(runtimeJdbc, new ObjectMapper(), runtimeOutbox, true));
                context.registerBean(CatalogOrderFormService.class, () -> new CatalogOrderFormService(runtimeJdbc,
                        context.getBean(ProductCatalogRepository.class), new ProductFormRuleEngine(),
                        new CatalogOrderAssetStorage("", "local", assets.toString()), runtimeOutbox, null));
                context.registerBean(CatalogReadRepository.class, () -> new CatalogReadRepository(runtimeJdbc, runtimeOutbox));
                context.refresh();
                var runtimeOrders = context.getBean(CatalogReadRepository.class);
                var runtimeForms = context.getBean(CatalogOrderFormService.class);
                var catalogApi = new CatalogReadController(runtimeOrders, "tok");
                var compatibleApi = new CatalogPublicCompatibilityController(runtimeOrders, "tok");
                var formApi = new CatalogOrderFormController(runtimeForms, runtimeOrders, "tok");

                school(1);
                String first = runtimeOrders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "orderData", data(true, 400, 600))).id();
                String second = runtimeOrders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "orderData", data(false, 30, 30))).id();
                catalogApi.placeOrder("tok", first, null);
                compatibleApi.placeOrder("tok", second);
                assertThat(observed).as("the attempt after each placement").containsExactly("resolve 0", "create 0", "resolve 0", "create 0");

                observed.clear();
                superadmin();
                assertThat(formApi.ensureTracker("tok", first)).containsEntry("trackerPending", false);
                var png = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
                var drafted = formApi.upload("tok", first, "DESIGN_DRAFT", new MockMultipartFile("file", "draft.png", "image/png", png.toByteArray()));
                formApi.remove("tok", first, id(drafted));
                assertThat(observed).as("ensure, then the draft upload's and delete's access checks")
                        .containsExactly("resolve 0", "create 0", "image-access 0", "image-access 0");
                assertThat(currentDrafts(first)).isZero();
                assertThat(pending(first) || pending(second)).isFalse();
                assertThat(active.getAsInt()).isZero();
            }
        }
    }

    @Test
    void placementCommitsWhenTrackerUnreachable() {
        TRACKER.failCreate = true;
        var order = notebook(true);
        var placed = place(order);

        assertThat(placed.status()).isEqualTo("ORDER_DETAILS");
        assertThat(status(order)).as("committed, not rolled back").isEqualTo("ORDER_DETAILS");
        assertThat(managed(order)).isTrue();
        assertThat(pending(order)).isTrue();
        assertThat(queued(order)).isEqualTo(1);
        assertThat(TRACKER.createCalls()).as("attempted once after commit").isEqualTo(1);
        assertThat(TRACKER.created).isEmpty();
        assertThat(lastError(order)).contains("tracker down");
    }

    @Test
    void placementMakesNoPeerCallInsideTransaction() {
        OPERATORS.outcome = "ITEM_SPECIFIC";
        OPERATORS.userId = 42L;
        var order = notebook(true);
        place(order);

        assertThat(TRACKER.createdInTransaction).containsExactly(false);
        assertThat(OPERATORS.resolvedInTransaction).containsExactly(false);
        assertThat(TRACKER.statusOthersSaw).as("the tracker is asked only once the placement is visible to everyone").containsExactly("ORDER_DETAILS");
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isEqualTo(42L);
        assertThat(pending(order)).isFalse();
        assertThat(managed(order)).isTrue();
        assertThat(queued(order)).isZero();
    }

    @Test
    void drainCreatesTrackerOnce() {
        TRACKER.failCreate = true;
        var order = notebook(true);
        place(order);
        TenantContext.clear(); // the relay trigger runs with no user

        assertThat(queue.drain(5)).as("still backing off after the failed attempt").isZero();
        assertThat(TRACKER.createCalls()).isEqualTo(1);

        TRACKER.failCreate = false;
        due(order);
        assertThat(queue.drain(5)).isEqualTo(1);
        assertThat(queue.drain(5)).as("a second drain creates nothing").isZero();

        assertThat(TRACKER.created).hasSize(1);
        assertThat(TRACKER.created.getFirst().placedBy()).as("taken from the order row, not the caller").isEqualTo(7L);
        assertThat(TRACKER.created.getFirst().orderedCount()).isEqualTo(2000);
        assertThat(TRACKER.createdInTransaction).containsOnly(false);
        assertThat(queued(order)).isZero();
        assertThat(pending(order)).isFalse();
        assertThat(managed(order)).isTrue();
    }

    @Test
    void failedAttemptsBackOffDoublingUpToAnHour() {
        TRACKER.failCreate = true;
        var order = notebook(false);
        place(order);
        TenantContext.clear();
        assertThat(attempts(order)).isEqualTo(1);
        assertThat(minutesToNextAttempt(order)).isCloseTo(2.0, within(0.2));

        due(order);
        assertThat(queue.drain(5)).isZero();
        assertThat(attempts(order)).isEqualTo(2);
        assertThat(minutesToNextAttempt(order)).isCloseTo(4.0, within(0.2));

        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET attempts = 9 WHERE order_id = :id").param("id", order).update();
        due(order);
        assertThat(queue.drain(5)).isZero();
        assertThat(minutesToNextAttempt(order)).isCloseTo(60.0, within(0.2));
        assertThat(pending(order)).isTrue();
    }

    @Test
    void aRowThatHasFailedForWeeksIsStillClaimedAndWaitsAtMostAnHour() {
        TRACKER.failCreate = true;
        var order = notebook(false);
        place(order);
        TenantContext.clear();
        // About six weeks at the hourly cap: 2^1101 minutes would overflow PostgreSQL's float8.
        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET attempts = 1100 WHERE order_id = :id").param("id", order).update();
        due(order);

        assertThat(queue.drain(5)).isZero();
        assertThat(attempts(order)).isEqualTo(1101);
        assertThat(minutesToNextAttempt(order)).isCloseTo(60.0, within(0.2));
        assertThat(lastError(order)).contains("tracker down");
    }

    @Test
    void oneFailingRowNeverStopsTheDrainFromReachingTheNextDueRow() {
        TRACKER.failCreate = true;
        var refusedAtClaim = notebook(false);
        place(refusedAtClaim);
        school(2);
        var failsWhileCreating = tx(() -> orders.createOrder(Map.of("schoolId", 2, "category", "NOTEBOOKS", "orderData", data(false, 30, 30)))).id();
        place(failsWhileCreating);
        var healthy = notebook(false);
        place(healthy);
        TenantContext.clear();
        TRACKER.failCreate = false;
        OPERATORS.brokenSchool = 2L;
        // Oldest first, so both broken rows come before the healthy one.
        for (var row : List.of(Map.entry(refusedAtClaim, 30), Map.entry(failsWhileCreating, 20), Map.entry(healthy, 10))) {
            jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET next_attempt_at = now() - make_interval(secs => :ago) WHERE order_id = :id")
                    .param("ago", row.getValue()).param("id", row.getKey()).update();
        }
        jdbc.sql("CREATE FUNCTION catalog.test_refuse_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'claim refused'; END $$").update();
        jdbc.sql("CREATE TRIGGER test_refuse_claim BEFORE UPDATE ON catalog.notebook_tracker_creation_queue FOR EACH ROW WHEN (OLD.order_id = '"
                + refusedAtClaim + "') EXECUTE FUNCTION catalog.test_refuse_claim()").update();
        try {
            assertThat(queue.drain(5)).as("the healthy order behind two failing ones still gets its tracker").isEqualTo(1);
        } finally {
            jdbc.sql("DROP TRIGGER test_refuse_claim ON catalog.notebook_tracker_creation_queue").update();
            jdbc.sql("DROP FUNCTION catalog.test_refuse_claim()").update();
        }
        assertThat(TRACKER.created).extracting(TrackerGateway.NewTracker::orderId).containsExactly(healthy);
        assertThat(queued(healthy)).isZero();
        assertThat(attempts(refusedAtClaim)).as("its claim rolled back").isEqualTo(1);
        assertThat(attempts(failsWhileCreating)).as("claimed, then failed while creating").isEqualTo(2);
        assertThat(pending(refusedAtClaim)).isTrue();
        assertThat(pending(failsWhileCreating)).isTrue();
    }

    @Test
    void anUnreachableTrackerCanBeStartedByHandOnceItIsBack() {
        TRACKER.failCreate = true;
        OPERATORS.outcome = "ITEM_SPECIFIC";
        OPERATORS.userId = 42L;
        var order = notebook(true);
        assertThat(place(order).status()).isEqualTo("ORDER_DETAILS");
        assertThat(TRACKER.created).isEmpty();
        assertThat(pending(order)).isTrue();

        TRACKER.failCreate = false;
        superadmin();
        forms.ensureTracker(order);
        assertThat(TRACKER.created).hasSize(1);
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isEqualTo(42L);
        assertThat(TRACKER.createdInTransaction).containsOnly(false);
        assertThat(pending(order)).isFalse();
        assertThat(queued(order)).isZero();
    }

    @Test
    void ensuringATrackerNeedsAPlacedNotebookOrderAndAnOperatorOrSuperadmin() {
        var draft = notebook(true);
        superadmin();
        assertThatThrownBy(() -> forms.ensureTracker(draft)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("placed notebook");
        school(1);
        assertThatThrownBy(() -> forms.ensureTracker(draft)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThat(TRACKER.createCalls()).isZero();
    }

    @Test
    void ensureTrackerRefusesClosedOrders() {
        var order = placedNotebook(true);
        var legacy = placedBeforeTheTracker(true);
        superadmin();
        for (String closed : List.of("CANCELLED", "DELIVERED", "COMPLETED")) {
            jdbc.sql("UPDATE catalog.catalog_orders SET status = :status WHERE id = :id").param("status", closed).param("id", order).update();
            assertThatThrownBy(() -> forms.ensureTracker(order), closed)
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThatThrownBy(() -> forms.ensureTracker(legacy)).as("an order placed before the tracker keeps its old flow")
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(TRACKER.createCalls()).isZero();
        assertThat(queued(order) + queued(legacy)).isZero();
    }

    @Test
    void otherCategoriesPlaceAsBeforeAndNeverTouchTheTracker() {
        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", data)));
        assertThat(trophy.pricingStatus()).isEqualTo("PENDING_PRICING");
        assertThat(place(trophy.id()).status()).isEqualTo("PROCESSING");
        assertThat(TRACKER.created).isEmpty();
        assertThatThrownBy(() -> tx(() -> forms.trackerSummary(trophy.id()))).isInstanceOf(ResponseStatusException.class);
    }

    // ---- design drafts -----------------------------------------------------------------------------------------------------

    @Test
    void draftsAreCappedAtSixAndOnlyCurrentOnesCount() {
        var order = placedNotebook(true);
        operator(8L, Set.of(1L), Set.of());
        List<Long> ids = new ArrayList<>();
        for (int color = 1; color <= 6; color++) ids.add(id(draft(order, color)));

        assertThatThrownBy(() -> draft(order, 7)).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getReason()).isEqualTo("No more images allowed. Contact IT.");
        });
        assertThat(currentDrafts(order)).isEqualTo(6);

        assertThat(id(draft(order, 3))).as("the same file again is not a new draft").isEqualTo(ids.get(2));

        forms.removeAsset(order, ids.get(0));
        assertThat(currentDrafts(order)).isEqualTo(5);
        draft(order, 7);
        assertThat(currentDrafts(order)).isEqualTo(6);
        assertThat(TRACKER.accessChecks).allMatch(check -> check.startsWith(order + ":8:OPERATIONS"));
    }

    @Test
    void anEmailIntentIsRecordedEachTimeTheDraftsRiseAboveFourAndNeverBefore() {
        var order = placedNotebook(true);
        operator(8L, Set.of(1L), Set.of());
        List<Long> ids = new ArrayList<>();
        for (int color = 1; color <= 4; color++) ids.add(id(draft(order, color)));
        assertThat(intents(order)).isEmpty();

        ids.add(id(draft(order, 5)));
        assertThat(intents(order)).containsExactly("5:NOT_SENT:ACTIVE_SUPERADMINS");
        ids.add(id(draft(order, 6)));
        assertThat(intents(order)).as("the sixth draft adds nothing").hasSize(1);

        forms.removeAsset(order, ids.get(0));
        forms.removeAsset(order, ids.get(1));
        assertThat(currentDrafts(order)).isEqualTo(4);
        draft(order, 8);
        assertThat(intents(order)).as("back above four after dropping to four").containsExactly("5:NOT_SENT:ACTIVE_SUPERADMINS", "5:NOT_SENT:ACTIVE_SUPERADMINS");
    }

    @Test
    void theTrackerDecidesWhoMayChangeDraftsAndAnUnreachableTrackerRefusesInsteadOfGuessing() {
        var order = placedNotebook(true);
        operator(8L, Set.of(1L), Set.of());
        TRACKER.access = new TrackerGateway.ImageAccess(false, "This step is assigned to another operator");
        assertThatThrownBy(() -> draft(order, 1)).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(ex.getReason()).isEqualTo("This step is assigned to another operator");
        });
        assertThat(currentDrafts(order)).isZero();

        TRACKER.access = new TrackerGateway.ImageAccess(true, null);
        var kept = id(draft(order, 1));
        TRACKER.access = new TrackerGateway.ImageAccess(false, "locked");
        assertThatThrownBy(() -> forms.removeAsset(order, kept)).isInstanceOf(ResponseStatusException.class);
        assertThat(currentDrafts(order)).isEqualTo(1);

        TRACKER.access = new TrackerGateway.ImageAccess(true, null);
        TRACKER.down = true;
        assertThatThrownBy(() -> draft(order, 2)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void draftsAreForSuperadminAndOperatorsOnPlacedCustomisedNotebookOrdersOnly() {
        var placed = placedNotebook(true);
        school(1);
        assertThatThrownBy(() -> draft(placed, 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(TRACKER.accessChecks).as("a school admin never reaches the tracker").isEmpty();

        superadmin();
        var wholesale = placedNotebook(false);
        assertThatThrownBy(() -> draft(wholesale, 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        school(1);
        var unplaced = notebook(true);
        superadmin();
        assertThatThrownBy(() -> draft(unplaced, 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        draft(placed, 1);
    }

    @Test
    void theSchoolNeverSeesDesignDraftsButTheTeamDoes() {
        var order = placedNotebook(true);
        operator(8L, Set.of(1L), Set.of());
        var draft = draft(order, 1);
        long draftId = id(draft);

        assertThat(tx(() -> forms.assets(order))).extracting(a -> a.get("assetKind")).contains("DESIGN_DRAFT");
        school(1);
        assertThat(tx(() -> forms.assets(order))).extracting(a -> a.get("assetKind")).doesNotContain("DESIGN_DRAFT");
        assertThat(tx(() -> forms.assetHistory(order))).extracting(a -> a.get("assetKind")).doesNotContain("DESIGN_DRAFT");
        assertThatThrownBy(() -> tx(() -> forms.content(order, draftId))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        superadmin();
        assertThat(tx(() -> forms.content(order, draftId)).bytes()).isNotEmpty();
    }

    @Test
    void draftsAreNotTheSchoolsSampleDesignSoUploadingOneNeverReplacesTheOther() {
        var order = placedNotebook(true);
        school(1);
        upload(order, "DESIGN", 100);
        operator(8L, Set.of(1L), Set.of());
        draft(order, 1);
        draft(order, 2);
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.catalog_order_assets WHERE order_id = :id AND asset_kind = 'DESIGN' AND superseded_at IS NULL")
                .param("id", order).query(Long.class).single()).isEqualTo(1L);
        assertThat(currentDrafts(order)).isEqualTo(2);
    }

    // ---- machine endpoints for the tracker -----------------------------------------------------------------------------------

    @Test
    void theTrackerCanListCurrentDraftsWithoutAUserAndOnlyForNotebooks() {
        var order = placedNotebook(true);
        operator(8L, Set.of(1L), Set.of());
        long first = id(draft(order, 1));
        long second = id(draft(order, 2));
        forms.removeAsset(order, first);
        TenantContext.clear();

        var listed = tx(() -> forms.draftImagesForTracker(order));
        assertThat(listed).extracting(d -> d.get("id")).containsExactly(second);
        assertThat(listed.getFirst()).containsKeys("contentType", "originalFilename", "uploadedAt");

        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", data)));
        TenantContext.clear();
        assertThatThrownBy(() -> tx(() -> forms.draftImagesForTracker(trophy.id()))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tx(() -> forms.draftImagesForTracker("CK-NO-SUCH"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void theOrderStatusFollowsTheTrackerAndIsPublishedToReporting() {
        var order = placedNotebook(true);
        TenantContext.clear();
        tx(() -> jdbc.sql("DELETE FROM tenant_school.outbox_events").update());

        tx(() -> { forms.applyTrackerStatus(order, "printing_press", 1L); return null; });
        assertThat(status(order)).isEqualTo("PRINTING_PRESS");
        var event = jdbc.sql("SELECT payload::text FROM tenant_school.outbox_events WHERE event_type = 'catalog-order.upserted.v1' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(event).contains("PRINTING_PRESS");

        long events = jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single();
        tx(() -> { forms.applyTrackerStatus(order, "PRINTING_PRESS", 2L); return null; });
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single()).as("no change, no event").isEqualTo(events);

        tx(() -> { forms.applyTrackerStatus(order, "COMPLETED", 3L); return null; });
        assertThat(status(order)).isEqualTo("COMPLETED");
    }

    @Test
    void aTrackerStatusIsRefusedForDraftsBadCodesAndOtherCategories() {
        var draft = notebook(true);
        TenantContext.clear();
        assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(draft, "PRINTING_PRESS", 1L); return null; }))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        school(1);
        var placed = placedNotebook(true);
        TenantContext.clear();
        for (String bad : new String[]{"", "lower case", "1ABC", "A;DROP", "X".repeat(50)}) {
            assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(placed, bad, 1L); return null; }), bad).isInstanceOf(ResponseStatusException.class);
        }
        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", data)));
        place(trophy.id());
        TenantContext.clear();
        assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(trophy.id(), "PRINTING_PRESS", 1L); return null; })).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void staleTrackerVersionIsIgnored() {
        var order = placedNotebook(true);
        TenantContext.clear();
        tx(() -> { forms.applyTrackerStatus(order, "PRINTING_PRESS", 5L); return null; });
        long events = jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single();

        // A push that arrives late, or twice, succeeds and changes nothing.
        tx(() -> { forms.applyTrackerStatus(order, "BOARD_PROCUREMENT", 4L); return null; });
        assertThat(status(order)).isEqualTo("PRINTING_PRESS");
        tx(() -> { forms.applyTrackerStatus(order, "PRE_PRINT_QUALITY_GATE", 5L); return null; });
        assertThat(status(order)).as("an equal version is already applied").isEqualTo("PRINTING_PRESS");
        assertThat(trackerStatusVersion(order)).isEqualTo(5L);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single()).isEqualTo(events);

        // A newer version that repeats the shown status still raises the bar for older ones.
        tx(() -> { forms.applyTrackerStatus(order, "PRINTING_PRESS", 7L); return null; });
        tx(() -> { forms.applyTrackerStatus(order, "PRE_PRINT_QUALITY_GATE", 6L); return null; });
        assertThat(status(order)).isEqualTo("PRINTING_PRESS");
        assertThat(trackerStatusVersion(order)).isEqualTo(7L);

        tx(() -> { forms.applyTrackerStatus(order, "PRE_PRINT_QUALITY_GATE", 8L); return null; });
        assertThat(status(order)).isEqualTo("PRE_PRINT_QUALITY_GATE");
        assertThat(trackerStatusVersion(order)).isEqualTo(8L);
    }

    @Test
    void unknownStatusCodeRejected() {
        var order = placedNotebook(true);
        TenantContext.clear();
        // Well-formed codes that are not tracker steps, including the old approval flow's statuses.
        for (String unknown : new String[]{"NOT_A_STEP", "DESIGN_APPROVAL", "PROCESSING", "DELIVERED", "CANCELLED", "DRAFT"}) {
            assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(order, unknown, 9L); return null; }), unknown)
                    .isInstanceOfSatisfying(ResponseStatusException.class, refused -> assertThat(refused.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(order, "PRINTING_PRESS", null); return null; }), "no version")
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> assertThat(refused.getStatusCode().value()).isEqualTo(400));
        assertThat(status(order)).isEqualTo("ORDER_DETAILS");
        // Every one of operations' TrackerStep codes and COMPLETED is accepted.
        long version = 10;
        for (String code : new String[]{"ORDER_DETAILS", "DESIGNING_ACTIVITY", "HEAD_APPROVAL", "CDR_CONVERSION", "PRODUCTION_PLANNING",
                "BOARD_PROCUREMENT", "PRINTING_PRESS", "PRE_PRINT_QUALITY_GATE", "FACTORY_HANDOVER", "PRODUCTION_FOLLOW_UP",
                "VALIDATION_COUNT", "DELIVERY_INVOICING", "COMPLETED"}) {
            long next = ++version;
            tx(() -> { forms.applyTrackerStatus(order, code, next); return null; });
            assertThat(status(order)).isEqualTo(code);
        }
    }

    // ---- row-level security, through the restricted runtime role ---------------------------------------------------------------

    @Test
    void rowLevelSecurityAdmitsAnItemScopedOperatorForItsSchoolAndItsItemOnly() {
        var notebookAtOne = placedNotebook(true);
        school(2);
        var notebookAtTwo = tx(() -> orders.createOrder(Map.of("schoolId", 2, "category", "NOTEBOOKS", "orderData", data(true, 400, 600)))).id();
        // Placed, so it is on the tracker: an item-only operator finds nothing off it (H1), which is not what this checks.
        place(notebookAtTwo);
        var trophyData = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var trophyAtOne = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", trophyData))).id();
        TenantContext.clear();

        var runtime = new TenantAwareDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt"));
        var runtimeJdbc = JdbcClient.create(runtime);
        var manager = new DataSourceTransactionManager(runtime);
        var runtimeOutbox = new OutboxWriter(runtimeJdbc, new ObjectMapper(), "tenant_school");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(TransactionalTestConfiguration.class);
            context.registerBean("transactionManager", org.springframework.transaction.PlatformTransactionManager.class, () -> manager);
            context.registerBean(ModuleEntitlementReadRepository.class, () -> new ModuleEntitlementReadRepository(runtimeJdbc));
            context.registerBean(ModuleEntitlementGuard.class, () -> new ModuleEntitlementGuard(context.getBean(ModuleEntitlementReadRepository.class)));
            context.registerBean(ProductCatalogRepository.class, () -> new ProductCatalogRepository(runtimeJdbc, new ObjectMapper(), runtimeOutbox, true));
            context.registerBean(CatalogOrderFormService.class, () -> new CatalogOrderFormService(runtimeJdbc,
                    context.getBean(ProductCatalogRepository.class), new ProductFormRuleEngine(),
                    new CatalogOrderAssetStorage("", "local", assets.toString()), runtimeOutbox, context.getBean(ModuleEntitlementGuard.class)));
            context.registerBean(CatalogReadRepository.class, () -> new CatalogReadRepository(runtimeJdbc, runtimeOutbox));
            context.refresh();
            var runtimeForms = context.getBean(CatalogOrderFormService.class);
            var runtimeOrders = context.getBean(CatalogReadRepository.class);

            operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
            assertThat(runtimeOrders.order(notebookAtOne)).as("the order row itself, as the order screen reads it").isPresent();
            assertThat(runtimeOrders.order(trophyAtOne)).isEmpty();
            assertThat(runtimeOrders.order(notebookAtTwo)).isEmpty();
            assertThat(runtimeForms.detail(notebookAtOne)).containsEntry("formVersion", 2);
            assertThat(runtimeForms.trackerSummary(notebookAtOne)).containsEntry("schoolName", "School A");
            assertThatThrownBy(() -> runtimeForms.detail(trophyAtOne)).as("another item at the same school")
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
            assertThatThrownBy(() -> runtimeForms.detail(notebookAtTwo)).as("the same item at another school")
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");

            operator(9L, Set.of(), Set.of("1:TIES"));
            assertThatThrownBy(() -> runtimeForms.detail(notebookAtOne)).isInstanceOf(ResponseStatusException.class);
            assertThat(runtimeOrders.order(notebookAtOne)).isEmpty();

            operator(9L, Set.of(1L), Set.of("2:NOTEBOOKS"));
            assertThat(runtimeOrders.order(trophyAtOne)).as("school-wide at one, item-scoped at two").isPresent();
            assertThat(runtimeOrders.order(notebookAtTwo)).isPresent();

            operator(9L, Set.of(), Set.of());
            assertThatThrownBy(() -> runtimeForms.detail(notebookAtOne)).as("no scope at all fails closed").isInstanceOf(ResponseStatusException.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalTestConfiguration {}

    @Test
    void theCreationQueueRunsAsTheRuntimeRoleUnderRowLevelSecurity() {
        TRACKER.failCreate = true;
        var atOne = notebook(true);
        place(atOne);
        school(2);
        var atTwo = tx(() -> orders.createOrder(Map.of("schoolId", 2, "category", "NOTEBOOKS", "orderData", data(false, 30, 30)))).id();
        place(atTwo);

        var runtime = new TenantAwareDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt"));
        var runtimeJdbc = JdbcClient.create(runtime);
        var runtimeManager = new DataSourceTransactionManager(runtime);
        var runtimeQueue = new NotebookTrackerCreationQueue(runtimeJdbc, TRACKER, OPERATORS, runtimeManager);
        assertThat(runtimeJdbc.sql("SELECT order_id FROM catalog.notebook_tracker_creation_queue").query(String.class).list())
                .as("a school sees only its own queue rows").containsExactly(atTwo);
        TenantContext.clear();
        assertThat(runtimeJdbc.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue").query(Long.class).single())
                .as("no scope, no rows").isZero();

        TRACKER.failCreate = false;
        due(atOne);
        due(atTwo);
        assertThat(runtimeQueue.drain(5)).as("the claim crosses schools; each row is then worked in its own school").isEqualTo(2);
        assertThat(queued(atOne) + queued(atTwo)).isZero();
        assertThat(pending(atOne)).isFalse();
        assertThat(pending(atTwo)).isFalse();
        assertThat(TRACKER.created).extracting(TrackerGateway.NewTracker::schoolId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void itemOnlyOperatorCannotListSchoolOrders() {
        var notebook = placedNotebook(true);
        var trophyData = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", trophyData))).id();
        TenantContext.clear();

        var runtime = new TenantAwareDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt"));
        var runtimeJdbc = JdbcClient.create(runtime);
        var runtimeTx = new TransactionTemplate(new DataSourceTransactionManager(runtime));
        var runtimeOutbox = new OutboxWriter(runtimeJdbc, new ObjectMapper(), "tenant_school");
        var runtimeOrders = new CatalogReadRepository(runtimeJdbc, runtimeOutbox);
        var students = new StudentReadController(new StudentReadRepository(runtimeJdbc, null, runtimeOutbox), new ImageUrlFetcher(1024), "student-token");

        operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
        @SuppressWarnings("unchecked")
        var listed = (List<CatalogReadRepository.CatalogOrderRow>) runtimeTx.execute(status -> runtimeOrders.ordersPage(null, null, 0, 200).get("content"));
        assertThat(listed).extracting(CatalogReadRepository.CatalogOrderRow::id).as("no school context, so no school-wide order list")
                .doesNotContain(trophy);
        for (Long requested : new Long[]{null, 1L}) {
            assertThatThrownBy(() -> students.list("student-token", requested, null, null, null, null, null, 0, 50))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        Optional<CatalogReadRepository.CatalogOrderRow> own = runtimeTx.execute(status -> runtimeOrders.order(notebook));
        Optional<CatalogReadRepository.CatalogOrderRow> other = runtimeTx.execute(status -> runtimeOrders.order(trophy));
        assertThat(own).as("the notebook order itself stays reachable").isPresent();
        assertThat(other).isEmpty();
    }

    // ---- who may read a notebook order's tracker data --------------------------------------------------------------------------

    @Test
    void anOperatorScopedToNotebooksReadsNotebookOrdersAtThatSchoolButNothingElse() {
        var notebook = placedNotebook(true);
        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", data)));

        operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
        assertThat(tx(() -> forms.detail(notebook))).containsEntry("formVersion", 2);
        assertThat(tx(() -> forms.trackerSummary(notebook))).containsEntry("orderId", notebook);
        assertThatThrownBy(() -> tx(() -> forms.detail(trophy.id()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");

        operator(9L, Set.of(), Set.of("1:TIES"));
        assertThatThrownBy(() -> tx(() -> forms.detail(notebook))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        operator(9L, Set.of(), Set.of("2:NOTEBOOKS"));
        assertThatThrownBy(() -> tx(() -> forms.detail(notebook))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");

        operator(9L, Set.of(1L), Set.of());
        assertThat(tx(() -> forms.detail(trophy.id()))).as("a school-wide operator covers every item").containsEntry("formVersion", 2);
    }

    @Test
    void theTrackerSummaryGivesTheHeaderFactsToTheTeamAndIsRefusedToTheSchool() {
        school(1);
        var order = tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "notes", "Please use green",
                "requiredByDate", "2026-12-15", "orderData", data(true, 400, 600))));
        place(order.id());

        superadmin();
        var summary = tx(() -> forms.trackerSummary(order.id()));
        assertThat(summary).containsEntry("schoolName", "School A").containsEntry("customised", true)
                .containsEntry("orderedCount", 2000).containsEntry("schoolNotes", "Please use green")
                .containsEntry("requiredByDate", "2026-12-15").containsEntry("status", "ORDER_DETAILS")
                .containsEntry("trackerPending", false);
        @SuppressWarnings("unchecked") var lines = (List<Map<String, Object>>) summary.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).containsKeys("lineNo", "selections", "bookCount", "pageCount");

        school(1);
        assertThatThrownBy(() -> tx(() -> forms.trackerSummary(order.id()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    @Test
    void theSummaryCarriesTheSchoolContactFromOnboardingAndNullWhenNoneWasGiven() {
        var order = placedNotebook(true);
        superadmin();
        assertThat(tx(() -> forms.trackerSummary(order))).containsEntry("schoolContactEmail", "office@school-a.test")
                .containsEntry("schoolContactPhone", "+91 98765 43210");

        jdbc.sql("UPDATE tenant_school.schools SET contact_email = '   ', contact_phone = NULL WHERE id = 1").update();
        var blank = tx(() -> forms.trackerSummary(order));
        assertThat(blank).containsKeys("schoolContactEmail", "schoolContactPhone");
        assertThat(blank.get("schoolContactEmail")).isNull();
        assertThat(blank.get("schoolContactPhone")).isNull();
    }

    // ---- an item-scoped operator works the tracker only (owner decision D2) ----------------------------------------------------

    /**
     * Owner decision D2: an operator who reaches a school only through an item scope may work that item's orders in the
     * tracker and nothing else, so every write of the old order flow is refused: editing a draft order, the school's
     * artwork, print references and delivery photo, and design approval or delivery of an order placed before the tracker.
     * An order that is not on the tracker is not found at all, as for its reads; one on the tracker is refused outright.
     */
    @Test
    void anItemOnlyOperatorCannotUseTheOldOrderFlow() {
        var tracked = placedNotebook(true);
        var legacy = placedBeforeTheTracker(true);
        var legacyApproved = placedBeforeTheTracker(false);
        jdbc.sql("UPDATE catalog.catalog_orders SET status = 'APPROVED', superadmin_approval_status = 'APPROVED' WHERE id = :id")
                .param("id", legacyApproved).update();
        var draftOrder = notebook(true);
        school(1);
        long artwork = id(upload(legacy, "DESIGN", 11));
        long reference = id(upload(legacy, "PRINT_REFERENCE", 12));
        long draftVersion = orderVersion(draftOrder);

        operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
        for (Runnable write : List.<Runnable>of(
                () -> tx(() -> { forms.updateDraft(draftOrder, Map.of("version", draftVersion, "orderData", data(true, 500, 500))); return null; }),
                () -> upload(legacy, "DESIGN", 21),
                () -> upload(legacy, "PRINT_REFERENCE", 22),
                () -> forms.removeAsset(legacy, reference),
                () -> forms.removeAsset(legacy, artwork),
                () -> tx(() -> orders.markDesignApproved(legacy)),
                () -> tx(() -> orders.updateOrderStatus(legacy, "DESIGN_APPROVED_PROCESSING")),
                () -> tx(() -> orders.markDelivered(legacyApproved, 9L)))) {
            assertThatThrownBy(write::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        for (Runnable write : List.<Runnable>of(
                () -> upload(tracked, "PRINT_REFERENCE", 23),
                () -> upload(tracked, "PRE_DELIVERY_PHOTO", 24))) {
            assertThatThrownBy(write::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(orderVersion(draftOrder)).as("the draft order is untouched").isEqualTo(draftVersion);
        assertThat(status(legacy)).isEqualTo("DESIGN_APPROVAL");
        assertThat(status(legacyApproved)).isEqualTo("APPROVED");
        assertThat(currentAssetIds(legacy)).containsExactlyInAnyOrder(artwork, reference);
        assertThat(currentAssetIds(tracked)).isEmpty();
    }

    /**
     * ...and keeps exactly the tracker's paths: reading a tracker order, its attachments and summary, starting its tracker
     * and its design drafts. An order placed before the tracker is not one of them.
     */
    @Test
    void anItemOnlyOperatorKeepsTheTrackerPathsAndOtherOperatorsKeepTheirs() {
        var tracked = placedNotebook(true);
        var legacy = placedBeforeTheTracker(true);
        school(1);
        long artwork = id(upload(legacy, "DESIGN", 11));
        long trackedArtwork = id(upload(tracked, "DESIGN", 12));

        operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
        assertThat(tx(() -> forms.detail(tracked))).containsEntry("formVersion", 2);
        assertThat(tx(() -> forms.content(tracked, trackedArtwork)).bytes()).as("the school's own artwork is part of reading the order").isNotEmpty();
        assertThatThrownBy(() -> tx(() -> forms.detail(legacy))).as("an order placed before the tracker is not found")
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> tx(() -> forms.content(legacy, artwork))).as("nor is its artwork")
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(tx(() -> forms.trackerSummary(tracked))).containsEntry("orderId", tracked);
        forms.ensureTracker(tracked);
        assertThat(tx(() -> forms.trackerPending(tracked))).isFalse();
        long drafted = id(draft(tracked, 5));
        assertThat(tx(() -> forms.assets(tracked))).extracting(a -> a.get("assetKind")).containsExactly("DESIGN", "DESIGN_DRAFT");
        assertThat(tx(() -> forms.content(tracked, drafted)).bytes()).isNotEmpty();
        forms.removeAsset(tracked, drafted);
        assertThat(currentDrafts(tracked)).isZero();
        assertThat(TRACKER.accessChecks).isNotEmpty().allMatch(check -> check.equals(tracked + ":9:OPERATIONS"));

        // A school-wide operator at the school keeps the old flow for an order placed before the tracker (D1, D2).
        operator(8L, Set.of(1L), Set.of());
        upload(legacy, "PRINT_REFERENCE", 31);
        assertThat(tx(() -> orders.markDesignApproved(legacy)).status()).isEqualTo("DESIGN_APPROVED_PROCESSING");
    }

    /**
     * D2 re-review: an item-only operator works the item's orders in the tracker, and an order placed before the tracker (D1)
     * or a notebook order not yet placed never reaches it. Order ids are sequential, so every read path answers "not found"
     * for those orders rather than letting the ids be walked for lines, notes, artwork and the school's contact details.
     */
    @Test
    void anItemOnlyOperatorFindsNoOrderThatIsNotOnTheTracker() {
        var tracked = placedNotebook(true);
        var legacy = placedBeforeTheTracker(true);
        var draftOrder = notebook(true);
        school(1);
        long legacyArtwork = id(upload(legacy, "DESIGN", 11));
        long draftArtwork = id(upload(draftOrder, "DESIGN", 12));

        operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
        for (var offTracker : Map.of(legacy, legacyArtwork, draftOrder, draftArtwork).entrySet()) {
            String order = offTracker.getKey();
            long asset = offTracker.getValue();
            Map<String, Runnable> reads = new java.util.LinkedHashMap<>();
            reads.put("GET /form", () -> tx(() -> forms.detail(order)));
            reads.put("current assets", () -> tx(() -> forms.assets(order)));
            reads.put("GET /assets", () -> tx(() -> forms.assetHistory(order)));
            reads.put("GET /assets/{id}/content", () -> tx(() -> forms.content(order, asset)));
            reads.put("GET /tracker-summary", () -> tx(() -> forms.trackerSummary(order)));
            reads.put("tracker pending", () -> tx(() -> forms.trackerPending(order)));
            reads.put("POST /tracker/ensure", () -> forms.ensureTracker(order));
            reads.forEach((path, read) -> assertThatThrownBy(read::run).as(order + " " + path)
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)));
            assertThat(tx(() -> orders.order(order))).as(order + " GET /orders/{id} and the order half of /form").isEmpty();
        }
        assertThat(tx(() -> orders.order(tracked))).as("a tracker order stays readable").isPresent();
        assertThat(queued(legacy) + queued(draftOrder)).isZero();

        // Everyone else reads an order placed before the tracker as before.
        operator(8L, Set.of(1L), Set.of());
        assertThat(tx(() -> forms.detail(legacy))).as("school-wide operator").containsEntry("formVersion", 2);
        assertThat(tx(() -> orders.order(legacy))).isPresent();
        assertThat(tx(() -> forms.content(legacy, legacyArtwork)).bytes()).isNotEmpty();
        assertThat(tx(() -> forms.trackerSummary(legacy))).containsEntry("orderId", legacy);
        operator(8L, Set.of(1L), Set.of("2:NOTEBOOKS"));
        assertThat(tx(() -> orders.order(legacy))).as("school-wide here, item-scoped elsewhere").isPresent();
        superadmin();
        assertThat(tx(() -> forms.detail(legacy))).as("superadmin").containsEntry("formVersion", 2);
        assertThat(tx(() -> orders.order(legacy))).isPresent();
        school(1);
        assertThat(tx(() -> forms.detail(legacy))).as("the school's admin").containsEntry("formVersion", 2);
        assertThat(tx(() -> orders.order(draftOrder))).isPresent();
        assertThat(tx(() -> forms.content(draftOrder, draftArtwork)).bytes()).isNotEmpty();
    }

    /**
     * D2: another catalogue item's order at the item operator's own school is not theirs to know about, so every path that
     * reads it answers "not found", as for an order that does not exist, never "forbidden" (order ids are sequential).
     */
    @Test
    void itemOnlyOperatorGetsNotFoundForOtherCategories() {
        var trophyData = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var draft = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", trophyData))).id();
        var placed = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", trophyData))).id();
        long artwork = id(upload(placed, "DESIGN", 11));
        place(placed);
        long draftVersion = orderVersion(draft);

        operator(9L, Set.of(), Set.of("1:NOTEBOOKS"));
        for (String order : List.of(draft, placed)) {
            Map<String, Runnable> paths = new java.util.LinkedHashMap<>();
            paths.put("GET /form", () -> tx(() -> forms.detail(order)));
            paths.put("PATCH /{id}", () -> tx(() -> { forms.updateDraft(order, Map.of("version", draftVersion, "orderData", trophyData)); return null; }));
            paths.put("GET /assets", () -> tx(() -> forms.assetHistory(order)));
            paths.put("GET /assets/{id}/content", () -> tx(() -> forms.content(order, artwork)));
            for (String kind : List.of("DESIGN", "PRINT_REFERENCE", "PRE_DELIVERY_PHOTO", "DESIGN_DRAFT")) {
                paths.put("POST /assets " + kind, () -> upload(order, kind, 21));
            }
            paths.put("DELETE /assets/{id}", () -> forms.removeAsset(order, artwork));
            paths.put("GET /tracker-summary", () -> tx(() -> forms.trackerSummary(order)));
            paths.put("POST /tracker/ensure", () -> forms.ensureTracker(order));
            paths.put("tracker pending", () -> tx(() -> forms.trackerPending(order)));
            paths.put("POST /orders/{id}/place", () -> tx(() -> orders.placeOrder(order, 9L)));
            paths.forEach((path, call) -> assertThatThrownBy(call::run).as(order + " " + path)
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)));
        }
        assertThat(TRACKER.accessChecks).as("the tracker is never asked about them").isEmpty();
        assertThat(currentAssetIds(placed)).containsExactly(artwork);
        assertThat(currentAssetIds(draft)).isEmpty();
        assertThat(orderVersion(draft)).isEqualTo(draftVersion);
        assertThat(status(draft)).isEqualTo("DRAFT");
    }

    /** Spec v4: a tracker order records delivery in the tracker, so the school's old delivery photo is refused there only. */
    @Test
    void aTrackerOrderTakesNoDeliveryPhotoWhileAnOrderPlacedBeforeTheTrackerStillDoes() {
        var tracked = placedNotebook(true);
        var legacy = placedBeforeTheTracker(true);
        for (Runnable caller : List.<Runnable>of(() -> school(1), NotebookTrackerIntegrationTest::superadmin, () -> operator(8L, Set.of(1L), Set.of()))) {
            caller.run();
            assertThatThrownBy(() -> upload(tracked, "PRE_DELIVERY_PHOTO", 5)).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(ex.getReason()).isEqualTo("Notebook orders on the tracker record delivery in the tracker");
            });
        }
        assertThat(currentAssetIds(tracked)).isEmpty();
        school(1);
        assertThat(upload(legacy, "PRE_DELIVERY_PHOTO", 6)).containsEntry("assetKind", "PRE_DELIVERY_PHOTO");
    }

    /**
     * D2 for an operator who is school-wide at one school and item-scoped at another: the module guard admits them, and the
     * item scope widens row-level security to the second school, so the old flow's writes on an order in the older,
     * unstructured form there (any category) must find nothing. Their own school is unaffected.
     */
    @Test
    void anOperatorScopedToAnItemAtASchoolCannotMoveThatSchoolsUnstructuredOrders() {
        for (String[] order : new String[][]{{"CK-V1-DESIGN", "DESIGN_APPROVAL", "PENDING", "NOT_SUBMITTED"}, {"CK-V1-DRAFT", "DRAFT", "NOT_REQUIRED", "NOT_SUBMITTED"}}) {
            jdbc.sql("""
                    INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount, status,
                        design_status, superadmin_approval_status, created_at, form_version)
                    VALUES (:id, 1, 'UNIFORMS', '{}', 0, 0, 0, :status, :design, :approval, now(), 1)
                    """).param("id", order[0]).param("status", order[1]).param("design", order[2]).param("approval", order[3]).update();
        }

        operator(9L, Set.of(2L), Set.of("1:NOTEBOOKS"));
        for (Runnable write : List.<Runnable>of(
                () -> tx(() -> orders.markDesignApproved("CK-V1-DESIGN")),
                () -> tx(() -> orders.placeOrder("CK-V1-DRAFT", 9L)))) {
            assertThatThrownBy(write::run).isInstanceOf(IllegalArgumentException.class).hasMessage("Order not found");
        }
        assertThat(status("CK-V1-DESIGN")).isEqualTo("DESIGN_APPROVAL");
        assertThat(status("CK-V1-DRAFT")).isEqualTo("DRAFT");

        operator(8L, Set.of(1L), Set.of());
        assertThat(tx(() -> orders.markDesignApproved("CK-V1-DESIGN")).status()).as("a school-wide operator there").isEqualTo("DESIGN_APPROVED_PROCESSING");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    private long orderVersion(String order) {
        return jdbc.sql("SELECT version FROM catalog.catalog_orders WHERE id = :id").param("id", order).query(Long.class).single();
    }

    private List<Long> currentAssetIds(String order) {
        return jdbc.sql("SELECT id FROM catalog.catalog_order_assets WHERE order_id = :id AND superseded_at IS NULL ORDER BY id")
                .param("id", order).query(Long.class).list();
    }

    private String notebook(boolean custom) {
        school(1);
        return tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "orderData", data(custom, 400, 600)))).id();
    }

    /** As the placing controllers do: place in the request's own transaction, then, with it committed and released, attempt the tracker. */
    private static CatalogReadRepository.CatalogOrderRow place(String order) {
        var placed = tx(() -> orders.placeOrder(order, 7L));
        orders.attemptTrackerAfterPlacement(placed);
        return placed;
    }

    private boolean deadLettered(String order) {
        return jdbc.sql("SELECT dead_lettered_at IS NOT NULL FROM catalog.notebook_tracker_creation_queue WHERE order_id = :id")
                .param("id", order).query(Boolean.class).single();
    }

    private static ListAppender<ILoggingEvent> capture(Class<?> type) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    private static void release(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
    }

    /** The event as the JSON encoder writes it to Cloud Logging, whose log metrics read these fields. */
    private static JsonNode json(ILoggingEvent event) {
        var encoder = new LogstashEncoder();
        encoder.setContext((ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());
        encoder.start();
        try {
            return new ObjectMapper().readTree(new String(encoder.encode(event), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            encoder.stop();
        }
    }

    private String placedNotebook(boolean custom) {
        var id = notebook(custom);
        place(id);
        TRACKER.forget();
        OPERATORS.resolvedInTransaction.clear();
        return id;
    }

    /** A notebook order as placement left it before the tracker existed: on the approval flow, with no tracker attributes. */
    private String placedBeforeTheTracker(boolean custom) {
        var id = notebook(custom);
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET status = :status, design_status = :design, superadmin_approval_status = :approval,
                    pricing_status = 'PENDING_PRICING', placed_at = now(), placed_by = 7, attributes = '{}'::jsonb WHERE id = :id
                """).param("id", id).param("status", custom ? "DESIGN_APPROVAL" : "PROCESSING")
                .param("design", custom ? "PENDING" : "NOT_REQUIRED").param("approval", custom ? "NOT_SUBMITTED" : "PENDING").update();
        return id;
    }

    private static Map<String, Object> data(boolean custom, int first, int second) {
        return Map.of("orderSelections", Map.of("CUSTOMIZATION", custom ? "CUSTOMIZED" : "NON_CUSTOMIZED"),
                "lines", List.of(Map.of("selections", Map.of("SIZE", "LONG", "RULING", "SINGLE_RULE"), "bookCount", first, "pageCount", 198),
                        Map.of("selections", Map.of("SIZE", "JUMBO_LONG", "RULING", "FOUR_RULE"), "bookCount", second, "pageCount", 100)));
    }

    private Map<String, Object> draft(String id, int color) { return upload(id, "DESIGN_DRAFT", color); }

    private Map<String, Object> upload(String id, String kind, int color) {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, color);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return forms.upload(id, kind, output.toByteArray(), "draft.png");
        } catch (java.io.IOException ex) { throw new AssertionError(ex); }
    }

    private static long id(Map<String, Object> asset) { return ((Number) asset.get("id")).longValue(); }

    private int currentDrafts(String order) {
        return jdbc.sql("SELECT count(*) FROM catalog.catalog_order_assets WHERE order_id = :id AND asset_kind = 'DESIGN_DRAFT' AND superseded_at IS NULL")
                .param("id", order).query(Integer.class).single();
    }

    private List<String> intents(String order) {
        return jdbc.sql("SELECT image_count || ':' || status || ':' || recipients FROM catalog.order_design_email_intents WHERE order_id = :id ORDER BY id")
                .param("id", order).query(String.class).list();
    }

    private boolean managed(String order) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT (attributes ->> 'trackerManaged')::boolean FROM catalog.catalog_orders WHERE id = :id")
                .param("id", order).query(Boolean.class).optional().orElse(false));
    }

    private long queued(String order) {
        return jdbc.sql("SELECT count(*) FROM catalog.notebook_tracker_creation_queue WHERE order_id = :id").param("id", order).query(Long.class).single();
    }

    /** Lets the backoff elapse. */
    private void due(String order) {
        jdbc.sql("UPDATE catalog.notebook_tracker_creation_queue SET next_attempt_at = now() - interval '1 second' WHERE order_id = :id")
                .param("id", order).update();
    }

    private int attempts(String order) {
        return jdbc.sql("SELECT attempts FROM catalog.notebook_tracker_creation_queue WHERE order_id = :id").param("id", order).query(Integer.class).single();
    }

    private double minutesToNextAttempt(String order) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM next_attempt_at - clock_timestamp()) / 60 FROM catalog.notebook_tracker_creation_queue WHERE order_id = :id")
                .param("id", order).query(Double.class).single();
    }

    private String lastError(String order) {
        return jdbc.sql("SELECT last_error FROM catalog.notebook_tracker_creation_queue WHERE order_id = :id").param("id", order).query(String.class).single();
    }

    private boolean pending(String order) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT (attributes ->> 'trackerPending')::boolean FROM catalog.catalog_orders WHERE id = :id")
                .param("id", order).query(Boolean.class).optional().orElse(false));
    }

    private String status(String order) {
        return jdbc.sql("SELECT status FROM catalog.catalog_orders WHERE id = :id").param("id", order).query(String.class).single();
    }

    private Long trackerStatusVersion(String order) {
        return jdbc.sql("SELECT (attributes ->> 'trackerStatusVersion')::bigint FROM catalog.catalog_orders WHERE id = :id")
                .param("id", order).query(Long.class).single();
    }

    private static <T> T tx(Supplier<T> operation) { return transaction.execute(status -> operation.get()); }

    private static void school(long id) {
        TenantContext.set(new TenantContext(7L, "school@example.test", "ADMIN", id, null, Set.of(),
                Set.of("order:create", "order:read", "order:update")));
    }

    private static void superadmin() { TenantContext.set(new TenantContext(99L, "superadmin@example.test", "SUPERADMIN", null, null)); }

    /** The OPERATIONS role's codes as identity grants them (V4, V5, V6, V12): no workflow:act. */
    static final Set<String> OPERATIONS = Set.of("workspace:access", "student:read", "student:create", "student:update",
            "attendance:read", "attendance:manage", "order:read", "order:create", "order:update", "firefighting:read",
            "firefighting:create", "firefighting:update", "timetable:read", "workflow:read", "student:photo-import",
            "student:export", "notebook-tracker:read", "notebook-tracker:act");
    /** What an operator holding only item scopes is granted (owner decision D2). */
    static final Set<String> ITEM_ONLY = Set.of("workspace:access", "notebook-tracker:read", "notebook-tracker:act",
            "order:read", "order:update");

    /**
     * An operator as the gateway forwards them: a school-wide operator carries their branch (the one school given) as the
     * school id and the OPERATIONS codes; an operator with item scopes only has no school id and the tracker codes.
     */
    private static void operator(long userId, Set<Long> schools, Set<String> scopes) {
        Long branch = schools.isEmpty() ? null : schools.iterator().next();
        Set<String> permissions = !schools.isEmpty() ? OPERATIONS : !scopes.isEmpty() ? ITEM_ONLY : Set.of();
        TenantContext.set(new TenantContext(userId, "operator@example.test", "OPERATIONS", branch, null, schools, permissions, scopes));
    }
}
