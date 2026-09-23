package com.custoking.ims.schoolcoreservice.catalog.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductFormRuleEngineTest {
    private final ProductFormRuleEngine engine = new ProductFormRuleEngine();

    @Test
    void sharedFixturesMatchBackendIncludingDraftFeedbackAndPlacement() throws Exception {
        Path path = Path.of("contracts/catalog-form-rule-fixtures.json");
        if (!Files.exists(path)) path = Path.of("../../contracts/catalog-form-rule-fixtures.json");
        for (var fixture : maps(new ObjectMapper().readValue(Files.readString(path), List.class))) {
            var input = map(fixture.get("input"));
            var expected = map(fixture.get("expected"));
            var definition = definition(maps(fixture.get("rules")));
            String name = String.valueOf(fixture.get("name"));
            if (expected.containsKey("error")) {
                var exception = assertThrows(ProductFormValidationException.class,
                        () -> engine.normalise(definition, map(input.get("orderSelections")), maps(input.get("lines"))), name);
                assertTrue(exception.fieldErrors().keySet().stream().anyMatch(key -> key.endsWith(String.valueOf(expected.get("error")))));
                continue;
            }
            var actual = engine.normalise(definition, map(input.get("orderSelections")), maps(input.get("lines")));
            assertEquals(expected.get("pageCounts"), actual.lines().stream().map(line -> line.get("pageCount")).toList(), name);
            assertEquals(expected.get("valid"), actual.violations().isEmpty(), name);
            if (expected.containsKey("actualTotal")) {
                assertEquals(((Number) expected.get("actualTotal")).longValue(), ((Number) actual.aggregateResults().getFirst().get("actualTotal")).longValue(), name);
                assertEquals(expected.get("requiredTotal"), actual.aggregateResults().getFirst().get("requiredTotal"), name);
            } else assertTrue(actual.aggregateResults().isEmpty(), name);
            if (Boolean.FALSE.equals(expected.get("valid"))) assertThrows(ProductFormValidationException.class,
                    () -> engine.requireStage(definition, actual, "ON_PLACE", Set.of()), name);
            else assertDoesNotThrow(() -> engine.requireStage(definition, actual, "ON_PLACE", Set.of()), name);
        }
    }

    @Test
    void lineCannotOverrideOrderCustomizationAndPendingSizesAreRejected() {
        var definition = definition(List.of());
        var overridden = row("selections", Map.of("SIZE", "LONG", "RULING", "PLAIN", "CUSTOMIZATION", "NON_CUSTOMIZED"), "bookCount", 1, "pageCount", 7);
        assertThrows(ProductFormValidationException.class, () -> engine.normalise(definition, Map.of("CUSTOMIZATION", "CUSTOMIZED"), List.of(overridden)));
        var pending = row("selections", Map.of("SIZE", "KING", "RULING", "PLAIN"), "bookCount", 1, "pageCount", 7);
        var exception = assertThrows(ProductFormValidationException.class, () -> engine.normalise(definition, Map.of("CUSTOMIZATION", "CUSTOMIZED"), List.of(pending)));
        assertTrue(exception.getMessage().contains("Specification pending"));
    }

    @Test
    void snapshotsPreserveLabelsRequestedCountsAndAppliedRules() {
        var rule = row("id", 9, "ruleType", "ROUND_TO_MULTIPLE", "targetField", "PAGE_COUNT", "params", Map.of("multiple", 7, "mode", "NEAREST", "minimum", 7));
        var result = engine.normalise(definition(List.of(rule)), Map.of("CUSTOMIZATION", "CUSTOMIZED"),
                List.of(row("selections", Map.of("SIZE", "LONG", "RULING", "PLAIN"), "bookCount", 1000, "pageCount", 198)));
        var line = result.lines().getFirst();
        assertEquals(198, line.get("requestedPageCount"));
        assertEquals(196, line.get("pageCount"));
        assertEquals("Long", map(map(line.get("optionSelections")).get("SIZE")).get("label"));
        assertEquals("17 cm x 27 cm", map(map(line.get("optionSelections")).get("SIZE")).get("spec"));
        assertEquals(198, maps(line.get("appliedRules")).getFirst().get("from"));
        assertEquals(196, maps(line.get("appliedRules")).getFirst().get("to"));
    }

    @Test
    void assetsApplyOnlyAtTheirStageAndForMatchingCustomization() {
        var assetRule = row("id", 10, "ruleType", "REQUIRE_ASSET", "matchOptions", Map.of("CUSTOMIZATION", "CUSTOMIZED"),
                "params", Map.of("assetKind", "DESIGN", "stage", "ON_PLACE"));
        var definition = definition(List.of(assetRule));
        var lines = List.of(row("selections", Map.of("SIZE", "LONG", "RULING", "PLAIN"), "bookCount", 1000, "pageCount", 196));
        var customized = engine.normalise(definition, Map.of("CUSTOMIZATION", "CUSTOMIZED"), lines);
        assertThrows(ProductFormValidationException.class, () -> engine.requireStage(definition, customized, "ON_PLACE", Set.of()));
        assertDoesNotThrow(() -> engine.requireStage(definition, customized, "ON_PLACE", Set.of("DESIGN")));
        assertDoesNotThrow(() -> engine.requireStage(definition, customized, "BEFORE_DELIVERY", Set.of()));
        var plain = engine.normalise(definition, Map.of("CUSTOMIZATION", "NON_CUSTOMIZED"), lines);
        assertDoesNotThrow(() -> engine.requireStage(definition, plain, "ON_PLACE", Set.of()));
    }

    @Test
    void malformedRuleParamsAndUnknownMatchGroupsAreRejected() {
        var groups = maps(definition(List.of()).get("groups"));
        var rule = row("ruleType", "ROUND_TO_MULTIPLE", "targetField", "PAGE_COUNT", "params", Map.of("multiple", 0, "mode", "NEAREST", "minimum", 7));
        assertThrows(ProductFormValidationException.class, () -> validateRule(rule, groups));
        rule.put("params", Map.of("multiple", 7, "mode", "EXEC", "minimum", 7));
        assertThrows(ProductFormValidationException.class, () -> validateRule(rule, groups));
        rule.put("params", Map.of("multiple", 7, "mode", "NEAREST", "minimum", 7));
        rule.put("matchOptions", Map.of("UNKNOWN", "LONG"));
        assertThrows(ProductFormValidationException.class, () -> validateRule(rule, groups));
        rule.put("matchOptions", Map.of("CUSTOMIZATION", "CUSTOMIZED"));
        assertDoesNotThrow(() -> validateRule(rule, groups));
    }

    @Test
    void customizedLinesAreFlooredPerRulingLineAndTheAdjustmentIsRecorded() {
        // The order form floors each customised ruling line at 1000 rather than requiring an exact
        // order total, and raises a low count instead of rejecting it.
        var rule = row("id", 90, "ruleType", "FLOOR_VALUE", "targetField", "BOOK_COUNT",
                "matchOptions", row("CUSTOMIZATION", "CUSTOMIZED"),
                "params", row("value", 1000), "message", "Customised lines floor at 1000 books");
        var definition = definition(List.of(rule));

        var result = engine.normalise(definition, row("CUSTOMIZATION", "CUSTOMIZED"), List.of(
                row("selections", row("SIZE", "LONG", "RULING", "SINGLE_RULE"), "bookCount", 400, "pageCount", 196),
                row("selections", row("SIZE", "LONG", "RULING", "PLAIN"), "bookCount", 1500, "pageCount", 196)));

        assertEquals(1000, ((Number) result.lines().get(0).get("bookCount")).intValue());
        assertEquals(400, ((Number) result.lines().get(0).get("requestedBookCount")).intValue());
        assertEquals(1500, ((Number) result.lines().get(1).get("bookCount")).intValue());
        assertTrue(result.violations().isEmpty(), "a per-line floor must not block placement");
        var applied = maps(result.lines().get(0).get("appliedRules"));
        assertTrue(applied.stream().anyMatch(a -> "FLOOR_VALUE".equals(a.get("type"))
                && ((Number) a.get("from")).intValue() == 400 && ((Number) a.get("to")).intValue() == 1000),
                "the floor adjustment must be auditable like rounding is");
    }

    @Test
    void nonCustomizedLinesAreNotFloored() {
        var rule = row("id", 90, "ruleType", "FLOOR_VALUE", "targetField", "BOOK_COUNT",
                "matchOptions", row("CUSTOMIZATION", "CUSTOMIZED"),
                "params", row("value", 1000), "message", "Customised lines floor at 1000 books");
        var definition = definition(List.of(rule));

        var result = engine.normalise(definition, row("CUSTOMIZATION", "NON_CUSTOMIZED"), List.of(
                row("selections", row("SIZE", "LONG", "RULING", "SINGLE_RULE"), "bookCount", 25, "pageCount", 196)));

        assertEquals(25, ((Number) result.lines().get(0).get("bookCount")).intValue());
    }

    static Map<String, Object> definition(List<Map<String, Object>> rules) {
        var sizes = new ArrayList<Map<String, Object>>();
        sizes.add(row("id", 3, "code", "LONG", "label", "Long", "specText", "17 cm x 27 cm", "widthMm", 170, "heightMm", 270, "specStatus", "CONFIRMED"));
        sizes.add(row("id", 4, "code", "JUMBO_LONG", "label", "Jumbo Long", "specText", "18 cm x 24 cm", "specStatus", "CONFIRMED"));
        sizes.add(row("id", 5, "code", "KING", "label", "King", "specStatus", "PENDING_SPEC"));
        return row("groups", List.of(
                row("code", "CUSTOMIZATION", "label", "Customization", "scope", "ORDER", "required", true, "options", List.of(
                        row("id", 1, "code", "CUSTOMIZED", "label", "Customized", "specStatus", "CONFIRMED"),
                        row("id", 2, "code", "NON_CUSTOMIZED", "label", "Non-customized", "specStatus", "CONFIRMED"))),
                row("code", "SIZE", "label", "Size", "scope", "LINE", "required", true, "options", sizes),
                row("code", "RULING", "label", "Ruling", "scope", "LINE", "required", true, "options", List.of(
                        row("id", 6, "code", "SINGLE_RULE", "label", "Single rule", "specStatus", "CONFIRMED"),
                        row("id", 7, "code", "FOUR_RULE", "label", "Four rule", "specStatus", "CONFIRMED"),
                        row("id", 8, "code", "PLAIN", "label", "Plain", "specStatus", "CONFIRMED")))), "rules", rules, "dependencies", List.of());
    }
}
