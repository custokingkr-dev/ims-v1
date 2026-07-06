package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.api.dto.DailyEntryRequest;
import com.custoking.ims.schoolcoreservice.api.dto.NotifyAbsenteesRequest;
import com.custoking.ims.schoolcoreservice.api.dto.SaveSectionRegisterRequest;
import com.custoking.ims.schoolcoreservice.api.dto.SubmitDayRequest;
import com.custoking.ims.schoolcoreservice.api.dto.SubmitSectionRequest;
import com.custoking.ims.schoolcoreservice.application.report.AttendanceReportCsv;
import com.custoking.ims.schoolcoreservice.application.report.AttendanceReportPdf;
import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository.DailyAttendanceRow;
import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository.StudentAttendanceRow;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceReadController {

    private final AttendanceReadRepository attendance;
    private final String readToken;

    public AttendanceReadController(
            AttendanceReadRepository attendance,
            @Value("${attendance.read-token:}") String readToken) {
        this.attendance = attendance;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/daily")
    public List<DailyAttendanceRow> daily(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) String sectionId,
            @RequestParam(required = false) String academicYearId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "attendance:read");
        return attendance.daily(sectionId, academicYearId, limit);
    }

    @GetMapping("/records")
    public List<StudentAttendanceRow> records(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return attendance.records(studentId, scope, date, limit);
    }

    @GetMapping("/daily-summary")
    public Map<String, Object> dailySummary(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.dailySummary(date, scope));
    }

    @GetMapping("/section-info")
    public Map<String, Object> sectionInfo(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String sectionId) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.sectionInfo(date, sectionId, scope));
    }

    @GetMapping("/section-register")
    public Map<String, Object> sectionRegister(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String classId,
            @RequestParam String sectionId) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.sectionRegister(date, classId, sectionId, scope));
    }

    @GetMapping("/absentees")
    public Map<String, Object> absentees(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String sectionId) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.absentees(date, sectionId, scope));
    }

    @PostMapping("/absentees/notify")
    public Map<String, Object> notifyAbsentees(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @Valid @RequestBody NotifyAbsenteesRequest body) {
        requireToken(token, "attendance:write");
        Long scope = TenantScope.resolveSchoolId(body.schoolId());
        LocalDate date = body.date() == null || body.date().isBlank() ? LocalDate.now() : LocalDate.parse(body.date());
        Long actorId = TenantContext.get() != null ? TenantContext.get().userId() : null;
        return execute(() -> attendance.notifyAbsentees(date, body.sectionId(), scope, actorId));
    }

    @GetMapping("/report/register")
    public Map<String, Object> reportRegister(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam String month,
            @RequestParam String classId,
            @RequestParam String sectionId) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.registerReport(month, classId, sectionId, scope));
    }

    @GetMapping("/report/student")
    public Map<String, Object> reportStudent(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.studentHistory(studentId, from, to, scope));
    }

    @GetMapping("/report/summary")
    public Map<String, Object> reportSummary(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return execute(() -> attendance.sectionSummary(from, to, scope));
    }

    @GetMapping("/report/register/export")
    public ResponseEntity<byte[]> exportRegister(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam String month, @RequestParam String classId, @RequestParam String sectionId,
            @RequestParam(defaultValue = "csv") String format) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> report = execute(() -> attendance.registerReport(month, classId, sectionId, scope));
        return respondReport("register-" + month, format, report, AttendanceReportCsv::register, AttendanceReportPdf::register);
    }

    @GetMapping("/report/student/export")
    public ResponseEntity<byte[]> exportStudent(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId, @RequestParam Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> report = execute(() -> attendance.studentHistory(studentId, from, to, scope));
        return respondReport("student-" + studentId + "-" + from + "_" + to, format, report,
                AttendanceReportCsv::student, AttendanceReportPdf::student);
    }

    @GetMapping("/report/summary/export")
    public ResponseEntity<byte[]> exportSummary(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format) {
        requireToken(token, "attendance:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        Map<String, Object> report = execute(() -> attendance.sectionSummary(from, to, scope));
        return respondReport("summary-" + from + "_" + to, format, report, AttendanceReportCsv::summary, AttendanceReportPdf::summary);
    }

    private ResponseEntity<byte[]> respondReport(String baseName, String format, Map<String, Object> report,
                                                 Function<Map<String, Object>, byte[]> csv,
                                                 Function<Map<String, Object>, byte[]> pdf) {
        String fmt = format == null ? "csv" : format.toLowerCase();
        byte[] body;
        String contentType;
        String ext;
        if ("csv".equals(fmt)) { body = csv.apply(report); contentType = "text/csv"; ext = "csv"; }
        else if ("pdf".equals(fmt)) { body = pdf.apply(report); contentType = MediaType.APPLICATION_PDF_VALUE; ext = "pdf"; }
        else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported format: " + format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + baseName + "." + ext)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(body);
    }

    // ─── PUT /section-register ───────────────────────────────────────────────

    @PutMapping("/section-register")
    public Map<String, Object> saveSectionRegister(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @Valid @RequestBody SaveSectionRegisterRequest body) {
        requireToken(token, "attendance:write");
        Long resolvedSchoolId = TenantScope.resolveSchoolId(body.schoolId());
        Map<String, Object> request = new HashMap<>();
        request.put("classId", body.classId());
        request.put("sectionId", body.sectionId());
        if (body.date() != null) request.put("date", body.date());
        if (body.actorId() != null) request.put("actorId", body.actorId());
        if (resolvedSchoolId != null) request.put("schoolId", resolvedSchoolId);
        if (body.records() != null) request.put("records", body.records());
        return execute(() -> attendance.saveSectionRegister(request));
    }

    // ─── POST /daily-entry ───────────────────────────────────────────────────

    @PostMapping("/daily-entry")
    public Map<String, Object> dailyEntry(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @Valid @RequestBody DailyEntryRequest body) {
        requireToken(token, "attendance:write");
        Long resolvedSchoolId = TenantScope.resolveSchoolId(body.schoolId());
        Map<String, Object> request = new HashMap<>();
        request.put("classId", body.classId());
        request.put("sectionId", body.sectionId());
        if (body.date() != null) request.put("date", body.date());
        if (body.totalEnrolled() != null) request.put("totalEnrolled", body.totalEnrolled());
        if (body.presentCount() != null) request.put("presentCount", body.presentCount());
        if (body.actorId() != null) request.put("actorId", body.actorId());
        if (resolvedSchoolId != null) request.put("schoolId", resolvedSchoolId);
        return execute(() -> attendance.saveDailyAttendance(request));
    }

    // ─── POST /submit-section ────────────────────────────────────────────────

    @PostMapping("/submit-section")
    public Map<String, Object> submitSection(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @Valid @RequestBody SubmitSectionRequest body) {
        requireToken(token, "attendance:write");
        Long resolvedSchoolId = TenantScope.resolveSchoolId(body.schoolId());
        Map<String, Object> request = new HashMap<>();
        request.put("classId", body.classId());
        request.put("sectionId", body.sectionId());
        if (body.date() != null) request.put("date", body.date());
        if (body.actorId() != null) request.put("actorId", body.actorId());
        if (resolvedSchoolId != null) request.put("schoolId", resolvedSchoolId);
        return execute(() -> attendance.submitAttendanceSection(request));
    }

    // ─── POST /submit-day — optional body, all fields optional, format-only ──

    @PostMapping("/submit-day")
    public Map<String, Object> submitDay(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @Valid @RequestBody(required = false) SubmitDayRequest body) {
        requireToken(token, "attendance:write");
        // required=false → body may be null; behave as an empty request in that case.
        SubmitDayRequest request = body == null ? new SubmitDayRequest(null, null, null) : body;
        Long scope = TenantScope.resolveSchoolId(request.schoolId());
        String date = request.date() == null ? "today" : request.date();
        Long actorId = request.actorId();
        return execute(() -> attendance.submitAttendanceDay(date, scope, actorId));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid attendance service token");
        }
    }

    private Map<String, Object> execute(Command command) {
        try {
            return command.run();
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date: " + ex.getParsedString(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private interface Command {
        Map<String, Object> run();
    }
}
