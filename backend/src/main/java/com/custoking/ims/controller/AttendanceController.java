package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.AttendanceService;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.UserContextService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/attendance")
@PreAuthorize(PermissionConstants.ATTENDANCE_READ)
public class AttendanceController {
    private final UserContextService userContext;
    private final AttendanceService attendanceService;
    private final ModuleEntitlementService moduleService;

    public AttendanceController(UserContextService userContext, AttendanceService attendanceService,
                                ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.attendanceService = attendanceService;
        this.moduleService = moduleService;
    }

    @GetMapping("/daily-summary")
    public Map<String, Object> dailySummary(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam String date,
                                            @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return attendanceService.attendanceDailySummary(date, actor, schoolId);
    }

    @GetMapping("/section-info")
    public Map<String, Object> sectionInfo(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestParam String date,
                                           @RequestParam String classId,
                                           @RequestParam String sectionId,
                                           @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return attendanceService.attendanceSectionInfo(date, classId, sectionId, actor, schoolId);
    }

    /**
     * GET /api/v1/attendance/section-register
     * Load students and attendance records for a section on a given date.
     */
    @GetMapping("/section-register")
    public Map<String, Object> getSectionRegister(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam String date,
                                                   @RequestParam String classId,
                                                   @RequestParam String sectionId) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.ATTENDANCE);
        LocalDate parsedDate = LocalDate.parse(date);
        return attendanceService.getSectionRegister(parsedDate, classId, sectionId, actor);
    }

    /**
     * PUT /api/v1/attendance/section-register
     * Save/update student attendance records for a section.
     */
    @PutMapping("/section-register")
    @PreAuthorize(PermissionConstants.ATTENDANCE_MANAGE)
    public Map<String, Object> saveSectionRegister(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.ATTENDANCE);
        
        String date = (String) request.get("date");
        String classId = (String) request.get("classId");
        String sectionId = (String) request.get("sectionId");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) request.get("records");
        
        if (date == null || classId == null || sectionId == null || records == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                "Missing required fields: date, classId, sectionId, records");
        }
        
        LocalDate parsedDate = LocalDate.parse(date);
        return attendanceService.saveSectionRegister(parsedDate, classId, sectionId, records, actor);
    }

    /**
     * POST /api/v1/attendance/submit-section
     * Lock a section's attendance (requires all students to have records).
     */
    @PostMapping("/submit-section")
    @PreAuthorize(PermissionConstants.ATTENDANCE_MANAGE)
    public Map<String, Object> submitSection(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.ATTENDANCE);
        
        String date = (String) request.get("date");
        String classId = (String) request.get("classId");
        String sectionId = (String) request.get("sectionId");
        
        if (date == null || classId == null || sectionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                "Missing required fields: date, classId, sectionId");
        }
        
        LocalDate parsedDate = LocalDate.parse(date);
        return attendanceService.submitSection(parsedDate, classId, sectionId, actor);
    }

    @PostMapping("/daily-entry")
    @PreAuthorize(PermissionConstants.ATTENDANCE_MANAGE)
    public Map<String, Object> dailyEntry(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.ATTENDANCE);
        return attendanceService.saveDailyAttendance(request, actor);
    }

    @PostMapping("/submit-day")
    @PreAuthorize(PermissionConstants.ATTENDANCE_MANAGE)
    public Map<String, Object> submitDay(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.ATTENDANCE);
        return attendanceService.submitAttendanceDay(String.valueOf(request.getOrDefault("date", "today")), actor);
    }

    private void requireSchoolModule(ModuleEntitlementService.Module module) {
        if (userContext.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform admins cannot perform school ERP operations. Use a school admin account.");
        }
        moduleService.requireModule(TenantContext.get(), module);
    }
}
