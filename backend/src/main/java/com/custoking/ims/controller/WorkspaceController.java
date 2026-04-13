package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.custoking.ims.model.Role;

import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
    private final DatabaseStore store;

    public WorkspaceController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> workspace(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = store.requireUser(authorization);
        return store.workspace(actor, schoolId);
    }

    @PostMapping("/students")
    public Map<String, Object> addStudent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return store.addStudent(request, actor);
    }

    @PostMapping("/bulk-import")
    public Map<String, Object> bulkImport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return store.bulkImport(request);
    }

    @PostMapping("/fees/record-payment")
    public Map<String, Object> recordPayment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return store.recordPayment(request, actor);
    }

    @PostMapping("/fee-structures")
    public Map<String, Object> addFeeItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return store.addFeeItem(request);
    }


    @PostMapping("/fees/assign-plan")
    public Map<String, Object> assignFeePlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.assignFeePlan(request);
    }

    @GetMapping(value = "/fees/receipts/{receiptNumber}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> feeReceiptPdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String receiptNumber) {
        store.requireUser(authorization);
        byte[] pdf = store.feeReceiptPdf(receiptNumber);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + receiptNumber + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @PostMapping("/students/import/preview")
    public Map<String, Object> previewStudentImport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        @SuppressWarnings("unchecked") var rows = (java.util.List<java.util.Map<String, Object>>) request.getOrDefault("rows", java.util.List.of());
        return store.previewStudentImport(rows, actor);
    }

    @PostMapping("/students/import/confirm")
    public Map<String, Object> confirmStudentImport(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        if (actor.role() == Role.SUPERADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        return store.confirmStudentImport(String.valueOf(request.get("fileToken")), actor);
    }

    @GetMapping("/students/import/status/{jobId}")
    public Map<String, Object> studentImportStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String jobId) {
        store.requireUser(authorization);
        return store.importJobStatus(jobId);
    }

    @GetMapping(value = "/students/import/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> studentImportTemplate(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        byte[] body = store.studentImportTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student-import-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @PostMapping("/attendance")
    public Map<String, Object> saveAttendance(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.saveAttendance(request);
    }

    @PostMapping("/timetable")
    public Map<String, Object> addTimetable(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.addTimetableEntry(request);
    }

    @PostMapping("/staff")
    public Map<String, Object> addStaff(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.addStaff(request);
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.createOrder(request);
    }

    @PostMapping("/annual-plan")
    public Map<String, Object> savePlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.savePlan(request);
    }

    @PostMapping("/firefighting")
    public Map<String, Object> createFirefighting(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestBody Map<String, Object> request) {
        store.requireUser(authorization);
        return store.createFirefightingRequest(request);
    }

    @PostMapping("/firefighting/{code}/{action}")
    public Map<String, Object> decideFirefighting(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable String code,
                                                  @PathVariable String action) {
        store.requireUser(authorization);
        return store.decideFirefighting(code, action);
    }
}
