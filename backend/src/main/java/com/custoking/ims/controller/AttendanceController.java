package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import com.custoking.ims.model.Role;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {
    private final DatabaseStore store;

    public AttendanceController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping("/daily-summary")
    public Map<String, Object> dailySummary(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam String date,
                                            @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.attendanceDailySummary(date, actor, schoolId);
    }

    @GetMapping("/section-info")
    public Map<String, Object> sectionInfo(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestParam String date,
                                           @RequestParam String classId,
                                           @RequestParam String sectionId,
                                           @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.attendanceSectionInfo(date, classId, sectionId, actor, schoolId);
    }

    @PostMapping("/daily-entry")
    public Map<String, Object> dailyEntry(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.saveDailyAttendance(request, actor);
    }

    @PostMapping("/submit-day")
    public Map<String, Object> submitDay(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.submitAttendanceDay(String.valueOf(request.getOrDefault("date", "today")), actor);
    }


    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
