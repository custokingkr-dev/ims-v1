package com.custoking.ims.controller;

import com.custoking.ims.dto.ApprovalDecisionRequest;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.model.Role;
import com.custoking.ims.service.DatabaseStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    private final DatabaseStore store;
    public ApprovalController(DatabaseStore store) { this.store = store; }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireUser(authorization);
        return store.approvals(store.requireUser(authorization));
    }

    @PostMapping("/{id}/{action}")
    public Map<String, Object> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable long id,
                           @PathVariable String action,
                           @RequestBody(required = false) ApprovalDecisionRequest request) {
        AuthUser user = store.requireUser(authorization);
        if (user.role() != Role.SUPERADMIN) {
            throw new IllegalArgumentException("Only super admin can review approvals");
        }
        return store.decideApproval(id, action, request);
    }
}
