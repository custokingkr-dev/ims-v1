package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Repository
public class ReportingApprovalRepository {

    private final JdbcClient jdbc;

    public ReportingApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> approvals(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(catalogApprovals(boundedLimit));
        items.addAll(firefightingApprovals(boundedLimit));
        items.sort((left, right) -> {
            OffsetDateTime leftAt = (OffsetDateTime) left.get("createdAt");
            OffsetDateTime rightAt = (OffsetDateTime) right.get("createdAt");
            if (leftAt == null && rightAt == null) return 0;
            if (leftAt == null) return 1;
            if (rightAt == null) return -1;
            return rightAt.compareTo(leftAt);
        });
        return items.stream().limit(boundedLimit).toList();
    }

    @Transactional
    public Map<String, Object> decide(String id, String action, Map<String, Object> request) {
        String normalizedAction = normalize(action);
        if (!List.of("APPROVE", "REJECT").contains(normalizedAction)) {
            throw new IllegalArgumentException("Unsupported approval action: " + action);
        }
        if (id == null || !id.contains(":")) {
            throw new IllegalArgumentException("Approval id must include source prefix");
        }
        int separator = id.indexOf(':');
        String source = normalize(id.substring(0, separator));
        String sourceId = id.substring(separator + 1);
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("Approval source id is required");
        }
        return switch (source) {
            case "CATALOG" -> decideCatalog(sourceId, normalizedAction, decisionNote(request));
            case "FIREFIGHTING" -> decideFirefighting(sourceId, normalizedAction, request == null ? Map.of() : request);
            default -> throw new IllegalArgumentException("Unsupported approval source: " + source);
        };
    }

    private List<Map<String, Object>> catalogApprovals(int limit) {
        return jdbc.sql("""
                SELECT co.id, co.category, co.total_amount, co.status, co.notes, co.created_at,
                       co.school_id, s.name AS school_name
                FROM reporting.fact_catalog_order co
                LEFT JOIN reporting.dim_school s ON s.id = co.school_id
                WHERE UPPER(co.status) IN ('DESIGN_APPROVED_PROCESSING', 'PROCESSING')
                  AND UPPER(co.superadmin_approval_status) = 'PENDING'
                ORDER BY co.created_at DESC NULLS LAST
                LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> row(
                        "id", "catalog:" + rs.getString("id"),
                        "sourceType", "CATALOG",
                        "sourceId", rs.getString("id"),
                        "invoiceNo", rs.getString("id"),
                        "requestType", "Supply order",
                        "status", "PENDING",
                        "reason", "Supply order approval for " + nullSafe(rs.getString("category")),
                        "schoolId", rs.getObject("school_id") == null ? null : rs.getLong("school_id"),
                        "schoolName", rs.getString("school_name"),
                        "amount", rs.getLong("total_amount"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "notes", rs.getString("notes")))
                .list();
    }

    private List<Map<String, Object>> firefightingApprovals(int limit) {
        return jdbc.sql("""
                SELECT ff.code, ff.title, ff.category, ff.status, ff.estimated_budget, ff.created_at,
                       ff.school_id, s.name AS school_name
                FROM reporting.fact_firefighting_request ff
                LEFT JOIN reporting.dim_school s ON s.id = ff.school_id
                WHERE ff.status IN ('AWAITING_BURSAR', 'AWAITING_PRINCIPAL', 'AWAITING_CUSTOKING')
                ORDER BY ff.created_at DESC NULLS LAST
                LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> row(
                        "id", "firefighting:" + rs.getString("code"),
                        "sourceType", "FIREFIGHTING",
                        "sourceId", rs.getString("code"),
                        "invoiceNo", rs.getString("code"),
                        "requestType", firefightingRequestType(rs.getString("status")),
                        "status", "PENDING",
                        "reason", nullSafe(rs.getString("title")),
                        "schoolId", rs.getObject("school_id") == null ? null : rs.getLong("school_id"),
                        "schoolName", rs.getString("school_name"),
                        "amount", rs.getLong("estimated_budget"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class),
                        "category", rs.getString("category")))
                .list();
    }

    private Map<String, Object> decideCatalog(String orderId, String action, String note) {
        int updated;
        if ("APPROVE".equals(action)) {
            updated = jdbc.sql("""
                    UPDATE catalog.catalog_orders
                    SET superadmin_approval_status = 'APPROVED', status = 'APPROVED', version = version + 1
                    WHERE id = :id
                      AND UPPER(status) IN ('DESIGN_APPROVED_PROCESSING', 'PROCESSING')
                      AND UPPER(superadmin_approval_status) = 'PENDING'
                    """)
                    .param("id", orderId)
                    .update();
        } else {
            updated = jdbc.sql("""
                    UPDATE catalog.catalog_orders
                    SET superadmin_approval_status = 'RETURNED',
                        status = CASE WHEN UPPER(category) IN ('UNIFORMS', 'NOTEBOOKS') THEN 'DESIGN_APPROVAL' ELSE 'PROCESSING' END,
                        design_status = CASE WHEN UPPER(category) IN ('UNIFORMS', 'NOTEBOOKS') THEN 'PENDING' ELSE 'NOT_REQUIRED' END,
                        notes = :note,
                        version = version + 1
                    WHERE id = :id
                      AND UPPER(status) IN ('DESIGN_APPROVED_PROCESSING', 'PROCESSING')
                      AND UPPER(superadmin_approval_status) = 'PENDING'
                    """)
                    .param("id", orderId)
                    .param("note", note == null || note.isBlank() ? "Returned by Superadmin" : note)
                    .update();
        }
        if (updated == 0) {
            throw new IllegalArgumentException("Approval not found");
        }
        return row("id", "catalog:" + orderId, "sourceType", "CATALOG", "sourceId", orderId,
                "status", "APPROVE".equals(action) ? "APPROVED" : "REJECTED");
    }

    private Map<String, Object> decideFirefighting(String code, String action, Map<String, Object> request) {
        Map<String, Object> current = jdbc.sql("""
                SELECT code, status
                FROM firefighting.firefighting_requests
                WHERE code = :code
                """)
                .param("code", code)
                .query((rs, rowNum) -> row("code", rs.getString("code"), "status", rs.getString("status")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Approval not found"));
        String status = normalize(String.valueOf(current.get("status")));
        if (!List.of("AWAITING_BURSAR", "AWAITING_PRINCIPAL", "AWAITING_CUSTOKING").contains(status)) {
            throw new IllegalArgumentException("Approval not found");
        }
        if ("REJECT".equals(action)) {
            jdbc.sql("""
                    UPDATE firefighting.firefighting_requests
                    SET rejected_by = :rejectedBy, rejected_reason = :reason, status = 'REJECTED'
                    WHERE code = :code
                    """)
                    .param("code", code)
                    .param("rejectedBy", text(request, "actorName", "Superadmin"))
                    .param("reason", text(request, "decisionNote", text(request, "reason", "")))
                    .update();
            return row("id", "firefighting:" + code, "sourceType", "FIREFIGHTING", "sourceId", code, "status", "REJECTED");
        }

        switch (status) {
            case "AWAITING_BURSAR" -> jdbc.sql("""
                    UPDATE firefighting.firefighting_requests
                    SET bursar_note = :note, bursar_approved_at = :approvedAt, status = 'AWAITING_PRINCIPAL'
                    WHERE code = :code
                    """)
                    .param("code", code)
                    .param("note", decisionNote(request))
                    .param("approvedAt", OffsetDateTime.now())
                    .update();
            case "AWAITING_PRINCIPAL" -> jdbc.sql("""
                    UPDATE firefighting.firefighting_requests
                    SET principal_note = :note, principal_approved_at = :approvedAt, status = 'APPROVED'
                    WHERE code = :code
                    """)
                    .param("code", code)
                    .param("note", decisionNote(request))
                    .param("approvedAt", OffsetDateTime.now())
                    .update();
            case "AWAITING_CUSTOKING" -> jdbc.sql("""
                    UPDATE firefighting.firefighting_requests
                    SET status = 'CUSTOKING_APPROVED'
                    WHERE code = :code
                    """)
                    .param("code", code)
                    .update();
            default -> throw new IllegalArgumentException("Approval not found");
        }
        return row("id", "firefighting:" + code, "sourceType", "FIREFIGHTING", "sourceId", code, "status", "APPROVED");
    }

    private String firefightingRequestType(String status) {
        return switch (normalize(status)) {
            case "AWAITING_BURSAR" -> "Urgent procurement - bursar approval";
            case "AWAITING_PRINCIPAL" -> "Urgent procurement - principal approval";
            case "AWAITING_CUSTOKING" -> "Urgent procurement - Custoking approval";
            default -> "Urgent procurement";
        };
    }

    private String decisionNote(Map<String, Object> request) {
        return text(request, "decisionNote", text(request, "note", ""));
    }

    private String text(Map<String, Object> request, String key, String fallback) {
        if (request == null || request.get(key) == null) return fallback;
        return String.valueOf(request.get(key));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
