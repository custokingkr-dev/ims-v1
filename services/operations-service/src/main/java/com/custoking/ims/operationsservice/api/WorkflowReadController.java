package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.dto.CreateInstanceRequest;
import com.custoking.ims.operationsservice.api.dto.WorkflowActionRequest;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository.WorkflowActionRow;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository.WorkflowDefinitionRow;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository.WorkflowInstanceRow;
import com.custoking.ims.operationsservice.persistence.WorkflowReadRepository.WorkflowStepRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowReadController {

    private final WorkflowReadRepository workflows;
    private final String readToken;

    public WorkflowReadController(
            WorkflowReadRepository workflows,
            @Value("${workflow.read-token:}") String readToken) {
        this.workflows = workflows;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/definitions")
    public List<WorkflowDefinitionRow> definitions(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        requireToken(token, "workflow:read");
        TenantScope.requirePermissionIfAuthenticated("workflow:read");
        return workflows.definitions(activeOnly);
    }

    @GetMapping("/definitions/{definitionId}/steps")
    public List<WorkflowStepRow> steps(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable String definitionId) {
        requireToken(token, "workflow:read");
        TenantScope.requirePermissionIfAuthenticated("workflow:read");
        return workflows.steps(definitionId);
    }

    @GetMapping("/instances")
    public List<WorkflowInstanceRow> instances(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "workflow:read");
        TenantScope.requirePermissionIfAuthenticated("workflow:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return workflows.instances(scope, status, entityType, limit);
    }

    @GetMapping("/pending")
    public List<WorkflowInstanceRow> pending(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "workflow:read");
        TenantScope.requirePermissionIfAuthenticated("workflow:read");
        Long pendingScope = TenantScope.resolveSchoolId(schoolId);
        return workflows.pending(pendingScope, limit);
    }

    @GetMapping("/instances/{id}")
    public WorkflowInstanceRow instance(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "workflow:read");
        TenantScope.requirePermissionIfAuthenticated("workflow:read");
        return workflows.instance(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow instance not found"));
    }

    @PostMapping("/instances")
    public Map<String, Object> createOrGetInstance(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @Valid @RequestBody CreateInstanceRequest req) {
        requireToken(token, "workflow:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        Map<String, Object> body = new HashMap<>();
        body.put("entityType", req.entityType());
        body.put("entityId", req.entityId());
        if (req.definitionId() != null) body.put("definitionId", req.definitionId());
        body.put("initiatedBy", TenantContext.get().userId());
        body.put("schoolId", TenantScope.resolveSchoolId(req.schoolId()));
        return execute(() -> workflows.createOrGetInstance(body));
    }

    @PostMapping("/instances/{id}/submit")
    public Map<String, Object> submit(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        requireToken(token, "workflow:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        return execute(() -> workflows.submit(id, toActionMap(req)));
    }

    @PostMapping("/{id}/submit")
    public Map<String, Object> submitLegacy(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        return submit(token, id, req);
    }

    @PostMapping("/instances/{id}/approve")
    public Map<String, Object> approve(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        requireToken(token, "workflow:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        return execute(() -> workflows.approve(id, toActionMap(req)));
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approveLegacy(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        return approve(token, id, req);
    }

    @PostMapping("/instances/{id}/reject")
    public Map<String, Object> reject(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        requireToken(token, "workflow:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        return execute(() -> workflows.reject(id, toActionMap(req)));
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> rejectLegacy(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        return reject(token, id, req);
    }

    @PostMapping("/instances/{id}/cancel")
    public Map<String, Object> cancel(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        requireToken(token, "workflow:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        return execute(() -> workflows.cancel(id, toActionMap(req)));
    }

    @PostMapping("/instances/{id}/complete")
    public Map<String, Object> complete(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) WorkflowActionRequest req) {
        requireToken(token, "workflow:write");
        TenantScope.requirePermissionIfAuthenticated("workflow:act");
        return execute(() -> workflows.complete(id, toActionMap(req)));
    }

    /** Converts a (possibly null) WorkflowActionRequest into the map the repository expects. */
    private Map<String, Object> toActionMap(WorkflowActionRequest req) {
        Map<String, Object> m = new HashMap<>();
        m.put("actorId",    TenantContext.get().userId());
        m.put("actorEmail", TenantContext.get().email());
        if (req != null && req.notes() != null) m.put("notes", req.notes());
        return m;
    }

    @GetMapping("/{instanceId}/actions")
    public List<WorkflowActionRow> actions(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @PathVariable Long instanceId) {
        requireToken(token, "workflow:read");
        TenantScope.requirePermissionIfAuthenticated("workflow:read");
        return workflows.actions(instanceId);
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid workflow service token");
        }
    }

    private Map<String, Object> execute(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private interface Command {
        Map<String, Object> run();
    }
}

