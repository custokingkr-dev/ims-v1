package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormValidationException;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogProductFormIntegrationTest {
    private static PostgreSQLContainer<?> pg;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaction;
    private ProductCatalogRepository repository;
    private OutboxWriter outbox;

    @BeforeAll
    static void database() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for catalog integration tests");
        pg = new PostgreSQLContainer<>("postgres:16").withUsername("owner").withPassword("owner");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), "owner", "owner").schemas("catalog").defaultSchema("catalog")
                .locations("classpath:db/migration/catalog").load().migrate();
        var dataSource = new DriverManagerDataSource(pg.getJdbcUrl(), "owner", "owner");
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setup() {
        outbox = mock(OutboxWriter.class);
        repository = new ProductCatalogRepository(jdbc, new ObjectMapper(), outbox, true);
        TenantContext.set(new TenantContext(7L, "sa@example.test", "SUPERADMIN", null, null));
    }
    @AfterEach void clear() { TenantContext.clear(); }
    @AfterAll static void stop() { if (pg != null) pg.stop(); }

    @Test
    void seedContainsAllConfirmedOptionsAndFourRules() {
        var definition = repository.form("NOTEBOOKS", false);
        assertEquals(7, repository.categories(false).size());
        assertTrue(Boolean.TRUE.equals(definition.get("enabled")));
        assertEquals(3, maps(definition.get("groups")).size());
        assertEquals(23, maps(definition.get("groups")).stream().mapToInt(g -> maps(g.get("options")).size()).sum());
        assertEquals(15, maps(group(definition, "RULING").get("options")).size());
        assertEquals(1, maps(group(definition, "SIZE").get("options")).stream().filter(o -> "PENDING_SPEC".equals(o.get("specStatus"))).count());
        assertEquals(1, maps(group(definition, "SIZE").get("options")).stream().filter(o -> "FA_A4".equals(o.get("code"))).count());
        assertEquals(4, maps(definition.get("rules")).size());
        assertTrue(maps(definition.get("dependencies")).isEmpty());
    }

    @Test
    void createEditDeactivateAndHardDeleteUnusedOption() {
        transaction.executeWithoutResult(status -> {
            var ruling = group(repository.form("NOTEBOOKS", true), "RULING");
            var option = repository.create("options", row("groupId", ruling.get("id"), "code", "TEST_RULING", "label", "Test ruling"));
            var renamed = repository.update("options", option.get("id"), Map.of("label", "Revised ruling", "sortOrder", 99));
            assertEquals("Revised ruling", renamed.get("label"));
            assertThrows(ProductFormValidationException.class, () -> repository.update("options", option.get("id"), Map.of("code", "DIFFERENT")));
            repository.delete("options", option.get("id"), false);
            assertTrue(maps(group(repository.form("NOTEBOOKS", false), "RULING").get("options")).stream().noneMatch(o -> "TEST_RULING".equals(o.get("code"))));
            assertTrue(maps(group(repository.form("NOTEBOOKS", true), "RULING").get("options")).stream().anyMatch(o -> "TEST_RULING".equals(o.get("code"))));
            repository.delete("options", option.get("id"), true);
            status.setRollbackOnly();
        });
        verify(outbox, atLeastOnce()).append(eq("catalog-configuration.changed.v1"), anyString(), eq("catalog-configuration"), anyString(), isNull(), argThat(payload -> Long.valueOf(7).equals(payload.get("actorId"))));
    }

    @Test
    void incompleteSizeCannotBecomeConfirmedWithoutDimensions() {
        transaction.executeWithoutResult(status -> {
            var definition = repository.form("NOTEBOOKS", true);
            var king = maps(group(definition, "SIZE").get("options")).stream().filter(o -> "DRAWING_BOOK".equals(o.get("code"))).findFirst().orElseThrow();
            assertThrows(ProductFormValidationException.class, () -> repository.update("options", king.get("id"), Map.of("specStatus", "CONFIRMED")));
            var confirmed = repository.update("options", king.get("id"), Map.of("specStatus", "CONFIRMED", "widthMm", 190, "heightMm", 250, "specText", "19 cm x 25 cm"));
            assertEquals("CONFIRMED", confirmed.get("specStatus"));
            status.setRollbackOnly();
        });
    }

    @Test
    void referencedOptionHardDeleteReportsOrdersAndSoftDeleteRetainsSnapshot() {
        transaction.executeWithoutResult(status -> {
            var definition = repository.form("NOTEBOOKS", true);
            var longSize = maps(group(definition, "SIZE").get("options")).stream().filter(o -> "LONG".equals(o.get("code"))).findFirst().orElseThrow();
            jdbc.sql("""
                    INSERT INTO catalog.catalog_orders (id,category,school_id,subtotal,gst,total_amount,form_version,order_selections,form_snapshot)
                    VALUES ('catalog-ref-test','NOTEBOOKS',10,0,0,0,2,'{"SIZE":{"code":"LONG","label":"Long"}}',:snapshot::jsonb)
                    """).param("snapshot", new ObjectMapper().writeValueAsString(definition)).update();
            var exception = assertThrows(ProductCatalogRepository.CatalogReferenceConflict.class, () -> repository.delete("options", longSize.get("id"), true));
            assertEquals(1, exception.referencingOrderCount());
            repository.delete("options", longSize.get("id"), false);
            assertEquals("Long", jdbc.sql("SELECT order_selections -> 'SIZE' ->> 'label' FROM catalog.catalog_orders WHERE id='catalog-ref-test'").query(String.class).single());
            status.setRollbackOnly();
        });
    }

    @Test
    void configurableQuantityAndRulesAreValidatedBeforeStorage() {
        transaction.executeWithoutResult(status -> {
            var definition = repository.form("NOTEBOOKS", true);
            var quantity = maps(definition.get("rules")).stream().filter(r -> "FLOOR_VALUE".equals(r.get("ruleType"))).findFirst().orElseThrow();
            var saved = repository.update("rules", quantity.get("id"), Map.of("params", Map.of("value", 500)));
            assertEquals(500, map(saved.get("params")).get("value"));
            assertThrows(ProductFormValidationException.class, () -> repository.update("rules", quantity.get("id"), Map.of("params", Map.of("value", -1))));
            assertThrows(ProductFormValidationException.class, () -> repository.update("rules", quantity.get("id"), Map.of("params", Map.of("value", 500, "unexpected", 1))));
            status.setRollbackOnly();
        });
    }

    @Test
    void createsCategoriesGroupsAndRulesWithTypedIdentifiers() {
        transaction.executeWithoutResult(status -> {
            var category = repository.create("categories", Map.of("code", "TEST_PRODUCT", "label", "Test product"));
            assertEquals("TEST_PRODUCT", category.get("code"));
            var group = repository.create("groups", Map.of("categoryCode", "TEST_PRODUCT", "code", "VARIANT", "label", "Variant", "scope", "LINE"));
            assertInstanceOf(Number.class, group.get("id"));
            var option = repository.create("options", Map.of("groupId", group.get("id"), "code", "STANDARD", "label", "Standard"));
            assertInstanceOf(Number.class, option.get("id"));
            var rule = repository.create("rules", row("categoryCode", "TEST_PRODUCT", "ruleType", "MIN_VALUE", "targetField", "BOOK_COUNT",
                    "matchOptions", Map.of("VARIANT", "STANDARD"), "params", Map.of("value", 10)));
            assertInstanceOf(Number.class, rule.get("id"));
            assertEquals(10, map(rule.get("params")).get("value"));
            assertThrows(ResponseStatusException.class, () -> repository.delete("options", option.get("id"), true));
            repository.delete("rules", rule.get("id"), true);
            repository.delete("options", option.get("id"), true);
            repository.delete("groups", group.get("id"), true);
            repository.delete("categories", category.get("code"), true);
            status.setRollbackOnly();
        });
    }

    private static Map<String, Object> group(Map<String, Object> definition, String code) {
        return maps(definition.get("groups")).stream().filter(g -> code.equals(g.get("code"))).findFirst().orElseThrow();
    }
}
