package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.FeeService;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.StudentService;
import com.custoking.ims.service.UserContextService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@PreAuthorize(PermissionConstants.STUDENT_READ)
public class FeeCollectionController {
    private final UserContextService userContext;
    private final StudentService studentService;
    private final FeeService feeService;
    private final ModuleEntitlementService moduleService;

    public FeeCollectionController(UserContextService userContext,
                                    StudentService studentService,
                                    FeeService feeService,
                                    ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.studentService = studentService;
        this.feeService = feeService;
        this.moduleService = moduleService;
    }

    @GetMapping("/api/v1/classes")
    public List<Map<String, Object>> classes(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.classesList(actor, schoolId);
    }

    @GetMapping("/api/v1/classes/{classId}/sections")
    public List<Map<String, Object>> sections(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String classId,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.sectionsForClass(classId, actor, schoolId);
    }

    @GetMapping("/api/v1/classes/{classId}/sections/{sectionId}/students")
    public List<Map<String, Object>> students(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String classId,
                                              @PathVariable String sectionId,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.studentsForClassSection(classId, sectionId, actor, schoolId);
    }

    @PostMapping("/api/v1/fee-assignments")
    @PreAuthorize(PermissionConstants.FEE_COLLECT)
    public Map<String, Object> assignFee(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.feeAssignmentApi(request, actor);
    }

    @PostMapping("/api/v1/payments")
    @PreAuthorize(PermissionConstants.FEE_COLLECT)
    public Map<String, Object> createPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.paymentApi(request, actor);
    }

    @GetMapping("/api/v1/fees/report")
    @PreAuthorize(PermissionConstants.FEE_READ)
    public List<Map<String, Object>> feeReport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam String classId,
                                               @RequestParam String sectionId,
                                               @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return feeService.feeReport(classId, sectionId, actor, schoolId);
    }

    @GetMapping("/api/v1/fees/overdue")
    @PreAuthorize(PermissionConstants.FEE_READ)
    public List<Map<String, Object>> feeOverdue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam String classId,
                                                @RequestParam String sectionId,
                                                @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return feeService.feeOverdue(classId, sectionId, actor, schoolId);
    }

    @GetMapping(value = "/api/v1/receipts/{paymentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(PermissionConstants.FEE_READ)
    public ResponseEntity<byte[]> receiptPdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String paymentId) {
        userContext.requireUser(authorization);
        byte[] pdf = feeService.receiptPdfByPaymentId(paymentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + paymentId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/api/v1/fees/send-reminders")
    @PreAuthorize(PermissionConstants.FEE_COLLECT)
    public Map<String, Object> sendReminders(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.sendFeeReminders(
                String.valueOf(request.getOrDefault("classId", "")),
                String.valueOf(request.getOrDefault("sectionId", "")),
                actor, null);
    }

    private void requireSchoolModule(ModuleEntitlementService.Module module) {
        if (userContext.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform admins cannot perform school ERP operations. Use a school admin account.");
        }
        moduleService.requireModule(TenantContext.get(), module);
    }
}
