package com.custoking.ims.controller;

import com.custoking.ims.service.SupplyOrderService;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.UserContextService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/supply")
@PreAuthorize(PermissionConstants.ORDER_READ)
public class SupplyController {
    private final UserContextService userContext;
    private final SupplyOrderService supplyOrderService;
    private final ModuleEntitlementService moduleService;

    public SupplyController(UserContextService userContext, SupplyOrderService supplyOrderService,
                            ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.supplyOrderService = supplyOrderService;
        this.moduleService = moduleService;
    }

    @GetMapping("/catalog-categories")
    public Object catalogCategories(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return supplyOrderService.catalogCategories();
    }

    @PostMapping("/orders")
    @PreAuthorize(PermissionConstants.ORDER_CREATE)
    public Map<String, Object> createOrder(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.ORDERS);
        return supplyOrderService.createCatalogOrder(request, actor);
    }

    @PostMapping("/orders/{orderId}/place")
    @PreAuthorize(PermissionConstants.ORDER_CREATE)
    public Map<String, Object> placeOrder(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String orderId) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.ORDERS);
        return supplyOrderService.placeCatalogOrder(orderId, actor);
    }

    @GetMapping("/orders")
    public Object orders(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return supplyOrderService.listCatalogOrders(userContext.requireUser(authorization), schoolId);
    }

    @GetMapping("/orders/stats")
    public Map<String, Object> orderStats(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return supplyOrderService.catalogOrderStats(userContext.requireUser(authorization), schoolId);
    }

    @GetMapping("/orders/pending-approval")
    @PreAuthorize(PermissionConstants.SUPERADMIN_ACCESS)
    public Object pendingApprovalOrders(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return supplyOrderService.listOrdersPendingApproval(userContext.requireSuperAdmin(authorization));
    }

    @PatchMapping("/orders/{orderId}/status")
    @PreAuthorize(PermissionConstants.ORDER_UPDATE)
    public Map<String, Object> updateOrderStatus(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String orderId,
                                                 @RequestBody Map<String, Object> request) {
        return supplyOrderService.updateCatalogOrderStatus(orderId, request, userContext.requireUser(authorization));
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> orderDetail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String orderId) {
        return supplyOrderService.catalogOrderDetail(orderId, userContext.requireUser(authorization));
    }

    @PostMapping("/orders/{orderId}/design-approved")
    @PreAuthorize(PermissionConstants.ORDER_UPDATE)
    public Map<String, Object> markDesignApproved(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable String orderId) {
        return supplyOrderService.markCatalogOrderDesignApproved(orderId, userContext.requireUser(authorization));
    }

    @PostMapping("/orders/{orderId}/superadmin-approve")
    @PreAuthorize(PermissionConstants.SUPERADMIN_ACCESS)
    public Map<String, Object> superadminApprove(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable String orderId) {
        return supplyOrderService.superadminApproveOrder(orderId, userContext.requireSuperAdmin(authorization));
    }

    @PostMapping("/orders/{orderId}/superadmin-reject")
    @PreAuthorize(PermissionConstants.SUPERADMIN_ACCESS)
    public Map<String, Object> superadminReject(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @PathVariable String orderId,
                                                 @RequestBody Map<String, Object> request) {
        return supplyOrderService.superadminRejectOrder(orderId, request, userContext.requireSuperAdmin(authorization));
    }

    @GetMapping("/annual-plan")
    public Map<String, Object> annualPlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "schoolId", required = false) Long schoolId) {
        return supplyOrderService.listAnnualPlan(userContext.requireUser(authorization), schoolId);
    }

    @PostMapping("/annual-plan/items")
    @PreAuthorize(PermissionConstants.ORDER_CREATE)
    public Map<String, Object> savePlanItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody Map<String, Object> request) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.ORDERS);
        return supplyOrderService.saveAnnualPlanItem(request, actor);
    }

    @PostMapping("/annual-plan/confirm")
    @PreAuthorize(PermissionConstants.ORDER_CREATE)
    public Map<String, Object> confirmPlan(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var actor = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.ORDERS);
        return supplyOrderService.confirmAnnualPlan(actor);
    }
}
