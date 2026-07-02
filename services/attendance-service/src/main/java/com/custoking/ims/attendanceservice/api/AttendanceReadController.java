package com.custoking.ims.attendanceservice.api;

import com.custoking.ims.attendanceservice.api.dto.DailyEntryRequest;
import com.custoking.ims.attendanceservice.api.dto.SaveSectionRegisterRequest;
import com.custoking.ims.attendanceservice.api.dto.SubmitSectionRequest;
import com.custoking.ims.attendanceservice.persistence.AttendanceReadRepository;
import com.custoking.ims.attendanceservice.persistence.AttendanceReadRepository.DailyAttendanceRow;
import com.custoking.ims.attendanceservice.persistence.AttendanceReadRepository.StudentAttendanceRow;
import com.custoking.ims.attendanceservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // ─── POST /submit-day — optional body, no required fields; SKIPPED ───────

    @PostMapping("/submit-day")
    public Map<String, Object> submitDay(
            @RequestHeader(value = "X-Attendance-Service-Token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "attendance:write");
        Long scope = applyResolvedSchool(request);
        String date = request == null ? "today" : String.valueOf(request.getOrDefault("date", "today"));
        Long actorId = request == null || request.get("actorId") == null
                ? null
                : Long.valueOf(String.valueOf(request.get("actorId")));
        return execute(() -> attendance.submitAttendanceDay(date, scope, actorId));
    }

    private Long applyResolvedSchool(Map<String, Object> request) {
        Long schoolId;
        if (request == null || request.get("schoolId") == null) {
            schoolId = null;
        } else {
            try {
                schoolId = Long.valueOf(String.valueOf(request.get("schoolId")));
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId");
            }
        }
        return TenantScope.resolveSchoolId(schoolId);
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
