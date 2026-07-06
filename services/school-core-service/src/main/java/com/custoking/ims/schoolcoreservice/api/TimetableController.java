package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.TimetableRepository;
import com.custoking.ims.schoolcoreservice.persistence.YearLockedException;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class TimetableController {

    private final TimetableRepository timetable;
    private final String readToken;

    public TimetableController(
            TimetableRepository timetable,
            @Value("${tenant-school.read-token:}") String readToken) {
        this.timetable = timetable;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/api/v1/timetable/bell-schedules")
    public List<Map<String, Object>> bellSchedules(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(value = "schoolId", required = false) Long schoolIdParam) {
        requireToken(token, "tenant-school:read");
        Long schoolId = TenantScope.resolveSchoolId(schoolIdParam);
        return timetable.bellSchedules(schoolId);
    }

    @PostMapping("/api/v1/timetable/bell-schedules")
    public Map<String, Object> createSchedule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        String name = requireText(request.get("name"), "name is required");
        try {
            return timetable.createSchedule(schoolId, name);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PutMapping("/api/v1/timetable/bell-schedules/{id}")
    public void renameSchedule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("id") long id,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        String name = requireText(request.get("name"), "name is required");
        try {
            timetable.renameSchedule(schoolId, id, name);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/api/v1/timetable/bell-schedules/{id}")
    public void deleteSchedule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("id") long id) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        timetable.deleteSchedule(schoolId, id);
    }

    @PostMapping("/api/v1/timetable/bell-schedules/{id}/periods")
    public Map<String, Object> addPeriod(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("id") long scheduleId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        try {
            return timetable.addPeriod(schoolId, scheduleId,
                    requireText(request.get("label"), "label is required"),
                    requireText(request.get("start"), "start is required"),
                    requireText(request.get("end"), "end is required"),
                    booleanValue(request.get("isBreak")),
                    intValue(request.get("sortOrder")));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PutMapping("/api/v1/timetable/bell-schedules/{id}/periods/{periodId}")
    public void updatePeriod(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("id") long scheduleId,
            @PathVariable("periodId") long periodId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        try {
            timetable.updatePeriod(schoolId, periodId,
                    requireText(request.get("label"), "label is required"),
                    requireText(request.get("start"), "start is required"),
                    requireText(request.get("end"), "end is required"),
                    booleanValue(request.get("isBreak")),
                    intValue(request.get("sortOrder")));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/api/v1/timetable/bell-schedules/{id}/periods/{periodId}")
    public void deletePeriod(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("id") long scheduleId,
            @PathVariable("periodId") long periodId) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        timetable.deletePeriod(schoolId, periodId);
    }

    @GetMapping("/api/v1/timetable/class-schedules")
    public List<Map<String, Object>> classSchedules(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(value = "schoolId", required = false) Long schoolIdParam) {
        requireToken(token, "tenant-school:read");
        Long schoolId = TenantScope.resolveSchoolId(schoolIdParam);
        return timetable.classSchedules(schoolId);
    }

    @PutMapping("/api/v1/timetable/class-schedules/{classId}")
    public void setClassSchedule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("classId") String classId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        long scheduleId = longValue(request.get("scheduleId"),
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduleId is required"));
        timetable.setClassSchedule(schoolId, classId, scheduleId);
    }

    @GetMapping("/api/v1/timetable/class-subjects")
    public Map<String, Object> classSubjects(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(value = "schoolId", required = false) Long schoolIdParam,
            @RequestParam(value = "classId") String classId,
            @RequestParam(value = "yearId", required = false) String yearIdParam) {
        requireToken(token, "tenant-school:read");
        Long schoolId = TenantScope.resolveSchoolId(schoolIdParam);
        String yearId = StringUtils.hasText(yearIdParam) ? yearIdParam : timetable.activeYearId(schoolId);
        return timetable.classSubjects(schoolId, classId, yearId);
    }

    @PostMapping("/api/v1/timetable/class-subjects")
    public Map<String, Object> addSubject(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        String classId = requireText(request.get("classId"), "classId is required");
        String subjectName = requireText(request.get("subjectName"), "subjectName is required");
        String yearId = timetable.activeYearId(schoolId);
        try {
            return timetable.addSubject(schoolId, classId, yearId, subjectName);
        } catch (YearLockedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/api/v1/timetable/class-subjects/{id}")
    public void deleteSubject(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable("id") long id) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSchoolAdmin();
        Long schoolId = TenantScope.resolveSchoolId(null);
        try {
            timetable.deleteSubject(schoolId, id);
        } catch (YearLockedException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant-school service token");
        }
    }

    private String requireText(Object value, String errorMessage) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
        return text;
    }

    private boolean booleanValue(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sortOrder must be a number");
        }
    }

    private long longValue(Object value, java.util.function.Supplier<ResponseStatusException> onMissing) {
        if (value == null || String.valueOf(value).isBlank()) throw onMissing.get();
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw onMissing.get();
        }
    }
}
