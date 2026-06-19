package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.dto.PaymentCreateRequest;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/billing-payments")
@PreAuthorize(PermissionConstants.PAYMENT_READ)
public class PaymentController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;
    private final ModuleEntitlementService moduleService;

    public PaymentController(UserContextService userContext, WorkspaceService workspaceService,
                             ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
        this.moduleService = moduleService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return workspaceService.invoices();
    }

    @PostMapping
    @PreAuthorize(PermissionConstants.PAYMENT_CREATE)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody PaymentCreateRequest request) {
        var user = userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), ModuleEntitlementService.Module.PAYMENTS);
        return workspaceService.addPayment(request, user);
    }
}
