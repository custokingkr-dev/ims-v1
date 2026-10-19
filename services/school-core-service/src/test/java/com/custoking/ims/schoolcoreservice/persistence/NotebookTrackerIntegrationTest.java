package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
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
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Notebook order tracker, school-core side (spec v4): placement starts the tracker, design drafts are capped and gated,
 * the email intent is recorded, and operators scoped to a catalogue item reach only that item. Runs against the real
 * migrated schema with the tracker and the operator directory replaced by controllable fakes.
 */
class NotebookTrackerIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;
    static CatalogReadRepository orders;
    static CatalogOrderFormService forms;
    @TempDir static Path assets;

    static final FakeTracker TRACKER = new FakeTracker();
    static final FakeOperators OPERATORS = new FakeOperators();

    static final class FakeTracker implements TrackerGateway {
        final List<NewTracker> created = new ArrayList<>();
        boolean failCreate;
        boolean down;
        ImageAccess access = new ImageAccess(true, null);
        final List<String> accessChecks = new ArrayList<>();

        @Override public void create(NewTracker tracker) {
            if (failCreate) throw new IllegalStateException("tracker down");
            created.add(tracker);
        }
        @Override public ImageAccess imageAccess(String orderId, long userId, String role) {
            if (down) throw new IllegalStateException("tracker down");
            accessChecks.add(orderId + ":" + userId + ":" + role);
            return access;
        }
        void reset() { created.clear(); accessChecks.clear(); failCreate = false; down = false; access = new ImageAccess(true, null); }
    }

    static final class FakeOperators implements OperatorResolver {
        String outcome = "NONE";
        Long userId;
        boolean down;
        @Override public Resolution resolve(long schoolId, String catalogCategory) {
            if (down) throw new IllegalStateException("identity down");
            return new Resolution(outcome, userId);
        }
        void reset() { outcome = "NONE"; userId = null; down = false; }
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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        var products = new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, true);
        forms = new CatalogOrderFormService(jdbc, products, new ProductFormRuleEngine(),
                new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, null);
        forms.configureTracker(TRACKER, OPERATORS);
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
        var placed = tx(() -> orders.placeOrder(order, 7L));

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
        tx(() -> orders.placeOrder(notebook(false), 7L));
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isEqualTo(6L);
        assertThat(TRACKER.created.getFirst().customised()).isFalse();

        TRACKER.reset();
        OPERATORS.outcome = "AMBIGUOUS";
        OPERATORS.userId = null;
        tx(() -> orders.placeOrder(notebook(true), 7L));
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isNull();

        TRACKER.reset();
        OPERATORS.outcome = "NONE";
        tx(() -> orders.placeOrder(notebook(true), 7L));
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isNull();
    }

    @Test
    void anUnreachableOperatorDirectoryStillPlacesAndCreatesTheTrackerUnassigned() {
        OPERATORS.down = true;
        var order = notebook(true);
        assertThat(tx(() -> orders.placeOrder(order, 7L)).status()).isEqualTo("ORDER_DETAILS");
        assertThat(TRACKER.created).hasSize(1);
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isNull();
        assertThat(pending(order)).isFalse();
    }

    @Test
    void anUnreachableTrackerStillPlacesTheOrderAndMarksItSoTheTrackerCanBeCreatedLater() {
        TRACKER.failCreate = true;
        OPERATORS.outcome = "ITEM_SPECIFIC";
        OPERATORS.userId = 42L;
        var order = notebook(true);
        assertThat(tx(() -> orders.placeOrder(order, 7L)).status()).isEqualTo("ORDER_DETAILS");
        assertThat(TRACKER.created).isEmpty();
        assertThat(pending(order)).isTrue();

        TRACKER.failCreate = false;
        superadmin();
        assertThat(tx(() -> forms.ensureTracker(order))).containsEntry("trackerPending", false);
        assertThat(TRACKER.created).hasSize(1);
        assertThat(TRACKER.created.getFirst().assigneeUserId()).isEqualTo(42L);
        assertThat(pending(order)).isFalse();
    }

    @Test
    void ensuringATrackerNeedsAPlacedNotebookOrderAndAnOperatorOrSuperadmin() {
        var draft = notebook(true);
        superadmin();
        assertThatThrownBy(() -> tx(() -> forms.ensureTracker(draft))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("placed notebook");
        school(1);
        assertThatThrownBy(() -> tx(() -> forms.ensureTracker(draft))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThat(TRACKER.created).isEmpty();
    }

    @Test
    void otherCategoriesPlaceAsBeforeAndNeverTouchTheTracker() {
        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", data)));
        assertThat(trophy.pricingStatus()).isEqualTo("PENDING_PRICING");
        assertThat(tx(() -> orders.placeOrder(trophy.id(), 7L)).status()).isEqualTo("PROCESSING");
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

        tx(() -> { forms.removeAsset(order, ids.get(0)); return null; });
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

        tx(() -> { forms.removeAsset(order, ids.get(0)); forms.removeAsset(order, ids.get(1)); return null; });
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
        assertThatThrownBy(() -> tx(() -> { forms.removeAsset(order, kept); return null; })).isInstanceOf(ResponseStatusException.class);
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
        tx(() -> { forms.removeAsset(order, first); return null; });
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

        tx(() -> { forms.applyTrackerStatus(order, "printing_press"); return null; });
        assertThat(status(order)).isEqualTo("PRINTING_PRESS");
        var event = jdbc.sql("SELECT payload::text FROM tenant_school.outbox_events WHERE event_type = 'catalog-order.upserted.v1' ORDER BY id DESC LIMIT 1")
                .query(String.class).single();
        assertThat(event).contains("PRINTING_PRESS");

        long events = jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single();
        tx(() -> { forms.applyTrackerStatus(order, "PRINTING_PRESS"); return null; });
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single()).as("no change, no event").isEqualTo(events);

        tx(() -> { forms.applyTrackerStatus(order, "COMPLETED"); return null; });
        assertThat(status(order)).isEqualTo("COMPLETED");
    }

    @Test
    void aTrackerStatusIsRefusedForDraftsBadCodesAndOtherCategories() {
        var draft = notebook(true);
        TenantContext.clear();
        assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(draft, "PRINTING_PRESS"); return null; }))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        school(1);
        var placed = placedNotebook(true);
        TenantContext.clear();
        for (String bad : new String[]{"", "lower case", "1ABC", "A;DROP", "X".repeat(50)}) {
            assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(placed, bad); return null; }), bad).isInstanceOf(ResponseStatusException.class);
        }
        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_B"), "bookCount", 3, "pageCount", 99)));
        school(1);
        var trophy = tx(() -> orders.createOrder(Map.of("category", "TROPHIES", "orderData", data)));
        tx(() -> orders.placeOrder(trophy.id(), 7L));
        TenantContext.clear();
        assertThatThrownBy(() -> tx(() -> { forms.applyTrackerStatus(trophy.id(), "PRINTING_PRESS"); return null; })).isInstanceOf(ResponseStatusException.class);
    }

    // ---- row-level security, through the restricted runtime role ---------------------------------------------------------------

    @Test
    void rowLevelSecurityAdmitsAnItemScopedOperatorForItsSchoolAndItsItemOnly() {
        var notebookAtOne = placedNotebook(true);
        school(2);
        var notebookAtTwo = tx(() -> orders.createOrder(Map.of("schoolId", 2, "category", "NOTEBOOKS", "orderData", data(true, 400, 600)))).id();
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
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
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
        assertThatThrownBy(() -> tx(() -> forms.detail(trophy.id()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");

        operator(9L, Set.of(), Set.of("1:TIES"));
        assertThatThrownBy(() -> tx(() -> forms.detail(notebook))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        operator(9L, Set.of(), Set.of("2:NOTEBOOKS"));
        assertThatThrownBy(() -> tx(() -> forms.detail(notebook))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");

        operator(9L, Set.of(1L), Set.of());
        assertThat(tx(() -> forms.detail(trophy.id()))).as("a school-wide operator covers every item").containsEntry("formVersion", 2);
    }

    @Test
    void theTrackerSummaryGivesTheHeaderFactsToTheTeamAndIsRefusedToTheSchool() {
        school(1);
        var order = tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "notes", "Please use green",
                "requiredByDate", "2026-12-15", "orderData", data(true, 400, 600))));
        tx(() -> orders.placeOrder(order.id(), 7L));

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

    // ---- helpers ---------------------------------------------------------------------------------------------------------------

    private String notebook(boolean custom) {
        school(1);
        return tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "orderData", data(custom, 400, 600)))).id();
    }

    private String placedNotebook(boolean custom) {
        var id = notebook(custom);
        tx(() -> orders.placeOrder(id, 7L));
        TRACKER.created.clear();
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
            return tx(() -> forms.upload(id, kind, output.toByteArray(), "draft.png"));
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

    private boolean pending(String order) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT (attributes ->> 'trackerPending')::boolean FROM catalog.catalog_orders WHERE id = :id")
                .param("id", order).query(Boolean.class).optional().orElse(false));
    }

    private String status(String order) {
        return jdbc.sql("SELECT status FROM catalog.catalog_orders WHERE id = :id").param("id", order).query(String.class).single();
    }

    private static <T> T tx(Supplier<T> operation) { return transaction.execute(status -> operation.get()); }

    private static void school(long id) {
        TenantContext.set(new TenantContext(7L, "school@example.test", "ADMIN", id, null, Set.of(),
                Set.of("order:create", "order:read", "order:update")));
    }

    private static void superadmin() { TenantContext.set(new TenantContext(99L, "superadmin@example.test", "SUPERADMIN", null, null)); }

    private static void operator(long userId, Set<Long> schools, Set<String> scopes) {
        TenantContext.set(new TenantContext(userId, "operator@example.test", "OPERATIONS", null, null, schools,
                Set.of("order:create", "order:read", "order:update", "workflow:read", "workflow:act"), scopes));
    }
}
