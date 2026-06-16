package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.dto.firefighting.FirefightingDecisionRequest;
import com.custoking.ims.dto.firefighting.FirefightingQuotationRequest;
import com.custoking.ims.dto.firefighting.FirefightingRequestCommand;
import com.custoking.ims.service.FirefightingService;
import com.custoking.ims.service.UserContextService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ff")
@PreAuthorize(PermissionConstants.FIREFIGHTING_READ)
public class FirefightingController {
    private final UserContextService userContext;
    private final FirefightingService firefightingService;

    public FirefightingController(UserContextService userContext, FirefightingService firefightingService) {
        this.userContext = userContext;
        this.firefightingService = firefightingService;
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
                                      @Valid @RequestBody FirefightingRequestCommand request) {
        return firefightingService.createFireRequest(request.toMap(), userContext.requireUser(authorization));
    }

    @PatchMapping("/requests/{id}")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @Valid @RequestBody FirefightingRequestCommand request) {
        return firefightingService.updateFireRequest(id, request.toMap(), userContext.requireUser(authorization));
    }

    @PatchMapping("/requests/{id}/quotations/{quotationId}")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> updateQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id,
                                               @PathVariable String quotationId,
                                               @Valid @RequestBody FirefightingQuotationRequest request) {
        return firefightingService.updateFireQuotation(id, quotationId, request.toMap(), userContext.requireUser(authorization));
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
                                            @Valid @RequestBody FirefightingQuotationRequest request) {
        return firefightingService.addFireQuotation(id, request.toMap(), userContext.requireUser(authorization));
    }

    @DeleteMapping("/requests/{id}/quotations/{quotationId}")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> deleteQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id,
                                               @PathVariable String quotationId) {
        return firefightingService.deleteFireQuotation(id, quotationId, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/submit")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_UPDATE)
    public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id) {
        return firefightingService.submitFireRequest(id, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/approve-bursar")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_APPROVE)
    public Map<String, Object> approveBursar(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String id,
                                             @RequestBody(required = false) FirefightingDecisionRequest request) {
        return firefightingService.approveFireBursar(id, request == null ? Map.of() : request.toMap(), userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/approve-principal")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_APPROVE)
    public Map<String, Object> approvePrincipal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id,
                                                @RequestBody(required = false) FirefightingDecisionRequest request) {
        return firefightingService.approveFirePrincipal(id, request == null ? Map.of() : request.toMap(), userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/reject")
    @PreAuthorize(PermissionConstants.FIREFIGHTING_APPROVE)
    public Map<String, Object> reject(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestBody(required = false) FirefightingDecisionRequest request) {
        return firefightingService.rejectFireRequest(id, request == null ? Map.of() : request.toMap(), userContext.requireUser(authorization));
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
