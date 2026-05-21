package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
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
@RequestMapping("/api/v1/students/import")
@PreAuthorize(PermissionConstants.STUDENT_IMPORT)
public class StudentImportController {
    private final UserContextService userContext;
    private final StudentService studentService;
    private final ModuleEntitlementService moduleService;

    public StudentImportController(UserContextService userContext, StudentService studentService,
                                   ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.studentService = studentService;
        this.moduleService = moduleService;
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.STUDENTS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) request.getOrDefault("rows", List.of());
        return studentService.previewStudentImport(rows, actor);
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.STUDENTS);
        return studentService.confirmStudentImport(String.valueOf(request.get("fileToken")), actor);
    }

    @GetMapping("/status/{jobId}")
    public Map<String, Object> status(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String jobId) {
        userContext.requireUser(authorization);
        return studentService.importJobStatus(jobId);
    }

    @GetMapping(value = "/template", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        byte[] body = studentService.studentImportTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=student-import-template.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    private void forbidSuperAdmin(AuthUser ignored) {
        if (userContext.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform admins cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
