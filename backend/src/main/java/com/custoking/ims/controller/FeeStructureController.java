package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.FeeService;
import com.custoking.ims.service.ModuleEntitlementService;
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
@PreAuthorize(PermissionConstants.FEE_READ)
public class FeeStructureController {
    private final UserContextService userContext;
    private final FeeService feeService;
    private final ModuleEntitlementService moduleService;

    public FeeStructureController(UserContextService userContext, FeeService feeService,
                                  ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.feeService = feeService;
        this.moduleService = moduleService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(required = false) String academicYearId) {
        userContext.requireUser(authorization);
        return feeService.feeStructureData(academicYearId);
    }

    @PostMapping("/item")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> addItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.addFeeStructureItem(request, actor);
    }

    @PutMapping("/item/{id}")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> updateItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.updateFeeStructureItem(id, request, actor);
    }

    @DeleteMapping("/item/{id}")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> deleteItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.deleteFeeStructureItem(id, actor);
    }

    @PostMapping("/band")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> createBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.createFeeStructureBand(request, actor);
    }

    @PutMapping("/band/{id}")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> updateBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id,
                                          @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.updateFeeStructureBand(id, request, actor);
    }

    @DeleteMapping("/band/{id}")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> deleteBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.deleteFeeStructureBand(id, actor);
    }

    @PatchMapping("/band/{id}")
    @PreAuthorize(PermissionConstants.FEE_CONFIGURE)
    public Map<String, Object> patchBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id,
                                         @RequestBody Map<String, Object> request) {
        AuthUser actor = userContext.requireUser(authorization);
        requireSchoolModule(ModuleEntitlementService.Module.FEES);
        return feeService.patchFeeStructureBand(id, request, actor);
    }

    @GetMapping("/match")
    public Map<String, Object> matchBand(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam String classId) {
        userContext.requireUser(authorization);
        return feeService.matchFeeStructureBand(classId);
    }

    @GetMapping(value = "/export", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(PermissionConstants.FEE_EXPORT)
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

    private void requireSchoolModule(ModuleEntitlementService.Module module) {
        if (userContext.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Platform admins cannot perform school ERP operations. Use a school admin account.");
        }
        moduleService.requireModule(TenantContext.get(), module);
    }
}
