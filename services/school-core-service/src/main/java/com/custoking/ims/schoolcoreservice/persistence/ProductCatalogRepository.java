package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine.*;

@Repository
public class ProductCatalogRepository {
    private record Entity(String table, String key, Map<String, String> columns) {}
    private static final Map<String, Entity> ENTITIES = Map.of(
            "categories", new Entity("product_categories", "code", columns("code", "label", "emoji", "description", "orderType", "formEnabled", "sortOrder", "active")),
            "groups", new Entity("product_option_groups", "id", columns("categoryCode", "code", "label", "level", "selectionType", "inputType", "unit", "required", "scope", "active")),
            "options", new Entity("product_options", "id", columns("groupId", "code", "label", "specText", "widthMm", "heightMm", "specStatus", "sortOrder", "active")),
            "rules", new Entity("product_form_rules", "id", columns("categoryCode", "ruleType", "targetField", "matchOptions", "params", "priority", "message", "active")));
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final OutboxWriter outbox;
    private final boolean enabled;

    public ProductCatalogRepository(JdbcClient jdbc, ObjectMapper mapper, OutboxWriter outbox,
                                    @Value("${catalog.product-form.enabled:${CATALOG_PRODUCT_FORM_ENABLED:false}}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Whether a category takes the structured order form. Read from the category rather than a
     * literal so that enabling a category is the only step needed to route it away from the legacy
     * order path, which has no form validation.
     */
    public boolean formEnabled(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) return false;
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT form_enabled FROM catalog.product_categories
                        WHERE code = :code AND active
                        """)
                .param("code", categoryCode.trim().toUpperCase(Locale.ROOT))
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }

    public List<Map<String, Object>> categories(boolean includeInactive) {
        return readList("categories", includeInactive ? "" : " WHERE active", Map.of(), " ORDER BY sort_order, code");
    }

    @Transactional
    public Map<String, Object> form(String categoryCode, boolean includeInactive) {
        lockCategory(categoryCode, false);
        var category = get("categories", categoryCode);
        if (!includeInactive && !Boolean.TRUE.equals(category.get("active"))) throw missing();
        var groups = readList("groups", " WHERE category_code = :category" + (includeInactive ? "" : " AND active"),
                Map.of("category", categoryCode), " ORDER BY level, id");
        for (var group : groups) {
            group.put("options", readList("options", " WHERE group_id = :groupId" + (includeInactive ? "" : " AND active"),
                    Map.of("groupId", group.get("id")), " ORDER BY sort_order, id"));
        }
        var dependencies = jdbc.sql("""
                SELECT d.id, d.parent_option_id AS "parentOptionId", d.child_option_id AS "childOptionId", d.allowed
                FROM catalog.product_option_dependencies d
                JOIN catalog.product_options p ON p.id = d.parent_option_id
                JOIN catalog.product_option_groups g ON g.id = p.group_id
                WHERE g.category_code = :category ORDER BY d.id
                """).param("category", categoryCode).query().listOfRows();
        var rules = readList("rules", " WHERE category_code = :category" + (includeInactive ? "" : " AND active"),
                Map.of("category", categoryCode), " ORDER BY priority, id");
        return row("enabled", enabled && Boolean.TRUE.equals(category.get("formEnabled")), "category", category,
                "groups", groups, "dependencies", dependencies, "rules", rules);
    }

    public Map<String, Object> form(String categoryCode) { return form(categoryCode, false); }

    @Transactional
    public Map<String, Object> create(String resource, Map<String, Object> input) {
        var entity = entity(resource);
        var values = defaults(resource);
        copyAllowed(entity, values, input);
        if (!"categories".equals(resource)) {
            if ("options".equals(resource)) positiveInt(values.get("groupId"), "groupId");
            else required(values, "categoryCode");
            lockCategory(categoryFor(resource, values), true);
        }
        validate(resource, values);
        var dbValues = databaseValues(entity, values);
        boolean audited = "categories".equals(resource) || "rules".equals(resource);
        if (audited) {
            dbValues.put("created_by", TenantContext.get().userId());
            dbValues.put("updated_by", TenantContext.get().userId());
        }
        String columnList = String.join(", ", dbValues.keySet());
        String bindings = String.join(", ", dbValues.keySet().stream().map(c -> ":" + c + (jsonColumn(c) ? "::jsonb" : "")).toList());
        Object id;
        try {
            var insert = jdbc.sql("INSERT INTO catalog." + entity.table() + " (" + columnList + ") VALUES (" + bindings + ") RETURNING " + entity.key())
                    .params(dbValues);
            id = "categories".equals(resource) ? insert.query(String.class).single() : insert.query(Long.class).single();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This code already exists or a referenced catalog entry is unavailable");
        }
        var saved = get(resource, id);
        audit(resource, id, "CREATE", Map.of(), saved);
        return saved;
    }

    @Transactional
    public Map<String, Object> update(String resource, Object id, Map<String, Object> input) {
        var entity = entity(resource);
        var before = get(resource, id);
        lockCategory(categoryFor(resource, before), true);
        before = get(resource, id);
        var merged = new LinkedHashMap<>(before);
        copyAllowed(entity, merged, input);
        for (String immutable : List.of("code", "categoryCode", "groupId")) {
            if (before.containsKey(immutable) && !java.util.Objects.equals(String.valueOf(before.get(immutable)), String.valueOf(merged.get(immutable)))) {
                throw error(immutable, "Codes and catalog ownership cannot change; add a new entry instead");
            }
        }
        validate(resource, merged);
        var dbValues = databaseValues(entity, merged);
        dbValues.remove(entity.key());
        String assignments = String.join(", ", dbValues.keySet().stream().map(c -> c + " = :" + c + (jsonColumn(c) ? "::jsonb" : "")).toList());
        if ("categories".equals(resource) || "rules".equals(resource)) {
            assignments += ", updated_at = now(), updated_by = :actor";
            dbValues.put("actor", TenantContext.get().userId());
        }
        dbValues.put("entryId", id);
        jdbc.sql("UPDATE catalog." + entity.table() + " SET " + assignments + " WHERE " + entity.key() + " = :entryId").params(dbValues).update();
        var saved = get(resource, id);
        audit(resource, id, "UPDATE", before, saved);
        return saved;
    }

    @Transactional
    public Map<String, Object> delete(String resource, Object id, boolean hard) {
        var before = get(resource, id);
        lockCategory(categoryFor(resource, before), true);
        if (!hard) {
            update(resource, id, Map.of("active", false));
            return row("deleted", true, "id", id, "hard", false);
        }
        long references = referencedOrderCount(resource, before);
        if (references > 0) throw new CatalogReferenceConflict(references);
        if ("options".equals(resource)) {
            var group = get("groups", before.get("groupId"));
            long ruleReferences = jdbc.sql("SELECT count(*) FROM catalog.product_form_rules WHERE category_code = :category AND match_options ->> :groupCode = :optionCode")
                    .param("category", group.get("categoryCode")).param("groupCode", group.get("code"))
                    .param("optionCode", before.get("code")).query(Long.class).single();
            if (ruleReferences > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "This option is referenced by catalog rules; deactivate it instead");
        }
        var entity = entity(resource);
        try {
            jdbc.sql("DELETE FROM catalog." + entity.table() + " WHERE " + entity.key() + " = :entryId").param("entryId", id).update();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This entry still contains groups, options or rules, or is referenced by an option dependency; deactivate it instead");
        }
        audit(resource, id, "DELETE", before, Map.of());
        return row("deleted", true, "id", id, "hard", true);
    }

    private void validate(String resource, Map<String, Object> value) {
        if (!"rules".equals(resource)) {
            text(value, "code", "options".equals(resource) ? 60 : 40, true);
            if (!String.valueOf(value.get("code")).matches("^[A-Z][A-Z0-9_]{1,59}$")) throw error("code", "Use uppercase letters, numbers and underscores");
            text(value, "label", "options".equals(resource) ? 120 : 80, true);
        }
        switch (resource) {
            case "categories" -> {
                text(value, "emoji", 16, false);
                text(value, "description", 200, false);
                enumValue(value, "orderType", Set.of("Recurring", "One-time", "Service"));
                if (Boolean.TRUE.equals(value.get("formEnabled")) && !"NOTEBOOKS".equals(value.get("code"))) {
                    throw error("formEnabled", "Structured order forms currently support Notebooks only");
                }
            }
            case "groups" -> {
                get("categories", required(value, "categoryCode"));
                positiveInt(value.get("level"), "level");
                enumValue(value, "selectionType", Set.of("SINGLE"));
                enumValue(value, "scope", Set.of("ORDER", "LINE"));
                if ("NOTEBOOKS".equals(value.get("categoryCode")) && "CUSTOMIZATION".equals(value.get("code"))
                        && (!"ORDER".equals(value.get("scope")) || !Boolean.TRUE.equals(value.get("required")) || !Boolean.TRUE.equals(value.get("active")))) {
                    throw error("scope", "Notebook customization must remain an active required order-level choice");
                }
            }
            case "options" -> {
                var group = get("groups", positiveInt(value.get("groupId"), "groupId"));
                text(value, "specText", 120, false);
                enumValue(value, "specStatus", Set.of("CONFIRMED", "PENDING_SPEC"));
                Object width = value.get("widthMm");
                Object height = value.get("heightMm");
                if ((width == null) != (height == null)) throw error("widthMm", "Provide both width and height");
                if (width != null) { positiveInt(width, "widthMm"); positiveInt(height, "heightMm"); }
                if ("SIZE".equals(group.get("code")) && "CONFIRMED".equals(value.get("specStatus"))
                        && (width == null || height == null || String.valueOf(value.getOrDefault("specText", "")).isBlank())) {
                    throw error("specStatus", "Complete the dimensions and specification before confirming this size");
                }
            }
            case "rules" -> {
                text(value, "message", 200, false);
                var definition = form(String.valueOf(required(value, "categoryCode")), true);
                ProductFormRuleEngine.validateRule(value, maps(definition.get("groups")));
                for (var other : maps(definition.get("rules"))) {
                    if (java.util.Objects.equals(other.get("id"), value.get("id")) || Boolean.FALSE.equals(other.get("active")) || Boolean.FALSE.equals(value.get("active"))) continue;
                    if (!java.util.Objects.equals(other.get("matchOptions"), value.get("matchOptions")) || !java.util.Objects.equals(other.get("targetField"), value.get("targetField"))) continue;
                    String first = String.valueOf(other.get("ruleType"));
                    String second = String.valueOf(value.get("ruleType"));
                    if (("MIN_VALUE".equals(first) && "MAX_VALUE".equals(second)) || ("MAX_VALUE".equals(first) && "MIN_VALUE".equals(second))) {
                        int otherValue = positiveInt(map(other.get("params")).get("value"), "params.value");
                        int thisValue = positiveInt(map(value.get("params")).get("value"), "params.value");
                        if (("MIN_VALUE".equals(first) && otherValue > thisValue) || ("MAX_VALUE".equals(first) && thisValue > otherValue)) throw error("params.value", "Minimum cannot exceed maximum");
                    }
                }
            }
            default -> throw missing();
        }
    }

    private long referencedOrderCount(String resource, Map<String, Object> value) {
        String category = categoryFor(resource, value);
        if ("categories".equals(resource)) return jdbc.sql("SELECT count(*) FROM catalog.catalog_orders WHERE category = :category").param("category", category).query(Long.class).single();
        if ("options".equals(resource)) {
            var group = get("groups", value.get("groupId"));
            return jdbc.sql("""
                    SELECT count(*) FROM catalog.catalog_orders o WHERE o.category = :category AND
                    ((o.order_selections -> :groupCode ->> 'code') = :optionCode OR EXISTS
                    (SELECT 1 FROM catalog.catalog_order_lines l WHERE l.order_id = o.id
                        AND (l.option_selections -> :groupCode ->> 'code') = :optionCode))
                    """).param("category", category).param("groupCode", group.get("code")).param("optionCode", value.get("code")).query(Long.class).single();
        }
        String snapshotKey = "groups".equals(resource) ? "groups" : "rules";
        String fragment = mapper.writeValueAsString(Map.of(snapshotKey, List.of(Map.of("id", value.get("id")))));
        return jdbc.sql("SELECT count(*) FROM catalog.catalog_orders WHERE category = :category AND form_snapshot @> :fragment::jsonb")
                .param("category", category).param("fragment", fragment).query(Long.class).single();
    }

    private Map<String, Object> get(String resource, Object id) {
        var entity = entity(resource);
        return readList(resource, " WHERE " + entity.key() + " = :entryId", Map.of("entryId", id), "").stream().findFirst().orElseThrow(ProductCatalogRepository::missing);
    }

    private List<Map<String, Object>> readList(String resource, String filter, Map<String, ?> params, String order) {
        var entity = entity(resource);
        var fields = new ArrayList<String>();
        if ("id".equals(entity.key())) fields.add("id");
        entity.columns().forEach((json, db) -> fields.add(db + (jsonColumn(db) ? "::text" : "") + " AS \"" + json + "\""));
        var rows = jdbc.sql("SELECT " + String.join(", ", fields) + " FROM catalog." + entity.table() + filter + order).params(params).query().listOfRows();
        var result = new ArrayList<Map<String, Object>>();
        for (var raw : rows) {
            var row = new LinkedHashMap<>(raw);
            for (String key : List.of("matchOptions", "params")) {
                if (row.get(key) instanceof String json) row.put(key, mapper.readValue(json, Map.class));
            }
            result.add(row);
        }
        return result;
    }

    private String categoryFor(String resource, Map<String, Object> row) {
        return switch (resource) {
            case "categories" -> String.valueOf(row.get("code"));
            case "options" -> String.valueOf(get("groups", row.get("groupId")).get("categoryCode"));
            default -> String.valueOf(row.get("categoryCode"));
        };
    }

    private void lockCategory(String code, boolean write) {
        jdbc.sql("SELECT code FROM catalog.product_categories WHERE code = :code FOR " + (write ? "UPDATE" : "SHARE"))
                .param("code", code).query(String.class).optional().orElseThrow(ProductCatalogRepository::missing);
    }

    private void audit(String resource, Object id, String operation, Map<String, Object> before, Map<String, Object> after) {
        outbox.append("catalog-configuration.changed.v1", "catalog-config:" + UUID.randomUUID(), "catalog-configuration", String.valueOf(id), null,
                row("resource", resource, "id", id, "operation", operation, "actorId", TenantContext.get().userId(), "before", before, "after", after));
    }

    private Map<String, Object> databaseValues(Entity entity, Map<String, Object> value) {
        var result = new LinkedHashMap<String, Object>();
        entity.columns().forEach((json, db) -> result.put(db, jsonColumn(db) ? mapper.writeValueAsString(value.get(json)) : value.get(json)));
        return result;
    }

    private static Map<String, Object> defaults(String resource) {
        var result = new LinkedHashMap<String, Object>();
        result.put("active", true);
        switch (resource) {
            case "categories" -> result.putAll(row("emoji", "", "description", "", "orderType", "Recurring", "formEnabled", false, "sortOrder", 0));
            case "groups" -> result.putAll(row("selectionType", "SINGLE", "inputType", "SELECT", "unit", "", "required", true, "level", 1));
            case "options" -> result.putAll(row("specText", "", "widthMm", null, "heightMm", null, "specStatus", "CONFIRMED", "sortOrder", 0));
            case "rules" -> result.putAll(row("targetField", null, "matchOptions", Map.of(), "priority", 0, "message", ""));
        }
        return result;
    }

    private static void copyAllowed(Entity entity, Map<String, Object> target, Map<String, Object> input) {
        input.forEach((key, value) -> {
            if (!entity.columns().containsKey(key)) throw error(key, "Unknown catalog field");
            target.put(key, value);
        });
    }
    private static Entity entity(String resource) { var entity = ENTITIES.get(resource); if (entity == null) throw missing(); return entity; }
    private static boolean jsonColumn(String column) { return Set.of("match_options", "params").contains(column); }
    private static Map<String, String> columns(String... names) {
        var result = new LinkedHashMap<String, String>();
        for (String name : names) result.put(name, name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT));
        return result;
    }
    private static Object required(Map<String, Object> value, String key) {
        if (value.get(key) == null || String.valueOf(value.get(key)).isBlank()) throw error(key, "This field is required");
        return value.get(key);
    }
    private static void text(Map<String, Object> value, String key, int max, boolean required) {
        Object input = value.get(key);
        if (required) required(value, key);
        if (!(input instanceof String) || ((String) input).length() > max) throw error(key, "Enter text up to " + max + " characters");
    }
    private static void enumValue(Map<String, Object> value, String key, Set<String> supported) {
        if (!supported.contains(String.valueOf(value.get(key)))) throw error(key, "Unsupported value");
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog entry not found"); }

    public static class CatalogReferenceConflict extends RuntimeException {
        private final long count;
        public CatalogReferenceConflict(long count) { super("This entry is referenced by " + count + " order(s); deactivate it instead"); this.count = count; }
        public long referencingOrderCount() { return count; }
    }
}
