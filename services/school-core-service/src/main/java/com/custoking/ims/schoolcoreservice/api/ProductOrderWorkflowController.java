package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.StageUpdate;
import com.custoking.ims.schoolcoreservice.persistence.ProductOrderWorkflowService.WorkflowView;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The production workflow of a placed product order. Refusals reach the screen as {@code {"message": ...}} through
 * {@link CatalogOrderFormExceptionHandler}. WhatsApp group links are not served here: product orders use the notebook
 * tracker's school-scoped links in operations-service.
 */
@RestController
@RequestMapping("/api/v1/catalog/orders")
public class ProductOrderWorkflowController {
    private final ProductOrderWorkflowService workflows;
    private final String token;

    public ProductOrderWorkflowController(ProductOrderWorkflowService workflows, @Value("${catalog.read-token:}") String token) {
        this.workflows = workflows;
        this.token = token == null ? "" : token.trim();
    }

    public record CompleteRequest(Long expectedVersion) { }

    @GetMapping("/{id}/production-workflow")
    public WorkflowView view(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String supplied,
                             @PathVariable String id) {
        requireToken(supplied, "order:read");
        return workflows.view(id);
    }

    @PutMapping("/{id}/production-workflow/stages/{stageCode}")
    public WorkflowView saveStage(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String supplied,
                                  @PathVariable String id, @PathVariable String stageCode,
                                  @RequestBody StageUpdate request) {
        requireToken(supplied, "order:update");
        return workflows.saveStage(id, stageCode, request);
    }

    @PostMapping("/{id}/production-workflow/stages/{stageCode}/complete")
    public WorkflowView completeStage(@RequestHeader(value = "X-Catalog-Service-Token", required = false) String supplied,
                                      @PathVariable String id, @PathVariable String stageCode,
                                      @RequestBody CompleteRequest request) {
        requireToken(supplied, "order:update");
        return workflows.completeStage(id, stageCode, request == null ? null : request.expectedVersion());
    }

    private void requireToken(String supplied, String permission) {
        if (token.isBlank() || !token.equals(supplied)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid catalog service token");
        TenantScope.requirePermissionIfAuthenticated(permission);
    }
}
