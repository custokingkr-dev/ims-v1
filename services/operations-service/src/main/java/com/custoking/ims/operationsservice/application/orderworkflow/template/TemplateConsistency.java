package com.custoking.ims.operationsservice.application.orderworkflow.template;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Compares the templates with the database they run against (design D2, §3.1): each template's stage numbers and codes
 * must equal its definition's {@code workflow_steps} rows exactly, because instance steps, SLA overrides and the order
 * statuses school-core stores are keyed by them. Every vendor category a template names must exist in
 * {@code workflow_vendor_categories}, or the vendor gate could never pass. Default time slots are deliberately not
 * compared: superadmin edits them at runtime.
 */
public final class TemplateConsistency {

    private TemplateConsistency() { }

    /** One {@code workflow_steps} row: its definition, number and code (null before the code column was filled). */
    public record StepRow(String definitionId, int stepOrder, String stepCode) { }

    /** Every mismatch found; empty when the templates and the database agree. */
    public static List<String> problems(Collection<WorkflowTemplate> templates, Collection<StepRow> rows, Set<String> vendorCategories) {
        List<String> problems = new ArrayList<>();
        Map<String, List<StepRow>> byDefinition = rows.stream().collect(Collectors.groupingBy(StepRow::definitionId));
        for (WorkflowTemplate template : templates) {
            String id = template.definitionId();
            Map<Integer, String> stored = new TreeMap<>();
            for (StepRow row : byDefinition.getOrDefault(id, List.of())) stored.put(row.stepOrder(), row.stepCode());
            if (stored.isEmpty()) {
                problems.add(id + ": the definition has no workflow_steps rows");
                continue;
            }
            Map<Integer, String> declared = new TreeMap<>();
            template.steps().forEach(step -> declared.put(step.order(), step.code()));
            for (Map.Entry<Integer, String> step : declared.entrySet()) {
                String code = stored.get(step.getKey());
                if (!stored.containsKey(step.getKey())) {
                    problems.add(id + ": stage " + step.getKey() + " (" + step.getValue() + ") has no workflow_steps row");
                } else if (!step.getValue().equals(code)) {
                    problems.add(id + ": stage " + step.getKey() + " is " + step.getValue() + " in the template but " + code
                            + " in workflow_steps");
                }
            }
            for (Integer order : stored.keySet()) {
                if (!declared.containsKey(order)) problems.add(id + ": workflow_steps row " + order + " is not a stage of the template");
            }
        }
        Set<String> referenced = referencedCategories(templates);
        referenced.removeAll(vendorCategories);
        for (String missing : referenced) problems.add("vendor category " + missing + " is named by a template but does not exist");
        return problems;
    }

    /** Every vendor category code the templates name, in gates and cards. */
    public static Set<String> referencedCategories(Collection<WorkflowTemplate> templates) {
        Set<String> codes = new LinkedHashSet<>();
        for (WorkflowTemplate template : templates) {
            for (StepSpec step : template.steps()) {
                step.gates().forEach(gate -> { if (gate.categories() != null) codes.addAll(gate.categories()); });
                for (BlockSpec block : step.blocks()) {
                    if (block.categories() != null) codes.addAll(block.categories());
                    if (block.suggestedByItem() != null) block.suggestedByItem().values().forEach(codes::addAll);
                }
            }
        }
        return codes.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
