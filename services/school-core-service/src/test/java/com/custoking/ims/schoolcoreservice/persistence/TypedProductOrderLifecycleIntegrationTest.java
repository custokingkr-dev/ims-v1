package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormController;
import com.custoking.ims.schoolcoreservice.api.CatalogOrderFormExceptionHandler;
import com.custoking.ims.schoolcoreservice.api.CatalogProductFormExceptionHandler;
import com.custoking.ims.schoolcoreservice.api.CatalogReadController;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Orders of every category whose lines carry typed values (a length in feet, a free-text size, a GSM), driven through the
 * order endpoints the screen calls, against the runtime role with row-level security forced, as production runs them. The
 * typed values are stored apart from the option selections, and every lifecycle step that re-checks the saved order (place,
 * final approval, delivery) must check what was saved. Until 2026-11-12 none of these could be placed ("Enter Length"), and
 * fliers and bill books could not even be saved, because a whole number typed as text was refused.
 */
class TypedProductOrderLifecycleIntegrationTest {
    private static final String TOKEN = "test-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> TYPED = ProductOrderFixtures.TYPED;

    static PostgreSQLContainer<?> pg;
    static JdbcClient owner;
    static AnnotationConfigApplicationContext context;
    static MockMvc mvc;
    @TempDir static Path assets;

    record Actor(String role, long userId, Long schoolId, String permissions) { }

    static final Actor SUPERADMIN = new Actor("SUPERADMIN", 99, null, null);
    static final Actor SCHOOL_ADMIN = new Actor("ADMIN", 7, 1L, "order:create,order:read,order:update");

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
        owner.sql("INSERT INTO tenant_school.schools(id, name, short_code, active, created_at) VALUES (1,'School A','SCA',true,now())").update();
        owner.sql("INSERT INTO tenant_school.school_module_entitlements(school_id,module_code,enabled) VALUES (1,'ORDERS',true)").update();
        owner.sql("CREATE ROLE app_rt LOGIN PASSWORD 'app_rt' NOINHERIT NOCREATEROLE NOCREATEDB NOBYPASSRLS").update();
        owner.sql("GRANT USAGE ON SCHEMA catalog, tenant_school TO app_rt").update();
        owner.sql("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA catalog, tenant_school TO app_rt").update();
        owner.sql("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA catalog, tenant_school TO app_rt").update();

        DataSource runtime = new TenantAwareDataSource(new DriverManagerDataSource(pg.getJdbcUrl(), "app_rt", "app_rt"));
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
        context.refresh();
        var orders = context.getBean(CatalogReadRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new CatalogReadController(orders, context.getBean(ModuleEntitlementGuard.class), TOKEN),
                        new CatalogOrderFormController(context.getBean(CatalogOrderFormService.class), orders, TOKEN))
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

    /** Saved, re-saved as a draft, placed, quoted, finally approved and delivered, with the typed values kept throughout. */
    @ParameterizedTest
    @FieldSource("TYPED")
    void aTypedOrderRunsTheWholeOrderLifecycle(String category) throws Exception {
        var expected = ProductOrderFixtures.storedAttributes(category);
        String id = create(category);
        var draft = form(id, SCHOOL_ADMIN);
        assertThat(attributes(draft)).as(category + " stores its typed values apart from the selections").isEqualTo(expected);

        // Saving the draft again sends the same text values through the draft path.
        var resaved = read(mvc.perform(as(patch("/api/v1/supply/orders/" + id), SCHOOL_ADMIN).contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("version", draft.get("version"), "orderData", ProductOrderFixtures.orderData(category)))))
                .andExpect(status().isOk()));
        assertThat(attributes(resaved)).as(category).isEqualTo(expected);

        // Placement re-checks the saved order, typed values included.
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/place"), SCHOOL_ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));

        var placed = form(id, SUPERADMIN);
        assertThat(attributes(placed)).as(category).isEqualTo(expected);
        var quote = new LinkedHashMap<String, Object>();
        quote.put("version", placed.get("version"));
        quote.put("gstPaise", 1800);
        quote.put("lines", lines(placed).stream().map(line -> Map.of("id", line.get("id"), "unitPricePaise", 1000)).toList());
        mvc.perform(as(put("/api/v1/supply/orders/" + id + "/quote"), SUPERADMIN).contentType("application/json")
                .content(JSON.writeValueAsString(quote))).andExpect(status().isOk()).andExpect(jsonPath("$.pricingStatus").value("QUOTED"));

        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/superadmin-approve"), SUPERADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/deliver"), SUPERADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELIVERED"));
        assertThat(attributes(form(id, SCHOOL_ADMIN))).as(category).isEqualTo(expected);
    }

    /** What placement checks is the saved order: a required typed value that is missing from it is still refused. */
    @Test
    void placementChecksTheTypedValuesThatWereSaved() throws Exception {
        String id = create("FLEX");
        owner.sql("UPDATE catalog.catalog_order_lines SET attributes = attributes - 'BREADTH_FT' WHERE order_id = :id").param("id", id).update();
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/place"), SCHOOL_ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Enter Breadth"))
                .andExpect(jsonPath("$.fieldErrors['lines[0].selections.BREADTH_FT']").value("Enter Breadth"));
        owner.sql("UPDATE catalog.catalog_order_lines SET attributes = jsonb_set(attributes, '{BREADTH_FT}', '-3') WHERE order_id = :id")
                .param("id", id).update();
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/place"), SCHOOL_ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Breadth must be greater than zero"));
        assertThat(owner.sql("SELECT status FROM catalog.catalog_orders WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("DRAFT");
    }

    /** A refused lifecycle step tells the screen why, rather than leaving it to say "Request failed with status code 400". */
    @Test
    void aRefusedLifecycleStepCarriesItsReason() throws Exception {
        String id = create("FLIERS");
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/superadmin-approve"), SUPERADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This action is unavailable while the order is DRAFT"));
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/place"), SCHOOL_ADMIN)).andExpect(status().isOk());
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/place"), SCHOOL_ADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("This action is unavailable while the order is PROCESSING"));
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/superadmin-approve"), SUPERADMIN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Save the superadmin quote before final approval"));
        mvc.perform(as(post("/api/v1/catalog/orders/" + id + "/deliver"), SCHOOL_ADMIN))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Only superadmin or operations can mark orders delivered"));
    }

    private static String create(String category) throws Exception {
        var created = read(mvc.perform(as(post("/api/v1/catalog/orders"), SCHOOL_ADMIN).contentType("application/json")
                        .content(JSON.writeValueAsString(Map.of("category", category, "orderData", ProductOrderFixtures.orderData(category)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.category").value(category)));
        return String.valueOf(created.get("id"));
    }

    private static Map<String, Object> form(String id, Actor actor) throws Exception {
        return read(mvc.perform(as(get("/api/v1/supply/orders/" + id + "/form"), actor)).andExpect(status().isOk()));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> form) {
        return (List<Map<String, Object>>) form.get("lines");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> form) {
        return (Map<String, Object>) lines(form).getFirst().get("attributes");
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Actor actor) {
        request.header("X-Catalog-Service-Token", TOKEN).header("X-Authenticated-Role", actor.role())
                .header("X-Authenticated-User-Id", String.valueOf(actor.userId()));
        if (actor.schoolId() != null) request.header("X-Authenticated-School-Id", String.valueOf(actor.schoolId()));
        if (actor.permissions() != null) request.header("X-Authenticated-Permissions", actor.permissions());
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(ResultActions result) throws Exception {
        return JSON.readValue(result.andReturn().getResponse().getContentAsString(), Map.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionalTestConfiguration {}
}
