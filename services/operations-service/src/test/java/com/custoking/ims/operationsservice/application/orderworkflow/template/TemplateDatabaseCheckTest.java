package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.TemplateFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The startup check against a database migrated by the real workflow migrations: the shipped templates match their
 * {@code workflow_steps} rows and vendor categories, and drift on either side refuses the startup (design D2, §5.5).
 */
class TemplateDatabaseCheckTest {
    static PostgreSQLContainer<?> pg;
    static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        pg = new PostgreSQLContainer<>("postgres:16");
        pg.start();
        Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .schemas("workflow").defaultSchema("workflow")
                .locations("classpath:db/migration/workflow").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
    }

    @AfterAll
    static void stop() {
        if (pg != null) pg.stop();
    }

    @Test
    void theShippedTemplatesMatchTheMigratedDatabase() {
        assertDoesNotThrow(() -> new TemplateDatabaseCheck(jdbc, new ClasspathWorkflowTemplates(), "workflow").verify());
    }

    @Test
    void aStepCodeChangedInTheDatabaseRefusesTheStartup() {
        jdbc.sql("UPDATE workflow.workflow_steps SET step_code = 'QUALITY_GATE' WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order = 7")
                .update();
        try {
            InvalidTemplateException refused = assertThrows(InvalidTemplateException.class,
                    () -> new TemplateDatabaseCheck(jdbc, new ClasspathWorkflowTemplates(), "workflow").verify());
            assertEquals(List.of("NOTEBOOK_ORDER_TRACKER: stage 7 is PRE_PRINT_QUALITY_GATE in the template but QUALITY_GATE in workflow_steps"),
                    refused.problems());
        } finally {
            jdbc.sql("UPDATE workflow.workflow_steps SET step_code = 'PRE_PRINT_QUALITY_GATE' WHERE definition_id = 'NOTEBOOK_ORDER_TRACKER' AND step_order = 7")
                    .update();
        }
    }

    @Test
    void aTemplateWithoutDefinitionRowsRefusesTheStartup() {
        WorkflowTemplates unknown = new ClasspathWorkflowTemplates(List.of(TemplateFixtures.load("minimal/MINIMAL_WORKFLOW.json")));
        InvalidTemplateException refused = assertThrows(InvalidTemplateException.class,
                () -> new TemplateDatabaseCheck(jdbc, unknown, "workflow").verify());
        assertEquals(List.of("MINIMAL_WORKFLOW: the definition has no workflow_steps rows"), refused.problems());
    }

    @Test
    void theSchemaNameIsValidatedBeforeItIsUsedInSql() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateDatabaseCheck(jdbc, new ClasspathWorkflowTemplates(), "workflow; DROP TABLE x"));
    }
}
