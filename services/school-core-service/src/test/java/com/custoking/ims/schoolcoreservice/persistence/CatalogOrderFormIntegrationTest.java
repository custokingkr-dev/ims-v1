package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormValidationException;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantAwareDataSource;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogOrderFormIntegrationTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;
    static CatalogReadRepository orders;
    static CatalogOrderFormService forms;
    static ProductCatalogRepository products;
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
        products = new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, true);
        forms = new CatalogOrderFormService(jdbc, products, new ProductFormRuleEngine(),
                new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, null);
        orders = new CatalogReadRepository(jdbc, outbox);
        orders.configureProductForms(forms, products);
        jdbc.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1,'School A','SCA',true,now()),(2,'School B','SCB',true,now()),(3,'Disabled School','SCD',true,now())").update();
        jdbc.sql("INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled) VALUES (1,'ORDERS',true),(2,'ORDERS',true),(3,'ORDERS',false)").update();
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
            jdbc.sql("UPDATE catalog.product_form_rules SET params = jsonb_set(params, '{value}', '1000') WHERE rule_type = 'FLOOR_VALUE'").update();
            return null;
        });
        school(1);
    }

    @AfterEach void clearContext() { TenantContext.clear(); }
    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    @Test
    void savesRequestedAndNormalizedCountsWithSnapshotAndCompatibilityItems() {
        var created = create(true, 400, 600);
        assertThat(created.formVersion()).isEqualTo(2);
        assertThat(created.pricingStatus()).isEqualTo("PENDING_PRICING");
        assertThat(created.totalAmount()).isZero();
        assertThat(created.orderData()).contains("items", "Long", "Jumbo Long", "196 printed pages");
        var detail = tx(() -> forms.detail(created.id()));
        var line = detailLines(detail).getFirst();
        assertThat(line.get("requestedPageCount")).isEqualTo(198);
        assertThat(line.get("pageCount")).isEqualTo(196);
        assertThat(line.get("unitPricePaise")).isNull();
        // Each customised ruling line is raised to the floor of 1000 instead of the order needing an
        // exact combined total, and the count the school asked for is preserved beside it.
        assertThat(line.get("requestedBookCount")).isEqualTo(400);
        assertThat(line.get("bookCount")).isEqualTo(1000);
        assertThat(String.valueOf(line.get("appliedRules"))).contains("FLOOR_VALUE");
        assertThat(detailLines(detail).get(1).get("bookCount")).isEqualTo(1000);
        upload(created.id(), "DESIGN", 1);
        assertThat(tx(() -> orders.placeOrder(created.id(), 7L)).status()).isEqualTo("DESIGN_APPROVAL");
    }

    @Test
    void aggregateDraftCanBeReopenedEditedAndPlacedWithSameId() {
        var created = create(true, 399, 600);
        tx(() -> { forms.updateDraft(created.id(), Map.of("version", 0, "orderData", data(true, 400, 600))); return null; });
        // Artwork is optional since 2026-09-25, so it is attached here to prove an upload survives
        // the edit rather than to unblock the placement.
        upload(created.id(), "DESIGN", 1);
        assertThat(tx(() -> orders.placeOrder(created.id(), 7L)).id()).isEqualTo(created.id());
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.catalog_orders").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void nonCustomizedSkipsDesignButRequiresQuoteAndTrustedSuperadmin() {
        var created = create(false, 25, 30);
        var placed = tx(() -> orders.placeOrder(created.id(), 7L));
        assertThat(placed.status()).isEqualTo("PROCESSING");
        assertThat(placed.designStatus()).isEqualTo("NOT_REQUIRED");
        assertThatThrownBy(() -> tx(() -> { forms.quote(created.id(), quote(created.id(), 125, 90)); return null; }))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        superadmin();
        assertThatThrownBy(() -> tx(() -> orders.approveBySuperadmin(created.id())))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("quote");
        tx(() -> { forms.quote(created.id(), quote(created.id(), 125, 90)); return null; });
        var quoted = tx(() -> orders.order(created.id()).orElseThrow());
        assertThat(quoted.totalAmount()).isEqualTo(55 * 125 + 90);
        assertThat(quoted.pricingStatus()).isEqualTo("QUOTED");
        assertThat(jdbc.sql("SELECT quoted_by FROM catalog.catalog_orders WHERE id = :id").param("id", created.id()).query(Long.class).single()).isEqualTo(99);
        var event = jdbc.sql("SELECT payload::text FROM tenant_school.outbox_events WHERE event_type = 'catalog-order.upserted.v1' ORDER BY id DESC LIMIT 1").query(String.class).single();
        assertThat(event).contains("QUOTED", "formVersion", "version", "6965");
        tx(() -> orders.approveBySuperadmin(created.id()));
        assertThat(tx(() -> orders.markDelivered(created.id(), 99L)).status()).isEqualTo("DELIVERED");
    }

    @Test
    void replacingArtworkInvalidatesApprovalAndPhotoBlocksDelivery() {
        var created = create(true, 400, 600);
        var design = upload(created.id(), "DESIGN", 1);
        assertThat(upload(created.id(), "DESIGN", 1).get("id")).isEqualTo(design.get("id"));
        tx(() -> orders.placeOrder(created.id(), 7L));
        superadmin();
        tx(() -> orders.markDesignApproved(created.id()));
        tx(() -> { forms.quote(created.id(), quote(created.id(), 120, 0)); return null; });
        var replacement = upload(created.id(), "DESIGN", 2);
        assertThat(replacement.get("id")).isNotEqualTo(design.get("id"));
        assertThat(tx(() -> forms.assetHistory(created.id()))).hasSize(2);
        assertThat(tx(() -> forms.content(created.id(), ((Number) design.get("id")).longValue())).bytes()).isNotEmpty();
        assertThat(tx(() -> orders.order(created.id()).orElseThrow()).status()).isEqualTo("DESIGN_APPROVAL");
        assertThat(tx(() -> forms.detail(created.id())).get("approvedDesignAssetId")).isNull();
        assertThatThrownBy(() -> tx(() -> orders.updateOrderStatus(created.id(), "APPROVED"))).isInstanceOf(ResponseStatusException.class);
        tx(() -> orders.markDesignApproved(created.id()));
        tx(() -> orders.approveBySuperadmin(created.id()));
        assertThatThrownBy(() -> upload(created.id(), "DESIGN", 3)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tx(() -> orders.updateOrderStatus(created.id(), "DELIVERED"))).isInstanceOf(ProductFormValidationException.class);
        upload(created.id(), "PRE_DELIVERY_PHOTO", 3);
        assertThat(tx(() -> orders.updateOrderStatus(created.id(), "DELIVERED")).status()).isEqualTo("DELIVERED");
        assertThatThrownBy(() -> tx(() -> { forms.removeAsset(created.id(), ((Number) replacement.get("id")).longValue()); return null; }))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsBypassPayloadsAndStatusAliases() {
        Map<String, Object> request = new LinkedHashMap<>(Map.of("category", "NOTEBOOKS", "schoolId", 1, "orderData", data(true, 400, 600)));
        request.put("status", "APPROVED");
        assertThatThrownBy(() -> tx(() -> orders.createOrder(request))).isInstanceOf(ResponseStatusException.class);
        request.put("category", "  nOtEbOoKs  ");
        assertThatThrownBy(() -> tx(() -> orders.createOrder(request))).isInstanceOf(ResponseStatusException.class);
        request.put("category", "NOTEBOOKS");
        request.put("status", "DRAFT");
        request.put("totalAmount", 0);
        assertThatThrownBy(() -> tx(() -> orders.createOrder(request))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tx(() -> orders.createOrder(Map.of("category", "NOTEBOOKS", "schoolId", 1, "orderData", Map.of("notebookRows", List.of())))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Refresh");
        var created = create(true, 400, 600);
        assertThatThrownBy(() -> tx(() -> orders.updateOrderStatus(created.id(), "PROCESSING"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tx(() -> orders.updateOrderStatus(created.id(), "APPROVED"))).isInstanceOf(ResponseStatusException.class);
        // DESIGN_APPROVAL is a real transition for a customised order and no longer waits on artwork.
        assertThat(tx(() -> orders.updateOrderStatus(created.id(), "DESIGN_APPROVAL")).status()).isEqualTo("DESIGN_APPROVAL");
        assertThatThrownBy(() -> tx(() -> orders.updateOrderStatus(created.id(), "FULFILLED"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void quoteCoverageAndVersionConflictsRollBackPricesAndTotals() {
        var created = create(false, 1, 2);
        tx(() -> orders.placeOrder(created.id(), 7L));
        superadmin();
        Map<String, Object> old = quote(created.id(), 100, 0);
        tx(() -> { forms.quote(created.id(), old); return null; });
        assertThatThrownBy(() -> tx(() -> { forms.quote(created.id(), old); return null; }))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        var invalid = new LinkedHashMap<>(quote(created.id(), 200, 0));
        invalid.put("lines", List.of(Map.of("id", detailLines(tx(() -> forms.detail(created.id()))).getFirst().get("id"), "unitPricePaise", 200)));
        assertThatThrownBy(() -> tx(() -> { forms.quote(created.id(), invalid); return null; })).isInstanceOf(ResponseStatusException.class);
        assertThat(tx(() -> orders.order(created.id()).orElseThrow()).totalAmount()).isEqualTo(300);
        assertThatThrownBy(() -> tx(() -> { forms.updateDraft(created.id(), Map.of("version", 0, "orderData", data(false, 2, 3))); return null; }))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void checksTenantForDetailsAssetsAndOperatorCreates() {
        var created = create(true, 400, 600);
        var asset = upload(created.id(), "DESIGN", 1);
        school(2);
        assertThatThrownBy(() -> tx(() -> forms.detail(created.id()))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tx(() -> forms.content(created.id(), ((Number) asset.get("id")).longValue()))).isInstanceOf(ResponseStatusException.class);
        TenantContext.set(new TenantContext(8L, "operator@example.test", "OPERATIONS", null, null, Set.of(1L), Set.of("order:create", "order:read", "order:update")));
        assertThat(tx(() -> forms.detail(created.id()))).containsEntry("formVersion", 2);
        assertThat(create(false, 5, 5).schoolId()).isEqualTo(1);
        assertThatThrownBy(() -> tx(() -> orders.createOrder(Map.of("schoolId", 2, "category", "NOTEBOOKS", "orderData", data(false, 1, 1)))))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    @Test
    void rollbackFlagKeepsVersionTwoWorkflowAndLegacyCreation() {
        var created = create(false, 2, 3);
        var outbox = new OutboxWriter(jdbc, new ObjectMapper(), "tenant_school");
        var disabledProducts = new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, false);
        var disabledForms = new CatalogOrderFormService(jdbc, disabledProducts, new ProductFormRuleEngine(),
                new CatalogOrderAssetStorage("", "local", assets.toString()), outbox, null);
        var rollbackOrders = new CatalogReadRepository(jdbc, outbox);
        rollbackOrders.configureProductForms(disabledForms, disabledProducts);
        assertThat(tx(() -> rollbackOrders.placeOrder(created.id(), 7L)).designStatus()).isEqualTo("NOT_REQUIRED");
        var legacy = tx(() -> rollbackOrders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "totalAmount", 900)));
        assertThat(legacy.formVersion()).isEqualTo(1);
        assertThat(tx(() -> rollbackOrders.placeOrder(legacy.id(), 7L)).status()).isEqualTo("DESIGN_APPROVAL");
    }

    @Test
    void acceptsJsonStringTransportButRequiresUpdateForDraftAndAssetChanges() {
        var created = tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", " nOtEbOoKs ",
                "orderData", Json.write(data(true, 400, 600)))));
        assertThat(created.formVersion()).isEqualTo(2);
        assertThat(created.category()).isEqualTo("NOTEBOOKS");
        TenantContext.set(new TenantContext(7L, "school@example.test", "ADMIN", 1L, null, Set.of(), Set.of("order:create", "order:read")));
        assertThatThrownBy(() -> tx(() -> { forms.updateDraft(created.id(), Map.of("version", 0, "orderData", data(true, 400, 600))); return null; }))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        assertThatThrownBy(() -> upload(created.id(), "DESIGN", 1)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    @Test
    void runtimeOperatorCanOrderForEnabledAssignedSchoolWithoutReferenceWriteAccess() throws Exception {
        var runtimeDataSource = new TenantAwareDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt"));
        var runtimeJdbc = JdbcClient.create(runtimeDataSource);
        var manager = new DataSourceTransactionManager(runtimeDataSource);
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
            var actualGuard = context.getBean(ModuleEntitlementGuard.class);
            var actualOrders = context.getBean(CatalogReadRepository.class);
            var actualForms = context.getBean(CatalogOrderFormService.class);
            TenantContext.set(new TenantContext(8L, "operator@example.test", "OPERATIONS", null, null,
                    Set.of(1L, 3L), Set.of("order:create", "order:read", "order:update", "order:fulfill")));

            // This is the controller's pre-transaction module check against the real runtime role.
            actualGuard.requireModuleEnabled(1L, "ORDERS");
            assertThatThrownBy(() -> actualGuard.requireModuleEnabled(2L, "ORDERS")).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> actualGuard.requireModuleEnabled(3L, "ORDERS")).isInstanceOf(ResponseStatusException.class);
            var created = actualOrders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "orderData", data(true, 400, 600)));
            assertThat(created.schoolName()).isEqualTo("School A");
            assertThat(actualForms.detail(created.id())).containsEntry("formVersion", 2);
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            var asset = actualForms.upload(created.id(), "DESIGN", output.toByteArray(), "design.png");
            assertThat(actualForms.content(created.id(), ((Number) asset.get("id")).longValue()).bytes()).isEqualTo(output.toByteArray());
            assertThat(actualOrders.placeOrder(created.id(), 8L).status()).isEqualTo("DESIGN_APPROVAL");
            assertThatThrownBy(() -> actualOrders.createOrder(Map.of("schoolId", 2, "category", "NOTEBOOKS", "orderData", data(false, 1, 1))))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> actualOrders.createOrder(Map.of("schoolId", 3, "category", "NOTEBOOKS", "orderData", data(false, 1, 1))))
                    .isInstanceOf(ResponseStatusException.class);
            new TransactionTemplate(manager).execute(status -> {
                runtimeJdbc.sql("SELECT set_config('app.operator_schools', '1', true)").query(String.class).single();
                assertThat(runtimeJdbc.sql("UPDATE tenant_school.schools SET name = name WHERE id = 1").update()).isZero();
                assertThat(runtimeJdbc.sql("UPDATE tenant_school.school_module_entitlements SET enabled = false WHERE school_id = 1").update()).isZero();
                return null;
            });
            assertThat(runtimeJdbc.sql("SELECT current_setting('app.operator_schools',true)").query(String.class).single()).isEmpty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalTestConfiguration {}

    private CatalogReadRepository.CatalogOrderRow create(boolean custom, int first, int second) {
        return tx(() -> orders.createOrder(Map.of("schoolId", 1, "category", "NOTEBOOKS", "orderData", data(custom, first, second))));
    }

    private static Map<String, Object> data(boolean custom, int first, int second) {
        return Map.of("orderSelections", Map.of("CUSTOMIZATION", custom ? "CUSTOMIZED" : "NON_CUSTOMIZED"),
                "lines", List.of(Map.of("selections", Map.of("SIZE", "LONG", "RULING", "SINGLE_RULE"), "bookCount", first, "pageCount", 198),
                        Map.of("selections", Map.of("SIZE", "JUMBO_LONG", "RULING", "FOUR_RULE"), "bookCount", second, "pageCount", 100)));
    }

    private Map<String, Object> quote(String id, int unit, int gst) {
        var detail = tx(() -> forms.detail(id));
        return Map.of("version", detail.get("version"), "gstPaise", gst,
                "lines", detailLines(detail).stream().map(line -> Map.of("id", line.get("id"), "unitPricePaise", unit)).toList());
    }

    // Every prototype offers an optional upload, and bill books and fliers additionally take several
    // print reference images plus a note. Only the notebook design existed before.
    @Test
    void printReferencesAcceptSeveralImagesWhereADesignKeepsOnlyTheCurrentOne() {
        var created = create(true, 400, 600);
        var first = upload(created.id(), "PRINT_REFERENCE", 1);
        var second = upload(created.id(), "PRINT_REFERENCE", 2);
        assertThat(second.get("id")).isNotEqualTo(first.get("id"));
        // Both stay current: a reference set is a set, not a replacement.
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.catalog_order_assets WHERE order_id = :id AND asset_kind = 'PRINT_REFERENCE' AND superseded_at IS NULL")
                .param("id", created.id()).query(Long.class).single()).isEqualTo(2L);
        // A design still supersedes, so there is exactly one current design.
        upload(created.id(), "DESIGN", 3);
        upload(created.id(), "DESIGN", 4);
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.catalog_order_assets WHERE order_id = :id AND asset_kind = 'DESIGN' AND superseded_at IS NULL")
                .param("id", created.id()).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void optionalUploadsAreOfferedForEveryCategoryAndNeverBlockPlacement() {
        var offers = jdbc.sql("SELECT category_code, params ->> 'assetKind' AS kind FROM catalog.product_form_rules WHERE rule_type = 'OFFER_ASSET' ORDER BY category_code, kind")
                .query((rs, n) -> rs.getString("category_code") + ":" + rs.getString("kind")).list();
        assertThat(offers).containsExactlyInAnyOrder(
                "BELTS:DESIGN", "BILLBOOKS:DESIGN", "BILLBOOKS:PRINT_REFERENCE",
                "FLEX:DESIGN", "FLIERS:DESIGN", "FLIERS:PRINT_REFERENCE", "NOTEBOOKS:DESIGN",
                "TIES:DESIGN");
        // The flex PDF is 10 MB in the prototype where the images are 5 MB.
        assertThat(jdbc.sql("SELECT params ->> 'maxBytes' FROM catalog.product_form_rules WHERE rule_type = 'OFFER_ASSET' AND category_code = 'FLEX'")
                .query(String.class).single()).isEqualTo("10485760");
        // A non-customised order places with no attachment at all, as it did before.
        var created = create(false, 25, 30);
        assertThat(tx(() -> orders.placeOrder(created.id(), 7L)).status()).isNotNull();
    }

    private Map<String, Object> upload(String id, String kind, int color) {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, color);
            var output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return tx(() -> forms.upload(id, kind, output.toByteArray(), "artwork.png"));
        } catch (java.io.IOException ex) { throw new AssertionError(ex); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> detailLines(Map<String, Object> detail) { return (List<Map<String, Object>>) detail.get("lines"); }
    private static <T> T tx(Supplier<T> operation) { return transaction.execute(status -> operation.get()); }
    private static void school(long id) { TenantContext.set(new TenantContext(7L, "school@example.test", "ADMIN", id, null, Set.of(), Set.of("order:create", "order:read", "order:update"))); }
    private static void superadmin() { TenantContext.set(new TenantContext(99L, "superadmin@example.test", "SUPERADMIN", null, null)); }
}
