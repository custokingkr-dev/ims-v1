package com.custoking.ims.controller;

import com.custoking.ims.dto.PaymentCreateRequest;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/billing-payments")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class PaymentController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;

    public PaymentController(UserContextService userContext, WorkspaceService workspaceService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return workspaceService.invoices();
    }

    @PostMapping
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody PaymentCreateRequest request) {
        var user = userContext.requireUser(authorization);
        return workspaceService.addPayment(request, user);
    }
}
