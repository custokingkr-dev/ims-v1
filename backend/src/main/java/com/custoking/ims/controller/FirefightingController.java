package com.custoking.ims.controller;

import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/ff")
public class FirefightingController {
    private final DatabaseStore store;
    public FirefightingController(DatabaseStore store) { this.store = store; }

    @GetMapping("/requests") public Object requests(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam(value = "schoolId", required = false) Long schoolId) { return store.listFireRequests(store.requireUser(authorization), schoolId); }
    @GetMapping("/requests/stats") public Map<String, Object> stats(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam(value = "schoolId", required = false) Long schoolId) { return store.fireRequestStats(store.requireUser(authorization), schoolId); }
    @PostMapping("/requests") public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) { return store.createFireRequest(request, store.requireUser(authorization)); }
    @GetMapping("/requests/{id}") public Map<String, Object> detail(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @RequestParam(value = "schoolId", required = false) Long schoolId) { return store.fireRequestDetail(id, store.requireUser(authorization), schoolId); }
    @PostMapping("/requests/{id}/quotations") public Map<String, Object> addQuotation(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @RequestBody Map<String, Object> request) { return store.addFireQuotation(id, request, store.requireUser(authorization)); }
    @DeleteMapping("/requests/{id}/quotations/{quotationId}") public Map<String, Object> deleteQuotation(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @PathVariable String quotationId) { return store.deleteFireQuotation(id, quotationId, store.requireUser(authorization)); }
    @PostMapping("/requests/{id}/submit") public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) { return store.submitFireRequest(id, store.requireUser(authorization)); }
    @PostMapping("/requests/{id}/approve-bursar") public Map<String, Object> approveBursar(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @RequestBody Map<String, Object> request) { return store.approveFireBursar(id, request, store.requireUser(authorization)); }
    @PostMapping("/requests/{id}/approve-principal") public Map<String, Object> approvePrincipal(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @RequestBody Map<String, Object> request) { return store.approveFirePrincipal(id, request, store.requireUser(authorization)); }
    @PostMapping("/requests/{id}/reject") public Map<String, Object> reject(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @RequestBody Map<String, Object> request) { return store.rejectFireRequest(id, request, store.requireUser(authorization)); }
    @PatchMapping("/requests/{id}/fulfill") public Map<String, Object> fulfill(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) { return store.fulfillFireRequest(id, store.requireUser(authorization)); }
    @GetMapping("/requests/pending-approvals") public Object pending(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam(value = "schoolId", required = false) Long schoolId) { return store.pendingFireApprovals(store.requireUser(authorization), schoolId); }
    @GetMapping("/requests/{id}/timeline") public Object timeline(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id, @RequestParam(value = "schoolId", required = false) Long schoolId) { return store.fireRequestTimeline(id, store.requireUser(authorization), schoolId); }
}
