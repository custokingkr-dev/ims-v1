package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.WorkflowView;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The retired PR #380 production workflow of a product order (item order workflows design D4). Reading it stays; saving or
 * completing a stage answers 409 {@code WORKFLOW_MOVED}: the order runs on its order workflow in operations-service now.
 * Other refusals reach the screen as {@code {"message": ...}} through {@link CatalogOrderFormExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/catalog/orders")
public class ProductOrderWorkflowController {
    static final String WORKFLOW_MOVED = "WORKFLOW_MOVED";
    static final String MOVED_MESSAGE = "This order's production workflow has moved to its order workflow. Open the order workflow to work on it";

    private final ProductOrderWorkflowService workflows;
    private final String token;

    public ProductOrderWorkflowController(ProductOrderWorkflowService workflows, @Value("${catalog.read-token:}") String token) {
        this.workflows = workflows;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping("/{id}/production-workflow")
    public WorkflowView view(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String supplied,
                             @PathVariable String id) {
        requireToken(supplied, "order:read");
        return workflows.view(id);
    }

    @PutMapping("/{id}/production-workflow/stages/{stageCode}")
    public ResponseEntity<Map<String, String>> saveStage(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String supplied,
                                                         @PathVariable String id, @PathVariable String stageCode) {
        requireToken(supplied, "order:update");
        return moved();
    }

    @PostMapping("/{id}/production-workflow/stages/{stageCode}/complete")
    public ResponseEntity<Map<String, String>> completeStage(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String supplied,
                                                             @PathVariable String id, @PathVariable String stageCode) {
        requireToken(supplied, "order:update");
        return moved();
    }

    private static ResponseEntity<Map<String, String>> moved() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", WORKFLOW_MOVED);
        body.put("message", MOVED_MESSAGE);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private void requireToken(String supplied, String permission) {
        if (token.isBlank() || !token.equals(supplied)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid catalog service token");
        TenantScope.requirePermissionIfAuthenticated(permission);
    }
}
