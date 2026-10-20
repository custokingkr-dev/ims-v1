package com.custoking.ims.platformservice.persistence;


import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure formatting shared by reporting query groups. */
final class ReportingPresentation {
    private ReportingPresentation() {}

    static String academicYearLabel(String yearId) {
        if (yearId == null || !yearId.matches("ay_\\d{4}_\\d{2}")) {
            return yearId;
        }
        return yearId.substring(3, 7) + "-" + yearId.substring(8, 10);
    }

    static double lakh(long paise) {
        double value = paise / 100.0 / 100000.0;
        return Math.round(value * 100.0) / 100.0;
    }

    static long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return 0L;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    static Map<String, Object> row(Object... values) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    static int alertOrder(String level) {
        return switch (level) {
            case "RED" -> 0;
            case "YELLOW" -> 1;
            default -> 2;
        };
    }

    static Map<String, Object> kpi(String key, String label, String value, String delta,
                                           String status, String drilldownTarget, String drilldownFilter) {
        return row("key", key, "label", label, "value", value, "delta", delta, "status", status,
                "drilldownTarget", drilldownTarget, "drilldownFilter", drilldownFilter);
    }

    static Map<String, Object> dashboardCommandCenterRow(
            long defaulterCount,
            long totalOverdueAmountPaise,
            int oldestDueDays,
            String eventId,
            long collectedAmount,
            long pendingAmount,
            long targetAmount,
            int pendingReviewCount,
            int longAbsenceCount,
            int sectionsBelowThresholdCount,
            int thresholdPercent,
            Map<String, Object> vendorDues,
            Map<String, Object> reorderSignals) {
        return row(
                "fees", row(
                        "defaulterCount", defaulterCount,
                        "totalOverdueAmountPaise", totalOverdueAmountPaise,
                        "oldestDueDays", oldestDueDays),
                "photography", row(
                        "eventId", eventId,
                        "collectedAmount", collectedAmount,
                        "pendingAmount", pendingAmount,
                        "targetAmount", targetAmount),
                "lifecycle", row(
                        "pendingReviewCount", pendingReviewCount,
                        "longAbsenceCount", longAbsenceCount),
                "attendance", row(
                        "sectionsBelowThresholdCount", sectionsBelowThresholdCount,
                        "thresholdPercent", thresholdPercent),
                "vendorDues", row(
                        "catalogOrderCount", longValue(vendorDues.get("catalogOrderCount")),
                        "catalogOrderTotalPaise", longValue(vendorDues.get("catalogOrderTotalPaise")),
                        "firefightingCount", longValue(vendorDues.get("firefightingCount")),
                        "firefightingTotalPaise", longValue(vendorDues.get("firefightingTotalPaise")),
                        "totalDuesPaise", longValue(vendorDues.get("totalDuesPaise"))),
                "reorderSignals", row(
                        "alertCount", (int) longValue(reorderSignals.get("alertCount"))));
    }

    static Map<String, Object> feeDefaulterRow(long totalDefaulters,
                                                       long totalOverdueAmount,
                                                       int oldestDueDays,
                                                       List<Map<String, Object>> items,
                                                       int page,
                                                       int size,
                                                       long totalElements) {
        return row(
                "totalDefaulters", totalDefaulters,
                "totalOverdueAmount", totalOverdueAmount,
                "oldestDueDays", oldestDueDays,
                "items", items,
                "page", page,
                "size", size,
                "totalElements", totalElements);
    }

    static String reminderStatus(String rawStatus, OffsetDateTime sentAt) {
        if (sentAt == null && (rawStatus == null || rawStatus.isBlank())) {
            return "NOT_SENT";
        }
        if ("SENT".equalsIgnoreCase(rawStatus)) return "SENT";
        if ("FAILED".equalsIgnoreCase(rawStatus)) return "FAILED";
        return "PENDING";
    }

    static String formatLakh(long paise) {
        double rupees = paise / 100.0;
        if (rupees >= 100000) return String.format("₹%.1fL", rupees / 100000);
        if (rupees >= 1000) return String.format("₹%.0fK", rupees / 1000);
        return String.format("₹%.0f", rupees);
    }
}
