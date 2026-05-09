package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/workspace")
@PreAuthorize(PermissionConstants.WORKSPACE_ACCESS)
public class WorkspaceController {

    private final UserContextService userContext;
    private final WorkspaceService workspaceService;
    private final StudentService studentService;
    private final FeeService feeService;
    private final AttendanceService attendanceService;

    public WorkspaceController(UserContextService userContext,
                                WorkspaceService workspaceService,
                                StudentService studentService,
                                FeeService feeService,
                                AttendanceService attendanceService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
        this.studentService = studentService;
        this.feeService = feeService;
        this.attendanceService = attendanceService;
    }

    @GetMapping
    public Map<String, Object> workspace(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return workspaceService.workspace(actor, schoolId);
    }

    @PostMapping("/students")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> addStudent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return studentService.addStudent(request, actor);
    }

    @PostMapping("/bulk-import")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> bulkImport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return workspaceService.bulkImport(request);
    }

    @PostMapping("/fees/record-payment")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> recordPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return feeService.recordPayment(request, actor);
    }

    @PostMapping("/fee-structures")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> addFeeItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return feeService.addFeeItem(request);
    }

    @PostMapping("/fees/assign-plan")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> assignFeePlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        userContext.requireUser(authorization);
        return feeService.assignFeePlan(request);
    }

    @GetMapping(value = "/fees/receipts/{receiptNumber}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> feeReceiptPdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String receiptNumber) {
        userContext.requireUser(authorization);
        byte[] pdf = feeService.feeReceiptPdf(receiptNumber);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + receiptNumber + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/students/import/preview")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> previewStudentImport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        @SuppressWarnings("unchecked") var rows = (java.util.List<java.util.Map<String, Object>>)
                request.getOrDefault("rows", java.util.List.of());
        return studentService.previewStudentImport(rows, actor);
    }

    @PostMapping("/students/import/confirm")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> confirmStudentImport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return studentService.confirmStudentImport(String.valueOf(request.get("fileToken")), actor);
    }

    @GetMapping("/students/import/status/{jobId}")
    public Map<String, Object> studentImportStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String jobId) {
        userContext.requireUser(authorization);
        return studentService.importJobStatus(jobId);
    }

    @GetMapping(value = "/students/import/template",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> studentImportTemplate(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        byte[] body = studentService.studentImportTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student-import-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @PostMapping("/attendance")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> saveAttendance(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        return attendanceService.saveDailyAttendance(request, actor);
    }

    @PostMapping("/timetable")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> addTimetable(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody Map<String, Object> request) {
        userContext.requireUser(authorization);
        return workspaceService.addTimetableEntry(request);
    }

    @PostMapping("/staff")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> addStaff(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> request) {
        userContext.requireUser(authorization);
        return workspaceService.addStaff(request);
    }

    @PostMapping("/orders")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> createOrder(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, Object> request) {
        userContext.requireUser(authorization);
        return workspaceService.createOrder(request);
    }

    @PostMapping("/annual-plan")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> savePlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> request) {
        userContext.requireUser(authorization);
        return workspaceService.savePlan(request);
    }

    @PostMapping("/firefighting")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> createFirefighting(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        return workspaceService.createFirefightingRequest(request, actor.userId());
    }

    @PostMapping("/firefighting/{code}/{action}")
    @PreAuthorize(PermissionConstants.WRITE_ACCESS)
    public Map<String, Object> decideFirefighting(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable String code,
                                                  @PathVariable String action) {
        userContext.requireUser(authorization);
        return workspaceService.decideFirefighting(code, action);
    }
}
