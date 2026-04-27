package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.entity.AnnualPlanItemEntity;
import com.custoking.ims.entity.AcademicYearEntity;
import com.custoking.ims.entity.CatalogOrderEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.repo.AcademicYearRepository;
import com.custoking.ims.repo.AnnualPlanItemRepository;
import com.custoking.ims.repo.CatalogOrderRepository;
import com.custoking.ims.repo.SchoolRepository;
import com.custoking.ims.repo.StudentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@Transactional
public class SupplyOrderService {

    private final CatalogOrderRepository catalogOrderRepository;
    private final SchoolRepository schoolRepository;
    private final AnnualPlanItemRepository annualPlanItemRepository;
    private final AcademicYearRepository academicYearRepository;
    private final StudentRepository studentRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SupplyOrderService(
            CatalogOrderRepository catalogOrderRepository,
            SchoolRepository schoolRepository,
            AnnualPlanItemRepository annualPlanItemRepository,
            AcademicYearRepository academicYearRepository,
            StudentRepository studentRepository,
            AuditLogService auditLogService
    ) {
        this.catalogOrderRepository = catalogOrderRepository;
        this.schoolRepository = schoolRepository;
        this.annualPlanItemRepository = annualPlanItemRepository;
        this.academicYearRepository = academicYearRepository;
        this.studentRepository = studentRepository;
        this.auditLogService = auditLogService;
    }

    // ── Catalog categories ──────────────────────────────────────

    public List<Map<String, Object>> catalogCategories() {
        return List.of(
                row("id", "UNIFORMS", "emoji", "👕", "label", "Uniforms", "orderType", "Recurring", "description", "Full uniform sets by size and house"),
                row("id", "NOTEBOOKS", "emoji", "📘", "label", "Notebooks", "orderType", "Recurring", "description", "Ruled, unruled, graph and custom books"),
                row("id", "STATIONERY", "emoji", "✏️", "label", "Stationery", "orderType", "Recurring", "description", "Student stationery kits and classroom essentials"),
                row("id", "IDCARDS", "emoji", "🪪", "label", "ID Cards", "orderType", "One-time", "description", "Student and staff ID cards, lanyards and holders"),
                row("id", "HOUSEKEEPING", "emoji", "🧹", "label", "Housekeeping", "orderType", "Service", "description", "Cleaning consumables and support services"),
                row("id", "EVENTS", "emoji", "🎉", "label", "Events", "orderType", "One-time", "description", "Trophies, certificates, banners and event kits"),
                row("id", "HEALTH", "emoji", "🩺", "label", "Health", "orderType", "Service", "description", "Infirmary essentials and annual health services")
        );
    }

    // ── Create order (status = DRAFT) ───────────────────────────

    public Map<String, Object> createCatalogOrder(Map<String, Object> request, AuthUser actor) {
        Long reqSchoolId = request.get("schoolId") == null ? null
                : longNum(request.get("schoolId"), -1) < 0 ? null : longNum(request.get("schoolId"), -1);
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : reqSchoolId;
        CatalogOrderEntity entity = new CatalogOrderEntity();
        entity.setId("CK-" + (1000 + catalogOrderRepository.count() + 1));
        entity.setSchool(resolveSchool(schoolId));
        entity.setCategory(str(request.get("category"), "STATIONERY").toUpperCase(Locale.ROOT));
        Map<String, Object> orderData = parseJsonMap(valueAsJson(request.get("orderData"),
                row("title", str(request.get("category"), "Order"), "items", str(request.get("items"), "1 unit"))));
        entity.setOrderData(valueAsJson(orderData,
                row("title", str(request.get("category"), "Order"), "items", str(request.get("items"), "1 unit"))));
        entity.setSubtotal(longNum(request.get("subtotal"), longNum(request.get("amount"), 0)));
        entity.setGst(longNum(request.get("gst"), 0));
        entity.setTotalAmount(longNum(request.get("totalAmount"), entity.getSubtotal() + entity.getGst()));
        entity.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        entity.setNotes(trimToNull(str(request.get("notes"), "")));
        entity.setStatus(str(request.get("status"), "DRAFT").toUpperCase(Locale.ROOT));
        entity.setEstimatedDelivery(defaultEstimatedDelivery(entity.getCategory()));
        entity.setPlacedBy(actor.userId());
        boolean designRequired = List.of("UNIFORMS", "NOTEBOOKS").contains(entity.getCategory().toUpperCase(Locale.ROOT));
        boolean superadminRequired = List.of("UNIFORMS", "NOTEBOOKS", "STATIONERY", "EVENTS").contains(entity.getCategory().toUpperCase(Locale.ROOT));
        entity.setDesignStatus(designRequired ? "PENDING" : "NOT_REQUIRED");
        entity.setSuperadminApprovalStatus(superadminRequired ? "NOT_SUBMITTED" : "NOT_REQUIRED");
        entity.setClassGroup(trimToNull(str(firstPresent(orderData, "classGroup", "class_group"), "")));
        entity.setLogoOnUniform(trimToNull(str(firstPresent(orderData, "logoOnUniform", "logo_on_uniform"), "")));
        entity.setNotebookCoverLogo(trimToNull(str(firstPresent(orderData, "coverLogo", "notebookCoverLogo"), "")));
        entity.setNotebookDeliveryMode(trimToNull(str(firstPresent(orderData, "delivery", "notebookDeliveryMode"), "")));
        entity.setNotebookSpineName(trimToNull(str(firstPresent(orderData, "schoolNameOnSpine", "notebookSpineName"), "")));
        entity.setStationeryPackType(trimToNull(str(firstPresent(orderData, "packType", "stationeryPackType"), "")));
        entity.setEventName(trimToNull(str(firstPresent(orderData, "eventName", "title"), "")));
        entity.setEventDate(parseNullableDate(str(firstPresent(orderData, "eventDate"), "")));
        if (!"DRAFT".equalsIgnoreCase(entity.getStatus())) entity.setPlacedAt(OffsetDateTime.now());
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    // ── Place order — state-machine routing ─────────────────────
    // UNIFORMS / NOTEBOOKS  → DESIGN_APPROVAL, designStatus=PENDING, superadminApprovalStatus=NOT_SUBMITTED
    // STATIONERY / EVENTS   → PROCESSING, designStatus=NOT_REQUIRED, superadminApprovalStatus=PENDING
    // all others            → AWAITING_APPROVAL

    public Map<String, Object> placeCatalogOrder(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertSchoolOwnership("order",
                entity.getSchool() != null ? entity.getSchool().getId() : null,
                TenantContext.get());
        String category = String.valueOf(entity.getCategory()).toUpperCase(Locale.ROOT);
        if (List.of("UNIFORMS", "NOTEBOOKS").contains(category)) {
            entity.setStatus("DESIGN_APPROVAL");
            entity.setDesignStatus("PENDING");
            entity.setSuperadminApprovalStatus("NOT_SUBMITTED");
        } else if (List.of("STATIONERY", "EVENTS").contains(category)) {
            entity.setStatus("PROCESSING");
            entity.setDesignStatus("NOT_REQUIRED");
            entity.setSuperadminApprovalStatus("PENDING");
        } else {
            entity.setStatus("AWAITING_APPROVAL");
        }
        entity.setPlacedAt(OffsetDateTime.now());
        entity.setPlacedBy(actor.userId());
        catalogOrderRepository.save(entity);
        auditLogService.statusTransition("catalog_order", orderId, "DRAFT", entity.getStatus(), actor.userId());
        return catalogOrderDetailRow(entity);
    }

    // ── Status patch (school-side freeform update) ──────────────

    public Map<String, Object> updateCatalogOrderStatus(String orderId, Map<String, Object> request, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertSchoolOwnership("order", entity.getSchool().getId(), TenantContext.get());
        String oldStatus = entity.getStatus();
        String newStatus = str(request.get("status"), entity.getStatus()).toUpperCase(Locale.ROOT);
        entity.setStatus(newStatus);
        catalogOrderRepository.save(entity);
        auditLogService.statusTransition("catalog_order", orderId, oldStatus, newStatus, actor.userId());
        return catalogOrderDetailRow(entity);
    }

    // ── List pending superadmin approval ────────────────────────

    public List<Map<String, Object>> listOrdersPendingApproval(AuthUser actor) {
        return catalogOrderRepository.findAll().stream()
                .filter(e -> ("DESIGN_APPROVED_PROCESSING".equalsIgnoreCase(e.getStatus())
                        || "PROCESSING".equalsIgnoreCase(e.getStatus()))
                        && "PENDING".equalsIgnoreCase(String.valueOf(e.getSuperadminApprovalStatus())))
                .sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>(catalogOrderListRow(e));
                    row.put("schoolName", e.getSchool() != null ? e.getSchool().getName() : "—");
                    row.put("schoolId", e.getSchool() != null ? e.getSchool().getId() : null);
                    return row;
                })
                .toList();
    }

    // ── Design approval — UNIFORMS/NOTEBOOKS only, must be in DESIGN_APPROVAL status ──

    public Map<String, Object> markCatalogOrderDesignApproved(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertSchoolOwnership("order", entity.getSchool().getId(), TenantContext.get());
        if (!List.of("UNIFORMS", "NOTEBOOKS").contains(
                String.valueOf(entity.getCategory()).toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Design approval is only supported for uniform and notebook orders.");
        }
        if (!"DESIGN_APPROVAL".equalsIgnoreCase(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only orders in DESIGN_APPROVAL status can be marked design approved. Current status: "
                            + entity.getStatus());
        }
        entity.setDesignStatus("APPROVED");
        entity.setSuperadminApprovalStatus("PENDING");
        entity.setStatus("DESIGN_APPROVED_PROCESSING");
        catalogOrderRepository.save(entity);
        auditLogService.statusTransition("catalog_order", orderId, "DESIGN_APPROVAL", "DESIGN_APPROVED_PROCESSING", actor.userId());
        return catalogOrderDetailRow(entity);
    }

    // ── Superadmin approve — must be DESIGN_APPROVED_PROCESSING or PROCESSING ──

    public Map<String, Object> superadminApproveOrder(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!("DESIGN_APPROVED_PROCESSING".equalsIgnoreCase(entity.getStatus())
                || "PROCESSING".equalsIgnoreCase(entity.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only orders in DESIGN_APPROVED_PROCESSING or PROCESSING status can be approved. Current status: "
                            + entity.getStatus());
        }
        String oldStatus = entity.getStatus();
        entity.setSuperadminApprovalStatus("APPROVED");
        entity.setStatus("APPROVED");
        catalogOrderRepository.save(entity);
        auditLogService.statusTransition("catalog_order", orderId, oldStatus, "APPROVED", actor.userId());
        return catalogOrderDetailRow(entity);
    }

    // ── Superadmin reject — UNIFORMS/NOTEBOOKS → DESIGN_APPROVAL, others → PROCESSING ──

    public Map<String, Object> superadminRejectOrder(String orderId, Map<String, Object> request, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (!("DESIGN_APPROVED_PROCESSING".equalsIgnoreCase(entity.getStatus())
                || "PROCESSING".equalsIgnoreCase(entity.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only orders in DESIGN_APPROVED_PROCESSING or PROCESSING status can be rejected. Current status: "
                            + entity.getStatus());
        }
        String oldStatus = entity.getStatus();
        entity.setSuperadminApprovalStatus("RETURNED");
        if (List.of("UNIFORMS", "NOTEBOOKS").contains(
                String.valueOf(entity.getCategory()).toUpperCase(Locale.ROOT))) {
            entity.setStatus("DESIGN_APPROVAL");
            entity.setDesignStatus("PENDING");
        } else {
            entity.setStatus("PROCESSING");
            entity.setDesignStatus("NOT_REQUIRED");
        }
        entity.setNotes(str(request.get("reason"), "Returned by Superadmin"));
        catalogOrderRepository.save(entity);
        auditLogService.statusTransition("catalog_order", orderId, oldStatus, entity.getStatus(), actor.userId());
        return catalogOrderDetailRow(entity);
    }

    // ── List orders ─────────────────────────────────────────────

    public List<Map<String, Object>> listCatalogOrders(AuthUser actor) {
        return listCatalogOrders(actor, null);
    }

    public List<Map<String, Object>> listCatalogOrders(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return catalogOrderRepository.findBySchool_Id(schoolId).stream()
                .sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(this::catalogOrderListRow)
                .toList();
    }

    // ── Order detail ─────────────────────────────────────────────

    public Map<String, Object> catalogOrderDetail(String orderId, AuthUser actor) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (actor.role() != Role.SUPERADMIN) {
            assertSchoolOwnership("order", entity.getSchool().getId(), TenantContext.get());
        }
        return catalogOrderDetailRow(entity);
    }

    // ── Order stats ──────────────────────────────────────────────

    public Map<String, Object> catalogOrderStats(AuthUser actor) {
        return catalogOrderStats(actor, null);
    }

    public Map<String, Object> catalogOrderStats(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        List<CatalogOrderEntity> orders = catalogOrderRepository.findBySchool_Id(schoolId);
        long activeOrders = orders.stream()
                .filter(o -> !List.of("DELIVERED", "DRAFT").contains(
                        String.valueOf(o.getStatus()).toUpperCase(Locale.ROOT)))
                .count();
        long termSpend = orders.stream().mapToLong(CatalogOrderEntity::getTotalAmount).sum();
        long activeServices = orders.stream()
                .filter(o -> "ACTIVE".equalsIgnoreCase(o.getStatus())).count();
        long deliveredCount = orders.stream()
                .filter(o -> "DELIVERED".equalsIgnoreCase(o.getStatus())).count();
        long termBudget = annualPlanItemRepository.findBySchool_Id(schoolId).stream()
                .mapToLong(AnnualPlanItemEntity::getEstimatedAmount).sum();
        return row("activeOrders", activeOrders, "termSpend", termSpend, "termBudget", termBudget,
                "activeServices", activeServices, "deliveredCount", deliveredCount);
    }

    // ── Private helpers ──────────────────────────────────────────

    private String defaultEstimatedDelivery(String category) {
        return switch (String.valueOf(category).toUpperCase(Locale.ROOT)) {
            case "UNIFORMS"             -> "3–4 weeks";
            case "NOTEBOOKS", "STATIONERY" -> "1–2 weeks";
            case "IDCARDS"              -> "10 days";
            default                     -> "2–3 weeks";
        };
    }

    private SchoolEntity resolveSchool(Long schoolId) {
        return schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
    }

    private void assertSchoolOwnership(String entityLabel, Long entitySchoolId, Long actorSchoolId) {
        if (actorSchoolId != null && entitySchoolId != null && !actorSchoolId.equals(entitySchoolId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You do not have access to this " + entityLabel);
        }
    }

    private Map<String, Object> catalogOrderListRow(CatalogOrderEntity e) {
        Map<String, Object> data = parseJsonMap(e.getOrderData());
        return row(
                "id", e.getId(), "code", e.getId(),
                "category", e.getCategory(),
                "description", str(firstPresent(data, "title", "description"), e.getCategory()),
                "title",       str(firstPresent(data, "title", "description"), e.getCategory()),
                "items",       str(firstPresent(data, "items", "quantitySummary"), "—"),
                "totalAmount", e.getTotalAmount(),
                "amount",      e.getTotalAmount(),
                "status",      e.getStatus(),
                "placedAt",    e.getPlacedAt() == null ? null : e.getPlacedAt().toString(),
                "estimatedDelivery", e.getEstimatedDelivery(),
                "date",        e.getCreatedAt() == null ? null : e.getCreatedAt().toLocalDate().toString(),
                "action",      "Track"
        );
    }

    private Map<String, Object> catalogOrderDetailRow(CatalogOrderEntity e) {
        Map<String, Object> base = new LinkedHashMap<>(catalogOrderListRow(e));
        base.put("orderData",              parseJsonMap(e.getOrderData()));
        base.put("subtotal",               e.getSubtotal());
        base.put("gst",                    e.getGst());
        base.put("requiredByDate",         e.getRequiredByDate() == null ? null : e.getRequiredByDate().toString());
        base.put("notes",                  e.getNotes());
        base.put("classGroup",             e.getClassGroup());
        base.put("logoOnUniform",          e.getLogoOnUniform());
        base.put("notebookCoverLogo",      e.getNotebookCoverLogo());
        base.put("notebookDeliveryMode",   e.getNotebookDeliveryMode());
        base.put("notebookSpineName",      e.getNotebookSpineName());
        base.put("stationeryPackType",     e.getStationeryPackType());
        base.put("eventName",              e.getEventName());
        base.put("eventDate",              e.getEventDate() == null ? null : e.getEventDate().toString());
        base.put("designStatus",           e.getDesignStatus());
        base.put("superadminApprovalStatus", e.getSuperadminApprovalStatus());
        return base;
    }

    private LocalDate parseNullableDate(String input) {
        if (input == null || input.isBlank()) return null;
        return LocalDate.parse(input);
    }

    private String valueAsJson(Object raw, Object fallback) {
        try {
            return objectMapper.writeValueAsString(raw == null ? fallback : raw);
        } catch (Exception e) {
            return toJson(fallback);
        }
    }

    // ── Annual plan ─────────────────────────────────────────────────

    public Map<String, Object> listAnnualPlan(AuthUser actor, Long requestedSchoolId) {
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : requestedSchoolId;
        return annualPlanPayload(schoolId);
    }

    public Map<String, Object> saveAnnualPlanItem(Map<String, Object> request, AuthUser actor) {
        Long reqSchoolId2 = request.get("schoolId") == null ? null
                : longNum(request.get("schoolId"), -1) < 0 ? null
                : longNum(request.get("schoolId"), -1);
        Long schoolId = TenantContext.get() != null ? TenantContext.get() : reqSchoolId2;
        String id = trimToNull(str(request.get("id"), ""));
        AnnualPlanItemEntity item = id == null ? new AnnualPlanItemEntity()
                : annualPlanItemRepository.findById(id).orElseGet(AnnualPlanItemEntity::new);
        if (item.getId() == null) item.setId(UUID.randomUUID().toString());
        item.setSchool(resolveSchool(schoolId));
        item.setAcademicYear(currentAcademicYearEntity());
        item.setTermName(str(request.get("termName"), str(request.get("term"), "Term 1")));
        item.setCategory(str(request.get("category"), "STATIONERY"));
        item.setDescription(str(request.get("description"), item.getCategory()));
        item.setQuantity(str(request.get("quantity"), "100 units"));
        item.setEstimatedAmount(longNum(firstPresent(request, "estimatedAmount", "amount"), 0));
        item.setStatus(str(request.get("status"), "PLANNED").toUpperCase(java.util.Locale.ROOT));
        annualPlanItemRepository.save(item);
        return annualPlanItemRow(item);
    }

    public Map<String, Object> confirmAnnualPlan(AuthUser actor) {
        return row("ok", true, "message", "Annual plan confirmed and Custoking notified");
    }

    private Map<String, Object> annualPlanPayload(Long schoolId) {
        String yearLabel = currentAcademicYearEntity().getLabel();
        List<Map<String, Object>> terms = annualPlanItemRepository.findBySchool_Id(schoolId).stream()
                .sorted(java.util.Comparator.comparing(AnnualPlanItemEntity::getTermName,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(AnnualPlanItemEntity::getCreatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(this::annualPlanItemRow).toList();
        long total = terms.stream().mapToLong(t -> longNum(t.get("estimatedAmount"), 0)).sum();
        long ordered = terms.stream().filter(t -> "ORDERED".equals(String.valueOf(t.get("status")))).count();
        int completion = terms.isEmpty() ? 0 : (int) Math.round((ordered * 100.0) / terms.size());
        long studentCount = studentRepository.findAll().stream()
                .filter(s -> s.getSchool() != null && schoolId.equals(s.getSchool().getId())).count();
        return row("completionPercent", completion,
                "academicYears", java.util.List.of(yearLabel),
                "terms", terms,
                "summary", row("total", total,
                        "pendingCount", terms.stream().filter(t -> !"ORDERED".equals(String.valueOf(t.get("status")))).count(),
                        "perStudentCost", total / Math.max(1, studentCount),
                        "vsLastYearPercent", 12));
    }

    private Map<String, Object> annualPlanItemRow(AnnualPlanItemEntity e) {
        return row("id", e.getId(), "term", e.getTermName(), "termName", e.getTermName(),
                "category", e.getCategory(), "description", e.getDescription(), "status", e.getStatus(),
                "quantity", e.getQuantity(), "amount", e.getEstimatedAmount(),
                "estimatedAmount", e.getEstimatedAmount(), "linkedOrderId", e.getLinkedOrderId());
    }

    private AcademicYearEntity currentAcademicYearEntity() {
        return academicYearRepository.findFirstByActiveTrue()
                .orElseThrow(() -> new IllegalArgumentException("No active academic year configured"));
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Object firstPresent(Map<String, Object> request, String... keys) {
        for (String k : keys)
            if (request.containsKey(k) && request.get(k) != null) return request.get(k);
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private long longNum(Object value, long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value).replace(",", "").trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
