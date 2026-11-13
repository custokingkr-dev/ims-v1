package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The item workflow templates the service runs (design D2, D8), validated at startup.
 */
public interface WorkflowTemplates {

    String NOTEBOOK = "NOTEBOOK_ORDER_TRACKER";
    String FLIER = "FLIER_ORDER_WORKFLOW";
    String FLEX = "FLEX_ORDER_WORKFLOW";
    String BILL_BOOK = "BILL_BOOK_ORDER_WORKFLOW";
    String GENERAL_ITEM = "GENERAL_ITEM_ORDER_WORKFLOW";

    /**
     * The five templates school-core's {@code catalog.product_categories.workflow_definition} CHECK allows; the service
     * refuses to start without every one of them.
     */
    List<String> DEFINITION_IDS = List.of(NOTEBOOK, FLIER, FLEX, BILL_BOOK, GENERAL_ITEM);

    /**
     * The template with this definition id.
     *
     * @throws WorkflowRuleViolation {@code UNKNOWN_WORKFLOW} (409) when no such template is loaded
     */
    WorkflowTemplate get(String definitionId);

    /** Every loaded template, in {@link #DEFINITION_IDS} order and then by id. */
    Collection<WorkflowTemplate> all();

    default Optional<WorkflowTemplate> find(String definitionId) {
        return all().stream().filter(template -> template.definitionId().equals(definitionId)).findFirst();
    }
}
