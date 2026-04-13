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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.custoking.ims.model.Role;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/students/import")
public class StudentImportController {
    private final DatabaseStore store;

    public StudentImportController(DatabaseStore store) {
        this.store = store;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) request.getOrDefault("rows", List.of());
        return store.previewStudentImport(rows, actor);
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = store.requireUser(authorization);
        forbidSuperAdmin(actor);
        return store.confirmStudentImport(String.valueOf(request.get("fileToken")), actor);
    }

    @GetMapping("/status/{jobId}")
    public Map<String, Object> status(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String jobId) {
        store.requireUser(authorization);
        return store.importJobStatus(jobId);
    }

    @GetMapping(value = "/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        byte[] body = store.studentImportTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student-import-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }


    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
