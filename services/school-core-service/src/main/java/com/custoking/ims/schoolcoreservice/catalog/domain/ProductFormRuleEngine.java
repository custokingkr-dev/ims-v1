package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProductFormRuleEngine {
    public record NormalisationResult(Map<String, Object> orderSelections, List<Map<String, Object>> lines,
                                      List<Map<String, Object>> aggregateResults, Map<String, String> violations) {}

    public NormalisationResult normalise(Map<String, Object> definition, Map<String, Object> orderSelections,
                                         List<Map<String, Object>> rawLines) {
        var groups = maps(definition.get("groups"));
        var order = selections(groups, orderSelections, "ORDER", "orderSelections");
        if (rawLines == null || rawLines.isEmpty() || rawLines.size() > 500) {
            throw error("lines", "Include between 1 and 500 notebook lines");
        }
        var rules = rules(definition);
        var normalized = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < rawLines.size(); index++) {
            var raw = rawLines.get(index);
            String path = "lines[" + index + "]";
            var selected = selections(groups, map(raw.get("selections")), "LINE", path + ".selections");
            var combined = new LinkedHashMap<>(order);
            combined.putAll(selected);
            checkDependencies(definition, groups, combined, path);
            int books = positiveInt(raw.get("bookCount"), path + ".bookCount");
            int pages = positiveInt(raw.get("pageCount"), path + ".pageCount");
            var line = new LinkedHashMap<String, Object>();
            line.put("lineNo", index + 1);
            line.put("optionSelections", selected);
            line.put("requestedBookCount", books);
            line.put("bookCount", books);
            line.put("requestedPageCount", pages);
            line.put("pageCount", pages);
            var applied = new ArrayList<Map<String, Object>>();
            // Normalize before bounds and order totals, regardless of cross-phase priorities.
            for (var rule : rules) {
                if (!"ROUND_TO_MULTIPLE".equals(rule.get("ruleType")) || !matches(rule, combined)) continue;
                String field = "BOOK_COUNT".equals(rule.get("targetField")) ? "bookCount" : "pageCount";
                int from = ((Number) line.get(field)).intValue();
                var params = map(rule.get("params"));
                int to = round(from, positiveInt(params.get("multiple"), "params.multiple"),
                        String.valueOf(params.get("mode")), positiveInt(params.getOrDefault("minimum", 1), "params.minimum"));
                line.put(field, to);
                if (from != to) applied.add(row("ruleId", rule.get("id"), "type", rule.get("ruleType"),
                        "field", rule.get("targetField"), "from", from, "to", to));
            }
            for (var rule : rules) {
                String type = String.valueOf(rule.get("ruleType"));
                if (!("MIN_VALUE".equals(type) || "MAX_VALUE".equals(type)) || !matches(rule, combined)) continue;
                String field = "BOOK_COUNT".equals(rule.get("targetField")) ? "bookCount" : "pageCount";
                int actual = ((Number) line.get(field)).intValue();
                int bound = positiveInt(map(rule.get("params")).get("value"), "params.value");
                if (("MIN_VALUE".equals(type) && actual < bound) || ("MAX_VALUE".equals(type) && actual > bound)) {
                    throw error(path + "." + field, message(rule, "Count does not meet the configured limit"));
                }
            }
            line.put("appliedRules", applied);
            normalized.add(line);
        }
        var aggregates = new ArrayList<Map<String, Object>>();
        var violations = new LinkedHashMap<String, String>();
        for (var rule : rules) {
            if (!"REQUIRE_QUANTITY_TOTAL".equals(rule.get("ruleType"))) continue;
            var participating = new ArrayList<Integer>();
            long actual = 0;
            long requested = 0;
            for (var line : normalized) {
                var combined = new LinkedHashMap<>(order);
                combined.putAll(map(line.get("optionSelections")));
                if (!matches(rule, combined)) continue;
                actual += ((Number) line.get("bookCount")).longValue();
                requested += ((Number) line.get("requestedBookCount")).longValue();
                participating.add(((Number) line.get("lineNo")).intValue());
            }
            if (participating.isEmpty()) continue;
            int target = positiveInt(map(rule.get("params")).get("value"), "params.value");
            boolean valid = actual == target;
            aggregates.add(row("ruleId", rule.get("id"), "type", "REQUIRE_QUANTITY_TOTAL", "scope", "ORDER",
                    "comparison", "EQ", "requiredTotal", target, "requestedTotal", requested, "actualTotal", actual,
                    "difference", target - actual, "lineNos", participating, "valid", valid,
                    "message", message(rule, "All sizes combined must total exactly " + target + " books")));
            if (!valid) violations.put("quantityTotal", "All sizes combined must total exactly " + target + " books; current total is " + actual);
        }
        return new NormalisationResult(order, normalized, aggregates, violations);
    }

    public void requireStage(Map<String, Object> definition, NormalisationResult result,
                             String stage, Set<String> assetKinds) {
        if (!Set.of("ON_PLACE", "BEFORE_DELIVERY").contains(stage)) throw error("stage", "Unsupported order stage");
        if ("ON_PLACE".equals(stage) && !result.violations().isEmpty()) {
            throw new ProductFormValidationException(result.violations());
        }
        for (var rule : rules(definition)) {
            var params = map(rule.get("params"));
            if (!"REQUIRE_ASSET".equals(rule.get("ruleType")) || !stage.equals(params.get("stage"))) continue;
            boolean applies = result.lines().stream().anyMatch(line -> {
                var combined = new LinkedHashMap<>(result.orderSelections());
                combined.putAll(map(line.get("optionSelections")));
                return matches(rule, combined);
            });
            String kind = String.valueOf(params.get("assetKind"));
            if (applies && !assetKinds.contains(kind)) throw error("assets." + kind, message(rule, "Upload " + kind + " before proceeding"));
        }
    }

    public static int round(int value, int multiple, String mode, int minimum) {
        if (value <= 0 || multiple <= 0 || minimum <= 0) throw error("pageCount", "Counts and rounding settings must be positive integers");
        long remainder = value % multiple;
        long down = value - remainder;
        long rounded = switch (mode) {
            case "NEAREST" -> remainder <= multiple / 2 ? down : down + multiple;
            case "UP" -> remainder == 0 ? value : down + multiple;
            case "DOWN" -> down;
            default -> throw error("params.mode", "Select NEAREST, UP or DOWN");
        };
        rounded = Math.max(rounded, minimum);
        if (rounded > Integer.MAX_VALUE) throw error("pageCount", "Rounded count is too large");
        return (int) rounded;
    }

    public static void validateRule(Map<String, Object> rule, List<Map<String, Object>> groups) {
        String type = String.valueOf(rule.get("ruleType"));
        var params = map(rule.get("params"));
        String target = String.valueOf(rule.get("targetField"));
        if (!Set.of("REQUIRE_QUANTITY_TOTAL", "ROUND_TO_MULTIPLE", "MIN_VALUE", "MAX_VALUE", "REQUIRE_ASSET").contains(type)) {
            throw error("ruleType", "Unsupported rule type");
        }
        if (!"REQUIRE_ASSET".equals(type) && !Set.of("BOOK_COUNT", "PAGE_COUNT").contains(target)) throw error("targetField", "Select books or printed pages");
        if ("REQUIRE_ASSET".equals(type) && rule.get("targetField") != null && !target.isBlank()) throw error("targetField", "Asset rules have no numeric target");
        switch (type) {
            case "REQUIRE_QUANTITY_TOTAL" -> {
                positiveInt(params.get("value"), "params.value");
                if (!"BOOK_COUNT".equals(target) || !"EQ".equals(params.get("comparison"))
                        || !"ORDER".equals(params.get("scope")) || !"ON_PLACE".equals(params.get("stage"))) {
                    throw error("params", "Quantity rules require BOOK_COUNT, EQ, ORDER and ON_PLACE");
                }
                exactKeys(params, Set.of("value", "comparison", "scope", "stage"));
            }
            case "ROUND_TO_MULTIPLE" -> {
                int multiple = positiveInt(params.get("multiple"), "params.multiple");
                int minimum = positiveInt(params.get("minimum"), "params.minimum");
                round(1, multiple, String.valueOf(params.get("mode")), minimum);
                if (minimum % multiple != 0) throw error("params.minimum", "Minimum must be a multiple of the rounding step");
                exactKeys(params, Set.of("multiple", "mode", "minimum"));
            }
            case "MIN_VALUE", "MAX_VALUE" -> {
                positiveInt(params.get("value"), "params.value");
                exactKeys(params, Set.of("value"));
            }
            case "REQUIRE_ASSET" -> {
                if (!Set.of("DESIGN", "PRE_DELIVERY_PHOTO").contains(String.valueOf(params.get("assetKind")))
                        || !Set.of("ON_PLACE", "BEFORE_DELIVERY").contains(String.valueOf(params.get("stage")))) {
                    throw error("params", "Choose a supported asset kind and stage");
                }
                exactKeys(params, Set.of("assetKind", "stage"));
            }
        }
        for (var entry : map(rule.get("matchOptions")).entrySet()) {
            var group = groups.stream().filter(g -> entry.getKey().equals(g.get("code"))).findFirst()
                    .orElseThrow(() -> error("matchOptions", "Unknown group: " + entry.getKey()));
            if (!(entry.getValue() instanceof String) || maps(group.get("options")).stream().noneMatch(o -> entry.getValue().equals(o.get("code")))) {
                throw error("matchOptions", "Unknown option for " + entry.getKey());
            }
            if ("REQUIRE_QUANTITY_TOTAL".equals(type) && !"ORDER".equals(group.get("scope"))) {
                throw error("matchOptions", "Order total rules may match order-level options only");
            }
        }
    }

    private static void exactKeys(Map<String, Object> params, Set<String> keys) {
        if (!keys.equals(params.keySet())) throw error("params", "Unexpected or missing rule parameters");
    }

    private Map<String, Object> selections(List<Map<String, Object>> groups, Map<String, Object> raw, String scope, String path) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : raw.keySet()) {
            if (groups.stream().noneMatch(g -> key.equals(g.get("code")) && scope.equals(g.get("scope")) && active(g))) {
                throw error(path + "." + key, "Unknown selection or incorrect option scope");
            }
        }
        for (var group : groups) {
            if (!active(group) || !scope.equals(group.get("scope"))) continue;
            String code = String.valueOf(group.get("code"));
            Object input = raw.get(code);
            if (input == null || String.valueOf(input).isBlank()) {
                if (Boolean.TRUE.equals(group.get("required"))) throw error(path + "." + code, "Select " + group.get("label"));
                continue;
            }
            if (!(input instanceof String)) throw error(path + "." + code, "Selection must be an option code");
            var option = maps(group.get("options")).stream().filter(o -> input.equals(o.get("code")) && active(o)).findFirst()
                    .orElseThrow(() -> error(path + "." + code, "Select an active option"));
            if (!"CONFIRMED".equals(option.get("specStatus"))) throw error(path + "." + code, "Specification pending from Custoking");
            result.put(code, row("code", option.get("code"), "label", option.get("label"), "spec", option.get("specText"),
                    "widthMm", option.get("widthMm"), "heightMm", option.get("heightMm")));
        }
        return result;
    }

    private void checkDependencies(Map<String, Object> definition, List<Map<String, Object>> groups, Map<String, Object> selections, String path) {
        var ids = new java.util.HashSet<Long>();
        for (var group : groups) {
            String selected = selectedCode(selections.get(String.valueOf(group.get("code"))));
            maps(group.get("options")).stream().filter(o -> selected.equals(o.get("code"))).forEach(o -> {
                if (o.get("id") instanceof Number n) ids.add(n.longValue());
            });
        }
        for (var dependency : maps(definition.get("dependencies"))) {
            if (Boolean.FALSE.equals(dependency.get("allowed")) && ids.contains(((Number) dependency.get("parentOptionId")).longValue())
                    && ids.contains(((Number) dependency.get("childOptionId")).longValue())) throw error(path + ".selections", "This option combination is unavailable");
        }
    }

    private static List<Map<String, Object>> rules(Map<String, Object> definition) {
        return maps(definition.get("rules")).stream().filter(ProductFormRuleEngine::active)
                .sorted(Comparator.<Map<String, Object>>comparingInt(r -> ((Number) r.getOrDefault("priority", 0)).intValue())
                        .thenComparingLong(r -> ((Number) r.getOrDefault("id", 0)).longValue())).toList();
    }

    private static boolean matches(Map<String, Object> rule, Map<String, Object> selections) {
        return map(rule.get("matchOptions")).entrySet().stream().allMatch(e -> String.valueOf(e.getValue()).equals(selectedCode(selections.get(e.getKey()))));
    }

    private static String selectedCode(Object selected) { return selected instanceof Map<?, ?> m ? String.valueOf(m.get("code")) : String.valueOf(selected); }
    private static boolean active(Map<String, Object> value) { return !Boolean.FALSE.equals(value.get("active")); }
    private static String message(Map<String, Object> rule, String fallback) { return rule.get("message") instanceof String s && !s.isBlank() ? s : fallback; }
    public static ProductFormValidationException error(String field, String message) { return new ProductFormValidationException(Map.of(field, message)); }
    public static int positiveInt(Object value, String field) {
        try {
            if (!(value instanceof Number)) throw new NumberFormatException();
            int result = new BigDecimal(value.toString()).intValueExact();
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (ArithmeticException | NumberFormatException exception) { throw error(field, "Enter a positive whole number up to 2147483647"); }
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) throw error("definition", "Expected an object");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof Map<?, ?>))) throw error("definition", "Expected an object list");
        return (List<Map<String, Object>>) value;
    }
    public static Map<String, Object> row(Object... values) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
