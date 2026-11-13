package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One stage of a workflow template (design §2.7, §3.3, §3.4).
 *
 * @param order               the stage number, 1 to n; also the {@code workflow_steps.step_order} of the definition
 * @param code                the stage code, also the order's status while this stage is active
 * @param title               the stage name on screen
 * @param description         the one-line description under the title
 * @param phase               the board column the stage belongs to
 * @param defaultSla          the default time slot, or null for no timer. The template file holds the shipped default;
 *                            served templates carry the current (superadmin-edited) default instead
 * @param customisedOnly      skipped on wholesale notebook orders (D7)
 * @param icon                lucide-react icon name for the stage tile, e.g. "ShieldCheck"
 * @param tabs                the stage's tabs, in order
 * @param blocks              the work-area cards, in order
 * @param fields              typed fields by key, in display order
 * @param checklists          checklists by key, in display order
 * @param gates               what {@code Complete} requires, in the order unmet requirements are listed
 * @param signOff             the stage's sign-off, or null
 * @param superadminCompletes only superadmin may complete the stage (Head Approval)
 */
public record StepSpec(int order, String code, String title, String description, Phase phase, Sla defaultSla,
                       boolean customisedOnly, String icon, List<Tab> tabs, List<BlockSpec> blocks, Map<String, FieldSpec> fields,
                       Map<String, ChecklistSpec> checklists, List<GateSpec> gates, SignOffSpec signOff, boolean superadminCompletes) {

    public StepSpec {
        if (order < 1) throw new IllegalArgumentException("A stage order starts at 1");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("A stage needs a code");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("A stage needs a title");
        if (phase == null) throw new IllegalArgumentException("A stage needs a phase");
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        fields = ordered(fields);
        checklists = ordered(checklists);
        gates = gates == null ? List.of() : List.copyOf(gates);
    }

    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    public Optional<FieldSpec> field(String key) { return Optional.ofNullable(fields.get(key)); }

    public Optional<ChecklistSpec> checklist(String key) { return Optional.ofNullable(checklists.get(key)); }

    public boolean hasSignOff() { return signOff != null; }

    /** Whether one of the stage's gates has this type. */
    public boolean hasGate(GateType type) { return gates.stream().anyMatch(gate -> gate.type() == type); }

    /** Whether one of the stage's cards has this type. */
    public boolean hasBlock(BlockType type) { return blocks.stream().anyMatch(block -> block.type() == type); }

    /** The title of the card that shows a checklist, when the template gives one. */
    public Optional<String> checklistTitle(String checklistKey) {
        return blocks.stream()
                .filter(block -> checklistKey.equals(block.checklist()) && block.title() != null && !block.title().isBlank())
                .map(BlockSpec::title).findFirst();
    }

    /** This stage with a different default time slot (null for no timer); served templates carry the current default. */
    public StepSpec withDefaultSla(Sla sla) {
        return new StepSpec(order, code, title, description, phase, sla, customisedOnly, icon, tabs, blocks, fields, checklists,
                gates, signOff, superadminCompletes);
    }
}
