package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.dto.ApprovalDecisionRequest;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/approvals")
@PreAuthorize(PermissionConstants.ORDER_READ)
public class ApprovalController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;

    public ApprovalController(UserContextService userContext, WorkspaceService workspaceService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var actor = userContext.requireUser(authorization);
        return workspaceService.approvals(actor);
    }

    @PostMapping("/{id}/{action}")
    @PreAuthorize(PermissionConstants.ORDER_APPROVE)
    public Map<String, Object> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable long id,
                                      @PathVariable String action,
                                      @RequestBody(required = false) ApprovalDecisionRequest request) {
        AuthUser user = userContext.requireUser(authorization);
        if (user.role() != Role.SUPERADMIN) {
            throw new IllegalArgumentException("Only super admin can review approvals");
        }
        return workspaceService.decideApproval(id, action, request);
    }
}
