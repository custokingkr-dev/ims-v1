package com.custoking.ims.platformservice.api;

import com.custoking.ims.platformservice.api.dto.EventContributionReminderRequest;
import com.custoking.ims.platformservice.api.dto.EventContributionReminderTargetsRequest;
import com.custoking.ims.platformservice.api.dto.CommandCenterActionRequest;
import com.custoking.ims.platformservice.api.dto.RecordFeedRequest;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository;
import com.custoking.ims.platformservice.persistence.ReportingCommandRepository;
import com.custoking.ims.platformservice.security.TenantContext;
import com.custoking.ims.platformservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reporting")
public class ReportingReadController {

    private final ReportingReadRepository reporting;
    private final ReportingCommandRepository commands;
    private final String readToken;

    public ReportingReadController(
            ReportingReadRepository reporting,
            ReportingCommandRepository commands,
            @Value("${reporting.read-token:}") String readToken) {
        this.reporting = reporting;
        this.commands = commands;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.summary(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/vendor-dues")
    public Map<String, Object> vendorDues(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.vendorDues(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/reorder-signals")
    public Map<String, Object> reorderSignals(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.reorderSignals(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/dashboard-command-center")
    public Map<String, Object> dashboardCommandCenter(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.dashboardCommandCenter(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/low-attendance/sections")
    public Map<String, Object> lowAttendanceSections(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.lowAttendanceSections(TenantScope.resolveSchoolId(schoolId), date);
    }

    @GetMapping("/low-attendance/sections/{sectionId}/students")
    public Object lowAttendanceStudents(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @PathVariable String sectionId) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.lowAttendanceStudents(TenantScope.resolveSchoolId(schoolId), sectionId);
    }

    @GetMapping("/fee-defaulters")
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
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.feeDefaulters(TenantScope.resolveSchoolId(schoolId), classId, sectionId, daysOverdue, reminderStatus, page, size);
    }

    @GetMapping("/command-center/feed")
    public Object feed(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String module,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.feed(TenantScope.resolveSchoolId(schoolId), module, limit);
    }

    @GetMapping("/command-center/actions")
    public Object actions(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.actions(TenantScope.resolveSchoolId(schoolId), status, limit);
    }

    @GetMapping("/command-center/summary")
    public Map<String, Object> commandCenterSummary(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "false") boolean platform) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        Long scope = TenantScope.resolveSchoolId(schoolId); // superadmin returns requested unchanged
        boolean effectivePlatform = platform && TenantContext.get().isSuperAdmin();
        return reporting.commandCenterSummary(scope, effectivePlatform);
    }

    @PostMapping("/command-center/actions/{id}/accept")
    public Map<String, Object> acceptAction(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CommandCenterActionRequest body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        boolean superAdmin = TenantContext.get().isSuperAdmin();
        Long resolvedSchoolId = TenantScope.resolveSchoolId(body == null ? null : body.schoolId());
        return command(() -> commands.acceptAction(id, TenantContext.get().userId(), resolvedSchoolId, superAdmin));
    }

    @PostMapping("/command-center/actions/{id}/dismiss")
    public Map<String, Object> dismissAction(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CommandCenterActionRequest body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        boolean superAdmin = TenantContext.get().isSuperAdmin();
        Long resolvedSchoolId = TenantScope.resolveSchoolId(body == null ? null : body.schoolId());
        return command(() -> commands.dismissAction(
                id,
                TenantContext.get().userId(),
                body == null ? null : (body.reason() == null ? "" : body.reason()),
                resolvedSchoolId,
                superAdmin));
    }

    @PostMapping("/command-center/feed")
    public Map<String, Object> recordFeed(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @Valid @RequestBody RecordFeedRequest body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        Map<String, Object> request = body.toMap();
        request.put("schoolId", TenantScope.resolveSchoolId(body.schoolId()));
        return command(() -> commands.recordFeed(request));
    }

    @PostMapping("/event-contributions/reminders")
    public Map<String, Object> markEventContributionReminders(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @Valid @RequestBody EventContributionReminderRequest body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("notification:send");
        Map<String, Object> request = body.toMap();
        request.put("schoolId", TenantScope.resolveSchoolId(body.schoolId()));
        return command(() -> commands.markEventContributionReminders(request));
    }

    @PostMapping("/event-contributions/reminder-targets")
    public Map<String, Object> eventPaymentReminderTargets(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @Valid @RequestBody EventContributionReminderTargetsRequest body) {
        requireToken(token, "reporting:write");
        TenantScope.requirePermissionIfAuthenticated("notification:send");
        Map<String, Object> request = body.toMap();
        request.put("schoolId", TenantScope.resolveSchoolId(body.schoolId()));
        return command(() -> commands.eventPaymentReminderTargets(request));
    }

    @GetMapping("/invoices")
    public Object invoices(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.invoices(TenantScope.resolveSchoolId(schoolId), status, limit);
    }

    @GetMapping("/invoices/stats")
    public Object invoiceStats(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.invoiceStats(TenantScope.resolveSchoolId(schoolId));
    }

    @GetMapping("/academic-events")
    public Object academicEvents(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.academicEvents(TenantScope.resolveSchoolId(schoolId), status, limit);
    }

    @GetMapping("/event-contributions")
    public Object eventContributions(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.eventContributions(eventId, TenantScope.resolveSchoolId(schoolId), limit);
    }

    @GetMapping("/class-photography/payment-status")
    public Object classPhotographyPaymentStatus(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) String sectionId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.classPhotographyPaymentStatus(TenantScope.resolveSchoolId(schoolId), classId, sectionId, status, page, size);
    }

    @GetMapping("/broadcasts")
    public Object broadcasts(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requirePermissionIfAuthenticated("report:read");
        return reporting.broadcasts(TenantScope.resolveSchoolId(schoolId), status, limit);
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid reporting service token");
        }
    }

    private Map<String, Object> command(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
            HttpStatus status = message.toLowerCase().contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, message, ex);
        }
    }


    private interface Command {
        Map<String, Object> run();
    }
}

