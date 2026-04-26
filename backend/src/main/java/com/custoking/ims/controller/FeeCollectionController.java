package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.service.FeeService;
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
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class FeeCollectionController {
    private final UserContextService userContext;
    private final StudentService studentService;
    private final FeeService feeService;

    public FeeCollectionController(UserContextService userContext,
                                    StudentService studentService,
                                    FeeService feeService) {
        this.userContext = userContext;
        this.studentService = studentService;
        this.feeService = feeService;
    }

    @GetMapping("/api/classes")
    public List<Map<String, Object>> classes(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.classesList(actor, schoolId);
    }

    @GetMapping("/api/classes/{classId}/sections")
    public List<Map<String, Object>> sections(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String classId,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.sectionsForClass(classId, actor, schoolId);
    }

    @GetMapping("/api/classes/{classId}/sections/{sectionId}/students")
    public List<Map<String, Object>> students(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String classId,
                                              @PathVariable String sectionId,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return studentService.studentsForClassSection(classId, sectionId, actor, schoolId);
    }

    @PostMapping("/api/fee-assignments")
    public Map<String, Object> assignFee(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.feeAssignmentApi(request, actor);
    }

    @PostMapping("/api/payments")
    public Map<String, Object> createPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.paymentApi(request, actor);
    }

    @GetMapping("/api/fees/report")
    public List<Map<String, Object>> feeReport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam String classId,
                                               @RequestParam String sectionId,
                                               @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return feeService.feeReport(classId, sectionId, actor, schoolId);
    }

    @GetMapping("/api/fees/overdue")
    public List<Map<String, Object>> feeOverdue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam String classId,
                                                @RequestParam String sectionId,
                                                @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return feeService.feeOverdue(classId, sectionId, actor, schoolId);
    }

    @GetMapping(value = "/api/receipts/{paymentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptPdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String paymentId) {
        userContext.requireUser(authorization);
        byte[] pdf = feeService.receiptPdfByPaymentId(paymentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + paymentId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/api/fees/send-reminders")
    public Map<String, Object> sendReminders(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.sendFeeReminders(
                String.valueOf(request.getOrDefault("classId", "")),
                String.valueOf(request.getOrDefault("sectionId", "")),
                actor, null);
    }

    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
