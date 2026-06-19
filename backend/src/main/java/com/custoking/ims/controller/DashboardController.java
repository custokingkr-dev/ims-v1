package com.custoking.ims.controller;

import com.custoking.ims.commandcenter.DashboardCommandCenterResponse;
import com.custoking.ims.commandcenter.DashboardCommandCenterService;
import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.context.TenantContext;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize(PermissionConstants.WORKSPACE_ACCESS)
public class DashboardController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;
    private final DashboardCommandCenterService dashboardCommandCenterService;

    public DashboardController(UserContextService userContext,
                               WorkspaceService workspaceService,
                               DashboardCommandCenterService dashboardCommandCenterService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
        this.dashboardCommandCenterService = dashboardCommandCenterService;
    }

    @GetMapping
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return workspaceService.workspace(actor, schoolId);
    }

    @GetMapping("/command-centre")
    public List<Map<String, Object>> commandCentre(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var actor = userContext.requireUser(authorization);
        return workspaceService.commandCentreCards(actor, TenantContext.get());
    }

    @GetMapping("/command-center")
    @PreAuthorize(PermissionConstants.DASHBOARD_VIEW)
    public DashboardCommandCenterResponse commandCenter(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return dashboardCommandCenterService.getCommandCenter(TenantContext.get());
    }
}
