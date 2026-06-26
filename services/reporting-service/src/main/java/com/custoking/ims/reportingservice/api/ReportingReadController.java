package com.custoking.ims.reportingservice.api;

import com.custoking.ims.reportingservice.persistence.ReportingReadRepository;
import com.custoking.ims.reportingservice.persistence.ReportingCommandRepository;
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
        return reporting.summary(schoolId);
    }

    @GetMapping("/vendor-dues")
    public Map<String, Object> vendorDues(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.vendorDues(schoolId);
    }

    @GetMapping("/reorder-signals")
    public Map<String, Object> reorderSignals(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.reorderSignals(schoolId);
    }

    @GetMapping("/dashboard-command-center")
    public Map<String, Object> dashboardCommandCenter(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.dashboardCommandCenter(schoolId);
    }

    @GetMapping("/low-attendance/sections")
    public Map<String, Object> lowAttendanceSections(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        requireToken(token, "reporting:read");
        return reporting.lowAttendanceSections(schoolId, date);
    }

    @GetMapping("/low-attendance/sections/{sectionId}/students")
    public Object lowAttendanceStudents(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @PathVariable String sectionId) {
        requireToken(token, "reporting:read");
        return reporting.lowAttendanceStudents(schoolId, sectionId);
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
        return reporting.feeDefaulters(schoolId, classId, sectionId, daysOverdue, reminderStatus, page, size);
    }

    @GetMapping("/command-center/feed")
    public Object feed(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String module,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.feed(schoolId, module, limit);
    }

    @GetMapping("/command-center/actions")
    public Object actions(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.actions(schoolId, status, limit);
    }

    @GetMapping("/command-center/summary")
    public Map<String, Object> commandCenterSummary(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "false") boolean platform) {
        requireToken(token, "reporting:read");
        return reporting.commandCenterSummary(schoolId, platform);
    }

    @PostMapping("/command-center/actions/{id}/accept")
    public Map<String, Object> acceptAction(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "reporting:write");
        return command(() -> commands.acceptAction(id, actorId(body), actorSchoolId(body), superAdmin(body)));
    }

    @PostMapping("/command-center/actions/{id}/dismiss")
    public Map<String, Object> dismissAction(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "reporting:write");
        return command(() -> commands.dismissAction(
                id,
                actorId(body),
                body == null ? null : String.valueOf(body.getOrDefault("reason", "")),
                actorSchoolId(body),
                superAdmin(body)));
    }

    @PostMapping("/command-center/feed")
    public Map<String, Object> recordFeed(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "reporting:write");
        return command(() -> commands.recordFeed(body));
    }

    @PostMapping("/event-contributions/reminders")
    public Map<String, Object> markEventContributionReminders(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "reporting:write");
        return command(() -> commands.markEventContributionReminders(body));
    }

    @PostMapping("/event-contributions/reminder-targets")
    public Map<String, Object> eventPaymentReminderTargets(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "reporting:write");
        return command(() -> commands.eventPaymentReminderTargets(body));
    }

    @GetMapping("/invoices")
    public Object invoices(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.invoices(schoolId, status, limit);
    }

    @GetMapping("/invoices/stats")
    public Object invoiceStats(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "reporting:read");
        return reporting.invoiceStats(schoolId);
    }

    @GetMapping("/academic-events")
    public Object academicEvents(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.academicEvents(schoolId, status, limit);
    }

    @GetMapping("/event-contributions")
    public Object eventContributions(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.eventContributions(eventId, schoolId, limit);
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
        return reporting.classPhotographyPaymentStatus(schoolId, classId, sectionId, status, page, size);
    }

    @GetMapping("/broadcasts")
    public Object broadcasts(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        return reporting.broadcasts(schoolId, status, limit);
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

    private Long actorId(Map<String, Object> body) {
        if (body == null || body.get("actorId") == null || String.valueOf(body.get("actorId")).isBlank()) {
            return null;
        }
        Object value = body.get("actorId");
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private Long actorSchoolId(Map<String, Object> body) {
        if (body == null || body.get("schoolId") == null || String.valueOf(body.get("schoolId")).isBlank()) {
            return null;
        }
        Object value = body.get("schoolId");
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private boolean superAdmin(Map<String, Object> body) {
        return body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("superAdmin", false)));
    }

    private interface Command {
        Map<String, Object> run();
    }
}

