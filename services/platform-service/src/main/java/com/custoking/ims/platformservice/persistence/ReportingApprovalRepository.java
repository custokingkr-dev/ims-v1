package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.infrastructure.ApprovalCommandClient;
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
    private final ApprovalCommandClient approvalCommandClient;

    public ReportingApprovalRepository(JdbcClient jdbc, ApprovalCommandClient approvalCommandClient) {
        this.jdbc = jdbc;
        this.approvalCommandClient = approvalCommandClient;
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
                WHERE ff.status IN ('AWAITING_BURSAR', 'AWAITING_PRINCIPAL', 'APPROVED')
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
        if ("APPROVE".equals(action)) {
            approvalCommandClient.approveCatalog(orderId);
        } else {
            approvalCommandClient.rejectCatalog(orderId, note == null || note.isBlank() ? "Returned by Superadmin" : note);
        }
        return row("id", "catalog:" + orderId, "sourceType", "CATALOG", "sourceId", orderId,
                "status", "APPROVE".equals(action) ? "APPROVED" : "REJECTED");
    }

    private Map<String, Object> decideFirefighting(String code, String action, Map<String, Object> request) {
        // The current status is read from the locally-projected fact table (reporting owns this
        // as a read model); the firefighting schema itself is never read or written from here.
        String current = jdbc.sql("""
                SELECT status
                FROM reporting.fact_firefighting_request
                WHERE code = :code
                """)
                .param("code", code)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Approval not found"));
        String status = normalize(current);
        if (!List.of("AWAITING_BURSAR", "AWAITING_PRINCIPAL", "APPROVED").contains(status)) {
            throw new IllegalArgumentException("Approval not found");
        }
        if ("REJECT".equals(action)) {
            approvalCommandClient.rejectFirefighting(code, text(request, "actorName", "Superadmin"),
                    text(request, "decisionNote", text(request, "reason", "")));
            return row("id", "firefighting:" + code, "sourceType", "FIREFIGHTING", "sourceId", code, "status", "REJECTED");
        }

        switch (status) {
            case "AWAITING_BURSAR" -> approvalCommandClient.approveFirefightingBursar(code, decisionNote(request));
            case "AWAITING_PRINCIPAL" -> approvalCommandClient.approveFirefightingPrincipal(code, decisionNote(request));
            case "APPROVED" -> approvalCommandClient.approveFirefightingCustoking(code);
            default -> throw new IllegalArgumentException("Approval not found");
        }
        return row("id", "firefighting:" + code, "sourceType", "FIREFIGHTING", "sourceId", code, "status", "APPROVED");
    }

    private String firefightingRequestType(String status) {
        return switch (normalize(status)) {
            case "AWAITING_BURSAR" -> "Urgent procurement - bursar approval";
            case "AWAITING_PRINCIPAL" -> "Urgent procurement - principal approval";
            case "APPROVED" -> "Urgent procurement - Custoking approval";
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
