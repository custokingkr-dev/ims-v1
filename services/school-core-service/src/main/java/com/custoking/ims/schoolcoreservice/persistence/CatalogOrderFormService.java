package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.catalog.domain.DesignDraftRules;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine;
import com.custoking.ims.schoolcoreservice.catalog.domain.ProductFormRuleEngine.NormalisationResult;
import com.custoking.ims.schoolcoreservice.catalog.domain.TitleCardQuantities;
import com.custoking.ims.schoolcoreservice.infrastructure.CatalogOrderAssetStorage;
import com.custoking.ims.schoolcoreservice.infrastructure.TrackerGateway;
import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.function.Supplier;

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
    private NotebookTrackerCreationQueue trackers;
    private TransactionTemplate writes;
    private TransactionTemplate reads;

    /**
     * An order of a category with a workflow template (product_categories.workflow_definition, design D3), placed now,
     * follows its order workflow in operations-service instead of the approval and delivery flow (D5). A notebook order
     * placed before the tracker existed keeps that flow (owner decision D1): see {@link #trackerManaged}. Notebooks also
     * skip the quote; every other workflow item keeps its pricing and may still be quoted.
     */
    private static final String NOTEBOOKS = "NOTEBOOKS";
    /** The first step of every template, which is the status of a freshly placed workflow order. */
    static final String FIRST_TRACKER_STATUS = "ORDER_DETAILS";
    /**
     * A status the workflow may show on an order: a step code of any template, or COMPLETED (design D5). Checked by
     * pattern, so a new template needs no copy of its codes here.
     */
    static final java.util.regex.Pattern WORKFLOW_STATUS = java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    /** The order flow's own statuses, which no template uses as a step code: a push of one is refused, not shown. */
    static final Set<String> ORDER_FLOW_STATUSES = Set.of("DRAFT", "CANCELLED", "DESIGN_APPROVAL", "DESIGN_APPROVED_PROCESSING",
            "PROCESSING", "AWAITING_APPROVAL", "APPROVED", "DELIVERED", "FULFILLED", "REJECTED", "RETURNED");
    /** Finished one way or another: no workflow starts or continues, nothing is quoted. */
    static final Set<String> FINISHED_STATUSES = Set.of("DELIVERED", "FULFILLED", "REJECTED", "RETURNED", "COMPLETED");

    public CatalogOrderFormService(JdbcClient jdbc, ProductCatalogRepository products, ProductFormRuleEngine rules,
                                   CatalogOrderAssetStorage storage, OutboxWriter outbox, ModuleEntitlementGuard moduleGuard) {
        this.jdbc = jdbc;
        this.products = products;
        this.rules = rules;
        this.storage = storage;
        this.outbox = outbox;
        this.moduleGuard = moduleGuard;
    }

    /** Optional: without these, notebook placement queues no tracker and design drafts are unavailable (unit tests). */
    @Autowired(required = false)
    public void configureTracker(TrackerGateway tracker, NotebookTrackerCreationQueue trackers) {
        this.tracker = tracker;
        this.trackers = trackers;
    }

    /**
     * The upload, attachment-removal and tracker-start paths ask a peer (operations' tracker, identity) and must not hold
     * a pooled connection while they do, so they open and close their own transactions around those calls instead of
     * running in one. Without a manager (unit tests that wrap each call in their own transaction) the work joins the
     * caller's transaction.
     */
    @Autowired(required = false)
    public void configureTransactions(PlatformTransactionManager manager) {
        this.writes = new TransactionTemplate(manager);
        this.reads = new TransactionTemplate(manager);
        this.reads.setReadOnly(true);
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
        refuseItemOnlyOperator(order);
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
        refuseItemOnlyOperator(order);
        requireV2(order);
        boolean custom = customized(map(order.get("order_selections")));
        if (trackerManaged(order)) {
            throw bad("This order moves through its order workflow, not through approval or delivery");
        }
        // Placing an order of a category with a template starts its workflow; an order placed before keeps the flow below.
        String definition = "PLACE".equals(action) ? workflowDefinition(string(order.get("category"))) : null;
        boolean startsTracker = definition != null;
        String nextStatus;
        String design = string(order.get("design_status"));
        String approval = string(order.get("superadmin_approval_status"));
        Long approvedAsset = nullableLong(order.get("approved_design_asset_id"));
        switch (action) {
            case "PLACE" -> {
                TenantScope.requirePermissionIfAuthenticated("order:create");
                requireStatus(order, "DRAFT");
                requireStage(order, "ON_PLACE");
                nextStatus = startsTracker ? FIRST_TRACKER_STATUS : custom ? "DESIGN_APPROVAL" : "PROCESSING";
                design = custom && !startsTracker ? "PENDING" : "NOT_REQUIRED";
                approval = startsTracker ? approval : custom ? "NOT_SUBMITTED" : "PENDING";
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
        if ("PLACE".equals(action)) OrderNumberAllocator.mint(jdbc, id);
        if (startsTracker) startTracker(id, number(order.get("school_id")), definition, isNotebook(order));
    }

    /** The template a placement of this category starts, or null when the category has no order workflow (design D3). */
    private String workflowDefinition(String category) {
        return jdbc.sql("SELECT workflow_definition FROM catalog.product_categories WHERE code = :code")
                .param("code", category).query(String.class).optional().orElse(null);
    }

    @Transactional
    public void updateStatus(String id, String status) {
        String normalized = string(status).trim().toUpperCase(Locale.ROOT);
        Map<String, Object> order = load(id, true);
        refuseItemOnlyOperator(order);
        if (trackerManaged(order)) throw bad("This order follows its order workflow; its status changes there");
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
        if ("PLACE".equals(action) && workflowDefinition(string(order.get("category"))) != null) {
            throw bad("Place this order with the place action; after that it follows its order workflow");
        }
        transition(id, action, TenantContext.get().userId());
    }

    @Transactional
    public void returnOrder(String id, String reason) {
        TenantScope.requireSuperAdmin();
        Map<String, Object> order = load(id, true);
        requireV2(order);
        if (trackerManaged(order)) throw bad("Orders on an order workflow are not returned for design approval; Head Approval requests changes there");
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
        if (trackerManaged(order) && isNotebook(order)) throw bad("Notebook orders on their order workflow are not quoted");
        requireVersion(order, request.get("version"));
        if (trackerManaged(order)) {
            // Design D5: the quote stays a commercial record for other items, which superadmin may save until the workflow
            // completes; it no longer gates production.
            String status = string(order.get("status"));
            if ("DRAFT".equals(status) || "CANCELLED".equals(status) || FINISHED_STATUSES.contains(status)) {
                throw bad("This action is unavailable while the order is " + status);
            }
        } else {
            requireStatus(order, "DESIGN_APPROVAL", "DESIGN_APPROVED_PROCESSING", "PROCESSING");
        }
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
                    uploaded_by AS "uploadedBy", uploaded_at AS "uploadedAt", superseded_at AS "supersededAt",
                    superseded_by AS "supersededBy"
                FROM catalog.catalog_order_assets WHERE order_id = :id
                """ + (includeSuperseded ? "" : " AND superseded_at IS NULL") + (internal ? "" : " AND asset_kind <> 'DESIGN_DRAFT'") + " ORDER BY uploaded_at, id")
                .param("id", id).query((rs, rowNum) -> {
                    Map<String, Object> asset = new LinkedHashMap<>();
                    for (String field : List.of("id", "assetKind", "contentType", "sizeBytes", "checksumSha256", "originalFilename", "uploadedBy", "uploadedAt", "supersededAt", "supersededBy")) {
                        asset.put(field, field.endsWith("At") ? timestamp(rs.getObject(field)) : rs.getObject(field));
                    }
                    asset.put("contentUrl", "/api/v1/supply/orders/" + id + "/assets/" + rs.getLong("id") + "/content");
                    return asset;
                }).list();
    }

    /**
     * The size cap a category allows for this kind, read from the same effective rules its order form shows, so the default
     * artwork upload (ProductFormRuleEngine.withDefaultArtwork) is enforced exactly as it is offered. A category that has
     * switched artwork off, by storing its own DESIGN offer inactive with nothing requiring one, takes no artwork at all.
     */
    private long maxUploadBytes(String category, String kind) {
        if (DesignDraftRules.KIND.equals(kind)) return CatalogOrderAssetStorage.DESIGN_DRAFT_MAX_BYTES;
        if (category == null || category.isBlank()) return CatalogOrderAssetStorage.MAX_BYTES;
        List<Map<String, Object>> assetRules = products.effectiveRules(category).stream()
                .filter(rule -> Set.of("OFFER_ASSET", "REQUIRE_ASSET").contains(string(rule.get("ruleType")))
                        && kind.equals(string(map(rule.get("params")).get("assetKind"))))
                .sorted(java.util.Comparator.comparingInt(rule -> rule.get("priority") instanceof Number n ? n.intValue() : 0))
                .toList();
        if ("DESIGN".equals(kind) && assetRules.isEmpty()) throw bad("This product does not take an artwork upload");
        return assetRules.stream().map(rule -> map(rule.get("params")).get("maxBytes"))
                .filter(value -> value != null && !string(value).isBlank()).findFirst()
                .map(value -> { try { return Long.parseLong(string(value)); } catch (NumberFormatException e) { return CatalogOrderAssetStorage.MAX_BYTES; } })
                .orElse(CatalogOrderAssetStorage.MAX_BYTES);
    }

    /**
     * Stores an attachment. A design draft first asks the tracker whether this user may change drafts now, after a short
     * read that has committed and returned its connection, so the call holds no pooled connection and no lock (spec
     * finding 9); everything is checked again under the order lock in the transaction that stores the file. Runs in a
     * transaction of its own: call it with none open.
     */
    public Map<String, Object> upload(String id, String assetKind, byte[] bytes, String filename) {
        TenantScope.requirePermissionIfAuthenticated("order:update");
        String kind = string(assetKind).toUpperCase(Locale.ROOT);
        boolean draft = DesignDraftRules.KIND.equals(kind);
        if (draft) {
            read(() -> {
                Map<String, Object> order = load(id, false);
                requireV2(order);
                requireDraftEligible(order);
                return null;
            });
            requireTrackerDraftAccess(id);
        }
        return write(() -> store(id, kind, draft, bytes, filename));
    }

    private Map<String, Object> store(String id, String kind, boolean draft, byte[] bytes, String filename) {
        Map<String, Object> order = load(id, true);
        if (!draft) refuseItemOnlyOperator(order);
        requireV2(order);
        if (!Set.of("DESIGN", "PRE_DELIVERY_PHOTO", "PRINT_REFERENCE", DesignDraftRules.KIND).contains(kind)) throw bad("Select a valid attachment kind");
        // Spec v4: the order workflow records delivery itself, and every notebook draft goes onto it when it is placed. An
        // order placed before (D1), and an order of a category without a workflow, still takes its photo.
        if ("PRE_DELIVERY_PHOTO".equals(kind) && (trackerManaged(order) || isNotebook(order) && "DRAFT".equals(order.get("status")))) {
            throw bad("Orders on an order workflow record delivery in the workflow");
        }
        if (draft) requireDraftEligible(order);
        requireAssetEditable(order, kind);
        var validated = storage.validate(bytes, filename, kind, maxUploadBytes(string(order.get("category")), kind));
        for (Map<String, Object> current : assets(id)) {
            if (kind.equals(current.get("assetKind")) && validated.checksumSha256().equals(current.get("checksumSha256"))) return current;
        }
        if (draft) {
            // Under the order's row lock (load above): a concurrent upload waits here and then counts this one (design D26).
            Map<String, Object> current = jdbc.sql("""
                    SELECT count(*) AS files, COALESCE(sum(size_bytes), 0) AS bytes FROM catalog.catalog_order_assets
                    WHERE order_id = :id AND asset_kind = 'DESIGN_DRAFT' AND superseded_at IS NULL
                    """).param("id", id).query().singleRow();
            String refusal = DesignDraftRules.refusal(((Number) current.get("files")).intValue(), ((Number) current.get("bytes")).longValue(), bytes.length);
            if (refusal != null) throw new ResponseStatusException(HttpStatus.CONFLICT, refusal);
        }
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
            jdbc.sql("""
                    UPDATE catalog.catalog_order_assets SET superseded_at = now(), superseded_by = :actor
                    WHERE order_id = :id AND asset_kind = :kind AND superseded_at IS NULL
                    """).param("id", id).param("kind", kind).param("actor", TenantContext.get().userId()).update();
        }
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

    /**
     * Removes an attachment. Removing a design draft asks the tracker first, as {@link #upload} does, with no connection
     * held; the removal itself re-checks everything under the order lock. Runs in transactions of its own: call it with
     * none open.
     */
    public void removeAsset(String id, long assetId) {
        TenantScope.requirePermissionIfAuthenticated("order:update");
        boolean draft = Boolean.TRUE.equals(read(() -> {
            Map<String, Object> unlocked = load(id, false);
            boolean isDraft = DesignDraftRules.KIND.equals(string(asset(id, assetId).get("asset_kind")));
            if (isDraft) {
                requireV2(unlocked);
                requireDraftEligible(unlocked);
            } else {
                refuseItemOnlyOperator(unlocked);
            }
            return isDraft;
        }));
        if (draft) requireTrackerDraftAccess(id);
        write(() -> {
            Map<String, Object> order = load(id, true);
            requireV2(order);
            Map<String, Object> asset = asset(id, assetId);
            String kind = string(asset.get("asset_kind"));
            if (DesignDraftRules.KIND.equals(kind)) requireDraftEligible(order);
            else refuseItemOnlyOperator(order);
            requireAssetEditable(order, kind);
            // The remover is kept beside the time (design D40): the Activity history names who took a design file out.
            jdbc.sql("UPDATE catalog.catalog_order_assets SET superseded_at = now(), superseded_by = :actor WHERE id = :asset AND order_id = :id")
                    .param("asset", assetId).param("id", id).param("actor", TenantContext.get().userId()).update();
            assetChanged(order, kind);
            emit(id);
            return null;
        });
    }

    /**
     * Starts the order's workflow by hand when it is still waiting to be created, or when its creation was given up on:
     * queues it with fresh retries, commits, and then, with no transaction open and no row locked, attempts it once.
     * Idempotent: the engine ignores a second create. Superadmin and the order's operators only, for a tracker-managed order
     * that is still open. Runs in a transaction of its own: call it with none open. Read {@link #trackerPending} afterwards.
     */
    public void ensureTracker(String id) {
        TenantScope.requireOperationsOrSuperAdmin();
        write(() -> {
            Map<String, Object> order = load(id, true);
            requireV2(order);
            if (!trackerManaged(order)) throw bad("Only a placed order on an order workflow has one; this order follows order approval");
            String status = string(order.get("status"));
            if (NotebookTrackerCreationQueue.CLOSED_ORDER_STATUSES.contains(status)) {
                throw bad("A " + status.toLowerCase(Locale.ROOT) + " order gets no workflow");
            }
            if (trackers != null) trackers.enqueue(id, number(order.get("school_id")));
            return null;
        });
        attemptTracker(id);
    }

    /**
     * One attempt at the order's tracker, for a caller whose transaction has committed and released its connection (the
     * placing controllers, after a placement). Never throws; the relay trigger retries whatever this leaves.
     */
    public void attemptTracker(String id) {
        if (trackers != null) trackers.attempt(id);
    }

    /**
     * The order a number names (design D14), for a pasted number: matched case-insensitively among the orders the caller
     * may read, through the same checks as every other order read, so any other number is simply not found.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> orderByNumber(String orderNumber) {
        TenantScope.requirePermissionIfAuthenticated("order:read");
        String number = string(orderNumber).strip().toUpperCase(Locale.ROOT);
        if (!com.custoking.ims.schoolcoreservice.catalog.domain.OrderNumbers.LOOKUP.matcher(number).matches()) {
            throw bad("Enter an order number such as NB-DPS_GUR-26-11-13-0042");
        }
        scope();
        String id = jdbc.sql("SELECT id FROM catalog.catalog_orders WHERE order_number = :number").param("number", number)
                .query(String.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        Map<String, Object> order = load(id, false);
        Map<String, Object> found = new LinkedHashMap<>();
        found.put("id", order.get("id"));
        found.put("orderNumber", order.get("order_number"));
        return found;
    }

    /** Whether the order's tracker is still waiting to be created. Superadmin and the order's operators only. */
    @Transactional(readOnly = true)
    public boolean trackerPending(String id) {
        TenantScope.requireOperationsOrSuperAdmin();
        return Boolean.TRUE.equals(map(load(id, false).get("attributes")).get("trackerPending"));
    }

    /**
     * The order workflow's header (design §3.5, D24, D25): the order and its number, the item, the school and its contact,
     * the placed lines and their quantities, and, for notebooks, the title-card quantities computed from those lines.
     * Only superadmin and operators scoped to this item may read it, for an order on an order workflow.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> trackerSummary(String id) {
        TenantScope.requireOperationsOrSuperAdmin();
        Map<String, Object> order = load(id, false);
        requireV2(order);
        // A notebook order placed before the tracker (owner D1) keeps the header it always had.
        if (!trackerManaged(order) && !isNotebook(order)) throw bad("Only an order on an order workflow has a workflow summary");
        List<Map<String, Object>> orderLines = lines(id);
        String category = string(order.get("category"));
        Map<String, Object> item = jdbc.sql("SELECT label, order_code FROM catalog.product_categories WHERE code = :code")
                .param("code", category).query().listOfRows().stream().findFirst().orElse(Map.of());
        // The school's contact is the one given at onboarding and nothing else (spec v4); blank means none was given.
        Map<String, Object> school = jdbc.sql("SELECT name, city, contact_email, contact_phone FROM tenant_school.schools WHERE id = :id")
                .param("id", order.get("school_id")).query().listOfRows().stream().findFirst().orElse(Map.of());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", id);
        summary.put("orderNumber", order.get("order_number"));
        summary.put("category", category);
        summary.put("itemCode", item.get("order_code"));
        summary.put("itemLabel", item.get("label") == null ? category : string(item.get("label")));
        summary.put("schoolId", order.get("school_id"));
        summary.put("schoolName", blankToNull(school.get("name")));
        summary.put("schoolCity", blankToNull(school.get("city")));
        summary.put("schoolContactEmail", blankToNull(school.get("contact_email")));
        summary.put("schoolContactPhone", blankToNull(school.get("contact_phone")));
        summary.put("status", order.get("status"));
        summary.put("customised", designStepsApply(order));
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
        summary.put("quantities", quantities(map(order.get("form_snapshot")), orderLines));
        summary.put("titleCards", isNotebook(order) ? titleCards(orderLines) : null);
        summary.put("trackerPending", Boolean.TRUE.equals(map(order.get("attributes")).get("trackerPending")));
        return summary;
    }

    /**
     * Design D25: each placed line's quantity (its book_count, the line quantity on every v2 form) with the label of its
     * main selection, and the line's other selections as details. Selections and typed values are taken in the order the
     * form lists its groups, so the main one is the form's first: a notebook's size, a flex's type, a certificate's category.
     */
    static Map<String, Object> quantities(Map<String, Object> form, List<Map<String, Object>> orderLines) {
        List<Map<String, Object>> groups = new ArrayList<>();
        if (form.get("groups") instanceof List<?> listed) {
            for (Object group : listed) if (group instanceof Map<?, ?>) groups.add(map(group));
        }
        int total = 0;
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> line : orderLines) {
            Map<String, Object> selections = map(line.get("optionSelections"));
            Map<String, Object> typed = map(line.get("attributes"));
            List<String> parts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> group : groups) {
                String code = string(group.get("code"));
                if (!seen.add(code)) continue;
                if (selections.get(code) != null) {
                    parts.add(selectionLabel(selections.get(code)));
                } else if (typed.get(code) != null && !string(typed.get(code)).isBlank()) {
                    String unit = string(group.get("unit")).trim();
                    String value = string(typedText(typed.get(code))) + (unit.isEmpty() ? "" : " " + unit);
                    parts.add(parts.isEmpty() ? value : string(group.get("label")) + ": " + value);
                }
            }
            selections.forEach((code, value) -> { if (seen.add(code) && value != null) parts.add(selectionLabel(value)); });
            parts.removeIf(String::isBlank);
            int quantity = ((Number) line.get("bookCount")).intValue();
            total += quantity;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", line.get("lineNo"));
            row.put("label", parts.isEmpty() ? "Line " + line.get("lineNo") : parts.getFirst());
            row.put("details", parts.size() < 2 ? null : String.join(" · ", parts.subList(1, parts.size())));
            row.put("quantity", quantity);
            lines.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("lines", lines);
        return result;
    }

    /** Design D24: the notebook title-card table, computed from the placed lines (they never change, so no snapshot). */
    private static TitleCardQuantities.Result titleCards(List<Map<String, Object>> orderLines) {
        List<TitleCardQuantities.Line> lines = new ArrayList<>();
        for (Map<String, Object> line : orderLines) {
            lines.add(new TitleCardQuantities.Line(((Number) line.get("lineNo")).intValue(), map(line.get("optionSelections")),
                    ((Number) line.get("bookCount")).intValue()));
        }
        return TitleCardQuantities.compute(lines);
    }

    private static String selectionLabel(Object selection) {
        if (selection instanceof Map<?, ?> snapshot) {
            Object label = snapshot.get("label");
            return label == null || string(label).isBlank() ? string(snapshot.get("code")) : string(label);
        }
        return string(selection);
    }

    /** Whether the workflow runs the design steps: a wholesale notebook skips them (design D7); every other item takes them. */
    private static boolean designStepsApply(Map<String, Object> order) {
        return !isNotebook(order) || customized(map(order.get("order_selections")));
    }

    private static String blankToNull(Object value) {
        return value == null || string(value).isBlank() ? null : string(value).trim();
    }

    /**
     * Machine read for the order workflow engine: the order's current design files, so the Designing Activity gate counts
     * files that exist. There is no user, so the read is explicitly privileged for this one order. Any order on an order
     * workflow has them (and a notebook order, as before); any other order is not found.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> draftImagesForTracker(String id) {
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        Map<String, Object> order = jdbc.sql("SELECT category, form_version, attributes FROM catalog.catalog_orders WHERE id = :id").param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!trackerManaged(order) && !NOTEBOOKS.equals(string(order.get("category")))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return jdbc.sql("""
                SELECT id, content_type AS "contentType", original_filename AS "originalFilename", uploaded_at AS "uploadedAt",
                    size_bytes AS "sizeBytes"
                FROM catalog.catalog_order_assets
                WHERE order_id = :id AND asset_kind = 'DESIGN_DRAFT' AND superseded_at IS NULL ORDER BY uploaded_at, id
                """).param("id", id).query((rs, n) -> {
                    Map<String, Object> draft = new LinkedHashMap<>();
                    draft.put("id", rs.getLong("id"));
                    draft.put("contentType", rs.getString("contentType"));
                    draft.put("originalFilename", rs.getString("originalFilename"));
                    draft.put("uploadedAt", timestamp(rs.getObject("uploadedAt")));
                    draft.put("sizeBytes", rs.getLong("sizeBytes"));
                    return draft;
                }).list();
    }

    /**
     * What the workflow reports with a status (design §3.5): its template, and the current step's position and title with
     * the path's size and how much of it is done. All optional; stored as {@code attributes.workflowStep} for lists (D5).
     */
    public record WorkflowStepReport(String definitionId, Integer stepOrder, String stepTitle, Integer totalSteps, Integer completedSteps) {
        public static final WorkflowStepReport NONE = new WorkflowStepReport(null, null, null, null, null);

        boolean complete() {
            return stepOrder != null && stepTitle != null && !stepTitle.isBlank() && totalSteps != null && completedSteps != null;
        }
    }

    @Transactional
    public void applyTrackerStatus(String id, String status, Long trackerVersion) {
        applyTrackerStatus(id, status, trackerVersion, WorkflowStepReport.NONE);
    }

    /**
     * Machine write from the order workflow engine: the order's status shows the current step's code, or COMPLETED (design
     * D5), accepted by pattern for every template. Draft orders keep "Draft" and a cancelled order keeps "Cancelled"; only
     * a placed, tracker-managed order follows its workflow. {@code trackerVersion} orders the engine's pushes, which are
     * retried and can arrive late or twice: a version no newer than the order's {@code attributes.trackerStatusVersion} is
     * already reflected and changes nothing. A report of the step is stored with the version that carried it.
     */
    @Transactional
    public void applyTrackerStatus(String id, String status, Long trackerVersion, WorkflowStepReport step) {
        String next = string(status).trim().toUpperCase(Locale.ROOT);
        if (!WORKFLOW_STATUS.matcher(next).matches() || ORDER_FLOW_STATUSES.contains(next)) {
            throw bad("A workflow status is a step code such as PRINTING_PRESS, or COMPLETED");
        }
        if (trackerVersion == null || trackerVersion < 0) throw bad("A trackerVersion of 0 or more is required");
        WorkflowStepReport report = step == null ? WorkflowStepReport.NONE : step;
        if (report.stepTitle() != null && report.stepTitle().length() > 200) throw bad("A step title is at most 200 characters");
        if (report.definitionId() != null && !WORKFLOW_STATUS.matcher(report.definitionId().strip()).matches()) {
            throw bad("A definitionId is a template id such as FLIER_ORDER_WORKFLOW");
        }
        jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        Map<String, Object> order = jdbc.sql("SELECT * FROM catalog.catalog_orders WHERE id = :id FOR UPDATE").param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!Integer.valueOf(2).equals(order.get("form_version"))) throw bad("Only a structured order follows an order workflow");
        String current = string(order.get("status"));
        if ("DRAFT".equals(current) || "CANCELLED".equals(current)) throw new ResponseStatusException(HttpStatus.CONFLICT, "A " + current.toLowerCase(Locale.ROOT) + " order has no workflow status");
        if (!trackerManaged(order)) throw bad("This order was not placed on an order workflow and follows order approval");
        // A push, even a late one, proves the workflow exists: a creation still queued for it (pending, or given up on) is done,
        // unless the order is open and numbered. Its queued create also carries the number to that workflow (design D13), and
        // an engine deployed before order numbers still pushes statuses while it refuses those creates; the row stays until a
        // create succeeds. A closed order's row is dropped by the queue's next claim in any case.
        Object orderNumber = order.get("order_number");
        boolean numberStillToSend = orderNumber != null && !string(orderNumber).isBlank()
                && !NotebookTrackerCreationQueue.CLOSED_ORDER_STATUSES.contains(next);
        if (trackers != null && !numberStillToSend) trackers.done(id);
        if (Boolean.TRUE.equals(map(order.get("attributes")).get("trackerPending"))) {
            jdbc.sql("UPDATE catalog.catalog_orders SET attributes = attributes || jsonb_build_object('trackerPending', false) WHERE id = :id")
                    .param("id", id).update();
        }
        if (map(order.get("attributes")).get("trackerStatusVersion") instanceof Number applied && trackerVersion <= applied.longValue()) return;
        boolean changed = !next.equals(current);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("trackerStatusVersion", trackerVersion);
        if (report.complete()) {
            Map<String, Object> workflowStep = new LinkedHashMap<>();
            workflowStep.put("order", report.stepOrder());
            workflowStep.put("title", report.stepTitle().strip());
            workflowStep.put("total", report.totalSteps());
            workflowStep.put("completed", report.completedSteps());
            attributes.put("workflowStep", workflowStep);
        }
        if (report.definitionId() != null && !report.definitionId().isBlank()) attributes.put("workflowDefinition", report.definitionId().strip());
        // A newer version that repeats the shown status still records its version, so an older push cannot follow it.
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET status = :status, version = version + CASE WHEN :changed THEN 1 ELSE 0 END,
                    attributes = attributes || CAST(:attributes AS jsonb)
                WHERE id = :id
                """).param("id", id).param("status", next).param("changed", changed).param("attributes", Json.write(attributes)).update();
        if (!changed) return;
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
        // Served under a name whose extension matches the stored type, also for a file stored before uploads were named so.
        return new AssetContent(storage.read(string(asset.get("storage_key"))), string(asset.get("content_type")),
                CatalogOrderAssetStorage.typedFilename(string(asset.get("original_filename")), string(asset.get("content_type"))));
    }

    private void assetChanged(Map<String, Object> order, String kind) {
        boolean invalidate = "DESIGN".equals(kind) && customized(map(order.get("order_selections")))
                && !"DRAFT".equals(order.get("status")) && !trackerManaged(order);
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

    /**
     * Re-checks the saved order against its form snapshot. Each line is rebuilt as a client sends it: the codes of its option
     * selections plus its typed values (length, size, GSM), which are stored apart from them in the line's attributes, so a
     * typed field is checked as saved rather than read as missing.
     */
    private void requireStage(Map<String, Object> order, String stage) {
        Map<String, Object> definition = map(order.get("form_snapshot"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("orderSelections", codes(map(order.get("order_selections"))));
        raw.put("lines", lines(string(order.get("id"))).stream().map(line -> {
            Map<String, Object> value = new LinkedHashMap<>();
            Map<String, Object> selections = codes(map(line.get("optionSelections")));
            map(line.get("attributes")).forEach((code, typed) -> selections.put(code, typedText(typed)));
            value.put("selections", selections);
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

    /**
     * One order as a feature on the order screen reads it (the product production workflow), through the checks every order
     * read here starts with: the operator's row-level scope, the item-only operator rule (D2), the read scope and the
     * school's ORDERS module. Call it inside a transaction: an operator's row-level scope is transaction-local, and what the
     * caller reads next relies on it. Returns the order's row.
     */
    public Map<String, Object> readableOrder(String id) {
        return load(id, false);
    }

    private Map<String, Object> load(String id, boolean lock) {
        scope();
        Map<String, Object> order = jdbc.sql("SELECT * FROM catalog.catalog_orders WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query().listOfRows().stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        long schoolId = number(order.get("school_id"));
        // Before the scope check, whose refusal is a 403: for an item-only operator that refusal would confirm the order.
        hideFromItemOnlyOperator(order);
        TenantScope.requireCatalogOrderReadScope(schoolId, string(order.get("category")));
        requireModule(schoolId);
        return order;
    }

    /**
     * Owner decision D2: an operator who reaches the order's school only through an item scope works that item's orders in
     * the tracker and nothing else. Any other order there (another item's, one placed before the tracker (D1), or one not
     * placed yet) does not exist for them: "not found", like an order out of scope, because order ids are sequential and a
     * refusal would confirm one. Every user's read and write of an order here starts with {@link #load}, so this one check
     * covers them all; the tracker's own machine calls do not come through here.
     */
    private static void hideFromItemOnlyOperator(Map<String, Object> order) {
        TenantContext ctx = TenantContext.get();
        long schoolId = number(order.get("school_id"));
        if (ctx.isOperations() && !ctx.operatorSchools().contains(schoolId)
                && !(trackerManaged(order) && ctx.operatesItem(schoolId, string(order.get("category"))))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
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
        outbox.append("catalog-order.upserted.v1", "CatalogOrderUpserted:" + id, "CatalogOrder", id, number(order.get("school_id")),
                upsertedPayload(jdbc, order));
    }

    /**
     * The {@code catalog-order.upserted.v1} payload of an order row as {@code SELECT *} reads it. Shared with the creation
     * queue, which tells reporting about the numbers it mints when it repairs an order.
     */
    static Map<String, Object> upsertedPayload(JdbcClient jdbc, Map<String, Object> order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String[] entry : new String[][]{{"id","id"},{"schoolId","school_id"},{"category","category"},{"status","status"},
                {"totalAmount","total_amount"},{"superadminApprovalStatus","superadmin_approval_status"},{"vendorPaidAt","vendor_paid_at"},
                {"createdAt","created_at"},{"requiredByDate","required_by_date"},{"designStatus","design_status"},{"notes","notes"},
                {"formVersion","form_version"},{"pricingStatus","pricing_status"},{"version","version"},{"orderNumber","order_number"}}) {
            Object value = order.get(entry[1]);
            if (value instanceof java.sql.Timestamp timestamp) value = timestamp.toInstant().toString();
            if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
            payload.put(entry[0], value);
        }
        // Additive (design §3.6): the item code of the order's category, null without one.
        payload.put("itemCode", jdbc.sql("SELECT order_code FROM catalog.product_categories WHERE code = :code")
                .param("code", string(order.get("category"))).query(String.class).optional().orElse(null));
        return payload;
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

    /**
     * Owner decision D2: an operator who reaches this order's school only through an item scope (no school-wide assignment
     * there) works the order in its order workflow and nothing else. What the workflow needs stays open to them on a
     * workflow order: reading the order, its form and attachments, the workflow summary, starting a pending workflow, and
     * design files (which the workflow itself gates). Every other write, the old order flow's, is refused here. Any other
     * order is not found for them at all ({@link #hideFromItemOnlyOperator}).
     */
    private static void refuseItemOnlyOperator(Map<String, Object> order) {
        TenantContext ctx = TenantContext.get();
        if (ctx.isOperations() && !ctx.operatorSchools().contains(number(order.get("school_id")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An item-specific operator works this order in its order workflow only");
        }
    }

    /**
     * Set at placement for an order of a category with a workflow template (catalog V38 set it on the product orders that
     * were open when the PR #380 workflow was retired); an order without it keeps the approval flow.
     */
    private static boolean trackerManaged(Map<String, Object> order) {
        return Boolean.TRUE.equals(map(order.get("attributes")).get("trackerManaged"));
    }

    /**
     * Design files (design D26) exist for placed orders on an order workflow whose path has Designing Activity: every item
     * but a wholesale notebook (catalog V23). Only superadmin and operators may touch them. Checked again under the order
     * lock, without asking the workflow.
     */
    private void requireDraftEligible(Map<String, Object> order) {
        String status = string(order.get("status"));
        if (!trackerManaged(order) || !designStepsApply(order) || "DRAFT".equals(status) || "CANCELLED".equals(status)) {
            throw bad("Design files apply to placed orders on an order workflow that has a Designing Activity step; wholesale notebooks take none");
        }
        TenantContext ctx = TenantContext.get();
        if (!ctx.isSuperAdmin() && !ctx.isOperations()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Design files are managed by superadmin and operators");
        }
    }

    /**
     * The workflow decides whether this user may change design files right now (Designing Activity's assignee while it is
     * active, or after Head Approval requested changes). The caller has already checked, in a read that committed, that the
     * order takes design files. The workflow is asked with no transaction open, so a slow peer holds neither a pooled
     * connection nor the order lock; asking it from inside a transaction is a programming error.
     */
    private void requireTrackerDraftAccess(String id) {
        if (TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("The order workflow must be asked about design files with no transaction open");
        }
        if (tracker == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The order workflow is not available");
        TenantContext ctx = TenantContext.get();
        TrackerGateway.ImageAccess access;
        try {
            access = tracker.imageAccess(id, ctx.userId() == null ? 0 : ctx.userId(), ctx.role());
        } catch (IllegalStateException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The order workflow is not available");
        }
        if (!access.allowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, access.reason() == null ? "Design files cannot be changed now" : access.reason());
        }
    }

    /**
     * Makes a freshly placed order tracker-managed under the template its category runs, and queues its workflow, all in the
     * placing transaction; no peer is called in it. The placing controller attempts the workflow once that transaction has
     * committed and released its connection ({@link #attemptTracker}), and the relay trigger retries whatever that attempt
     * leaves. A notebook draft saved before the tracker existed still carries a pending quote, which placement settles:
     * notebook workflow orders are not quoted (owner decision D1). Other items keep their pricing (design D5).
     */
    private void startTracker(String id, long schoolId, String definition, boolean notebook) {
        jdbc.sql("""
                UPDATE catalog.catalog_orders SET pricing_status = CASE WHEN :notebook THEN 'NOT_APPLICABLE' ELSE pricing_status END,
                    attributes = attributes || jsonb_build_object('trackerManaged', true, 'trackerPending', true,
                        'workflowDefinition', CAST(:definition AS text))
                WHERE id = :id
                """).param("id", id).param("notebook", notebook).param("definition", definition).update();
        if (trackers != null) trackers.enqueue(id, schoolId);
    }
    /** Runs {@code work} in a read-only transaction of its own, or in the caller's when no manager is configured. */
    private <T> T read(Supplier<T> work) {
        return reads == null ? work.get() : reads.execute(status -> work.get());
    }

    /** Runs {@code work} in a transaction of its own (joining one already open), or in the caller's when no manager is configured. */
    private <T> T write(Supplier<T> work) {
        return writes == null ? work.get() : writes.execute(status -> work.get());
    }

    private static boolean customized(Map<String, Object> selections) {
        Object selection = selections.get("CUSTOMIZATION");
        return "CUSTOMIZED".equals(selection instanceof Map<?, ?> ? map(selection).get("code") : selection);
    }

    /** A stored typed value as the text a client sends: a number read back from JSON is written out plainly, never as 1.0E7. */
    private static Object typedText(Object value) {
        return value instanceof Number number ? new java.math.BigDecimal(number.toString()).toPlainString() : value;
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
