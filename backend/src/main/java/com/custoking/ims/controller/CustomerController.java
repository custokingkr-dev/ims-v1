package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.dto.CustomerCreateRequest;
import com.custoking.ims.service.ModuleEntitlementService;
import com.custoking.ims.service.ModuleEntitlementService.Module;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
@PreAuthorize(PermissionConstants.CUSTOMER_READ)
public class CustomerController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;
    private final ModuleEntitlementService moduleService;

    public CustomerController(UserContextService userContext, WorkspaceService workspaceService,
                              ModuleEntitlementService moduleService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
        this.moduleService = moduleService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), Module.INVOICES);
        return workspaceService.customers();
    }

    @PostMapping
    @PreAuthorize(PermissionConstants.CUSTOMER_CREATE)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody CustomerCreateRequest request) {
        userContext.requireUser(authorization);
        moduleService.requireModule(TenantContext.get(), Module.INVOICES);
        return workspaceService.addCustomer(Map.of("code", request.code(), "name", request.name(),
                "email", request.email(), "phone", request.phone(), "gstin", request.gstin()));
    }
}
