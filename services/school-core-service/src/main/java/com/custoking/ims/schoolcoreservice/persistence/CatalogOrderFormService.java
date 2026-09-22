package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine.NormalisationResult;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CatalogOrderFormService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JdbcClient jdbc;
    private final ProductCatalogRepository products;
    private final ProductFormRuleEngine rules;
    private final CatalogOrderAssetStorage storage;
    private final OutboxWriter outbox;
    private final ModuleEntitlementGuard moduleGuard;

    public CatalogOrderFormService(JdbcClient jdbc, ProductCatalogRepository products, ProductFormRuleEngine rules,
                                   CatalogOrderAssetStorage storage, OutboxWriter outbox, ModuleEntitlementGuard moduleGuard) {
        this.jdbc = jdbc;
        this.products = products;
        this.rules = rules;
        this.storage = storage;
        this.outbox = outbox;
        this.moduleGuard = moduleGuard;
    }

    public boolean handlesCreation(String category) {
        return category != null && "NOTEBOOKS".equalsIgnoreCase(category.trim()) && products.isEnabled();
    }

    @Transactional
    public String create(Map<String, Object> request) {
        TenantScope.requirePermissionIfAuthenticated("order:create");
        scope();
        Long requestedSchool = nullableLong(request.get("schoolId"));
        Long schoolId = TenantContext.get().isOperations()
                ? TenantScope.resolveOperationsWriteScope(requestedSchool) : TenantScope.resolveSchoolId(requestedSchool);
        if (schoolId == null) throw bad("Select a school for the order");
        if (jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE id = :id").param("id", schoolId)
                .query(Long.class).single() == 0) throw bad("School not found");
        requireModule(schoolId);
        if (!"DRAFT".equalsIgnoreCase(string(request.getOrDefault("status", "DRAFT")))) {
            throw bad("Create a draft, upload any required artwork, then place the saved order");
        }
        rejectPrices(request);
        Map<String, Object> definition = products.form("NOTEBOOKS", false);
        Map<String, Object> category = map(definition.get("category"));
        if (Boolean.FALSE.equals(category.get("active")) || !Boolean.TRUE.equals(category.get("formEnabled"))) {
            throw bad("Notebook ordering is currently unavailable");
        }
        NormalisationResult result = normalise(definition, request.get("orderData"));
        String id = "CK-" + jdbc.sql("SELECT nextval('catalog.seq_catalog_order_id')").query(Long.class).single();
        boolean custom = customized(result.orderSelections());
        jdbc.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount,
                    status, required_by_date, notes, estimated_delivery, design_status, superadmin_approval_status,
                    notebook_cover_logo, notebook_delivery_mode, notebook_spine_name, created_at, created_by,
                    form_version, order_selections, form_snapshot, pricing_status, quantity_rule_results)
                VALUES (:id, :school, 'NOTEBOOKS', :data, 0, 0, 0, 'DRAFT', :date, :notes, '1-2 weeks',
                    :design, 'NOT_SUBMITTED', :cover, 'SCHOOL', 'NO', now(), :actor,
                    2, CAST(:selections AS jsonb), CAST(:snapshot AS jsonb), 'PENDING_PRICING', CAST(:aggregate AS jsonb))
                """).param("id", id).param("school", schoolId)
                .param("data", Json.write(compatibility(result)))
                .param("date", date(request.get("requiredByDate"))).param("notes", notes(request.get("notes")))
                .param("design", custom ? "PENDING" : "NOT_REQUIRED").param("cover", custom ? "YES" : "NO")
                .param("actor", actorString()).param("selections", Json.write(result.orderSelections()))
                .param("snapshot", Json.write(definition)).param("aggregate", Json.write(result.aggregateResults())).update();
        saveLines(id, schoolId, result);
        return id;
    }

    @Transactional(readOnly = true)
    public boolean isStructured(String id) {
        scope();
        return jdbc.sql("SELECT form_version FROM catalog.catalog_orders WHERE id = :id").param("id", id)
                .query(Integer.class).optional().orElse(1) == 2;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(String id) {
        Map<String, Object> order = load(id, false);
        requireV2(order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formDefinition", map(order.get("form_snapshot")));
        result.put("orderSelections", map(order.get("order_selections")));
        result.put("lines", lines(id));
        result.put("assets", assets(id));
        result.put("formVersion", 2);
        result.put("pricingStatus", order.get("pricing_status"));
        result.put("version", order.get("version"));
        result.put("quantityRuleResults", array(order.get("quantity_rule_results")));
        result.put("quotedAt", timestamp(order.get("quoted_at")));
        result.put("quotedBy", order.get("quoted_by"));
        result.put("approvedDesignAssetId", order.get("approved_design_asset_id"));
        return result;
    }

    @Transactional
    public void updateDraft(String id, Map<String, Object> request) {
        TenantScope.requirePermissionIfAuthenticated("order:update");
        Map<String, Object> order = load(id, true);
        requireV2(order);
        requireVersion(order, request.get("version"));
        requireStatus(order, "DRAFT");
        rejectPrices(request);
        NormalisationResult result = normalise(map(order.get("form_snapshot")), request.get("orderData"));
        jdbc.sql("DELETE FROM catalog.catalog_order_lines WHERE order_id = :id").param("id", id).update();
        saveLines(id, number(order.get("school_id")), result);
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET order_data = :data, order_selections = CAST(:selections AS jsonb),
                    required_by_date = :date, notes = :notes, design_status = :design, notebook_cover_logo = :cover,
                    quantity_rule_results = CAST(:aggregate AS jsonb), updated_by = :actor, version = version + 1
                WHERE id = :id
                """).param("id", id).param("data", Json.write(compatibility(result)))
                .param("selections", Json.write(result.orderSelections()))
                .param("date", request.containsKey("requiredByDate") ? date(request.get("requiredByDate")) : order.get("required_by_date"))
                .param("notes", request.containsKey("notes") ? notes(request.get("notes")) : order.get("notes"))
                .param("design", customized(result.orderSelections()) ? "PENDING" : "NOT_REQUIRED")
                .param("cover", customized(result.orderSelections()) ? "YES" : "NO")
                .param("aggregate", Json.write(aggregateWithLineIds(id, result.aggregateResults())))
                .param("actor", actorString()).update();
        emit(id);
    }

    @Transactional
    public void transition(String id, String action, Long actorId) {
        Map<String, Object> order = load(id, true);
        requireV2(order);
        boolean custom = customized(map(order.get("order_selections")));
        String nextStatus;
        String design = string(order.get("design_status"));
        String approval = string(order.get("superadmin_approval_status"));
        Long approvedAsset = nullableLong(order.get("approved_design_asset_id"));
        switch (action) {
            case "PLACE" -> {
                TenantScope.requirePermissionIfAuthenticated("order:create");
                requireStatus(order, "DRAFT");
                requireStage(order, "ON_PLACE");
                nextStatus = custom ? "DESIGN_APPROVAL" : "PROCESSING";
                design = custom ? "PENDING" : "NOT_REQUIRED";
                approval = custom ? "NOT_SUBMITTED" : "PENDING";
            }
            case "DESIGN_APPROVED" -> {
                TenantScope.requireOperationsOrSuperAdmin();
                TenantScope.requirePermissionIfAuthenticated("order:update");
                requireStatus(order, "DESIGN_APPROVAL");
                if (!custom) throw bad("Non-customized notebooks do not require design approval");
                requireStage(order, "ON_PLACE");
                approvedAsset = currentAsset(id, "DESIGN");
                if (approvedAsset == null) throw bad("Upload artwork before approving the design");
                nextStatus = "DESIGN_APPROVED_PROCESSING";
                design = "APPROVED";
                approval = "PENDING";
            }
            case "APPROVED" -> {
                TenantScope.requireSuperAdmin();
                TenantScope.requirePermissionIfAuthenticated("order:approve");
                requireStatus(order, "PROCESSING", "DESIGN_APPROVED_PROCESSING");
                if (!"QUOTED".equals(order.get("pricing_status"))) throw bad("Save the superadmin quote before final approval");
                requireStage(order, "ON_PLACE");
                if (custom && (!"APPROVED".equals(design) || approvedAsset == null || !approvedAsset.equals(currentAsset(id, "DESIGN")))) {
                    throw bad("Approve the current artwork before final approval");
                }
                nextStatus = "APPROVED";
                approval = "APPROVED";
            }
            case "DELIVERED" -> {
                TenantScope.requireOperationsOrSuperAdmin();
                TenantScope.requireAnyPermissionIfAuthenticated("order:fulfill", "order:update");
                requireStatus(order, "APPROVED");
                requireStage(order, "BEFORE_DELIVERY");
                nextStatus = "DELIVERED";
            }
            default -> throw bad("Unsupported notebook order transition");
        }
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET status = :status, design_status = :design,
                    superadmin_approval_status = :approval, approved_design_asset_id = :asset,
                    placed_at = CASE WHEN :placing THEN now() ELSE placed_at END,
                    placed_by = CASE WHEN :placing THEN :actor ELSE placed_by END,
                    delivered_at = CASE WHEN :delivering THEN now() ELSE delivered_at END,
                    delivered_by = CASE WHEN :delivering THEN :actor ELSE delivered_by END,
                    updated_by = :actorString, version = version + 1 WHERE id = :id
                """).param("id", id).param("status", nextStatus).param("design", design).param("approval", approval)
                .param("asset", approvedAsset).param("placing", "PLACE".equals(action)).param("delivering", "DELIVERED".equals(action))
                .param("actor", TenantContext.get().userId()).param("actorString", actorString()).update();
    }

    @Transactional
    public void updateStatus(String id, String status) {
        String normalized = string(status).trim().toUpperCase(Locale.ROOT);
        Map<String, Object> order = load(id, true);
        String action = switch (normalized) {
            case "DESIGN_APPROVAL" -> "PLACE";
            case "PROCESSING" -> {
                if (customized(map(order.get("order_selections")))) throw bad("Customized notebooks must complete design approval");
                yield "PLACE";
            }
            case "DESIGN_APPROVED_PROCESSING" -> "DESIGN_APPROVED";
            case "APPROVED" -> "APPROVED";
            case "DELIVERED" -> "DELIVERED";
            default -> throw bad("Use the supported place, design approval, final approval, and delivery actions");
        };
        transition(id, action, TenantContext.get().userId());
    }

    @Transactional
    public void returnOrder(String id, String reason) {
        TenantScope.requireSuperAdmin();
        Map<String, Object> order = load(id, true);
        requireV2(order);
        requireStatus(order, "PROCESSING", "DESIGN_APPROVED_PROCESSING");
        boolean custom = customized(map(order.get("order_selections")));
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET status = :status, design_status = :design,
                    superadmin_approval_status = 'RETURNED', approved_design_asset_id = NULL,
                    notes = :notes, updated_by = :actor, version = version + 1 WHERE id = :id
                """).param("id", id).param("status", custom ? "DESIGN_APPROVAL" : "PROCESSING")
                .param("design", custom ? "PENDING" : "NOT_REQUIRED").param("notes", notes(reason))
                .param("actor", actorString()).update();
    }

    @Transactional
    public void quote(String id, Map<String, Object> request) {
        TenantScope.requireSuperAdmin();
        TenantScope.requirePermissionIfAuthenticated("catalog:quote");
        Map<String, Object> order = load(id, true);
        requireV2(order);
        requireVersion(order, request.get("version"));
        requireStatus(order, "DESIGN_APPROVAL", "DESIGN_APPROVED_PROCESSING", "PROCESSING");
        List<Map<String, Object>> persisted = lines(id);
        List<Map<String, Object>> prices = objectList(request.get("lines"));
        if (prices.size() != persisted.size()) throw bad("Quote every persisted order line exactly once");
        Map<Long, Long> quoted = new LinkedHashMap<>();
        for (Map<String, Object> price : prices) {
            long lineId = positiveInteger(price.get("id"), "line id", false);
            long amount = positiveInteger(price.get("unitPricePaise"), "Unit price in paise", true);
            if (quoted.put(lineId, amount) != null) throw bad("Duplicate line in quote");
        }
        long subtotal = 0;
        try {
            for (Map<String, Object> line : persisted) {
                long lineId = number(line.get("id"));
                Long price = quoted.get(lineId);
                if (price == null) throw bad("Quote every persisted order line exactly once");
                long total = Math.multiplyExact(number(line.get("bookCount")), price);
                subtotal = Math.addExact(subtotal, total);
                jdbc.sql("UPDATE catalog.catalog_order_lines SET unit_price_paise = :price, line_total_paise = :total WHERE id = :id AND order_id = :order")
                        .param("id", lineId).param("order", id).param("price", price).param("total", total).update();
            }
            long gst = positiveInteger(request.get("gstPaise"), "GST in paise", true);
            long total = Math.addExact(subtotal, gst);
            jdbc.sql("""
                    UPDATE catalog.catalog_orders SET subtotal = :subtotal, gst = :gst, total_amount = :total,
                        pricing_status = 'QUOTED', quoted_at = now(), quoted_by = :actor,
                        updated_by = :actorString, version = version + 1 WHERE id = :id
                    """).param("id", id).param("subtotal", subtotal).param("gst", gst).param("total", total)
                    .param("actor", TenantContext.get().userId()).param("actorString", actorString()).update();
        } catch (ArithmeticException ex) { throw bad("The quoted total exceeds the supported amount"); }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("orderId", id);
        audit.put("actorId", TenantContext.get().userId());
        audit.put("before", persisted);
        audit.put("lines", lines(id));
        audit.put("previousGstPaise", order.get("gst"));
        audit.put("gstPaise", request.get("gstPaise"));
        outbox.append("catalog-order.quoted.v1", "CatalogOrderQuoted:" + id, "CatalogOrder", id, number(order.get("school_id")), audit);
        emit(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assets(String id) {
        return assetList(id, false);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assetHistory(String id) {
        return assetList(id, true);
    }

    private List<Map<String, Object>> assetList(String id, boolean includeSuperseded) {
        load(id, false);
        return jdbc.sql("""
                SELECT id, asset_kind AS "assetKind", content_type AS "contentType", size_bytes AS "sizeBytes",
                    checksum_sha256 AS "checksumSha256", original_filename AS "originalFilename",
                    uploaded_by AS "uploadedBy", uploaded_at AS "uploadedAt", superseded_at AS "supersededAt"
                FROM catalog.catalog_order_assets WHERE order_id = :id
                """ + (includeSuperseded ? "" : " AND superseded_at IS NULL") + " ORDER BY uploaded_at, id")
                .param("id", id).query((rs, rowNum) -> {
                    Map<String, Object> asset = new LinkedHashMap<>();
                    for (String field : List.of("id", "assetKind", "contentType", "sizeBytes", "checksumSha256", "originalFilename", "uploadedBy", "uploadedAt", "supersededAt")) {
                        asset.put(field, field.endsWith("At") ? timestamp(rs.getObject(field)) : rs.getObject(field));
                    }
                    asset.put("contentUrl", "/api/v1/supply/orders/" + id + "/assets/" + rs.getLong("id") + "/content");
                    return asset;
                }).list();
    }

    @Transactional
    public Map<String, Object> upload(String id, String assetKind, byte[] bytes, String filename) {
        TenantScope.requirePermissionIfAuthenticated("order:update");
        Map<String, Object> order = load(id, true);
        requireV2(order);
        String kind = string(assetKind).toUpperCase(Locale.ROOT);
        if (!Set.of("DESIGN", "PRE_DELIVERY_PHOTO").contains(kind)) throw bad("Select a valid attachment kind");
        requireAssetEditable(order, kind);
        var validated = storage.validate(bytes, filename, kind);
        for (Map<String, Object> current : assets(id)) {
            if (kind.equals(current.get("assetKind")) && validated.checksumSha256().equals(current.get("checksumSha256"))) return current;
        }
        String key = storage.store(number(order.get("school_id")), id, validated);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) storage.deleteUncommitted(key);
                }
            });
        }
        jdbc.sql("UPDATE catalog.catalog_order_assets SET superseded_at = now() WHERE order_id = :id AND asset_kind = :kind AND superseded_at IS NULL")
                .param("id", id).param("kind", kind).update();
        Long assetId = jdbc.sql("""
                INSERT INTO catalog.catalog_order_assets(order_id, school_id, asset_kind, storage_key, content_type,
                    size_bytes, checksum_sha256, original_filename, uploaded_by)
                VALUES (:id, :school, :kind, :key, :type, :bytes, :checksum, :filename, :actor) RETURNING id
                """).param("id", id).param("school", order.get("school_id")).param("kind", kind).param("key", key)
                .param("type", validated.contentType()).param("bytes", bytes.length).param("checksum", validated.checksumSha256())
                .param("filename", validated.filename()).param("actor", TenantContext.get().userId()).query(Long.class).single();
        assetChanged(order, kind);
        emit(id);
        return assets(id).stream().filter(asset -> number(asset.get("id")) == assetId).findFirst().orElseThrow();
    }

    @Transactional
    public void removeAsset(String id, long assetId) {
        TenantScope.requirePermissionIfAuthenticated("order:update");
        Map<String, Object> order = load(id, true);
        requireV2(order);
        Map<String, Object> asset = asset(id, assetId);
        String kind = string(asset.get("asset_kind"));
        requireAssetEditable(order, kind);
        jdbc.sql("UPDATE catalog.catalog_order_assets SET superseded_at = now() WHERE id = :asset AND order_id = :id")
                .param("asset", assetId).param("id", id).update();
        assetChanged(order, kind);
        emit(id);
    }

    @Transactional(readOnly = true)
    public AssetContent content(String id, long assetId) {
        TenantScope.requirePermissionIfAuthenticated("order:read");
        load(id, false);
        Map<String, Object> asset = asset(id, assetId, true);
        return new AssetContent(storage.read(string(asset.get("storage_key"))), string(asset.get("content_type")),
                string(asset.get("original_filename")));
    }

    private void assetChanged(Map<String, Object> order, String kind) {
        boolean invalidate = "DESIGN".equals(kind) && customized(map(order.get("order_selections")))
                && !"DRAFT".equals(order.get("status"));
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET version = version + 1, updated_by = :actor,
                    approved_design_asset_id = CASE WHEN :invalidate THEN NULL ELSE approved_design_asset_id END,
                    design_status = CASE WHEN :invalidate THEN 'PENDING' ELSE design_status END,
                    status = CASE WHEN :invalidate THEN 'DESIGN_APPROVAL' ELSE status END,
                    superadmin_approval_status = CASE WHEN :invalidate THEN 'NOT_SUBMITTED' ELSE superadmin_approval_status END
                WHERE id = :id
                """).param("id", order.get("id")).param("actor", actorString()).param("invalidate", invalidate).update();
    }

    private void requireAssetEditable(Map<String, Object> order, String kind) {
        if ("DELIVERED".equals(order.get("status"))) throw bad("Delivered order attachments cannot be changed");
        if ("DESIGN".equals(kind) && "APPROVED".equals(order.get("status"))) throw bad("Artwork cannot change after final approval");
    }

    private Map<String, Object> asset(String id, long assetId) {
        return asset(id, assetId, false);
    }

    private Map<String, Object> asset(String id, long assetId, boolean includeSuperseded) {
        return jdbc.sql("SELECT * FROM catalog.catalog_order_assets WHERE id = :asset AND order_id = :id"
                        + (includeSuperseded ? "" : " AND superseded_at IS NULL"))
                .param("asset", assetId).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found"));
    }

    private Long currentAsset(String id, String kind) {
        return jdbc.sql("SELECT id FROM catalog.catalog_order_assets WHERE order_id = :id AND asset_kind = :kind AND superseded_at IS NULL")
                .param("id", id).param("kind", kind).query(Long.class).optional().orElse(null);
    }

    private void requireStage(Map<String, Object> order, String stage) {
        Map<String, Object> definition = map(order.get("form_snapshot"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("orderSelections", codes(map(order.get("order_selections"))));
        raw.put("lines", lines(string(order.get("id"))).stream().map(line -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("selections", codes(map(line.get("optionSelections"))));
            value.put("bookCount", line.get("requestedBookCount"));
            value.put("pageCount", line.get("requestedPageCount"));
            return value;
        }).toList());
        NormalisationResult normalized = normalise(definition, raw);
        Set<String> kinds = new HashSet<>();
        assets(string(order.get("id"))).forEach(asset -> kinds.add(string(asset.get("assetKind"))));
        rules.requireStage(definition, normalized, stage, kinds);
    }

    private NormalisationResult normalise(Map<String, Object> definition, Object value) {
        Map<String, Object> data = map(value);
        if (!data.containsKey("orderSelections") || !data.containsKey("lines")) {
            throw bad("Notebook ordering has changed. Refresh the catalog and use the structured notebook form");
        }
        rejectPrices(data);
        List<Map<String, Object>> lines = objectList(data.get("lines"));
        lines.forEach(this::rejectPrices);
        return rules.normalise(definition, map(data.get("orderSelections")), lines);
    }

    private void saveLines(String id, long schoolId, NormalisationResult result) {
        for (Map<String, Object> line : result.lines()) {
            jdbc.sql("""
                    INSERT INTO catalog.catalog_order_lines(order_id, school_id, line_no, option_selections,
                        requested_book_count, book_count, requested_page_count, page_count, applied_rules, created_by)
                    VALUES (:order, :school, :line, CAST(:options AS jsonb), :requestedBooks, :books,
                        :requestedPages, :pages, CAST(:rules AS jsonb), :actor)
                    """).param("order", id).param("school", schoolId).param("line", line.get("lineNo"))
                    .param("options", Json.write(line.get("optionSelections"))).param("requestedBooks", line.get("requestedBookCount"))
                    .param("books", line.get("bookCount")).param("requestedPages", line.get("requestedPageCount"))
                    .param("pages", line.get("pageCount")).param("rules", Json.write(line.get("appliedRules")))
                    .param("actor", TenantContext.get().userId()).update();
        }
        jdbc.sql("UPDATE catalog.catalog_orders SET quantity_rule_results = CAST(:results AS jsonb) WHERE id = :id")
                .param("id", id).param("results", Json.write(aggregateWithLineIds(id, result.aggregateResults()))).update();
    }

    private List<Map<String, Object>> aggregateWithLineIds(String id, List<Map<String, Object>> aggregate) {
        List<Map<String, Object>> persisted = lines(id);
        return aggregate.stream().map(result -> {
            Map<String, Object> copy = new LinkedHashMap<>(result);
            Object participating = result.get("lineNos");
            List<?> numbers = participating instanceof List<?> values ? values : List.of();
            copy.put("lineIds", persisted.stream().filter(line -> numbers.isEmpty()
                    || numbers.stream().anyMatch(n -> number(n) == number(line.get("lineNo"))))
                    .map(line -> line.get("id")).toList());
            return copy;
        }).toList();
    }

    private List<Map<String, Object>> lines(String id) {
        return jdbc.sql("""
                SELECT id, line_no, option_selections, requested_book_count, book_count, requested_page_count,
                    page_count, applied_rules, unit_price_paise, line_total_paise
                FROM catalog.catalog_order_lines WHERE order_id = :id ORDER BY line_no, id
                """).param("id", id).query((rs, n) -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("id", rs.getLong("id"));
                    line.put("lineNo", rs.getInt("line_no"));
                    line.put("optionSelections", map(rs.getString("option_selections")));
                    line.put("requestedBookCount", rs.getInt("requested_book_count"));
                    line.put("bookCount", rs.getInt("book_count"));
                    line.put("requestedPageCount", rs.getInt("requested_page_count"));
                    line.put("pageCount", rs.getInt("page_count"));
                    line.put("appliedRules", array(rs.getString("applied_rules")));
                    line.put("unitPricePaise", rs.getObject("unit_price_paise"));
                    line.put("lineTotalPaise", rs.getObject("line_total_paise"));
                    return line;
                }).list();
    }

    private Map<String, Object> load(String id, boolean lock) {
        scope();
        Map<String, Object> order = jdbc.sql("SELECT * FROM catalog.catalog_orders WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        long schoolId = number(order.get("school_id"));
        TenantScope.resolvePlatformReadScope(schoolId);
        requireModule(schoolId);
        return order;
    }

    private void requireModule(Long schoolId) {
        if (moduleGuard != null) moduleGuard.requireModuleEnabled(schoolId, "ORDERS");
    }

    private void scope() {
        TenantContext ctx = TenantContext.get();
        if (ctx.isOperations()) jdbc.sql("SELECT set_config('app.operator_schools', :schools, true)")
                .param("schools", String.join(",", ctx.operatorSchools().stream().map(String::valueOf).toList()))
                .query(String.class).single();
    }

    private void emit(String id) {
        Map<String, Object> order = load(id, false);
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String[] entry : new String[][]{{"id","id"},{"schoolId","school_id"},{"category","category"},{"status","status"},
                {"totalAmount","total_amount"},{"superadminApprovalStatus","superadmin_approval_status"},{"vendorPaidAt","vendor_paid_at"},
                {"createdAt","created_at"},{"requiredByDate","required_by_date"},{"designStatus","design_status"},{"notes","notes"},
                {"formVersion","form_version"},{"pricingStatus","pricing_status"},{"version","version"}}) {
            Object value = order.get(entry[1]);
            if (value instanceof java.sql.Timestamp timestamp) value = timestamp.toInstant().toString();
            if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
            payload.put(entry[0], value);
        }
        outbox.append("catalog-order.upserted.v1", "CatalogOrderUpserted:" + id, "CatalogOrder", id, number(order.get("school_id")), payload);
    }

    private Map<String, Object> compatibility(NormalisationResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderSelections", codes(result.orderSelections()));
        data.put("items", result.lines().stream().map(line -> {
            Map<String, Object> selections = map(line.get("optionSelections"));
            String name = selections.values().stream().map(option -> string(map(option).get("label")))
                    .filter(label -> !label.isBlank()).reduce((a, b) -> a + " / " + b).orElse("Notebook");
            return Map.<String, Object>of("name", name + " / " + line.get("pageCount") + " printed pages", "qty", line.get("bookCount"));
        }).toList());
        return data;
    }

    private void rejectPrices(Map<String, Object> request) {
        for (String field : List.of("subtotal", "amount", "gst", "gstPaise", "totalAmount", "unitPrice", "unitPricePaise", "lineTotalPaise", "pricingStatus", "formSnapshot", "formVersion", "quotedBy")) {
            if (request.get(field) != null) throw bad("Prices and form metadata are server-owned; superadmin quotes after placement");
        }
    }

    private static void requireV2(Map<String, Object> order) {
        if (number(order.get("form_version")) != 2) throw bad("This order uses the legacy form");
    }

    private static void requireStatus(Map<String, Object> order, String... allowed) {
        if (!Set.of(allowed).contains(string(order.get("status")))) throw bad("This action is unavailable while the order is " + order.get("status"));
    }

    private static void requireVersion(Map<String, Object> order, Object version) {
        if (version == null || positiveInteger(version, "Version", true) != number(order.get("version"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The order changed. Reload it before saving");
        }
    }

    private static boolean customized(Map<String, Object> selections) {
        Object selection = selections.get("CUSTOMIZATION");
        return "CUSTOMIZED".equals(selection instanceof Map<?, ?> ? map(selection).get("code") : selection);
    }

    private static Map<String, Object> codes(Map<String, Object> snapshots) {
        Map<String, Object> codes = new LinkedHashMap<>();
        snapshots.forEach((key, value) -> codes.put(key, value instanceof Map<?, ?> ? map(value).get("code") : value));
        return codes;
    }

    private static Map<String, Object> map(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            raw.forEach((key, entry) -> result.put(String.valueOf(key), entry));
            return result;
        }
        try { return MAPPER.readValue(String.valueOf(value), new TypeReference<Map<String, Object>>() {}); }
        catch (RuntimeException ex) { throw bad("Invalid notebook form data"); }
    }

    private static List<Map<String, Object>> objectList(Object value) {
        if (!(value instanceof List<?> entries)) throw bad("Order lines must be a list");
        if (entries.isEmpty() || entries.size() > 200) throw bad("An order must contain 1 to 200 lines");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?>)) throw bad("Each order line must be an object");
            result.add(map(entry));
        }
        return result;
    }

    private static List<Object> array(Object value) {
        if (value == null) return List.of();
        try { return MAPPER.readValue(String.valueOf(value), new TypeReference<List<Object>>() {}); }
        catch (RuntimeException ex) { throw new IllegalStateException("Stored notebook rule results are invalid", ex); }
    }

    private static long positiveInteger(Object value, String field, boolean zeroAllowed) {
        try {
            long number = new java.math.BigDecimal(string(value)).longValueExact();
            if (number < (zeroAllowed ? 0 : 1)) throw bad(field + " must be " + (zeroAllowed ? "non-negative" : "positive"));
            return number;
        } catch (NumberFormatException | ArithmeticException ex) { throw bad(field + " must be a whole number"); }
    }

    private static String notes(Object value) {
        if (value == null) return null;
        String notes = string(value).trim();
        if (notes.length() > 255) throw bad("Notes must be 255 characters or fewer");
        return notes;
    }

    private static LocalDate date(Object value) {
        if (value == null || string(value).isBlank()) return null;
        try { return LocalDate.parse(string(value)); }
        catch (java.time.DateTimeException ex) { throw bad("Required-by date must be a valid ISO date"); }
    }

    private static Long nullableLong(Object value) { return value == null ? null : positiveInteger(value, "Id", false); }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static String actorString() { return TenantContext.get().userId() == null ? null : String.valueOf(TenantContext.get().userId()); }
    private static Object timestamp(Object value) { return value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().toString() : value; }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record AssetContent(byte[] bytes, String contentType, String filename) {}
}
