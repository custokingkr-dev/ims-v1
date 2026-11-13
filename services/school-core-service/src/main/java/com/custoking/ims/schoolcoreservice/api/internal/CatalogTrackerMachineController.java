package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService.WorkflowStepReport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The order workflow engine's calls into the catalogue (operations-service): read an order's current design files, and
 * show the workflow's step as the order's status. Cloud Run IAM and the exact signed MachineCallerFilter authorize the
 * caller (only operations-service's service account is on CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS); no shared token or
 * user context.
 */
@RestController
@RequestMapping("/api/v1/internal/catalog-orders")
public class CatalogTrackerMachineController {
    private final CatalogOrderFormService forms;

    public CatalogTrackerMachineController(CatalogOrderFormService forms) {
        this.forms = forms;
    }

    /**
     * {@code trackerVersion} orders the engine's retried pushes; an older or repeated one is accepted and changes nothing.
     * {@code status} is any step code or COMPLETED (design D5). The step fields (design §3.5) are optional and stored for
     * order lists: the template, the current step's order and title, the path's size and how many steps are done.
     */
    public record TrackerStatusRequest(String status, Long trackerVersion, String definitionId, Integer stepOrder,
                                       String stepTitle, Integer totalSteps, Integer completedSteps) { }

    @GetMapping("/{orderId}/draft-images")
    public List<Map<String, Object>> draftImages(@PathVariable String orderId) {
        return forms.draftImagesForTracker(orderId);
    }

    @PutMapping("/{orderId}/tracker-status")
    public ResponseEntity<Void> trackerStatus(@PathVariable String orderId, @RequestBody TrackerStatusRequest request) {
        if (request == null) {
            forms.applyTrackerStatus(orderId, null, null, WorkflowStepReport.NONE);
        } else {
            forms.applyTrackerStatus(orderId, request.status(), request.trackerVersion(), new WorkflowStepReport(request.definitionId(),
                    request.stepOrder(), request.stepTitle(), request.totalSteps(), request.completedSteps()));
        }
        return ResponseEntity.noContent().build();
    }
}
