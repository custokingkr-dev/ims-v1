package com.custoking.ims.platformservice.api.compat;

import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ReportingPublicCompatibilityController {

    private final ReportingReadRepository reporting;
    private final ReportingCommandRepository commands;
    private final String readToken;

    public ReportingPublicCompatibilityController(
            ReportingReadRepository reporting,
            ReportingCommandRepository commands,
            @Value("${reporting.read-token:}") String readToken) {
        this.reporting = reporting;
        this.commands = commands;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/api/v1/dashboard")
    public Map<String, Object> dashboard(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.summary(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/api/v1/workspace")
    public Map<String, Object> workspace(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> summary = reporting.workspaceDashboardSummary(scope);
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

    @GetMapping("/api/v1/dashboard/command-center")
    public Map<String, Object> dashboardCommandCenter(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.dashboardCommandCenter(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/api/v1/dashboard/vendor-dues")
    public Map<String, Object> vendorDues(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.vendorDues(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/api/v1/dashboard/reorder-signals")
    public Map<String, Object> reorderSignals(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.reorderSignals(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/api/v1/dashboard/attendance/low-sections")
    public Map<String, Object> lowAttendanceSections(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        requireToken(token, "reporting:read");
        return reporting.lowAttendanceSections(TenantScope.resolveSchoolId(schoolId), date);
    }

    @GetMapping("/api/v1/dashboard/attendance/sections/{sectionId}/low-students")
    public Object lowAttendanceStudents(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @PathVariable String sectionId) {
        requireToken(token, "reporting:read");
        return reporting.lowAttendanceStudents(TenantScope.resolveSchoolId(schoolId), sectionId);
    }

    @GetMapping("/api/v1/dashboard/finance/fee-defaulters")
    public Map<String, Object> feeDefaulters(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(required = false) Integer daysOverdue,
            @RequestParam(required = false) String reminderStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireToken(token, "reporting:read");
        return reporting.feeDefaulters(TenantScope.resolveSchoolId(schoolId), classId, sectionId, daysOverdue, reminderStatus, page, size);
    }

    @GetMapping("/api/v1/dashboard/events/class-photography/payment-status")
    public Object classPhotographyPaymentStatus(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireToken(token, "reporting:read");
        return reporting.classPhotographyPaymentStatus(TenantScope.resolveSchoolId(schoolId), classId, sectionId, status, page, size);
    }

    @GetMapping("/api/v1/command-centre/summary")
    public Map<String, Object> commandCenterSummary(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "false") boolean platform) {
        requireToken(token, "reporting:read");
        Long scope = TenantScope.resolveSchoolId(schoolId); // superadmin returns requested unchanged
        boolean effectivePlatform = platform && TenantContext.get().isSuperAdmin();
        return reporting.commandCenterSummary(scope, effectivePlatform);
    }

    @GetMapping("/api/v1/command-centre/actions")
    public Object actions(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.actions(TenantScope.resolveSchoolId(schoolId), status, limit);
    }

    @PostMapping("/api/v1/command-centre/actions/{id}/accept")
    public Map<String, Object> acceptAction(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        boolean superAdmin = TenantContext.get().isSuperAdmin();
        Long resolvedSchoolId = TenantScope.resolveSchoolId(actorSchoolId(body));
        return command(() -> commands.acceptAction(id, TenantContext.get().userId(), resolvedSchoolId, superAdmin));
    }

    @PostMapping("/api/v1/command-centre/actions/{id}/dismiss")
    public Map<String, Object> dismissAction(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        boolean superAdmin = TenantContext.get().isSuperAdmin();
        Long resolvedSchoolId = TenantScope.resolveSchoolId(actorSchoolId(body));
        return command(() -> commands.dismissAction(id, TenantContext.get().userId(), reason(body), resolvedSchoolId, superAdmin));
    }

    @GetMapping("/api/v1/command-centre/feed")
    public Object feed(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String module,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.feed(TenantScope.resolveSchoolId(schoolId), module, limit);
    }

    @GetMapping("/api/v1/command-centre/brief")
    public Map<String, Object> brief(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.commandCenterSummary(TenantScope.resolveSchoolId(schoolId), false);
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid reporting service token");
        }
        if ("reporting:read".equals(requiredScope)) {
            TenantScope.requirePermissionIfAuthenticated("report:read");
        }
    }

    private Map<String, Object> command(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || String.valueOf(value).isBlank()) return 0;
        return Double.parseDouble(String.valueOf(value));
    }

    private String text(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private Long actorSchoolId(Map<String, Object> body) {
        return longBody(body, "schoolId");
    }

    private Long longBody(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null || String.valueOf(body.get(key)).isBlank()) return null;
        Object value = body.get(key);
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private String reason(Map<String, Object> body) {
        return body == null ? "" : String.valueOf(body.getOrDefault("reason", ""));
    }

    private interface Command {
        Map<String, Object> run();
    }
}
