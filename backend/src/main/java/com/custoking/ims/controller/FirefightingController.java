package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.FirefightingService;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ff")
@PreAuthorize(PermissionConstants.FIREFIGHTING_READ)
public class FirefightingController {
    private final UserContextService userContext;
    private final FirefightingService firefightingService;
    private final ModuleEntitlementService moduleService;

    public FirefightingController(UserContextService userContext, FirefightingService firefightingService,
                                  ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.firefightingService = firefightingService;
        this.moduleService = moduleService;
    }

    @GetMapping("/requests")
    public Object requests(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return firefightingService.listFireRequests(userContext.requireUser(authorization), schoolId);
    }

    @GetMapping("/requests/stats")
    public Map<String, Object> stats(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return firefightingService.fireRequestStats(userContext.requireUser(authorization), schoolId);
    }

    @PostMapping("/requests")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_CREATE)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.createFireRequest(request, actor);
    }

    @PatchMapping("/requests/{id}")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.updateFireRequest(id, request, actor);
    }

    @PatchMapping("/requests/{id}/quotations/{quotationId}")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> updateQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id,
                                               @PathVariable String quotationId,
                                               @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.updateFireQuotation(id, quotationId, request, actor);
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> detail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return firefightingService.fireRequestDetail(id, userContext.requireUser(authorization), schoolId);
    }

    @PostMapping("/requests/{id}/quotations")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> addQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable String id,
                                            @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.addFireQuotation(id, request, actor);
    }

    @DeleteMapping("/requests/{id}/quotations/{quotationId}")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> deleteQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id,
                                               @PathVariable String quotationId) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.deleteFireQuotation(id, quotationId, actor);
    }

    @PostMapping("/requests/{id}/submit")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.submitFireRequest(id, actor);
    }

    @PostMapping("/requests/{id}/approve-bursar")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_APPROVE)
    public Map<String, Object> approveBursar(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String id,
                                             @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.approveFireBursar(id, request, actor);
    }

    @PostMapping("/requests/{id}/approve-principal")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_APPROVE)
    public Map<String, Object> approvePrincipal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id,
                                                @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.approveFirePrincipal(id, request, actor);
    }

    @PostMapping("/requests/{id}/reject")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_APPROVE)
    public Map<String, Object> reject(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.FIREFIGHTING);
        return firefightingService.rejectFireRequest(id, request, actor);
    }

    @PostMapping("/requests/{id}/approve-custoking")
    @PreAuthorize(PermissionConstants.SUPERADMIN_ACCESS)
    public Map<String, Object> approveCustoking(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id) {
        return firefightingService.approveCustoking(id, userContext.requireUser(authorization));
    }

    @PatchMapping("/requests/{id}/fulfill")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_FULFILL)
    public Map<String, Object> fulfill(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id) {
        return firefightingService.fulfillFireRequest(id, userContext.requireUser(authorization));
    }

    @GetMapping("/requests/pending-approvals")
    public Object pending(@RequestHeader(value = "Authorization", required = false) String authorization,
                          @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return firefightingService.pendingFireApprovals(userContext.requireUser(authorization), schoolId);
    }

    @GetMapping("/requests/{id}/timeline")
    public Object timeline(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable String id,
                           @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return firefightingService.fireRequestTimeline(id, userContext.requireUser(authorization), schoolId);
    }
}
