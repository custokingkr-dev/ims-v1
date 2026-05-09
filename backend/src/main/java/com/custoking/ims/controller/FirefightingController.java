package com.custoking.ims.controller;

import com.custoking.ims.service.FirefightingService;
import com.custoking.ims.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ff")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
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
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Map<String, Object> request) {
        return firefightingService.createFireRequest(request, userContext.requireUser(authorization));
    }

    @PatchMapping("/requests/{id}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestBody Map<String, Object> request) {
        return firefightingService.updateFireRequest(id, request, userContext.requireUser(authorization));
    }

    @PatchMapping("/requests/{id}/quotations/{quotationId}")
    public Map<String, Object> updateQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id,
                                               @PathVariable String quotationId,
                                               @RequestBody Map<String, Object> request) {
        return firefightingService.updateFireQuotation(id, quotationId, request, userContext.requireUser(authorization));
    }

    @GetMapping("/requests/{id}")
    public Map<String, Object> detail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return firefightingService.fireRequestDetail(id, userContext.requireUser(authorization), schoolId);
    }

    @PostMapping("/requests/{id}/quotations")
    public Map<String, Object> addQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable String id,
                                            @RequestBody Map<String, Object> request) {
        return firefightingService.addFireQuotation(id, request, userContext.requireUser(authorization));
    }

    @DeleteMapping("/requests/{id}/quotations/{quotationId}")
    public Map<String, Object> deleteQuotation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id,
                                               @PathVariable String quotationId) {
        return firefightingService.deleteFireQuotation(id, quotationId, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/submit")
    public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id) {
        return firefightingService.submitFireRequest(id, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/approve-bursar")
    public Map<String, Object> approveBursar(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String id,
                                             @RequestBody Map<String, Object> request) {
        return firefightingService.approveFireBursar(id, request, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/approve-principal")
    public Map<String, Object> approvePrincipal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id,
                                                @RequestBody Map<String, Object> request) {
        return firefightingService.approveFirePrincipal(id, request, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/reject")
    public Map<String, Object> reject(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id,
                                      @RequestBody Map<String, Object> request) {
        return firefightingService.rejectFireRequest(id, request, userContext.requireUser(authorization));
    }

    @PostMapping("/requests/{id}/approve-custoking")
    public Map<String, Object> approveCustoking(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id) {
        return firefightingService.approveCustoking(id, userContext.requireUser(authorization));
    }

    @PatchMapping("/requests/{id}/fulfill")
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
