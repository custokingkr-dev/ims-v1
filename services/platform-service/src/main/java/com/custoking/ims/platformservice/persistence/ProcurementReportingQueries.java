package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** Owns procurement reporting queries; tenant filters, SQL and response shapes are preserved. */
@Repository
public class ProcurementReportingQueries {
    private final JdbcClient jdbc;

    public ProcurementReportingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> vendorDues(Long schoolId) {
        if (schoolId == null) {
            return Map.of(
                    "catalogOrderCount", 0L,
                    "catalogOrderTotalPaise", 0L,
                    "firefightingCount", 0L,
                    "firefightingTotalPaise", 0L,
                    "totalDuesPaise", 0L,
                    "items", List.of());
        }
        List<Map<String, Object>> catalog = jdbc.sql("""
                SELECT 'CATALOG_ORDER' AS source_type, id, category AS title, category,
                       NULL::varchar AS vendor_name, total_amount AS amount_paise,
                       status, created_at
                FROM reporting.fact_catalog_order
                WHERE school_id = :schoolId
                  AND status IN ('APPROVED', 'FULFILLED')
                  AND vendor_paid_at IS NULL
                ORDER BY created_at DESC NULLS LAST
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "sourceType", rs.getString("source_type"),
                        "id", rs.getString("id"),
                        "title", rs.getString("title"),
                        "category", rs.getString("category"),
                        "vendorName", rs.getString("vendor_name"),
                        "amountPaise", rs.getLong("amount_paise"),
                        "status", rs.getString("status"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        List<Map<String, Object>> firefighting = jdbc.sql("""
                SELECT 'FIREFIGHTING' AS source_type, code AS id, title, category,
                       winner_vendor AS vendor_name, winner_amount AS amount_paise,
                       status, created_at
                FROM reporting.fact_firefighting_request
                WHERE school_id = :schoolId
                  AND status = 'APPROVED'
                  AND winner_amount IS NOT NULL
                  AND vendor_paid_at IS NULL
                ORDER BY created_at DESC NULLS LAST
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "sourceType", rs.getString("source_type"),
                        "id", rs.getString("id"),
                        "title", rs.getString("title"),
                        "category", rs.getString("category"),
                        "vendorName", rs.getString("vendor_name"),
                        "amountPaise", rs.getLong("amount_paise"),
                        "status", rs.getString("status"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        long catalogTotal = catalog.stream().mapToLong(row -> longValue(row.get("amountPaise"))).sum();
        long firefightingTotal = firefighting.stream().mapToLong(row -> longValue(row.get("amountPaise"))).sum();
        java.util.ArrayList<Map<String, Object>> items = new java.util.ArrayList<>();
        items.addAll(catalog);
        items.addAll(firefighting);
        return Map.of(
                "catalogOrderCount", (long) catalog.size(),
                "catalogOrderTotalPaise", catalogTotal,
                "firefightingCount", (long) firefighting.size(),
                "firefightingTotalPaise", firefightingTotal,
                "totalDuesPaise", catalogTotal + firefightingTotal,
                "items", items);
    }

    public Map<String, Object> reorderSignals(Long schoolId) {
        if (schoolId == null) {
            return Map.of("alertCount", 0, "items", List.of());
        }
        List<Map<String, Object>> orders = jdbc.sql("""
                SELECT category, created_at
                FROM reporting.fact_catalog_order
                WHERE school_id = :schoolId
                  AND status IN ('APPROVED', 'FULFILLED')
                  AND created_at IS NOT NULL
                ORDER BY created_at ASC
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "category", rs.getString("category"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        Map<String, List<Map<String, Object>>> byCategory = orders.stream()
                .collect(Collectors.groupingBy(row -> String.valueOf(row.get("category"))));
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byCategory.entrySet()) {
            List<Map<String, Object>> catOrders = new ArrayList<>(entry.getValue());
            catOrders.sort(Comparator.comparing(row -> (OffsetDateTime) row.get("createdAt")));
            LocalDate lastDate = ((OffsetDateTime) catOrders.get(catOrders.size() - 1).get("createdAt")).toLocalDate();
            int daysSinceLast = (int) Math.max(0, ChronoUnit.DAYS.between(lastDate, today));
            Integer avgInterval = null;
            LocalDate predictedNext = null;
            if (catOrders.size() >= 2) {
                LocalDate firstDate = ((OffsetDateTime) catOrders.get(0).get("createdAt")).toLocalDate();
                long span = ChronoUnit.DAYS.between(firstDate, lastDate);
                int computed = (int) (span / (catOrders.size() - 1));
                if (computed > 0) {
                    avgInterval = computed;
                    predictedNext = lastDate.plusDays(computed);
                }
            }
            String alertLevel;
            if (avgInterval != null) {
                if (daysSinceLast > avgInterval * 1.2) {
                    alertLevel = "RED";
                } else if (daysSinceLast > avgInterval * 0.7) {
                    alertLevel = "YELLOW";
                } else {
                    alertLevel = "OK";
                }
            } else {
                alertLevel = daysSinceLast >= 180 ? "YELLOW" : "OK";
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("category", entry.getKey());
            item.put("lastOrderDate", lastDate);
            item.put("daysSinceLastOrder", daysSinceLast);
            item.put("avgIntervalDays", avgInterval);
            item.put("predictedNextOrderDate", predictedNext);
            item.put("alertLevel", alertLevel);
            items.add(item);
        }
        items.sort(Comparator
                .<Map<String, Object>, Integer>comparing(row -> alertOrder(String.valueOf(row.get("alertLevel"))))
                .thenComparing(row -> -((Number) row.get("daysSinceLastOrder")).intValue()));
        long alertCount = items.stream()
                .filter(row -> List.of("RED", "YELLOW").contains(String.valueOf(row.get("alertLevel"))))
                .count();
        return Map.of("alertCount", (int) alertCount, "items", items);
    }
}
