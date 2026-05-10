package com.custoking.ims.controller;

import com.custoking.ims.dto.workflow.WorkflowActionRequest;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.WorkflowService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows")
@PreAuthorize("isAuthenticated()")
public class WorkflowController {

    private final UserContextService userContext;
    private final WorkflowService workflowService;

    public WorkflowController(UserContextService userContext, WorkflowService workflowService) {
        this.userContext = userContext;
        this.workflowService = workflowService;
    }

    @GetMapping("/pending")
    public List<Map<String, Object>> getPendingApprovals(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long schoolId) {
        userContext.requireUser(authorization);
        return workflowService.getPendingApprovals(schoolId);
    }

    @GetMapping("/{instanceId}/actions")
    public List<Map<String, Object>> getActions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long instanceId) {
        userContext.requireUser(authorization);
        return workflowService.getInstanceActions(instanceId);
    }

    @PostMapping("/{instanceId}/approve")
    public Map<String, Object> approve(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long instanceId,
            @RequestBody(required = false) WorkflowActionRequest request) {
        AuthUser actor = userContext.requireUser(authorization);
        var instance = workflowService.approve(instanceId, actor.userId(), actor.email(),
                request != null ? request.notes() : null);
        return Map.of("id", instance.getId(), "status", instance.getStatus(),
                "currentStep", instance.getCurrentStep());
    }

    @PostMapping("/{instanceId}/reject")
    public Map<String, Object> reject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long instanceId,
            @RequestBody(required = false) WorkflowActionRequest request) {
        AuthUser actor = userContext.requireUser(authorization);
        var instance = workflowService.reject(instanceId, actor.userId(), actor.email(),
                request != null ? request.notes() : null);
        return Map.of("id", instance.getId(), "status", instance.getStatus(),
                "currentStep", instance.getCurrentStep());
    }
}
