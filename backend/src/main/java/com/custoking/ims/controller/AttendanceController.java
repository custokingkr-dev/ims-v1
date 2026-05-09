package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.service.AttendanceService;
import com.custoking.ims.service.UserContextService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/attendance")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class AttendanceController {
    private final UserContextService userContext;
    private final AttendanceService attendanceService;

    public AttendanceController(UserContextService userContext, AttendanceService attendanceService) {
        this.userContext = userContext;
        this.attendanceService = attendanceService;
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
    public Map<String, Object> dailyEntry(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return attendanceService.saveDailyAttendance(request, actor);
    }

    @PostMapping("/submit-day")
    public Map<String, Object> submitDay(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return attendanceService.submitAttendanceDay(String.valueOf(request.getOrDefault("date", "today")), actor);
    }

    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
