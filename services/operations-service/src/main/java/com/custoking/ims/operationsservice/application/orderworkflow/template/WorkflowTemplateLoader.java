package com.custoking.ims.operationsservice.application.orderworkflow.template;

import com.custoking.ims.operationsservice.application.orderworkflow.rules.Gates;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Role;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.StepDataRules;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates workflow template files (design D2, §3.3). Every problem is collected, with the file and the JSON
 * path it concerns, and reported at once as an {@link InvalidTemplateException}, so the service fails fast at startup
 * with the whole list. Besides the file format, the loader checks that the template is consistent with itself:
 * <ul>
 *   <li>stages are numbered 1 to n in file order, with unique codes (also order statuses, so never {@code COMPLETED});</li>
 *   <li>every field, checklist and choice value a gate or card names is declared on its stage, with the right kind;</li>
 *   <li>a sign-off item, {@code SIGNED_OFF} gate or {@code SIGN_OFF} card needs the stage's sign-off;</li>
 *   <li>keys that do not belong to an object (or to a gate or card type) are refused, so a misspelt key never silently
 *       weakens a gate. Keys starting with {@code $} or {@code _} are comments and ignored.</li>
 * </ul>
 * Each field kind's limits are filled in where the file leaves them out, so a served template shows what is enforced.
 */
public final class WorkflowTemplateLoader {
    /** Where the service's templates live: one file per definition, named {@code <definitionId>.json}. */
    public static final String CLASSPATH_LOCATION = "classpath*:workflow-templates/*.json";

    public static final long DEFAULT_TEXT_MAX = 1000;
    public static final long DEFAULT_WORDS_MAX = 100;
    public static final long MAX_WORDS_LIMIT = 1000;
    public static final long DEFAULT_NUMBER_MAX = 100_000_000L;
    public static final int MAX_CHECKLIST_ITEMS = 64;

    private static final Pattern DEFINITION_ID = Pattern.compile("[A-Z][A-Z0-9_]{1,99}");
    /** The order-status pattern school-core accepts (D5). */
    private static final Pattern STEP_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9]{0,63}");
    private static final Pattern CATEGORY = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Pattern ITEM_CODE = Pattern.compile("[A-Z]{2,4}");
    private static final Pattern ICON = Pattern.compile("[A-Z][A-Za-z0-9]{1,63}");

    private static final Set<String> TEMPLATE_KEYS = Set.of("definitionId", "title", "introMessage", "steps");
    private static final Set<String> STEP_KEYS = Set.of("order", "code", "title", "description", "phase", "sla", "defaultSla",
            "customisedOnly", "icon", "tabs", "blocks", "fields", "checklists", "gates", "signOff", "superadminCompletes");
    private static final Set<String> FIELD_KEYS = Set.of("kind", "label", "placeholder", "min", "max", "choices", "legacy");
    private static final Set<String> CHECKLIST_KEYS = Set.of("items", "signOffItem", "source");
    private static final Set<String> SIGN_OFF_KEYS = Set.of("label", "role", "banner");
    private static final Set<String> SLA_KEYS = Set.of("value", "unit");

    /** The parameters each gate type takes, besides {@code type}. */
    private static final Map<GateType, Set<String>> GATE_KEYS = new EnumMap<>(Map.of(
            GateType.FIELDS_PRESENT, Set.of("fields"),
            GateType.ALL_CHECKED, Set.of("checklist", "except"),
            GateType.CHOICE_IN, Set.of("field", "values"),
            GateType.DESIGN_FILES_PRESENT, Set.of(),
            GateType.VENDORS_SELECTED, Set.of("categories"),
            GateType.HEAD_APPROVED, Set.of(),
            GateType.SIGNED_OFF, Set.of(),
            GateType.COUNTS_CONSISTENT, Set.of("fields", "field")));

    /** The parameters each card type takes, besides {@code type} and {@code title}. */
    private static final Map<BlockType, Set<String>> BLOCK_KEYS = new EnumMap<>(BlockType.class);

    static {
        for (BlockType type : BlockType.values()) BLOCK_KEYS.put(type, Set.of());
        BLOCK_KEYS.put(BlockType.FIELDS, Set.of("fields", "save"));
        BLOCK_KEYS.put(BlockType.ORDER_PDF_LINK, Set.of("fields", "save"));
        BLOCK_KEYS.put(BlockType.LEGACY_DETAILS, Set.of("fields"));
        BLOCK_KEYS.put(BlockType.CHECKLIST, Set.of("checklist"));
        // The introduction card's one TEXT field keeps the message as sent, saved with the tick (D28).
        BLOCK_KEYS.put(BlockType.INTRO_MESSAGE, Set.of("checklist", "fields"));
        BLOCK_KEYS.put(BlockType.VENDOR_DIRECTORY, Set.of("categories"));
        BLOCK_KEYS.put(BlockType.VENDOR_GROUPS, Set.of("categories"));
        BLOCK_KEYS.put(BlockType.VENDOR_SELECTION, Set.of("categories", "suggestedByItem"));
    }

    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private WorkflowTemplateLoader() { }

    /** Parses and validates one template; {@code source} names it in problems and is checked against its definition id. */
    public static WorkflowTemplate parse(String json, String source) {
        return parse(json.getBytes(StandardCharsets.UTF_8), source);
    }

    public static WorkflowTemplate parse(InputStream input, String source) {
        try {
            return parse(input.readAllBytes(), source);
        } catch (IOException unreadable) {
            throw new InvalidTemplateException(List.of(source + ": cannot be read: " + unreadable.getMessage()), unreadable);
        }
    }

    private static WorkflowTemplate parse(byte[] json, String source) {
        Object tree;
        try {
            tree = JSON.readValue(json, Object.class);
        } catch (JacksonException malformed) {
            throw new InvalidTemplateException(List.of(source + ": is not valid JSON: " + malformed.getOriginalMessage()), malformed);
        }
        Parse parse = new Parse(source);
        WorkflowTemplate template = parse.template(tree);
        if (!parse.problems.isEmpty() || template == null) throw new InvalidTemplateException(parse.problems);
        return template;
    }

    /**
     * Loads every template matching the resource pattern and requires each of {@code required} to be among them. Every
     * problem in every file is reported together.
     */
    public static List<WorkflowTemplate> loadAll(String locationPattern, Collection<String> required) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException unreadable) {
            throw new InvalidTemplateException(List.of(locationPattern + ": cannot be listed: " + unreadable.getMessage()), unreadable);
        }
        Arrays.sort(resources, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
        List<String> problems = new ArrayList<>();
        Map<String, WorkflowTemplate> loaded = new LinkedHashMap<>();
        for (Resource resource : resources) {
            String name = resource.getFilename() == null ? resource.getDescription() : resource.getFilename();
            try (InputStream input = resource.getInputStream()) {
                WorkflowTemplate template = parse(input, name);
                if (loaded.putIfAbsent(template.definitionId(), template) != null) {
                    problems.add(name + ": definition " + template.definitionId() + " is defined by more than one file");
                }
            } catch (InvalidTemplateException invalid) {
                problems.addAll(invalid.problems());
            } catch (IOException unreadable) {
                problems.add(name + ": cannot be read: " + unreadable.getMessage());
            }
        }
        for (String id : required) {
            if (!loaded.containsKey(id)) problems.add(locationPattern + ": no template for required definition " + id);
        }
        if (!problems.isEmpty()) throw new InvalidTemplateException(problems);
        return List.copyOf(loaded.values());
    }

    // ---- parsing --------------------------------------------------------------------------------------------------------

    /** One file's parse: builds what it can and collects every problem. */
    private static final class Parse {
        private final String source;
        private final List<String> problems = new ArrayList<>();

        Parse(String source) { this.source = source; }

        WorkflowTemplate template(Object tree) {
            Map<String, Object> root = object(tree, "$");
            if (root == null) return null;
            allowKeys(root, "$", TEMPLATE_KEYS);
            String definitionId = string(root, "definitionId", "$", true);
            if (definitionId != null) {
                if (!DEFINITION_ID.matcher(definitionId).matches()) problem("$.definitionId", "must match " + DEFINITION_ID.pattern());
                if (source.endsWith(".json") && !source.equals(definitionId + ".json")) {
                    problem("$.definitionId", "is " + definitionId + " but the file must then be named " + definitionId + ".json");
                }
            }
            String title = string(root, "title", "$", true);
            String intro = string(root, "introMessage", "$", true);
            if (intro != null && !intro.contains("{Name}")) problem("$.introMessage", "must contain the {Name} placeholder");
            List<Object> rawSteps = list(root, "steps", "$", true);
            List<StepSpec> steps = new ArrayList<>();
            Set<String> codes = new HashSet<>();
            if (rawSteps != null) {
                if (rawSteps.isEmpty()) problem("$.steps", "must list at least one stage");
                for (int index = 0; index < rawSteps.size(); index++) {
                    String path = "$.steps[" + index + "]";
                    StepSpec step = step(rawSteps.get(index), path, index + 1);
                    if (step == null) continue;
                    if (!codes.add(step.code())) problem(path + ".code", "repeats stage code " + step.code());
                    steps.add(step);
                }
                if (!steps.isEmpty() && steps.get(0).customisedOnly()) {
                    problem("$.steps[0].customisedOnly", "the first stage must apply to every order");
                }
            }
            if (!problems.isEmpty()) return null;
            try {
                return new WorkflowTemplate(definitionId, title, intro, steps);
            } catch (IllegalArgumentException invalid) {
                problem("$", invalid.getMessage());
                return null;
            }
        }

        private StepSpec step(Object node, String path, int expectedOrder) {
            Map<String, Object> raw = object(node, path);
            if (raw == null) return null;
            int before = problems.size();
            allowKeys(raw, path, STEP_KEYS);
            Long order = whole(raw, "order", path, true);
            if (order != null && order != expectedOrder) {
                problem(path + ".order", "is " + order + "; stages must be numbered 1 to n in file order, so this one is " + expectedOrder);
            }
            String code = string(raw, "code", path, true);
            if (code != null && (!STEP_CODE.matcher(code).matches() || "COMPLETED".equals(code))) {
                problem(path + ".code", "must match " + STEP_CODE.pattern() + " and must not be COMPLETED");
            }
            String title = string(raw, "title", path, true);
            String description = string(raw, "description", path, true);
            Phase phase = enumValue(Phase.class, raw.get("phase"), path + ".phase", true);
            Sla sla = sla(raw, path);
            boolean customisedOnly = bool(raw, "customisedOnly", path, false);
            String icon = string(raw, "icon", path, true);
            if (icon != null && !ICON.matcher(icon).matches()) problem(path + ".icon", "must be a lucide icon name such as ShieldCheck");
            List<Tab> tabs = tabs(raw, path);

            Map<String, FieldSpec> fields = fields(raw, path);
            Map<String, ChecklistSpec> checklists = checklists(raw, path);
            for (String key : checklists.keySet()) {
                if (fields.containsKey(key)) problem(path + ".checklists." + key, "uses the same key as a field");
            }
            SignOffSpec signOff = signOff(raw.get("signOff"), path + ".signOff");
            checklists.forEach((key, checklist) -> {
                if (checklist.signOffItem() != null && signOff == null) {
                    problem(path + ".checklists." + key + ".signOffItem", "needs the stage to declare a signOff");
                }
            });
            StepContext context = new StepContext(fields, checklists, signOff);
            List<GateSpec> gates = gates(raw, path, context);
            List<BlockSpec> blocks = blocks(raw, path, context);
            boolean headApproval = gates.stream().anyMatch(gate -> gate.type() == GateType.HEAD_APPROVED);
            boolean superadminCompletes = bool(raw, "superadminCompletes", path, headApproval);

            if (problems.size() > before) return null;
            try {
                return new StepSpec(order.intValue(), code, title, description, phase, sla, customisedOnly, icon, tabs, blocks, fields,
                        checklists, gates, signOff, superadminCompletes);
            } catch (IllegalArgumentException invalid) {
                problem(path, invalid.getMessage());
                return null;
            }
        }

        private Sla sla(Map<String, Object> raw, String path) {
            if (raw.containsKey("sla") && raw.containsKey("defaultSla")) {
                problem(path, "gives both sla and defaultSla; use one");
                return null;
            }
            String key = raw.containsKey("defaultSla") ? "defaultSla" : "sla";
            Object node = raw.get(key);
            if (node == null) return null;
            String slaPath = path + "." + key;
            Map<String, Object> sla = object(node, slaPath);
            if (sla == null) return null;
            allowKeys(sla, slaPath, SLA_KEYS);
            Long value = whole(sla, "value", slaPath, true);
            Sla.Unit unit = enumValue(Sla.Unit.class, sla.get("unit"), slaPath + ".unit", true);
            if (value == null || unit == null) return null;
            if (value < 1 || value > Sla.MAX_VALUE) {
                problem(slaPath + ".value", "must be between 1 and " + Sla.MAX_VALUE);
                return null;
            }
            return new Sla(value, unit);
        }

        private List<Tab> tabs(Map<String, Object> raw, String path) {
            List<Object> values = list(raw, "tabs", path, true);
            List<Tab> tabs = new ArrayList<>();
            if (values == null) return tabs;
            if (values.isEmpty()) problem(path + ".tabs", "must list at least one tab");
            for (int index = 0; index < values.size(); index++) {
                Tab tab = enumValue(Tab.class, values.get(index), path + ".tabs[" + index + "]", true);
                if (tab == null) continue;
                if (tabs.contains(tab)) problem(path + ".tabs[" + index + "]", "repeats " + tab);
                else tabs.add(tab);
            }
            return tabs;
        }

        // ---- fields and checklists ------------------------------------------------------------------------------------

        private Map<String, FieldSpec> fields(Map<String, Object> raw, String path) {
            Map<String, FieldSpec> fields = new LinkedHashMap<>();
            Map<String, Object> declared = optionalObject(raw, "fields", path);
            if (declared == null) return fields;
            declared.forEach((key, node) -> {
                if (comment(key)) return;
                String fieldPath = path + ".fields." + key;
                if (!KEY.matcher(key).matches() || StepDataRules.SIGN_OFF_KEY.equals(key)) {
                    problem(fieldPath, "field keys must match " + KEY.pattern() + " and must not be " + StepDataRules.SIGN_OFF_KEY);
                    return;
                }
                FieldSpec field = field(node, fieldPath);
                if (field != null) fields.put(key, field);
            });
            return fields;
        }

        private FieldSpec field(Object node, String path) {
            Map<String, Object> raw = object(node, path);
            if (raw == null) return null;
            int before = problems.size();
            allowKeys(raw, path, FIELD_KEYS);
            FieldKind kind = enumValue(FieldKind.class, raw.get("kind"), path + ".kind", true);
            String label = string(raw, "label", path, true);
            String placeholder = string(raw, "placeholder", path, false);
            boolean legacy = bool(raw, "legacy", path, false);
            Long min = whole(raw, "min", path, false);
            Long max = whole(raw, "max", path, false);
            List<List<String>> choices = null;
            if (kind == null) return null;
            boolean numeric = kind == FieldKind.WHOLE || kind == FieldKind.NUMBER;
            if (min != null && !numeric) problem(path + ".min", "applies only to WHOLE and NUMBER fields");
            if (max != null && (kind == FieldKind.DATE || kind == FieldKind.CHOICE)) problem(path + ".max", "does not apply to " + kind + " fields");
            if (raw.containsKey("choices") && kind != FieldKind.CHOICE) problem(path + ".choices", "applies only to CHOICE fields");
            switch (kind) {
                case TEXT -> max = bounded(max, DEFAULT_TEXT_MAX, 1, StepDataRules.TEXT_MAX_CHARS, path + ".max");
                case WORDS -> max = bounded(max, DEFAULT_WORDS_MAX, 1, MAX_WORDS_LIMIT, path + ".max");
                case URL -> max = bounded(max, StepDataRules.URL_MAX_CHARS, 20, StepDataRules.URL_MAX_CHARS, path + ".max");
                case WHOLE, NUMBER -> {
                    min = min == null ? 0L : min;
                    max = max == null ? DEFAULT_NUMBER_MAX : max;
                    if (min > max) problem(path + ".min", "must not be greater than max");
                }
                case CHOICE -> choices = choices(raw.get("choices"), path + ".choices");
                case DATE -> { }
            }
            if (problems.size() > before) return null;
            return new FieldSpec(kind, label, placeholder, numeric ? min : null, max, choices, legacy);
        }

        private Long bounded(Long value, long fallback, long lowest, long highest, String path) {
            if (value == null) return fallback;
            if (value < lowest || value > highest) problem(path, "must be between " + lowest + " and " + highest);
            return value;
        }

        private List<List<String>> choices(Object node, String path) {
            if (!(node instanceof List<?> raw) || raw.isEmpty()) {
                problem(path, "a CHOICE field needs a non-empty list of [value, label] pairs");
                return null;
            }
            List<List<String>> choices = new ArrayList<>();
            Set<String> values = new HashSet<>();
            for (int index = 0; index < raw.size(); index++) {
                Object item = raw.get(index);
                String itemPath = path + "[" + index + "]";
                String value = null, label = null;
                if (item instanceof List<?> pair && pair.size() == 2 && pair.get(0) instanceof String v && pair.get(1) instanceof String l) {
                    value = v;
                    label = l;
                } else if (item instanceof Map<?, ?> map && map.get("value") instanceof String v && map.get("label") instanceof String l
                        && map.keySet().stream().allMatch(key -> "value".equals(key) || "label".equals(key))) {
                    value = v;
                    label = l;
                }
                if (value == null || value.isBlank() || label.isBlank() || value.length() > 64) {
                    problem(itemPath, "must be a [value, label] pair of non-blank text (value at most 64 characters)");
                    continue;
                }
                if (!values.add(value)) problem(itemPath, "repeats the value " + value);
                choices.add(List.of(value, label));
            }
            return choices;
        }

        private Map<String, ChecklistSpec> checklists(Map<String, Object> raw, String path) {
            Map<String, ChecklistSpec> checklists = new LinkedHashMap<>();
            Map<String, Object> declared = optionalObject(raw, "checklists", path);
            if (declared == null) return checklists;
            declared.forEach((key, node) -> {
                if (comment(key)) return;
                String listPath = path + ".checklists." + key;
                if (!KEY.matcher(key).matches() || StepDataRules.SIGN_OFF_KEY.equals(key)) {
                    problem(listPath, "checklist keys must match " + KEY.pattern() + " and must not be " + StepDataRules.SIGN_OFF_KEY);
                    return;
                }
                Map<String, Object> spec = object(node, listPath);
                if (spec == null) return;
                int before = problems.size();
                allowKeys(spec, listPath, CHECKLIST_KEYS);
                List<String> items = strings(spec, "items", listPath, true);
                if (items != null && (items.isEmpty() || items.size() > MAX_CHECKLIST_ITEMS)) {
                    problem(listPath + ".items", "must list between 1 and " + MAX_CHECKLIST_ITEMS + " items");
                }
                Long signOffItem = whole(spec, "signOffItem", listPath, false);
                if (items != null && signOffItem != null && (signOffItem < 0 || signOffItem >= items.size())) {
                    problem(listPath + ".signOffItem", "must be the zero-based index of one of the items");
                }
                String sourceName = string(spec, "source", listPath, true);
                if (sourceName != null && !ChecklistSpec.SOURCES.contains(sourceName)) {
                    problem(listPath + ".source", "must be one of " + ChecklistSpec.SOURCES);
                }
                if (problems.size() > before) return;
                checklists.put(key, new ChecklistSpec(items, signOffItem == null ? null : signOffItem.intValue(), sourceName));
            });
            return checklists;
        }

        private SignOffSpec signOff(Object node, String path) {
            if (node == null) return null;
            Map<String, Object> raw = object(node, path);
            if (raw == null) return null;
            allowKeys(raw, path, SIGN_OFF_KEYS);
            String label = string(raw, "label", path, true);
            String role = raw.containsKey("role") ? string(raw, "role", path, true) : Role.SUPERADMIN.name();
            if (role != null && !Role.SUPERADMIN.name().equals(role)) problem(path + ".role", "must be SUPERADMIN (D20)");
            String banner = string(raw, "banner", path, false);
            return label == null ? null : new SignOffSpec(label, Role.SUPERADMIN, banner);
        }

        // ---- gates ----------------------------------------------------------------------------------------------------

        private List<GateSpec> gates(Map<String, Object> raw, String path, StepContext step) {
            List<GateSpec> gates = new ArrayList<>();
            List<Object> declared = list(raw, "gates", path, false);
            if (declared == null) return gates;
            for (int index = 0; index < declared.size(); index++) {
                GateSpec gate = gate(declared.get(index), path + ".gates[" + index + "]", step);
                if (gate != null) gates.add(gate);
            }
            return gates;
        }

        private GateSpec gate(Object node, String path, StepContext step) {
            Map<String, Object> raw = object(node, path);
            if (raw == null) return null;
            GateType type = enumValue(GateType.class, raw.get("type"), path + ".type", true);
            if (type == null) return null;
            int before = problems.size();
            Set<String> allowed = new HashSet<>(GATE_KEYS.get(type));
            allowed.add("type");
            allowKeys(raw, path, allowed);
            GateSpec gate = switch (type) {
                case FIELDS_PRESENT -> {
                    List<String> fields = strings(raw, "fields", path, true);
                    if (fields != null && fields.isEmpty()) problem(path + ".fields", "must name at least one field");
                    if (fields != null) fields.forEach(key -> step.requireField(this, path + ".fields", key, false, null));
                    yield GateSpec.fieldsPresent(fields);
                }
                case ALL_CHECKED -> {
                    String key = string(raw, "checklist", path, true);
                    ChecklistSpec checklist = key == null ? null : step.checklists.get(key);
                    if (key != null && checklist == null) problem(path + ".checklist", "names undeclared checklist " + key);
                    List<Integer> except = indexes(raw, "except", path);
                    if (checklist != null && except != null) {
                        for (Integer item : except) {
                            if (item < 0 || item >= checklist.size()) problem(path + ".except", "item " + item + " is not one of the checklist's items");
                        }
                        if (new HashSet<>(except).size() >= checklist.size()) problem(path + ".except", "leaves no item to tick");
                    }
                    yield GateSpec.allChecked(key, except);
                }
                case CHOICE_IN -> {
                    String key = string(raw, "field", path, true);
                    FieldSpec field = key == null ? null : step.requireField(this, path + ".field", key, false, FieldKind.CHOICE);
                    List<String> values = strings(raw, "values", path, true);
                    if (values != null && values.isEmpty()) problem(path + ".values", "must list at least one value");
                    if (field != null && values != null) {
                        for (String value : values) {
                            if (!field.choiceValues().contains(value)) problem(path + ".values", value + " is not a choice of " + key);
                        }
                    }
                    yield GateSpec.choiceIn(key, values);
                }
                case VENDORS_SELECTED -> {
                    List<String> categories = strings(raw, "categories", path, false);
                    if (categories != null) categoryCodes(categories, path + ".categories", false);
                    yield GateSpec.vendorsSelected(categories);
                }
                case COUNTS_CONSISTENT -> {
                    List<String> counts = raw.containsKey("fields") ? strings(raw, "fields", path, true) : Gates.DEFAULT_COUNT_FIELDS;
                    if (counts != null && counts.size() != 3) {
                        problem(path + ".fields", "must name the produced, accepted and dispatched count fields, in that order");
                    } else if (counts != null) {
                        counts.forEach(key -> step.requireField(this, path + ".fields", key, false, FieldKind.WHOLE));
                    }
                    String note = raw.containsKey("field") ? string(raw, "field", path, true) : Gates.DEFAULT_DISCREPANCY_FIELD;
                    FieldSpec noteField = note == null ? null : step.requireField(this, path + ".field", note, false, null);
                    if (noteField != null && noteField.kind() != FieldKind.TEXT && noteField.kind() != FieldKind.WORDS) {
                        problem(path + ".field", note + " must be a TEXT or WORDS field");
                    }
                    yield GateSpec.countsConsistent(counts, note);
                }
                case SIGNED_OFF -> {
                    if (step.signOff == null) problem(path, "a SIGNED_OFF gate needs the stage to declare a signOff");
                    yield GateSpec.of(type);
                }
                case DESIGN_FILES_PRESENT, HEAD_APPROVED -> GateSpec.of(type);
            };
            return problems.size() > before ? null : gate;
        }

        private List<Integer> indexes(Map<String, Object> raw, String key, String path) {
            if (!raw.containsKey(key) || raw.get(key) == null) return null;
            if (!(raw.get(key) instanceof List<?> values)) {
                problem(path + "." + key, "must be a list of zero-based item numbers");
                return null;
            }
            List<Integer> indexes = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Integer index) indexes.add(index);
                else problem(path + "." + key, "must be a list of zero-based item numbers");
            }
            return indexes;
        }

        // ---- blocks ---------------------------------------------------------------------------------------------------

        private List<BlockSpec> blocks(Map<String, Object> raw, String path, StepContext step) {
            List<BlockSpec> blocks = new ArrayList<>();
            List<Object> declared = list(raw, "blocks", path, false);
            if (declared == null) return blocks;
            for (int index = 0; index < declared.size(); index++) {
                BlockSpec block = block(declared.get(index), path + ".blocks[" + index + "]", step);
                if (block != null) blocks.add(block);
            }
            return blocks;
        }

        private BlockSpec block(Object node, String path, StepContext step) {
            Map<String, Object> raw = object(node, path);
            if (raw == null) return null;
            BlockType type = enumValue(BlockType.class, raw.get("type"), path + ".type", true);
            if (type == null) return null;
            int before = problems.size();
            Set<String> allowed = new HashSet<>(BLOCK_KEYS.get(type));
            allowed.add("type");
            allowed.add("title");
            allowKeys(raw, path, allowed);
            String title = string(raw, "title", path, false);
            String save = string(raw, "save", path, false);

            List<String> fields = strings(raw, "fields", path, type == BlockType.FIELDS);
            if (fields != null) {
                if (fields.isEmpty()) problem(path + ".fields", "must name at least one field");
                boolean editable = type != BlockType.LEGACY_DETAILS;
                boolean intro = type == BlockType.INTRO_MESSAGE;
                if (intro && fields.size() > 1) problem(path + ".fields", "an introduction card keeps one field: the message as sent");
                fields.forEach(key -> step.requireField(this, path + ".fields", key, !editable, intro ? FieldKind.TEXT : null));
            }

            String checklist = string(raw, "checklist", path, type == BlockType.CHECKLIST);
            if (checklist != null && !step.checklists.containsKey(checklist)) {
                problem(path + ".checklist", "names undeclared checklist " + checklist);
            }

            List<String> categories = strings(raw, "categories", path, false);
            if (categories != null) categoryCodes(categories, path + ".categories", true);

            Map<String, List<String>> suggested = null;
            if (raw.get("suggestedByItem") != null) {
                if (categories != null) problem(path, "a vendor selection takes either categories or suggestedByItem, not both");
                suggested = suggestedByItem(raw.get("suggestedByItem"), path + ".suggestedByItem");
            }

            if (type == BlockType.SIGN_OFF && step.signOff == null) problem(path, "a SIGN_OFF card needs the stage to declare a signOff");
            return problems.size() > before ? null : new BlockSpec(type, title, fields, checklist, save, categories, suggested);
        }

        private Map<String, List<String>> suggestedByItem(Object node, String path) {
            Map<String, Object> raw = object(node, path);
            if (raw == null) return null;
            Map<String, List<String>> suggested = new LinkedHashMap<>();
            raw.forEach((item, value) -> {
                if (comment(item)) return;
                if (!ITEM_CODE.matcher(item).matches()) problem(path + "." + item, "item codes must match " + ITEM_CODE.pattern());
                List<String> codes = stringList(value, path + "." + item);
                if (codes != null) {
                    categoryCodes(codes, path + "." + item, true);
                    suggested.put(item, codes);
                }
            });
            return suggested;
        }

        private void categoryCodes(List<String> codes, String path, boolean allowEmpty) {
            if (codes.isEmpty() && !allowEmpty) problem(path, "must name at least one vendor category, or be left out for any");
            if (new HashSet<>(codes).size() != codes.size()) problem(path, "repeats a vendor category");
            for (String code : codes) {
                if (!CATEGORY.matcher(code).matches()) problem(path, code + " is not a vendor category code (" + CATEGORY.pattern() + ")");
            }
        }

        // ---- JSON helpers ---------------------------------------------------------------------------------------------

        void problem(String path, String message) {
            problems.add(source + ": " + path + ": " + message);
        }

        private static boolean comment(String key) {
            return key.startsWith("$") || key.startsWith("_");
        }

        private void allowKeys(Map<String, Object> raw, String path, Set<String> allowed) {
            for (String key : raw.keySet()) {
                if (!allowed.contains(key) && !comment(key)) problem(path + "." + key, "is not a known key here");
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> object(Object node, String path) {
            if (node instanceof Map<?, ?> map) return (Map<String, Object>) map;
            problem(path, "must be an object");
            return null;
        }

        private Map<String, Object> optionalObject(Map<String, Object> raw, String key, String path) {
            Object node = raw.get(key);
            return node == null ? null : object(node, path + "." + key);
        }

        private String string(Map<String, Object> raw, String key, String path, boolean required) {
            Object value = raw.get(key);
            if (value == null) {
                if (required) problem(path + "." + key, "is required");
                return null;
            }
            if (!(value instanceof String text) || text.isBlank()) {
                problem(path + "." + key, "must be non-blank text");
                return null;
            }
            return text;
        }

        private Long whole(Map<String, Object> raw, String key, String path, boolean required) {
            Object value = raw.get(key);
            if (value == null) {
                if (required) problem(path + "." + key, "is required");
                return null;
            }
            if (value instanceof Integer || value instanceof Long) return ((Number) value).longValue();
            problem(path + "." + key, "must be a whole number");
            return null;
        }

        private boolean bool(Map<String, Object> raw, String key, String path, boolean fallback) {
            Object value = raw.get(key);
            if (value == null) return fallback;
            if (value instanceof Boolean flag) return flag;
            problem(path + "." + key, "must be true or false");
            return fallback;
        }

        @SuppressWarnings("unchecked")
        private List<Object> list(Map<String, Object> raw, String key, String path, boolean required) {
            Object value = raw.get(key);
            if (value == null) {
                if (required) problem(path + "." + key, "is required");
                return null;
            }
            if (value instanceof List<?> items) return (List<Object>) items;
            problem(path + "." + key, "must be a list");
            return null;
        }

        private List<String> strings(Map<String, Object> raw, String key, String path, boolean required) {
            Object value = raw.get(key);
            if (value == null) {
                if (required) problem(path + "." + key, "is required");
                return null;
            }
            return stringList(value, path + "." + key);
        }

        private List<String> stringList(Object value, String path) {
            if (!(value instanceof List<?> items)) {
                problem(path, "must be a list of text");
                return null;
            }
            List<String> strings = new ArrayList<>();
            for (Object item : items) {
                if (item instanceof String text && !text.isBlank()) strings.add(text);
                else problem(path, "must be a list of non-blank text");
            }
            return strings;
        }

        private <E extends Enum<E>> E enumValue(Class<E> type, Object value, String path, boolean required) {
            if (value == null) {
                if (required) problem(path, "is required");
                return null;
            }
            if (value instanceof String text) {
                for (E constant : type.getEnumConstants()) if (constant.name().equals(text)) return constant;
            }
            Set<String> names = new LinkedHashSet<>();
            for (E constant : type.getEnumConstants()) names.add(constant.name());
            problem(path, "must be one of " + names);
            return null;
        }
    }

    /** What a stage declares, for checking the references of its gates and cards. */
    private record StepContext(Map<String, FieldSpec> fields, Map<String, ChecklistSpec> checklists, SignOffSpec signOff) {

        /**
         * Reports a reference to an undeclared field, a field of the wrong kind, or (unless {@code legacyAllowed}) a legacy
         * field, which is read-only and can neither be required nor edited.
         */
        FieldSpec requireField(Parse parse, String path, String key, boolean legacyAllowed, FieldKind kind) {
            FieldSpec field = fields.get(key);
            if (field == null) {
                parse.problem(path, "names undeclared field " + key);
                return null;
            }
            if (field.legacy() && !legacyAllowed) {
                parse.problem(path, key + " is a legacy field; it is read-only and cannot be required or edited");
                return null;
            }
            if (kind != null && field.kind() != kind) {
                parse.problem(path, key + " must be a " + kind + " field");
                return null;
            }
            return field;
        }
    }
}
