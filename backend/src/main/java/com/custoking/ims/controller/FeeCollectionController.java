package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.custoking.ims.model.Role;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class FeeCollectionController {
    private final DatabaseStore store;

    public FeeCollectionController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping("/api/classes")
    public List<Map<String, Object>> classes(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.classesList(actor, schoolId);
    }

    @GetMapping("/api/classes/{classId}/sections")
    public List<Map<String, Object>> sections(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String classId,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.sectionsForClass(classId, actor, schoolId);
    }

    @GetMapping("/api/classes/{classId}/sections/{sectionId}/students")
    public List<Map<String, Object>> students(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String classId,
                                              @PathVariable String sectionId,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.studentsForClassSection(classId, sectionId, actor, schoolId);
    }

    @PostMapping("/api/fee-assignments")
    public Map<String, Object> assignFee(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.feeAssignmentApi(request, actor);
    }

    @PostMapping("/api/payments")
    public Map<String, Object> createPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.paymentApi(request, actor);
    }

    @GetMapping("/api/fees/report")
    public List<Map<String, Object>> feeReport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam String classId,
                                               @RequestParam String sectionId,
                                               @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.feeReport(classId, sectionId, actor, schoolId);
    }

    @GetMapping("/api/fees/overdue")
    public List<Map<String, Object>> feeOverdue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam String classId,
                                                @RequestParam String sectionId,
                                                @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.feeOverdue(classId, sectionId, actor, schoolId);
    }

    @GetMapping(value = "/api/receipts/{paymentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptPdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String paymentId) {
        store.requireUser(authorization);
        byte[] pdf = store.receiptPdfByPaymentId(paymentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + paymentId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/api/fees/send-reminders")
    public Map<String, Object> sendReminders(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.sendFeeReminders(String.valueOf(request.getOrDefault("classId", "")), String.valueOf(request.getOrDefault("sectionId", "")), actor, null);
    }

    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
