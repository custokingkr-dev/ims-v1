package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.DesignDraftRules;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine.NormalisationResult;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.OperatorResolver;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private TrackerGateway tracker;
    private OperatorResolver operators;

    private static final Logger LOG = LoggerFactory.getLogger(CatalogOrderFormService.class);
    /** Notebook orders skip the approval, quote and delivery flow; their progress comes from the notebook order tracker. */
    private static final String NOTEBOOKS = "NOTEBOOKS";
    /** The first tracker step, which is the status of a freshly placed notebook order. */
    static final String FIRST_TRACKER_STATUS = "ORDER_DETAILS";

    public CatalogOrderFormService(JdbcClient jdbc, ProductCatalogRepository products, ProductFormRuleEngine rules,
                                   CatalogOrderAssetStorage storage, OutboxWriter outbox, ModuleEntitlementGuard moduleGuard) {
        this.jdbc = jdbc;
        this.products = products;
        this.rules = rules;
        this.storage = storage;
        this.outbox = outbox;
        this.moduleGuard = moduleGuard;
    }

    /** Optional: without these the service behaves as before and notebook placement starts no tracker (unit tests). */
    @Autowired(required = false)
    public void configureTracker(TrackerGateway tracker, OperatorResolver operators) {
        this.tracker = tracker;
        this.operators = operators;
    }

    public boolean handlesCreation(String category) {
        // Driven by the category's own form_enabled flag. A hardcoded category name would drop any
        // newly configured category onto the legacy order path, which performs no form validation.
        return category != null && products.isEnabled() && products.formEnabled(category);
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
        String categoryCode = string(request.get("category")).trim().toUpperCase(Locale.ROOT);
        Map<String, Object> definition = products.form(categoryCode, false);
        Map<String, Object> category = map(definition.get("category"));
        if (Boolean.FALSE.equals(category.get("active")) || !Boolean.TRUE.equals(category.get("formEnabled"))) {
            throw bad("Ordering for this category is currently unavailable");
        }
        NormalisationResult result = normalise(definition, request.get("orderData"));
        String id = "CK-" + jdbc.sql("SELECT nextval('catalog.seq_catalog_order_id')").query(Long.class).single();
        boolean custom = customized(result.orderSelections());
        jdbc.sql("""
                INSERT INTO catalog.catalog_orders (id, school_id, category, order_data, subtotal, gst, total_amount,
                    status, required_by_date, notes, estimated_delivery, design_status, superadmin_approval_status,
                    notebook_cover_logo, notebook_delivery_mode, notebook_spine_name, created_at, created_by,
                    form_version, order_selections, form_snapshot, pricing_status, quantity_rule_results)
                VALUES (:id, :school, :category, :data, 0, 0, 0, 'DRAFT', :date, :notes,
                    CASE WHEN :category = 'NOTEBOOKS' THEN '1-2 weeks' ELSE NULL END,
                    :design, 'NOT_SUBMITTED', :cover, 'SCHOOL', 'NO', now(), :actor,
                    2, CAST(:selections AS jsonb), CAST(:snapshot AS jsonb),
                    CASE WHEN :category = 'NOTEBOOKS' THEN 'NOT_APPLICABLE' ELSE 'PENDING_PRICING' END, CAST(:aggregate AS jsonb))
                """).param("id", id).param("school", schoolId).param("category", categoryCode)
                .param("data", Json.write(compatibility(result, definition)))
                .param("date", date(request.get("requiredByDate"))).param("notes", notes(request.get("notes")))
                .param("design", custom && !NOTEBOOKS.equals(categoryCode) ? "PENDING" : "NOT_REQUIRED").param("cover", custom ? "YES" : "NO")
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
                """).param("id", id).param("data", Json.write(compatibility(result, map(order.get("form_snapshot")))))
                .param("selections", Json.write(result.orderSelections()))
                .param("date", request.containsKey("requiredByDate") ? date(request.get("requiredByDate")) : order.get("required_by_date"))
                .param("notes", request.containsKey("notes") ? notes(request.get("notes")) : order.get("notes"))
                .param("design", customized(result.orderSelections()) && !isNotebook(order) ? "PENDING" : "NOT_REQUIRED")
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
        boolean notebook = isNotebook(order);
        if (notebook && !"PLACE".equals(action)) {
            throw bad("Notebook orders move through the notebook order tracker, not through approval or delivery");
        }
        String nextStatus;
        String design = string(order.get("design_status"));
        String approval = string(order.get("superadmin_approval_status"));
        Long approvedAsset = nullableLong(order.get("approved_design_asset_id"));
        switch (action) {
            case "PLACE" -> {
                TenantScope.requirePermissionIfAuthenticated("order:create");
                requireStatus(order, "DRAFT");
                requireStage(order, "ON_PLACE");
                nextStatus = notebook ? FIRST_TRACKER_STATUS : custom ? "DESIGN_APPROVAL" : "PROCESSING";
                design = custom && !notebook ? "PENDING" : "NOT_REQUIRED";
                approval = notebook ? approval : custom ? "NOT_SUBMITTED" : "PENDING";
            }
            case "DESIGN_APPROVED" -> {
                TenantScope.requireOperationsOrSuperAdmin();
                TenantScope.requirePermissionIfAuthenticated("order:update");
                requireStatus(order, "DESIGN_APPROVAL");
                if (!custom) throw bad("This order does not require design approval");
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
            default -> throw bad("Unsupported order transition");
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
        if (notebook && "PLACE".equals(action)) startTracker(id);
    }

    @Transactional
    public void updateStatus(String id, String status) {
        String normalized = string(status).trim().toUpperCase(Locale.ROOT);
        Map<String, Object> order = load(id, true);
        if (isNotebook(order)) throw bad("Place a notebook order with the place action; after that it follows the notebook order tracker");
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
        if (isNotebook(order)) throw bad("Notebook orders are not returned for design approval");
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
        if (isNotebook(order)) throw bad("Notebook orders are not quoted");
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
        // Design drafts are internal to superadmin and operators; the school never sees them.
        boolean internal = TenantContext.get().isSuperAdmin() || TenantContext.get().isOperations();
        return jdbc.sql("""
                SELECT id, asset_kind AS "assetKind", content_type AS "contentType", size_bytes AS "sizeBytes",
                    checksum_sha256 AS "checksumSha256", original_filename AS "originalFilename",
                    uploaded_by AS "uploadedBy", uploaded_at AS "uploadedAt", superseded_at AS "supersededAt"
                FROM catalog.catalog_order_assets WHERE order_id = :id
                """ + (includeSuperseded ? "" : " AND superseded_at IS NULL") + (internal ? "" : " AND asset_kind <> 'DESIGN_DRAFT'") + " ORDER BY uploaded_at, id")
                .param("id", id).query((rs, rowNum) -> {
                    Map<String, Object> asset = new LinkedHashMap<>();
                    for (String field : List.of("id", "assetKind", "contentType", "sizeBytes", "checksumSha256", "originalFilename", "uploadedBy", "uploadedAt", "supersededAt")) {
                        asset.put(field, field.endsWith("At") ? timestamp(rs.getObject(field)) : rs.getObject(field));
                    }
                    asset.put("contentUrl", "/api/v1/supply/orders/" + id + "/assets/" + rs.getLong("id") + "/content");
                    return asset;
                }).list();
    }

    /** The size cap a category allows for this kind, taken from its seeded asset rule. */
    private long maxUploadBytes(String category, String kind) {
        if (category == null || category.isBlank()) return CatalogOrderAssetStorage.MAX_BYTES;
        return jdbc.sql("""
                        SELECT params ->> 'maxBytes' FROM catalog.product_form_rules
                        WHERE category_code = :category AND rule_type IN ('OFFER_ASSET', 'REQUIRE_ASSET')
                          AND params ->> 'assetKind' = :kind AND active AND params ->> 'maxBytes' IS NOT NULL
                        ORDER BY priority LIMIT 1
                        """)
                .param("category", category).param("kind", kind)
                .query(String.class).optional()
                .map(value -> { try { return Long.parseLong(value); } catch (NumberFormatException e) { return CatalogOrderAssetStorage.MAX_BYTES; } })
                .orElse(CatalogOrderAssetStorage.MAX_BYTES);
    }

    @Transactional
    public Map<String, Object> upload(String id, String assetKind, byte[] bytes, String filename) {
        TenantScope.requirePermissionIfAuthenticated("order:update");
        Map<String, Object> order = load(id, true);
        requireV2(order);
        String kind = string(assetKind).toUpperCase(Locale.ROOT);
        if (!Set.of("DESIGN", "PRE_DELIVERY_PHOTO", "PRINT_REFERENCE", DesignDraftRules.KIND).contains(kind)) throw bad("Select a valid attachment kind");
        boolean draft = DesignDraftRules.KIND.equals(kind);
        if (draft) requireDraftUploadAllowed(order);
        requireAssetEditable(order, kind);
        var validated = storage.validate(bytes, filename, kind, maxUploadBytes(string(order.get("category")), kind));
        for (Map<String, Object> current : assets(id)) {
            if (kind.equals(current.get("assetKind")) && validated.checksumSha256().equals(current.get("checksumSha256"))) return current;
        }
        int draftsBefore = draft ? currentDrafts(id) : 0;
        if (draft && !DesignDraftRules.hasRoom(draftsBefore)) throw new ResponseStatusException(HttpStatus.CONFLICT, DesignDraftRules.LIMIT_MESSAGE);
        String key = storage.store(number(order.get("school_id")), id, validated);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) storage.deleteUncommitted(key);
                }
            });
        }
        // A design or delivery photo is replaced by the next one; print references accumulate, because
        // the prototypes let a school share several images for the same order.
        if (!"PRINT_REFERENCE".equals(kind) && !draft) {
            jdbc.sql("UPDATE catalog.catalog_order_assets SET superseded_at = now() WHERE order_id = :id AND asset_kind = :kind AND superseded_at IS NULL")
                    .param("id", id).param("kind", kind).update();
        }
        Long assetId = jdbc.sql("""
                INSERT INTO catalog.catalog_order_assets(order_id, school_id, asset_kind, storage_key, content_type,
                    size_bytes, checksum_sha256, original_filename, uploaded_by)
                VALUES (:id, :school, :kind, :key, :type, :bytes, :checksum, :filename, :actor) RETURNING id
                """).param("id", id).param("school", order.get("school_id")).param("kind", kind).param("key", key)
                .param("type", validated.contentType()).param("bytes", bytes.length).param("checksum", validated.checksumSha256())
                .param("filename", validated.filename()).param("actor", TenantContext.get().userId()).query(Long.class).single();
        if (draft && DesignDraftRules.emailIntentDue(draftsBefore, draftsBefore + 1)) {
            // Recorded for a future sender; recipients are every active superadmin, resolved when it is sent.
            jdbc.sql("INSERT INTO catalog.order_design_email_intents (order_id, school_id, image_count, created_by) VALUES (:id, :school, :count, :actor)")
                    .param("id", id).param("school", order.get("school_id")).param("count", draftsBefore + 1)
                    .param("actor", TenantContext.get().userId()).update();
        }
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
        if (DesignDraftRules.KIND.equals(kind)) requireDraftUploadAllowed(order);
        requireAssetEditable(order, kind);
        jdbc.sql("UPDATE catalog.catalog_order_assets SET superseded_at = now() WHERE id = :asset AND order_id = :id")
                .param("asset", assetId).param("id", id).update();
        assetChanged(order, kind);
        emit(id);
    }

    /**
     * Creates the tracker for a placed notebook order when placement could not (the tracker was unreachable). Idempotent:
     * the tracker ignores a second create. Superadmin and the order's operators only.
     */
    @Transactional
    public Map<String, Object> ensureTracker(String id) {
        TenantScope.requireOperationsOrSuperAdmin();
        Map<String, Object> order = load(id, true);
        requireV2(order);
        if (!isNotebook(order) || "DRAFT".equals(order.get("status"))) throw bad("Only a placed notebook order has a tracker");
        startTracker(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("trackerPending", Boolean.TRUE.equals(map(load(id, false).get("attributes")).get("trackerPending")));
        return result;
    }

    /**
     * What the tracker's header needs about the order: size and page lines, quantity, delivery date and the school's
     * placement notes (shown as school feedback). Only superadmin and operators scoped to this item may read it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trackerSummary(String id) {
        TenantScope.requireOperationsOrSuperAdmin();
        Map<String, Object> order = load(id, false);
        requireV2(order);
        if (!isNotebook(order)) throw bad("Only a notebook order has a tracker");
        List<Map<String, Object>> orderLines = lines(id);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", id);
        summary.put("schoolId", order.get("school_id"));
        summary.put("schoolName", jdbc.sql("SELECT name FROM tenant_school.schools WHERE id = :id").param("id", order.get("school_id"))
                .query(String.class).optional().orElse(null));
        // The school's contact is the one given at onboarding and nothing else (spec v4); blank means none was given.
        Map<String, Object> contact = jdbc.sql("SELECT contact_email, contact_phone FROM tenant_school.schools WHERE id = :id")
                .param("id", order.get("school_id")).query().listOfRows().stream().findFirst().orElse(Map.of());
        summary.put("schoolContactEmail", string(contact.get("contact_email")).isBlank() ? null : string(contact.get("contact_email")).trim());
        summary.put("schoolContactPhone", string(contact.get("contact_phone")).isBlank() ? null : string(contact.get("contact_phone")).trim());
        summary.put("status", order.get("status"));
        summary.put("customised", customized(map(order.get("order_selections"))));
        summary.put("requiredByDate", order.get("required_by_date") == null ? null : String.valueOf(order.get("required_by_date")));
        summary.put("placedAt", timestamp(order.get("placed_at")));
        summary.put("schoolNotes", order.get("notes"));
        summary.put("orderedCount", orderLines.stream().mapToInt(line -> ((Number) line.get("bookCount")).intValue()).sum());
        summary.put("lines", orderLines.stream().map(line -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.get("lineNo"));
            row.put("selections", line.get("optionSelections"));
            row.put("bookCount", line.get("bookCount"));
            row.put("pageCount", line.get("pageCount"));
            return row;
        }).toList());
        summary.put("trackerPending", Boolean.TRUE.equals(map(order.get("attributes")).get("trackerPending")));
        return summary;
    }

    /**
     * Machine read for the tracker: the order's current design drafts, so a design is only sent or approved against
     * images that exist. There is no user, so the read is explicitly privileged for this one order.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> draftImagesForTracker(String id) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        Map<String, Object> order = jdbc.sql("SELECT category, form_version FROM catalog.catalog_orders WHERE id = :id").param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!NOTEBOOKS.equals(string(order.get("category")))) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return jdbc.sql("""
                SELECT id, content_type AS "contentType", original_filename AS "originalFilename", uploaded_at AS "uploadedAt"
                FROM catalog.catalog_order_assets
                WHERE order_id = :id AND asset_kind = 'DESIGN_DRAFT' AND superseded_at IS NULL ORDER BY uploaded_at, id
                """).param("id", id).query((rs, n) -> {
                    Map<String, Object> draft = new LinkedHashMap<>();
                    draft.put("id", rs.getLong("id"));
                    draft.put("contentType", rs.getString("contentType"));
                    draft.put("originalFilename", rs.getString("originalFilename"));
                    draft.put("uploadedAt", timestamp(rs.getObject("uploadedAt")));
                    return draft;
                }).list();
    }

    /**
     * Machine write from the tracker: the order's status shows the current tracker step. Draft orders keep "Draft" and a
     * cancelled order keeps "Cancelled"; only a placed notebook order follows the tracker.
     */
    @Transactional
    public void applyTrackerStatus(String id, String status) {
        String next = string(status).trim().toUpperCase(Locale.ROOT);
        if (!next.matches("[A-Z][A-Z0-9_]{1,40}")) throw bad("A tracker status is an upper-case code such as PRINTING_PRESS");
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        Map<String, Object> order = jdbc.sql("SELECT * FROM catalog.catalog_orders WHERE id = :id FOR UPDATE").param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!isNotebook(order) || !Integer.valueOf(2).equals(order.get("form_version"))) throw bad("Only a structured notebook order follows a tracker");
        String current = string(order.get("status"));
        if ("DRAFT".equals(current) || "CANCELLED".equals(current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + current.toLowerCase(Locale.ROOT) + " order has no tracker status");
        if (next.equals(current)) return;
        jdbc.sql("UPDATE catalog.catalog_orders SET status = :status, version = version + 1 WHERE id = :id").param("id", id).param("status", next).update();
        order.put("status", next);
        order.put("version", number(order.get("version")) + 1);
        emitOrder(order);
    }

    @Transactional(readOnly = true)
    public AssetContent content(String id, long assetId) {
        TenantScope.requirePermissionIfAuthenticated("order:read");
        load(id, false);
        Map<String, Object> asset = asset(id, assetId, true);
        if (DesignDraftRules.KIND.equals(string(asset.get("asset_kind")))
                && !TenantContext.get().isSuperAdmin() && !TenantContext.get().isOperations()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment not found");
        }
        return new AssetContent(storage.read(string(asset.get("storage_key"))), string(asset.get("content_type")),
                string(asset.get("original_filename")));
    }

    private void assetChanged(Map<String, Object> order, String kind) {
        boolean invalidate = "DESIGN".equals(kind) && customized(map(order.get("order_selections")))
                && !"DRAFT".equals(order.get("status")) && !isNotebook(order);
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
            throw bad("Ordering has changed. Refresh the catalog and use the structured product form");
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
                        attributes, requested_book_count, book_count, requested_page_count, page_count,
                        applied_rules, created_by)
                    VALUES (:order, :school, :line, CAST(:options AS jsonb), CAST(:attributes AS jsonb),
                        :requestedBooks, :books, :requestedPages, :pages, CAST(:rules AS jsonb), :actor)
                    """).param("order", id).param("school", schoolId).param("line", line.get("lineNo"))
                    .param("options", Json.write(line.get("optionSelections")))
                    .param("attributes", Json.write(line.getOrDefault("attributes", Map.of())))
                    .param("requestedBooks", line.get("requestedBookCount"))
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
                SELECT id, line_no, option_selections, attributes, requested_book_count, book_count,
                    requested_page_count, page_count, applied_rules, unit_price_paise, line_total_paise
                FROM catalog.catalog_order_lines WHERE order_id = :id ORDER BY line_no, id
                """).param("id", id).query((rs, n) -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("id", rs.getLong("id"));
                    line.put("lineNo", rs.getInt("line_no"));
                    line.put("optionSelections", map(rs.getString("option_selections")));
                    line.put("attributes", map(rs.getString("attributes")));
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
        TenantScope.requireCatalogOrderReadScope(schoolId, string(order.get("category")));
        requireModule(schoolId);
        return order;
    }

    private void requireModule(Long schoolId) {
        if (moduleGuard != null) moduleGuard.requireModuleEnabled(schoolId, "ORDERS");
    }

    private void scope() {
        TenantContext ctx = TenantContext.get();
        if (ctx.isOperations()) {
            // The row-level policy works per school; the catalogue item is checked in load() once the order is read.
            java.util.Set<Long> admitted = new java.util.LinkedHashSet<>(ctx.operatorSchools());
            admitted.addAll(ctx.scopedSchools());
            jdbc.sql("SELECT set_config('app.operator_schools', :schools, true)")
                    .param("schools", String.join(",", admitted.stream().map(String::valueOf).toList()))
                    .query(String.class).single();
        }
    }

    private void emit(String id) {
        emitOrder(load(id, false));
    }

    private void emitOrder(Map<String, Object> order) {
        String id = string(order.get("id"));
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

    private Map<String, Object> compatibility(NormalisationResult result, Map<String, Object> definition) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderSelections", codes(result.orderSelections()));
        data.put("items", result.lines().stream().map(line -> {
            Map<String, Object> selections = map(line.get("optionSelections"));
            String name = selections.values().stream().map(option -> string(map(option).get("label")))
                    .filter(label -> !label.isBlank()).reduce((a, b) -> a + " / " + b)
                    .orElse(string(map(definition.get("category")).get("label")));
            if (Boolean.TRUE.equals(map(definition.get("category")).get("paged"))) {
                name += " / " + line.get("pageCount") + " printed pages";
            }
            return Map.<String, Object>of("name", name, "qty", line.get("bookCount"));
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

    private static boolean isNotebook(Map<String, Object> order) {
        return NOTEBOOKS.equals(string(order.get("category")));
    }

    private int currentDrafts(String id) {
        return jdbc.sql("SELECT count(*) FROM catalog.catalog_order_assets WHERE order_id = :id AND asset_kind = 'DESIGN_DRAFT' AND superseded_at IS NULL")
                .param("id", id).query(Integer.class).single();
    }

    /**
     * Drafts exist only for placed, customised notebook orders, and only superadmin and operators may touch them. The
     * tracker decides whether this user may do so right now (the assigned operator in step 2, or after a change request).
     */
    private void requireDraftUploadAllowed(Map<String, Object> order) {
        TenantContext ctx = TenantContext.get();
        if (!isNotebook(order) || !customized(map(order.get("order_selections"))) || "DRAFT".equals(order.get("status"))) {
            throw bad("Design drafts apply to placed customised notebook orders only");
        }
        if (!ctx.isSuperAdmin() && !ctx.isOperations()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Design drafts are managed by superadmin and operators");
        }
        if (tracker == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The notebook order tracker is not available");
        TrackerGateway.ImageAccess access;
        try {
            access = tracker.imageAccess(string(order.get("id")), ctx.userId() == null ? 0 : ctx.userId(), ctx.role());
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The notebook order tracker is not available");
        }
        if (!access.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.reason() == null ? "Design drafts cannot be changed now" : access.reason());
        }
    }

    /**
     * Creates the tracker for a freshly placed notebook order. The order is placed either way: when the tracker cannot be
     * reached the order is marked so the tracker is created on the next attempt ({@link #ensureTracker}). When no
     * operator can be chosen the tracker is created unassigned and superadmin assigns it.
     */
    private void startTracker(String id) {
        if (tracker == null) return;
        Map<String, Object> order = load(id, false);
        long schoolId = number(order.get("school_id"));
        Long assignee = null;
        try {
            OperatorResolver.Resolution picked = operators == null ? null : operators.resolve(schoolId, NOTEBOOKS);
            if (picked != null && picked.userId() != null
                    && ("ITEM_SPECIFIC".equals(picked.outcome()) || "SCHOOL_WIDE".equals(picked.outcome()))) assignee = picked.userId();
        } catch (IllegalStateException unavailable) {
            LOG.warn("Order {} placed without an automatic operator: {}", id, unavailable.getMessage());
        }
        try {
            tracker.create(new TrackerGateway.NewTracker(id, schoolId, customized(map(order.get("order_selections"))),
                    lines(id).stream().mapToInt(line -> ((Number) line.get("bookCount")).intValue()).sum(), placedAt(order),
                    jdbc.sql("SELECT time_zone FROM tenant_school.schools WHERE id = :id").param("id", schoolId).query(String.class).single(),
                    TenantContext.get().userId(), assignee,
                    jdbc.sql("SELECT name FROM tenant_school.schools WHERE id = :id").param("id", schoolId).query(String.class).optional().orElse(null)));
            setTrackerPending(id, false);
        } catch (IllegalStateException unavailable) {
            LOG.warn("Order {} placed but its tracker could not be created yet: {}", id, unavailable.getMessage());
            setTrackerPending(id, true);
        }
    }

    private void setTrackerPending(String id, boolean pending) {
        jdbc.sql(pending
                ? "UPDATE catalog.catalog_orders SET attributes = jsonb_set(attributes, '{trackerPending}', 'true') WHERE id = :id"
                : "UPDATE catalog.catalog_orders SET attributes = attributes - 'trackerPending' WHERE id = :id")
                .param("id", id).update();
    }

    private static java.time.OffsetDateTime placedAt(Map<String, Object> order) {
        Object value = order.get("placed_at");
        if (value instanceof java.time.OffsetDateTime offset) return offset;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
        throw new IllegalStateException("A placed order has no placement time");
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
