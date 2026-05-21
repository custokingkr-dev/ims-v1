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
