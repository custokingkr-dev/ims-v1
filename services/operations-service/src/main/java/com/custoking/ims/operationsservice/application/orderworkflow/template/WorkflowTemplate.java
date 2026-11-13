package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One item workflow template (design D2, §3.3): every stage with its cards, fields, checklists, gates and sign-off. Loaded
 * from {@code workflow-templates/<definitionId>.json} at startup and served to the browser, which renders stages from it.
 *
 * @param definitionId the {@code workflow_definitions.id}, e.g. {@code NOTEBOOK_ORDER_TRACKER}
 * @param title        the template's name, e.g. "Notebook order workflow"
 * @param introMessage the SOP introduction message with a {@code {Name}} placeholder the browser fills (D28)
 * @param steps        every stage in order; a wholesale order's path leaves out the customised-only stages
 */
public record WorkflowTemplate(String definitionId, String title, String introMessage, List<StepSpec> steps) {

    public WorkflowTemplate {
        if (definitionId == null || definitionId.isBlank()) throw new IllegalArgumentException("A template needs its definition id");
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (steps.isEmpty()) throw new IllegalArgumentException("A template needs at least one stage");
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).order() != index + 1) {
                throw new IllegalArgumentException("Template stages must be numbered 1 to " + steps.size() + " in order");
            }
        }
    }

    /** The stages an order runs: every stage for a customised order, the stages that are not customised-only otherwise. */
    public List<StepSpec> pathFor(boolean customised) {
        return steps.stream().filter(step -> customised || !step.customisedOnly()).toList();
    }

    /** The stage with this number, whether or not a given order's path includes it. */
    public Optional<StepSpec> step(int order) {
        return order >= 1 && order <= steps.size() ? Optional.of(steps.get(order - 1)) : Optional.empty();
    }

    public Optional<StepSpec> stepByCode(String code) {
        return steps.stream().filter(step -> step.code().equals(code)).findFirst();
    }

    /**
     * This template with the current default time slots. A stage missing from {@code defaults} keeps the template file's
     * default; an empty value means no timer.
     */
    public WorkflowTemplate withDefaultSlas(Map<Integer, Optional<Sla>> defaults) {
        List<StepSpec> updated = steps.stream().map(step -> defaults.containsKey(step.order())
                ? step.withDefaultSla(defaults.get(step.order()).orElse(null)) : step).toList();
        return new WorkflowTemplate(definitionId, title, introMessage, updated);
    }
}
