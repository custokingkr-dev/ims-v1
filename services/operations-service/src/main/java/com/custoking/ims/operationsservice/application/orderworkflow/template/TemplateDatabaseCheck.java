package com.custoking.ims.operationsservice.application.orderworkflow.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fails the startup when the templates do not match the migrated database (design D2; risk "a template drifts from the
 * workflow_steps rows" of §5.5). Runs after every singleton is created, so the Flyway beans have migrated the schema.
 * {@code workflow.templates.verify-steps=false} turns it off for contexts without the workflow schema.
 */
@Component
@ConditionalOnProperty(name = "workflow.templates.verify-steps", havingValue = "true", matchIfMissing = true)
public class TemplateDatabaseCheck implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(TemplateDatabaseCheck.class);
    private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final JdbcClient jdbc;
    private final WorkflowTemplates templates;
    private final String schema;

    public TemplateDatabaseCheck(JdbcClient jdbc, WorkflowTemplates templates, @Value("${workflow.db.schema:workflow}") String schema) {
        if (!SCHEMA.matcher(schema).matches()) throw new IllegalArgumentException("Invalid workflow schema name " + schema);
        this.jdbc = jdbc;
        this.templates = templates;
        this.schema = schema;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    /** Compares the loaded templates with {@code workflow_steps} and {@code workflow_vendor_categories}. */
    public void verify() {
        List<String> ids = templates.all().stream().map(WorkflowTemplate::definitionId).toList();
        if (ids.isEmpty()) throw new InvalidTemplateException(List.of("no order workflow templates are loaded"));
        List<TemplateConsistency.StepRow> rows = jdbc.sql("SELECT definition_id, step_order, step_code FROM " + schema
                        + ".workflow_steps WHERE definition_id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new TemplateConsistency.StepRow(rs.getString("definition_id"), rs.getInt("step_order"),
                        rs.getString("step_code")))
                .list();
        Set<String> categories = new HashSet<>(jdbc.sql("SELECT code FROM " + schema + ".workflow_vendor_categories")
                .query(String.class).list());
        List<String> problems = TemplateConsistency.problems(templates.all(), rows, categories);
        if (!problems.isEmpty()) throw new InvalidTemplateException(problems);
        log.info("Order workflow templates match the database: {}", ids);
    }
}
