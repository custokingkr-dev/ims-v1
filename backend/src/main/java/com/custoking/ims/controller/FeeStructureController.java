package com.custoking.ims.controller;

import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.service.FeeService;
import com.custoking.ims.service.UserContextService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/fee-structure")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class FeeStructureController {
    private final UserContextService userContext;
    private final FeeService feeService;

    public FeeStructureController(UserContextService userContext, FeeService feeService) {
        this.userContext = userContext;
        this.feeService = feeService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(required = false) String academicYearId) {
        userContext.requireUser(authorization);
        return feeService.feeStructureData(academicYearId);
    }

    @PostMapping("/item")
    public Map<String, Object> addItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.addFeeStructureItem(request, actor);
    }

    @PutMapping("/item/{id}")
    public Map<String, Object> updateItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.updateFeeStructureItem(id, request, actor);
    }

    @DeleteMapping("/item/{id}")
    public Map<String, Object> deleteItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.deleteFeeStructureItem(id, actor);
    }

    @PostMapping("/band")
    public Map<String, Object> createBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.createFeeStructureBand(request, actor);
    }

    @PutMapping("/band/{id}")
    public Map<String, Object> updateBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.updateFeeStructureBand(id, request, actor);
    }

    @DeleteMapping("/band/{id}")
    public Map<String, Object> deleteBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.deleteFeeStructureBand(id, actor);
    }

    @PatchMapping("/band/{id}")
    public Map<String, Object> patchBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        forbidSuperAdmin(actor);
        return feeService.patchFeeStructureBand(id, request, actor);
    }

    @GetMapping("/match")
    public Map<String, Object> matchBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam String classId) {
        userContext.requireUser(authorization);
        return feeService.matchFeeStructureBand(classId);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(required = false) String academicYearId,
                                         @RequestParam(defaultValue = "pdf") String format) {
        userContext.requireUser(authorization);
        byte[] pdf = feeService.exportFeeStructurePdf(academicYearId, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fee-structure-"
                        + (academicYearId == null ? "current" : academicYearId) + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private void forbidSuperAdmin(AuthUser actor) {
        if (actor.role() == Role.SUPERADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "SUPERADMIN cannot perform school ERP operations. Use a school admin account.");
        }
    }
}
