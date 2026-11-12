package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.api.CatalogReadController;
import com.custoking.ims.schoolcoreservice.api.compat.CatalogPublicCompatibilityController;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A legacy order (form version 1) is placed once, from DRAFT, like a structured one. Without this guard a superadmin, or any
 * school user, could re-place an approved or delivered legacy order and reset its approval state.
 */
class LegacyOrderPlacementIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;
    /** Production wiring: structured orders go to the form service, the rest down the legacy branch. */
    static CatalogReadRepository orders;
    /** No form service: creates form-version-1 orders, as the legacy create path does. */
    static CatalogReadRepository legacyCreator;
    static MockMvc mvc;
    @TempDir static Path assets;

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
        var forms = new CatalogOrderFormService(jdbc, products, new ProductFormRuleEngine(),
                new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, null);
        orders = new CatalogReadRepository(jdbc, outbox);
        orders.configureProductForms(forms, products);
        legacyCreator = new CatalogReadRepository(jdbc, outbox);
        mvc = MockMvcBuilders.standaloneSetup(new CatalogReadController(orders, "tok"),
                new CatalogPublicCompatibilityController(orders, "tok")).build();
        jdbc.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1,'School A','SCA',true,now())").update();
        jdbc.sql("INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled) VALUES (1,'ORDERS',true)").update();
    }

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM catalog.catalog_orders").update();
        jdbc.sql("DELETE FROM tenant_school.outbox_events").update();
        superadmin();
    }

    @AfterEach void clearContext() { TenantContext.clear(); }
    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    @Test
    void aLegacyDraftStillPlaces() {
        String id = legacyDraft("STATIONERY");
        var placed = tx(() -> orders.placeOrder(id, 99L));
        assertThat(placed.formVersion()).isNotEqualTo(2);
        assertThat(placed.status()).isEqualTo("PROCESSING");
        assertThat(placed.superadminApprovalStatus()).isEqualTo("PENDING");
        assertThat(placed.placedBy()).isEqualTo(99L);
        assertThat(placed.placedAt()).isNotNull();
        assertThat(placed.version()).isEqualTo(1L);
    }

    /** APPROVED and DELIVERED are set as the approval and delivery steps leave them; the others by a real placement. */
    @Test
    void aLegacyOrderThatIsNoLongerADraftIsNotPlacedAgainAndKeepsEveryColumn() {
        String approved = legacyDraft("STATIONERY");
        setState(approved, "APPROVED", "NOT_REQUIRED", "APPROVED");
        String delivered = legacyDraft("UNIFORMS");
        setState(delivered, "DELIVERED", "APPROVED", "APPROVED");
        String awaiting = legacyDraft("IDCARDS");
        assertThat(tx(() -> orders.placeOrder(awaiting, 7L)).status()).isEqualTo("AWAITING_APPROVAL");
        String designApproval = legacyDraft("NOTEBOOKS");
        assertThat(tx(() -> orders.placeOrder(designApproval, 7L)).status()).isEqualTo("DESIGN_APPROVAL");
        String processing = legacyDraft("STATIONERY");
        assertThat(tx(() -> orders.placeOrder(processing, 7L)).status()).isEqualTo("PROCESSING");

        for (String id : List.of(approved, delivered, awaiting, designApproval, processing)) {
            var before = state(id);
            long events = outboxEvents();
            assertThatThrownBy(() -> tx(() -> orders.placeOrder(id, 99L)), id)
                    .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(refused.getReason()).isEqualTo("This action is unavailable while the order is " + before.get("status"));
                    });
            assertThat(state(id)).as(id).isEqualTo(before);
            assertThat(outboxEvents()).as("no upsert event for a refused placement").isEqualTo(events);
        }
    }

    /** The legacy refusal is the structured path's, at the service and over HTTP on both place routes. */
    @Test
    void legacyAndStructuredOrdersRefuseASecondPlacementAlike() throws Exception {
        String legacy = legacyDraft("STATIONERY");
        tx(() -> orders.placeOrder(legacy, 7L));
        var data = Map.of("orderSelections", Map.of(), "lines", List.of(
                Map.of("selections", Map.of("VARIANT", "T_WM001_A"), "bookCount", 1, "pageCount", 1)));
        String structured = tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", "TROPHIES", "orderData", data))).id();
        assertThat(tx(() -> orders.order(structured).orElseThrow()).formVersion()).isEqualTo(2);
        tx(() -> orders.placeOrder(structured, 7L));

        for (String id : List.of(legacy, structured)) {
            assertThatThrownBy(() -> tx(() -> orders.placeOrder(id, 99L)), id)
                    .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(refused.getReason()).isEqualTo("This action is unavailable while the order is PROCESSING");
                    });
            var before = state(id);
            for (String path : List.of("/api/v1/catalog/orders/" + id + "/place", "/api/v1/supply/orders/" + id + "/place")) {
                superadmin();
                mvc.perform(post(path).header("X-Catalog-Service-Token", "tok"))
                        .andExpect(status().isBadRequest())
                        .andExpect(status().reason("This action is unavailable while the order is PROCESSING"));
            }
            assertThat(state(id)).as(id).isEqualTo(before);
        }
    }

    /**
     * Two placements of one legacy draft at once: both read DRAFT, because the read takes no lock. The second's write waits
     * for the first's row lock and must then find the order placed and change nothing.
     */
    @Test
    void twoConcurrentPlacementsPlaceALegacyDraftOnce() throws Exception {
        String id = legacyDraft("STATIONERY");
        CountDownLatch firstPlaced = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> {
                superadmin();
                return tx(() -> {
                    orders.placeOrder(id, 1L);
                    firstPlaced.countDown();
                    try { assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException ex) { throw new AssertionError(ex); }
                    return null;
                });
            });
            assertThat(firstPlaced.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> second = threads.submit(() -> {
                superadmin();
                return tx(() -> orders.placeOrder(id, 2L));
            });
            waitForALockWait();
            releaseFirst.countDown();
            first.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(30, TimeUnit.SECONDS)).as("the second placement")
                    .cause().isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(refused.getReason()).isEqualTo("This action is unavailable while the order is PROCESSING");
                    });
        } finally {
            releaseFirst.countDown();
            threads.shutdownNow();
        }
        var placed = state(id);
        assertThat(placed.get("placed_by")).as("the first placement stands").isEqualTo(1L);
        assertThat(placed.get("version")).as("placed once").isEqualTo(1L);
    }

    private static void waitForALockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("the second placement never waited for the first one's row lock");
    }

    private static String legacyDraft(String category) {
        var created = tx(() -> legacyCreator.createOrder(Map.of("schoolId", 1, "category", category, "totalAmount", 900, "status", "DRAFT")));
        assertThat(created.formVersion()).isNotEqualTo(2);
        assertThat(created.status()).isEqualTo("DRAFT");
        return created.id();
    }

    private static void setState(String id, String status, String design, String approval) {
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET status = :status, design_status = :design, superadmin_approval_status = :approval,
                    placed_at = TIMESTAMPTZ '2026-10-01 09:30:00+00', placed_by = 7, version = 4 WHERE id = :id
                """).param("id", id).param("status", status).param("design", design).param("approval", approval).update();
    }

    private static Map<String, Object> state(String id) {
        return jdbc.sql("""
                SELECT status, design_status, superadmin_approval_status, placed_at, placed_by, version
                FROM catalog.catalog_orders WHERE id = :id
                """).param("id", id).query().singleRow();
    }

    private static long outboxEvents() {
        return jdbc.sql("SELECT count(*) FROM tenant_school.outbox_events").query(Long.class).single();
    }

    private static void superadmin() {
        TenantContext.set(new TenantContext(99L, "superadmin@example.test", "SUPERADMIN", null, null));
    }

    private static <T> T tx(Supplier<T> operation) { return transaction.execute(status -> operation.get()); }
}
