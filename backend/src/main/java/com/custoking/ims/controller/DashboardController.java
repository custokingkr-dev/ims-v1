package com.custoking.ims.controller;

import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkspaceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("hasAnyRole('ADMIN','SUPERADMIN')")
public class DashboardController {
    private final UserContextService userContext;
    private final WorkspaceService workspaceService;

    public DashboardController(UserContextService userContext, WorkspaceService workspaceService) {
        this.userContext = userContext;
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestParam(value = "schoolId", required = false) Long schoolId) {
        var actor = userContext.requireUser(authorization);
        return workspaceService.workspace(actor, schoolId);
    }
}
