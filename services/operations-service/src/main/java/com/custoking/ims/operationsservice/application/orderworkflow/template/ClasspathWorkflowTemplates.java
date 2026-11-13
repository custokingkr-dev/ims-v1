package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The service's templates, loaded from {@code workflow-templates/*.json} on the classpath when the bean is created. An
 * invalid file, or a missing one of the five definitions, fails the bean and so the startup (design D2). That the files
 * also match their {@code workflow_steps} rows is checked once the database is migrated
 * ({@link TemplateDatabaseCheck}).
 */
@Component
public class ClasspathWorkflowTemplates implements WorkflowTemplates {
    private final Map<String, WorkflowTemplate> templates;

    /** The shipped templates; all five definitions are required. */
    public ClasspathWorkflowTemplates() {
        this(WorkflowTemplateLoader.CLASSPATH_LOCATION, DEFINITION_IDS);
    }

    /** Templates from another resource pattern (tests), requiring the given definitions. */
    public ClasspathWorkflowTemplates(String locationPattern, Collection<String> required) {
        this(WorkflowTemplateLoader.loadAll(locationPattern, required));
    }

    /** Already-loaded templates (tests and tools). */
    public ClasspathWorkflowTemplates(Collection<WorkflowTemplate> loaded) {
        List<WorkflowTemplate> sorted = new ArrayList<>(loaded);
        sorted.sort(Comparator.comparing((WorkflowTemplate template) -> rank(template.definitionId()))
                .thenComparing(WorkflowTemplate::definitionId));
        Map<String, WorkflowTemplate> byId = new LinkedHashMap<>();
        for (WorkflowTemplate template : sorted) {
            if (byId.putIfAbsent(template.definitionId(), template) != null) {
                throw new IllegalArgumentException("Definition " + template.definitionId() + " is loaded twice");
            }
        }
        this.templates = Collections.unmodifiableMap(byId);
    }

    @Override
    public WorkflowTemplate get(String definitionId) {
        WorkflowTemplate template = definitionId == null ? null : templates.get(definitionId);
        if (template == null) {
            throw new WorkflowRuleViolation("UNKNOWN_WORKFLOW", "No order workflow template " + definitionId + " is installed");
        }
        return template;
    }

    @Override
    public Collection<WorkflowTemplate> all() {
        return templates.values();
    }

    private static int rank(String definitionId) {
        int index = DEFINITION_IDS.indexOf(definitionId);
        return index < 0 ? DEFINITION_IDS.size() : index;
    }
}
