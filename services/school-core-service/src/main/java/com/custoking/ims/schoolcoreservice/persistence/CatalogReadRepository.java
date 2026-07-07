package com.custoking.ims.schoolcoreservice.persistence;

import com.custoking.ims.schoolcoreservice.outbox.OutboxWriter;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CatalogReadRepository {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public CatalogReadRepository(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public List<CatalogItemRow> items() {
        return jdbc.sql("""
                SELECT id, title, subtitle, icon, order_type, sample_amount
                FROM catalog.catalog_items
                ORDER BY id
                """).query(CatalogItemRow.class).list();
    }

    public List<Map<String, Object>> categories() {
        return List.of(
                row("id", "UNIFORMS", "emoji", "\uD83D\uDC55", "label", "Uniforms", "orderType", "Recurring",
                        "description", "Full uniform sets by size and house"),
                row("id", "NOTEBOOKS", "emoji", "\uD83D\uDCD8", "label", "Notebooks", "orderType", "Recurring",
                        "description", "Ruled, unruled, graph and custom books"),
                row("id", "STATIONERY", "emoji", "\u270F\uFE0F", "label", "Stationery", "orderType", "Recurring",
                        "description", "Student stationery kits and classroom essentials"),
                row("id", "IDCARDS", "emoji", "\uD83E\uDEAA", "label", "ID Cards", "orderType", "One-time",
                        "description", "Student and staff ID cards, lanyards and holders"),
                row("id", "HOUSEKEEPING", "emoji", "\uD83E\uDDF9", "label", "Housekeeping", "orderType", "Service",
                        "description", "Cleaning consumables and support services"),
                row("id", "EVENTS", "emoji", "\uD83C\uDF89", "label", "Events", "orderType", "One-time",
                        "description", "Trophies, certificates, banners and event kits"),
                row("id", "HEALTH", "emoji", "\uD83E\uDE7A", "label", "Health", "orderType", "Service",
                        "description", "Infirmary essentials and annual health services")
        );
    }

    public List<CatalogOrderRow> orders(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder(orderSelect()).append(" WHERE 1=1");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY co.created_at DESC NULLS LAST LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(CatalogOrderRow.class).list();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> ordersPage(Long schoolId, String status, int page, int size) {
        allowCrossSchoolReadForOperations();
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 200));

        StringBuilder filter = new StringBuilder(" WHERE 1=1");
        if (schoolId != null) filter.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) filter.append(" AND status = :status");

        var countSpec = jdbc.sql("SELECT count(*) FROM catalog.catalog_orders" + filter);
        if (schoolId != null) countSpec = countSpec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) countSpec = countSpec.param("status", status);
        long totalElements = countSpec.query(Long.class).single();

        StringBuilder sql = new StringBuilder(orderSelect()).append(filter)
                .append(" ORDER BY co.created_at DESC NULLS LAST, co.id DESC LIMIT :size OFFSET :offset");
        var itemSpec = jdbc.sql(sql.toString())
                .param("size", safeSize)
                .param("offset", (long) safePage * safeSize);
        if (schoolId != null) itemSpec = itemSpec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) itemSpec = itemSpec.param("status", status);
        List<CatalogOrderRow> content = itemSpec.query(CatalogOrderRow.class).list();

        int totalPages = (int) Math.ceil(totalElements / (double) safeSize);
        return row(
                "content", content,
                "page", safePage,
                "size", safeSize,
                "totalElements", totalElements,
                "totalPages", totalPages);
    }

    @Transactional(readOnly = true)
    public Optional<CatalogOrderRow> order(String id) {
        allowCrossSchoolReadForOperations();
        return orderQuery(id);
    }

    /**
     * Non-bypassing order lookup for internal/write flows. RLS stays enforced here, so an
     * OPERATIONS user (who gets a cross-school read bypass only via {@link #order(String)},
     * {@link #ordersPage}, {@link #orderStats}) cannot resolve — and then mutate — another
     * school's order through a write path that begins with a lookup.
     */
    private Optional<CatalogOrderRow> orderQuery(String id) {
        return jdbc.sql(orderSelect() + " WHERE co.id = :id")
                .param("id", id)
                .query(CatalogOrderRow.class)
                .optional();
    }

    public List<PendingCatalogOrderRow> pendingApprovalOrders(int limit) {
        return jdbc.sql("""
                SELECT co.id, co.category, co.order_data, co.subtotal, co.gst, co.total_amount, co.status,
                       co.class_group, co.logo_on_uniform, co.notebook_cover_logo, co.notebook_delivery_mode,
                       co.notebook_spine_name, co.stationery_pack_type, co.event_name, co.event_date,
                       co.design_status, co.superadmin_approval_status, co.required_by_date,
                       co.estimated_delivery, co.placed_by, co.placed_at, co.notes, co.created_at, co.school_id,
                       co.version, co.created_by, co.updated_by, co.vendor_paid_at, co.vendor_paid_by,
                       co.vendor_payment_notes, s.name AS school_name
                FROM catalog.catalog_orders co
                LEFT JOIN tenant_school.schools s ON s.id = co.school_id
                WHERE UPPER(co.status) IN ('DESIGN_APPROVED_PROCESSING', 'PROCESSING')
                  AND UPPER(co.superadmin_approval_status) = 'PENDING'
                ORDER BY co.created_at DESC NULLS LAST
                LIMIT :limit
                """)
                .param("limit", Math.max(1, Math.min(limit, 500)))
                .query(PendingCatalogOrderRow.class)
                .list();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> orderStats(Long schoolId) {
        allowCrossSchoolReadForOperations();
        if (schoolId == null) {
            return Map.of(
                    "totalOrders", count("SELECT count(*) FROM catalog.catalog_orders"),
                    "pendingApproval", count("SELECT count(*) FROM catalog.catalog_orders WHERE superadmin_approval_status = 'PENDING' OR status IN ('Pending', 'Pending approval')"),
                    "approved", count("SELECT count(*) FROM catalog.catalog_orders WHERE superadmin_approval_status = 'APPROVED' OR status = 'Approved'"),
                    "rejected", count("SELECT count(*) FROM catalog.catalog_orders WHERE superadmin_approval_status = 'REJECTED' OR status = 'Rejected'"),
                    "gmv", sum("SELECT COALESCE(SUM(total_amount), 0) FROM catalog.catalog_orders"));
        }
        return Map.of(
                "totalOrders", count("SELECT count(*) FROM catalog.catalog_orders WHERE school_id = :schoolId", schoolId),
                "pendingApproval", count("SELECT count(*) FROM catalog.catalog_orders WHERE school_id = :schoolId AND (superadmin_approval_status = 'PENDING' OR status IN ('Pending', 'Pending approval'))", schoolId),
                "approved", count("SELECT count(*) FROM catalog.catalog_orders WHERE school_id = :schoolId AND (superadmin_approval_status = 'APPROVED' OR status = 'Approved')", schoolId),
                "rejected", count("SELECT count(*) FROM catalog.catalog_orders WHERE school_id = :schoolId AND (superadmin_approval_status = 'REJECTED' OR status = 'Rejected')", schoolId),
                "gmv", sum("SELECT COALESCE(SUM(total_amount), 0) FROM catalog.catalog_orders WHERE school_id = :schoolId", schoolId));
    }

    public List<SupplyOrderRow> supplyOrders(int limit) {
        return jdbc.sql("""
                SELECT code, title, category, items, amount, status, order_date, action_label
                FROM catalog.supply_orders
                ORDER BY order_date DESC NULLS LAST, code
                LIMIT :limit
                """).param("limit", Math.max(1, Math.min(limit, 500)))
                .query(SupplyOrderRow.class)
                .list();
    }

    public List<AnnualPlanItemRow> annualPlanItems(Long schoolId, String academicYearId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, term_name, category, description, quantity, estimated_amount,
                       status, linked_order_id, created_at, school_id, academic_year_id
                FROM catalog.annual_plan_items
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (academicYearId != null && !academicYearId.isBlank()) sql.append(" AND academic_year_id = :academicYearId");
        sql.append(" ORDER BY created_at DESC NULLS LAST LIMIT :limit");

        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (academicYearId != null && !academicYearId.isBlank()) spec = spec.param("academicYearId", academicYearId);
        return spec.query(AnnualPlanItemRow.class).list();
    }

    public Map<String, Object> annualPlan(Long schoolId) {
        String yearLabel = jdbc.sql("""
                SELECT label FROM tenant_school.academic_years
                WHERE active = true
                ORDER BY id DESC
                LIMIT 1
                """).query(String.class).optional().orElse("Current Academic Year");
        List<AnnualPlanItemRow> items = jdbc.sql("""
                SELECT id, term_name, category, description, quantity, estimated_amount,
                       status, linked_order_id, created_at, school_id, academic_year_id
                FROM catalog.annual_plan_items
                WHERE school_id = :schoolId
                ORDER BY term_name ASC NULLS LAST, created_at ASC NULLS LAST
                """)
                .param("schoolId", schoolId)
                .query(AnnualPlanItemRow.class)
                .list();
        long studentCount = count("""
                SELECT count(*)
                FROM student.students
                WHERE school_id = :schoolId
                  AND deleted_at IS NULL
                """, schoolId);
        long total = items.stream().mapToLong(item -> item.estimatedAmount() == null ? 0 : item.estimatedAmount()).sum();
        long ordered = items.stream().filter(item -> "ORDERED".equals(item.status())).count();
        int completion = items.isEmpty() ? 0 : (int) Math.round((ordered * 100.0) / items.size());
        long pending = items.stream().filter(item -> !"ORDERED".equals(item.status())).count();

        return row("completionPercent", completion,
                "academicYears", List.of(yearLabel),
                "terms", items.stream().map(this::annualPlanItemMap).toList(),
                "summary", row("total", total,
                        "pendingCount", pending,
                        "perStudentCost", total / Math.max(1, studentCount),
                        "vsLastYearPercent", 12));
    }

    public List<AnnualPlanEntryRow> annualPlanEntries() {
        return jdbc.sql("""
                SELECT id, term_name, category, status, quantity, amount
                FROM catalog.annual_plan_entries
                ORDER BY id
                """).query(AnnualPlanEntryRow.class).list();
    }

    @Transactional
    public CatalogOrderRow createOrder(Map<String, Object> request) {
        Long schoolId = longObj(request.get("schoolId"));
        if (schoolId != null && schoolId > 0) {
            requireSchool(schoolId);
        } else {
            schoolId = null;
        }
        String category = str(request.get("category"), "STATIONERY").toUpperCase(Locale.ROOT);
        Map<String, Object> orderData = mapValue(request.get("orderData"));
        if (orderData.isEmpty()) {
            orderData = row("title", str(request.get("category"), "Order"), "items", str(request.get("items"), "1 unit"));
        }
        long subtotal = longNum(request.get("subtotal"), longNum(request.get("amount"), 0));
        long gst = longNum(request.get("gst"), 0);
        long totalAmount = longNum(request.get("totalAmount"), subtotal + gst);
        String status = str(request.get("status"), "DRAFT").toUpperCase(Locale.ROOT);
        boolean designRequired = requiresDesignApproval(category);
        boolean superadminRequired = requiresSuperadminApproval(category);
        OffsetDateTime now = OffsetDateTime.now();
        String id = trimToNull(str(firstPresent(request, "id", "orderId"), ""));
        if (id == null) id = nextCatalogOrderId();

        jdbc.sql("""
                INSERT INTO catalog.catalog_orders (
                    id, school_id, category, order_data, subtotal, gst, total_amount, status,
                    required_by_date, notes, estimated_delivery, placed_by, placed_at,
                    design_status, superadmin_approval_status, class_group, logo_on_uniform,
                    notebook_cover_logo, notebook_delivery_mode, notebook_spine_name,
                    stationery_pack_type, event_name, event_date, created_at, version
                ) VALUES (
                    :id, :schoolId, :category, CAST(:orderData AS text), :subtotal, :gst, :totalAmount, :status,
                    :requiredByDate, :notes, :estimatedDelivery, :placedBy, :placedAt,
                    :designStatus, :superadminApprovalStatus, :classGroup, :logoOnUniform,
                    :notebookCoverLogo, :notebookDeliveryMode, :notebookSpineName,
                    :stationeryPackType, :eventName, :eventDate, :createdAt, 0
                )
                """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("category", category)
                .param("orderData", Json.write(orderData))
                .param("subtotal", subtotal)
                .param("gst", gst)
                .param("totalAmount", totalAmount)
                .param("status", status)
                .param("requiredByDate", localDate(request.get("requiredByDate")))
                .param("notes", trimToNull(str(request.get("notes"), "")))
                .param("estimatedDelivery", defaultEstimatedDelivery(category))
                .param("placedBy", longObj(request.get("actorId")))
                .param("placedAt", "DRAFT".equalsIgnoreCase(status) ? null : now)
                .param("designStatus", designRequired ? "PENDING" : "NOT_REQUIRED")
                .param("superadminApprovalStatus", superadminRequired ? "NOT_SUBMITTED" : "NOT_REQUIRED")
                .param("classGroup", trimToNull(str(firstPresent(orderData, "classGroup", "class_group"), "")))
                .param("logoOnUniform", trimToNull(str(firstPresent(orderData, "logoOnUniform", "logo_on_uniform"), "")))
                .param("notebookCoverLogo", trimToNull(str(firstPresent(orderData, "coverLogo", "notebookCoverLogo"), "")))
                .param("notebookDeliveryMode", trimToNull(str(firstPresent(orderData, "delivery", "notebookDeliveryMode"), "")))
                .param("notebookSpineName", trimToNull(str(firstPresent(orderData, "schoolNameOnSpine", "notebookSpineName"), "")))
                .param("stationeryPackType", trimToNull(str(firstPresent(orderData, "packType", "stationeryPackType"), "")))
                .param("eventName", trimToNull(str(firstPresent(orderData, "eventName", "title"), "")))
                .param("eventDate", localDate(firstPresent(orderData, "eventDate")))
                .param("createdAt", now)
                .update();
        CatalogOrderRow created = orderQuery(id).orElseThrow();
        emitOrderUpserted(created);
        return created;
    }

    @Transactional
    public CatalogOrderRow placeOrder(String id, Long actorId) {
        CatalogOrderRow current = requiredOrder(id);
        String category = normalize(current.category(), "");
        String newStatus;
        String designStatus;
        String superadminStatus;
        if (requiresDesignApproval(category)) {
            newStatus = "DESIGN_APPROVAL";
            designStatus = "PENDING";
            superadminStatus = "NOT_SUBMITTED";
        } else if (requiresSuperadminApproval(category)) {
            newStatus = "PROCESSING";
            designStatus = "NOT_REQUIRED";
            superadminStatus = "PENDING";
        } else {
            newStatus = "AWAITING_APPROVAL";
            designStatus = current.designStatus();
            superadminStatus = current.superadminApprovalStatus();
        }
        jdbc.sql("""
                UPDATE catalog.catalog_orders
                SET status = :status, design_status = :designStatus,
                    superadmin_approval_status = :superadminStatus,
                    placed_at = :placedAt, placed_by = :placedBy, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .param("status", newStatus)
                .param("designStatus", designStatus)
                .param("superadminStatus", superadminStatus)
                .param("placedAt", OffsetDateTime.now())
                .param("placedBy", actorId)
                .update();
        CatalogOrderRow placed = requiredOrder(id);
        emitOrderUpserted(placed);
        return placed;
    }

    @Transactional
    public CatalogOrderRow updateOrderStatus(String id, String status) {
        requiredOrder(id);
        jdbc.sql("UPDATE catalog.catalog_orders SET status = :status, version = version + 1 WHERE id = :id")
                .param("id", id)
                .param("status", normalize(status, ""))
                .update();
        CatalogOrderRow updated = requiredOrder(id);
        emitOrderUpserted(updated);
        return updated;
    }

    @Transactional
    public CatalogOrderRow markVendorPaid(String id, Long schoolId, Long actorId, String notes) {
        CatalogOrderRow current = requiredOrder(id);
        if (schoolId != null && current.schoolId() != null && !schoolId.equals(current.schoolId())) {
            throw new SecurityException("Cross-school access denied");
        }
        if (current.vendorPaidAt() != null) {
            throw new IllegalStateException("Order already marked as vendor-paid");
        }
        jdbc.sql("""
                UPDATE catalog.catalog_orders
                SET vendor_paid_at = :paidAt, vendor_paid_by = :paidBy,
                    vendor_payment_notes = :notes, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .param("paidAt", OffsetDateTime.now())
                .param("paidBy", actorId)
                .param("notes", notes)
                .update();
        CatalogOrderRow paid = requiredOrder(id);
        emitOrderUpserted(paid);
        return paid;
    }

    @Transactional
    public CatalogOrderRow markDesignApproved(String id) {
        CatalogOrderRow current = requiredOrder(id);
        if (!requiresDesignApproval(current.category())) {
            throw new IllegalStateException("Design approval is only supported for uniform and notebook orders.");
        }
        if (!"DESIGN_APPROVAL".equalsIgnoreCase(current.status())) {
            throw new IllegalStateException("Only orders in DESIGN_APPROVAL status can be marked design approved. Current status: " + current.status());
        }
        jdbc.sql("""
                UPDATE catalog.catalog_orders
                SET design_status = 'APPROVED', superadmin_approval_status = 'PENDING',
                    status = 'DESIGN_APPROVED_PROCESSING', version = version + 1
                WHERE id = :id
                """).param("id", id).update();
        CatalogOrderRow approved = requiredOrder(id);
        emitOrderUpserted(approved);
        return approved;
    }

    @Transactional
    public CatalogOrderRow approveBySuperadmin(String id) {
        CatalogOrderRow current = requiredOrder(id);
        requireSuperadminReviewStatus(current.status(), "approved");
        jdbc.sql("""
                UPDATE catalog.catalog_orders
                SET superadmin_approval_status = 'APPROVED', status = 'APPROVED', version = version + 1
                WHERE id = :id
                """).param("id", id).update();
        CatalogOrderRow updated = requiredOrder(id);
        emitOrderUpserted(updated);
        return updated;
    }

    @Transactional
    public CatalogOrderRow returnBySuperadmin(String id, String reason) {
        CatalogOrderRow current = requiredOrder(id);
        requireSuperadminReviewStatus(current.status(), "rejected");
        String newStatus = requiresDesignApproval(current.category()) ? "DESIGN_APPROVAL" : "PROCESSING";
        String designStatus = requiresDesignApproval(current.category()) ? "PENDING" : "NOT_REQUIRED";
        jdbc.sql("""
                UPDATE catalog.catalog_orders
                SET superadmin_approval_status = 'RETURNED', status = :status,
                    design_status = :designStatus, notes = :notes, version = version + 1
                WHERE id = :id
                """)
                .param("id", id)
                .param("status", newStatus)
                .param("designStatus", designStatus)
                .param("notes", reason == null || reason.isBlank() ? "Returned by Superadmin" : reason.trim())
                .update();
        CatalogOrderRow returned = requiredOrder(id);
        emitOrderUpserted(returned);
        return returned;
    }

    @Transactional
    public AnnualPlanItemRow saveAnnualPlanItem(Long schoolId, Map<String, Object> request) {
        requireSchool(schoolId);
        String id = trimToNull(str(request.get("id"), ""));
        if (id == null) id = UUID.randomUUID().toString();
        String academicYearId = currentAcademicYearId();
        String category = str(request.get("category"), "STATIONERY");
        boolean exists = jdbc.sql("SELECT count(*) FROM catalog.annual_plan_items WHERE id = :id")
                .param("id", id).query(Long.class).single() > 0;
        if (exists) {
            jdbc.sql("""
                    UPDATE catalog.annual_plan_items
                    SET school_id = :schoolId, academic_year_id = :academicYearId, term_name = :termName,
                        category = :category, description = :description, quantity = :quantity,
                        estimated_amount = :estimatedAmount, status = :status
                    WHERE id = :id
                    """)
                    .param("id", id)
                    .param("schoolId", schoolId)
                    .param("academicYearId", academicYearId)
                    .param("termName", str(firstPresent(request, "termName", "term"), "Term 1"))
                    .param("category", category)
                    .param("description", str(request.get("description"), category))
                    .param("quantity", str(request.get("quantity"), "100 units"))
                    .param("estimatedAmount", longNum(firstPresent(request, "estimatedAmount", "amount"), 0))
                    .param("status", str(request.get("status"), "PLANNED").toUpperCase(Locale.ROOT))
                    .update();
        } else {
            jdbc.sql("""
                    INSERT INTO catalog.annual_plan_items (
                        id, school_id, academic_year_id, term_name, category, description,
                        quantity, estimated_amount, status, created_at
                    ) VALUES (
                        :id, :schoolId, :academicYearId, :termName, :category, :description,
                        :quantity, :estimatedAmount, :status, :createdAt
                    )
                    """)
                    .param("id", id)
                    .param("schoolId", schoolId)
                    .param("academicYearId", academicYearId)
                    .param("termName", str(firstPresent(request, "termName", "term"), "Term 1"))
                    .param("category", category)
                    .param("description", str(request.get("description"), category))
                    .param("quantity", str(request.get("quantity"), "100 units"))
                    .param("estimatedAmount", longNum(firstPresent(request, "estimatedAmount", "amount"), 0))
                    .param("status", str(request.get("status"), "PLANNED").toUpperCase(Locale.ROOT))
                    .param("createdAt", OffsetDateTime.now())
                    .update();
        }
        return annualPlanItem(id).orElseThrow();
    }

    public Optional<AnnualPlanItemRow> annualPlanItem(String id) {
        return jdbc.sql("""
                SELECT id, term_name, category, description, quantity, estimated_amount,
                       status, linked_order_id, created_at, school_id, academic_year_id
                FROM catalog.annual_plan_items
                WHERE id = :id
                """).param("id", id).query(AnnualPlanItemRow.class).optional();
    }

    private Map<String, Object> annualPlanItemMap(AnnualPlanItemRow item) {
        return row("id", item.id(),
                "term", item.termName(),
                "termName", item.termName(),
                "category", item.category(),
                "description", item.description(),
                "status", item.status(),
                "quantity", item.quantity(),
                "amount", item.estimatedAmount(),
                "estimatedAmount", item.estimatedAmount(),
                "linkedOrderId", item.linkedOrderId());
    }

    private String orderSelect() {
        return """
                SELECT co.id, co.category, co.order_data, co.subtotal, co.gst, co.total_amount, co.status,
                       co.class_group, co.logo_on_uniform, co.notebook_cover_logo, co.notebook_delivery_mode,
                       co.notebook_spine_name, co.stationery_pack_type, co.event_name, co.event_date,
                       co.design_status, co.superadmin_approval_status, co.required_by_date,
                       co.estimated_delivery, co.placed_by, co.placed_at, co.notes, co.created_at, co.school_id,
                       co.version, co.created_by, co.updated_by, co.vendor_paid_at, co.vendor_paid_by,
                       co.vendor_payment_notes, s.name AS school_name
                FROM catalog.catalog_orders co
                LEFT JOIN tenant_school.schools s ON s.id = co.school_id
                """;
    }

    /**
     * Operations users are cross-school for the orders read; grant a transaction-local RLS bypass
     * (superadmin already bypasses session-level). MUST be called inside a @Transactional read.
     */
    private void allowCrossSchoolReadForOperations() {
        if (TenantContext.get().isOperations()) {
            jdbc.sql("SELECT set_config('app.bypass_rls', 'on', true)").query(String.class).single();
        }
    }

    private CatalogOrderRow requiredOrder(String id) {
        return orderQuery(id).orElseThrow(() -> new IllegalArgumentException("Order not found"));
    }

    /**
     * Emits {@code catalog-order.upserted.v1} for the reporting service's catalog-fact
     * projection (Reporting Decoupling SP4), inside the caller's existing transaction so the
     * outbox row commits (or rolls back) atomically with the order mutation.
     */
    private void emitOrderUpserted(CatalogOrderRow order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", order.id());
        payload.put("schoolId", order.schoolId());
        payload.put("category", order.category());
        payload.put("status", order.status());
        payload.put("totalAmount", order.totalAmount());
        payload.put("superadminApprovalStatus", order.superadminApprovalStatus());
        payload.put("vendorPaidAt", order.vendorPaidAt());
        payload.put("createdAt", order.createdAt());
        payload.put("requiredByDate", order.requiredByDate());
        payload.put("designStatus", order.designStatus());
        payload.put("notes", order.notes());
        outbox.append("catalog-order.upserted.v1", "CatalogOrderUpserted:" + order.id(), "CatalogOrder",
                order.id(), order.schoolId(), payload);
    }

    private void requireSchool(Long schoolId) {
        if (schoolId == null || jdbc.sql("SELECT count(*) FROM tenant_school.schools WHERE id = :schoolId")
                .param("schoolId", schoolId).query(Long.class).single() == 0) {
            throw new IllegalArgumentException("School not found");
        }
    }

    private String currentAcademicYearId() {
        return jdbc.sql("""
                SELECT id FROM tenant_school.academic_years
                ORDER BY active DESC, id DESC
                LIMIT 1
                """).query(String.class).single();
    }

    private String nextCatalogOrderId() {
        // Use a global sequence (nextval ignores RLS) rather than MAX(id)+1, which ran under
        // RLS scoped to the caller's own school and collided with other schools' global PKs.
        Long next = jdbc.sql("SELECT nextval('catalog.seq_catalog_order_id')")
                .query(Long.class).single();
        return "CK-" + next;
    }

    private boolean requiresDesignApproval(String category) {
        return List.of("UNIFORMS", "NOTEBOOKS").contains(normalize(category, ""));
    }

    private boolean requiresSuperadminApproval(String category) {
        return List.of("UNIFORMS", "NOTEBOOKS", "STATIONERY", "EVENTS").contains(normalize(category, ""));
    }

    private void requireSuperadminReviewStatus(String status, String action) {
        if (!List.of("DESIGN_APPROVED_PROCESSING", "PROCESSING").contains(normalize(status, ""))) {
            throw new IllegalStateException("Only orders in DESIGN_APPROVED_PROCESSING or PROCESSING status can be "
                    + action + ". Current status: " + status);
        }
    }

    private String defaultEstimatedDelivery(String category) {
        return switch (normalize(category, "")) {
            case "UNIFORMS" -> "3-4 weeks";
            case "NOTEBOOKS", "STATIONERY" -> "1-2 weeks";
            case "IDCARDS" -> "10 days";
            default -> "2-3 weeks";
        };
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDate localDate(Object input) {
        if (input == null || String.valueOf(input).isBlank()) return null;
        return LocalDate.parse(String.valueOf(input));
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String key : keys) {
            if (request.containsKey(key) && request.get(key) != null) return request.get(key);
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Long longObj(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value).trim());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Json.readMap(text);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long count(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    private long sum(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long sum(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    public record CatalogItemRow(
            Long id,
            String title,
            String subtitle,
            String icon,
            String orderType,
            Long sampleAmount) {
    }

    public record CatalogOrderRow(
            String id,
            String category,
            String orderData,
            Long subtotal,
            Long gst,
            Long totalAmount,
            String status,
            String classGroup,
            String logoOnUniform,
            String notebookCoverLogo,
            String notebookDeliveryMode,
            String notebookSpineName,
            String stationeryPackType,
            String eventName,
            LocalDate eventDate,
            String designStatus,
            String superadminApprovalStatus,
            LocalDate requiredByDate,
            String estimatedDelivery,
            Long placedBy,
            OffsetDateTime placedAt,
            String notes,
            OffsetDateTime createdAt,
            Long schoolId,
            Long version,
            String createdBy,
            String updatedBy,
            OffsetDateTime vendorPaidAt,
            Long vendorPaidBy,
            String vendorPaymentNotes,
            String schoolName) {
    }

    public record PendingCatalogOrderRow(
            String id,
            String category,
            String orderData,
            Long subtotal,
            Long gst,
            Long totalAmount,
            String status,
            String classGroup,
            String logoOnUniform,
            String notebookCoverLogo,
            String notebookDeliveryMode,
            String notebookSpineName,
            String stationeryPackType,
            String eventName,
            LocalDate eventDate,
            String designStatus,
            String superadminApprovalStatus,
            LocalDate requiredByDate,
            String estimatedDelivery,
            Long placedBy,
            OffsetDateTime placedAt,
            String notes,
            OffsetDateTime createdAt,
            Long schoolId,
            Long version,
            String createdBy,
            String updatedBy,
            OffsetDateTime vendorPaidAt,
            Long vendorPaidBy,
            String vendorPaymentNotes,
            String schoolName) {
    }

    public record SupplyOrderRow(
            String code,
            String title,
            String category,
            String items,
            Long amount,
            String status,
            LocalDate orderDate,
            String actionLabel) {
    }

    public record AnnualPlanItemRow(
            String id,
            String termName,
            String category,
            String description,
            String quantity,
            Long estimatedAmount,
            String status,
            String linkedOrderId,
            OffsetDateTime createdAt,
            Long schoolId,
            String academicYearId) {
    }

    public record AnnualPlanEntryRow(
            Long id,
            String termName,
            String category,
            String status,
            String quantity,
            Long amount) {
    }
}
