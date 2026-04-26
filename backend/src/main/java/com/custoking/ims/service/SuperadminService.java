package com.custoking.ims.service;

import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
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
public class SuperadminService {

    private final CatalogOrderRepository catalogOrderRepository;
    private final SuperadminInvoiceRepository saInvoiceRepository;
    private final SuperadminOrderSeqRepository saSeqRepository;
    private final SchoolRepository schoolRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SuperadminService(CatalogOrderRepository catalogOrderRepository,
                              SuperadminInvoiceRepository saInvoiceRepository,
                              SuperadminOrderSeqRepository saSeqRepository,
                              SchoolRepository schoolRepository) {
        this.catalogOrderRepository = catalogOrderRepository;
        this.saInvoiceRepository = saInvoiceRepository;
        this.saSeqRepository = saSeqRepository;
        this.schoolRepository = schoolRepository;
    }

    public List<Map<String, Object>> listAllOrdersForSuperadmin() {
        return catalogOrderRepository.findAll().stream()
                .sorted(Comparator.comparing(CatalogOrderEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(e -> {
                    Map<String, Object> r = new LinkedHashMap<>(catalogOrderListRow(e));
                    r.put("schoolName", e.getSchool() != null ? e.getSchool().getName() : "—");
                    r.put("schoolId", e.getSchool() != null ? e.getSchool().getId() : null);
                    return r;
                }).toList();
    }

    public Map<String, Object> allOrdersStatsForSuperadmin() {
        List<CatalogOrderEntity> all = catalogOrderRepository.findAll();
        long total = all.size();
        long newReq = all.stream().filter(o -> "AWAITING_APPROVAL".equalsIgnoreCase(o.getStatus())).count();
        long inProgress = all.stream().filter(o -> List.of("PROCESSING", "IN_PROGRESS", "APPROVED")
                .contains(String.valueOf(o.getStatus()).toUpperCase(Locale.ROOT))).count();
        long delivered = all.stream().filter(o -> "DELIVERED".equalsIgnoreCase(o.getStatus())).count();
        long gmv = all.stream().mapToLong(CatalogOrderEntity::getTotalAmount).sum();
        return row("total", total, "newRequests", newReq, "inProgress", inProgress,
                "delivered", delivered, "gmv", gmv);
    }

    public Map<String, Object> superadminUpdateOrderStatus(String orderId, Map<String, Object> request) {
        CatalogOrderEntity entity = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        String newStatus = str(request.get("status"), entity.getStatus()).toUpperCase(Locale.ROOT);
        entity.setStatus(newStatus);
        catalogOrderRepository.save(entity);
        return catalogOrderDetailRow(entity);
    }

    public Map<String, Object> superadminCreateOrder(Map<String, Object> request) {
        String newId = nextOrderId();
        CatalogOrderEntity entity = new CatalogOrderEntity();
        entity.setId(newId);
        Long schoolId = longNum(request.get("schoolId"), -1L);
        entity.setSchool(schoolId > 0 ? schoolRepository.findById(schoolId).orElse(null) : null);
        entity.setCategory(str(request.get("category"), "CUSTOM").toUpperCase(Locale.ROOT));
        entity.setOrderData(valueAsJson(request.get("orderData"),
                row("title", str(request.get("category"), "Order"))));
        entity.setSubtotal(longNum(request.get("subtotal"), 0));
        entity.setGst(longNum(request.get("gst"), 0));
        entity.setTotalAmount(longNum(request.get("totalAmount"), entity.getSubtotal() + entity.getGst()));
        entity.setRequiredByDate(parseNullableDate(str(request.get("requiredByDate"), "")));
        entity.setNotes(trimToNull(str(request.get("notes"), "")));
        entity.setStatus("AWAITING_APPROVAL");
        entity.setEstimatedDelivery(defaultEstimatedDelivery(entity.getCategory()));
        entity.setPlacedAt(OffsetDateTime.now());
        catalogOrderRepository.save(entity);
        Map<String, Object> result = new LinkedHashMap<>(catalogOrderDetailRow(entity));
        result.put("schoolName", entity.getSchool() != null ? entity.getSchool().getName() : "—");
        result.put("schoolId", entity.getSchool() != null ? entity.getSchool().getId() : null);
        return result;
    }

    public List<Map<String, Object>> listSuperadminInvoices() {
        return saInvoiceRepository.findAllByOrderByCreatedAtDesc().stream().map(this::saInvoiceRow).toList();
    }

    public Map<String, Object> superadminInvoiceStats() {
        List<SuperadminInvoiceEntity> all = saInvoiceRepository.findAllByOrderByCreatedAtDesc();
        long paid = all.stream().filter(i -> "Paid".equalsIgnoreCase(i.getStatus())).count();
        long pending = all.stream().filter(i -> "Awaiting payment".equalsIgnoreCase(i.getStatus())).count();
        long total = all.stream().mapToLong(SuperadminInvoiceEntity::getTotal).sum();
        return row("sentThisMonth", (long) all.size(), "paid", paid, "pending", pending, "totalInvoiced", total);
    }

    public Map<String, Object> findInvoiceByOrderRef(String orderRef) {
        return saInvoiceRepository.findByOrderRefOrderByCreatedAtDesc(orderRef).stream()
                .findFirst().map(this::saInvoiceRow).orElse(null);
    }

    public Map<String, Object> createSuperadminInvoice(Map<String, Object> request) {
        String id = nextInvoiceId();
        SuperadminInvoiceEntity e = new SuperadminInvoiceEntity();
        e.setId(id);
        e.setOrderRef(str(request.get("orderRef"), ""));
        e.setSchool(str(request.get("school"), ""));
        e.setSchoolId(request.get("schoolId") != null ? longNum(request.get("schoolId"), 0L) : null);
        e.setDescription(str(request.get("description"), ""));
        e.setQty((int) longNum(request.get("qty"), 1));
        e.setRate(longNum(request.get("rate"), 0));
        e.setAmount(longNum(request.get("amount"), (long) e.getQty() * e.getRate()));
        e.setGstAmount(Math.round(e.getAmount() * 0.12));
        e.setTotal(e.getAmount() + e.getGstAmount());
        e.setStatus("Awaiting payment");
        e.setIssuedAt(LocalDate.now().toString());
        e.setDueAt(LocalDate.now().plusDays(14).toString());
        e.setNotes(trimToNull(str(request.get("notes"), "")));
        saInvoiceRepository.save(e);
        return saInvoiceRow(e);
    }

    public Map<String, Object> updateSuperadminInvoice(String id, Map<String, Object> request) {
        SuperadminInvoiceEntity e = saInvoiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found"));
        if (request.containsKey("description")) e.setDescription(str(request.get("description"), ""));
        if (request.containsKey("qty")) e.setQty((int) longNum(request.get("qty"), e.getQty()));
        if (request.containsKey("rate")) e.setRate(longNum(request.get("rate"), e.getRate()));
        if (request.containsKey("school")) e.setSchool(str(request.get("school"), e.getSchool()));
        if (request.containsKey("status")) e.setStatus(str(request.get("status"), e.getStatus()));
        long amount = (long) e.getQty() * e.getRate();
        e.setAmount(amount);
        e.setGstAmount(Math.round(amount * 0.12));
        e.setTotal(e.getAmount() + e.getGstAmount());
        saInvoiceRepository.save(e);
        return saInvoiceRow(e);
    }

    @Transactional
    public String nextOrderId() {
        SuperadminOrderSeqEntity seq = saSeqRepository.findById("SINGLETON")
                .orElseGet(() -> {
                    SuperadminOrderSeqEntity s = new SuperadminOrderSeqEntity();
                    saSeqRepository.save(s);
                    return s;
                });
        seq.setOrderSeq(seq.getOrderSeq() + 1);
        saSeqRepository.save(seq);
        return "CK-2025-0" + seq.getOrderSeq();
    }

    @Transactional
    public String nextInvoiceId() {
        SuperadminOrderSeqEntity seq = saSeqRepository.findById("SINGLETON")
                .orElseGet(() -> {
                    SuperadminOrderSeqEntity s = new SuperadminOrderSeqEntity();
                    saSeqRepository.save(s);
                    return s;
                });
        seq.setInvoiceSeq(seq.getInvoiceSeq() + 1);
        saSeqRepository.save(seq);
        return "INV-2025-0" + seq.getInvoiceSeq();
    }

    // ── Private helpers ──────────────────────────────────────────────

    private Map<String, Object> saInvoiceRow(SuperadminInvoiceEntity e) {
        return row("id", e.getId(), "orderRef", e.getOrderRef(), "school", e.getSchool(),
                "schoolId", e.getSchoolId(), "description", e.getDescription(),
                "qty", e.getQty(), "rate", e.getRate(), "amount", e.getAmount(),
                "gstAmount", e.getGstAmount(), "total", e.getTotal(),
                "status", e.getStatus(), "issuedAt", e.getIssuedAt(), "dueAt", e.getDueAt(),
                "notes", e.getNotes());
    }

    private Map<String, Object> catalogOrderListRow(CatalogOrderEntity e) {
        Map<String, Object> data = parseJsonMap(e.getOrderData());
        return row("id", e.getId(), "code", e.getId(), "category", e.getCategory(),
                "description", str(firstPresent(data, "title", "description"), e.getCategory()),
                "title", str(firstPresent(data, "title", "description"), e.getCategory()),
                "items", str(firstPresent(data, "items", "quantitySummary"), "—"),
                "totalAmount", e.getTotalAmount(), "amount", e.getTotalAmount(),
                "status", e.getStatus(),
                "placedAt", e.getPlacedAt() == null ? null : e.getPlacedAt().toString(),
                "estimatedDelivery", e.getEstimatedDelivery(),
                "date", e.getCreatedAt().toLocalDate().toString(), "action", "Track");
    }

    private Map<String, Object> catalogOrderDetailRow(CatalogOrderEntity e) {
        Map<String, Object> base = new LinkedHashMap<>(catalogOrderListRow(e));
        base.put("orderData", parseJsonMap(e.getOrderData()));
        base.put("subtotal", e.getSubtotal());
        base.put("gst", e.getGst());
        base.put("requiredByDate", e.getRequiredByDate() == null ? null : e.getRequiredByDate().toString());
        base.put("notes", e.getNotes());
        base.put("designStatus", e.getDesignStatus());
        base.put("superadminApprovalStatus", e.getSuperadminApprovalStatus());
        return base;
    }

    private String defaultEstimatedDelivery(String category) {
        return switch (String.valueOf(category).toUpperCase(Locale.ROOT)) {
            case "UNIFORMS" -> "3–4 weeks";
            case "NOTEBOOKS", "STATIONERY" -> "1–2 weeks";
            case "IDCARDS" -> "10 days";
            default -> "2–3 weeks";
        };
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

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
