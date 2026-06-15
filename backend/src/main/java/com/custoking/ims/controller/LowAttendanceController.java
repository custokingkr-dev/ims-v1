package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.LowAttendanceService;
import com.custoking.ims.commandcenter.dto.*;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard/attendance")
public class LowAttendanceController {

    private final LowAttendanceService lowAttendanceService;

    public LowAttendanceController(LowAttendanceService lowAttendanceService) {
        this.lowAttendanceService = lowAttendanceService;
    }

    @GetMapping("/low-sections")
    @PreAuthorize(PermissionConstants.ATTENDANCE_READ)
    public LowAttendanceSectionsResponse getLowAttendanceSections(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return lowAttendanceService.getLowAttendanceSections(
                TenantContext.get(),
                date != null ? date : LocalDate.now());
    }

    @GetMapping("/sections/{sectionId}/low-students")
    @PreAuthorize(PermissionConstants.ATTENDANCE_READ)
    public List<LowAttendanceStudentItem> getStudentsForSection(
            @PathVariable String sectionId) {
        return lowAttendanceService.getStudentsForSection(TenantContext.get(), sectionId);
    }

    @PostMapping("/meeting-invites")
    @PreAuthorize(PermissionConstants.ATTENDANCE_MANAGE)
    public SendMeetingInvitesResult sendMeetingInvites(
            @Valid @RequestBody SendMeetingInvitesRequest request,
            @AuthenticationPrincipal AuthUser actor) {
        return lowAttendanceService.sendMeetingInvites(TenantContext.get(), request, actor);
    }
}
