package com.custoking.ims.controller;

import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/supply")
public class SupplyController {
    private final DatabaseStore store;
    public SupplyController(DatabaseStore store) { this.store = store; }

    @GetMapping("/catalog-categories")
    public Object catalogCategories(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        return store.catalogCategories();
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return store.createCatalogOrder(request, store.requireUser(authorization));
    }

    @PostMapping("/orders/{orderId}/place")
    public Map<String, Object> placeOrder(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String orderId) {
        return store.placeCatalogOrder(orderId, store.requireUser(authorization));
    }

    @GetMapping("/orders")
    public Object orders(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return store.listCatalogOrders(store.requireUser(authorization), schoolId);
    }

    @GetMapping("/orders/stats")
    public Map<String, Object> orderStats(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return store.catalogOrderStats(store.requireUser(authorization), schoolId);
    }

    @GetMapping("/orders/pending-approval")
    public Object pendingApprovalOrders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return store.listOrdersPendingApproval(store.requireSuperAdmin(authorization));
    }

    @PatchMapping("/orders/{orderId}/status")
    public Map<String, Object> updateOrderStatus(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String orderId, @RequestBody Map<String, Object> request) {
        return store.updateCatalogOrderStatus(orderId, request, store.requireUser(authorization));
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> orderDetail(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String orderId) {
        return store.catalogOrderDetail(orderId, store.requireUser(authorization));
    }

    @PostMapping("/orders/{orderId}/design-approved")
    public Map<String, Object> markDesignApproved(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String orderId) {
        return store.markCatalogOrderDesignApproved(orderId, store.requireUser(authorization));
    }

    @PostMapping("/orders/{orderId}/superadmin-approve")
    public Map<String, Object> superadminApprove(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String orderId) {
        return store.superadminApproveOrder(orderId, store.requireSuperAdmin(authorization));
    }

    @PostMapping("/orders/{orderId}/superadmin-reject")
    public Map<String, Object> superadminReject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String orderId,
            @RequestBody Map<String, Object> request) {
        return store.superadminRejectOrder(orderId, request, store.requireSuperAdmin(authorization));
    }

    @GetMapping("/annual-plan")
    public Map<String, Object> annualPlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return store.listAnnualPlan(store.requireUser(authorization), schoolId);
    }

    @PostMapping("/annual-plan/items")
    public Map<String, Object> savePlanItem(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return store.saveAnnualPlanItem(request, store.requireUser(authorization));
    }

    @PostMapping("/annual-plan/confirm")
    public Map<String, Object> confirmPlan(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return store.confirmAnnualPlan(store.requireUser(authorization));
    }
}
