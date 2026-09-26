package com.custoking.ims.platformservice.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared stable browser workspace view, retained by both canonical and legacy routes. */
public final class ReportingWorkspaceView {
    private ReportingWorkspaceView() {}
    public static Map<String, Object> assemble(Map<String, Object> summary) {
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        double feeCollectedPaise = number(summary.get("feeCollectedPaise"));
        double feeTargetPaise = number(summary.get("feeTargetPaise"));
        double feeOutstandingPaise = Math.max(0, feeTargetPaise - feeCollectedPaise);
        double feeProgressPercent = feeTargetPaise <= 0
                ? 0
                : Math.round((feeCollectedPaise * 10000) / feeTargetPaise) / 100.0;
        LinkedHashMap<String, Object> school = new LinkedHashMap<>();
        school.put("name", text(summary.get("schoolName"), "Custoking School"));
        school.put("meta", text(summary.get("schoolMeta"), "Service workspace"));
        school.put("academicYearStartMonth", number(summary.getOrDefault("academicYearStartMonth", 4)));
        school.put("financialYearStartMonth", number(summary.getOrDefault("financialYearStartMonth", 4)));
        school.put("timeZone", text(summary.get("timeZone"), "Asia/Kolkata"));
        school.put("countryCode", text(summary.get("countryCode"), "IN"));
        school.put("locale", text(summary.get("locale"), "en-IN"));
        school.put("currencyCode", text(summary.get("currencyCode"), "INR"));
        school.put("phoneRegion", text(summary.get("phoneRegion"), "IN"));
        school.put("students", number(summary.get("students")));
        school.put("sections", number(summary.get("sections")));
        response.put("school", school);
        LinkedHashMap<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("students", number(summary.get("students")));
        dashboard.put("sections", number(summary.get("sections")));
        dashboard.put("attendancePercent", number(summary.get("attendancePercent")));
        dashboard.put("attendancePresent", number(summary.get("attendancePresent")));
        dashboard.put("attendanceSubmittedSections", number(summary.get("attendanceSubmittedSections")));
        dashboard.put("attendanceState", text(summary.get("attendanceState"), "NOT_STARTED"));
        dashboard.put("feeCollectedLakh", number(summary.get("feeCollectedLakh")));
        dashboard.put("feeTargetLakh", number(summary.get("feeTargetLakh")));
        dashboard.put("feesConfigured", booleanValue(summary.get("feesConfigured")));
        dashboard.put("feeOverdueCount", number(summary.get("feeOverdueCount")));
        dashboard.put("firefightingActive", number(summary.get("firefightingActive")));
        dashboard.put("pendingApprovals", number(summary.get("pendingApprovals")));
        response.put("dashboard", dashboard);
        response.put("recentActivity", List.of());
        response.put("students", Map.of(
                "content", List.of(),
                "totalElements", number(summary.get("students"))));
        response.put("fees", Map.of(
                "summary", Map.of(
                        "progressPercent", feeProgressPercent,
                        "collected", feeCollectedPaise,
                        "outstanding", feeOutstandingPaise,
                        "overdueCount", number(summary.get("feeOverdueCount")),
                        "target", feeTargetPaise),
                "records", List.of()));
        response.put("feeStructures", List.of());
        response.put("attendance", Map.of(
                "summary", Map.of(
                        "overallPercent", number(summary.get("attendancePercent")),
                        "submittedSections", number(summary.get("attendanceSubmittedSections")),
                        "state", text(summary.get("attendanceState"), "NOT_STARTED")),
                "classes", List.of()));
        response.put("staff", List.of());
        response.put("catalog", List.of());
        response.put("orders", List.of());
        response.put("annualPlan", Map.of("terms", List.of()));
        response.put("firefighting", Map.of("requests", List.of()));
        response.put("users", List.of());
        return response;
    }

    private static double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || String.valueOf(value).isBlank()) return 0;
        return Double.parseDouble(String.valueOf(value));
    }

    private static String text(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

}
