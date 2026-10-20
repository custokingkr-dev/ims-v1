package com.custoking.ims.schoolcoreservice.api.internal;

import com.custoking.ims.schoolcoreservice.persistence.CatalogOrderFormService;
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
 * The notebook order tracker's calls into the catalogue: read an order's current design drafts, and show the tracker's
 * step as the order's status. Cloud Run IAM and the exact signed MachineCallerFilter authorize the caller (only
 * operations-service's service account is on CATALOG_TRACKER_CALLER_SERVICE_ACCOUNTS); no shared token or user context.
 */
@RestController
@RequestMapping("/api/v1/internal/catalog-orders")
public class CatalogTrackerMachineController {
    private final CatalogOrderFormService forms;

    public CatalogTrackerMachineController(CatalogOrderFormService forms) {
        this.forms = forms;
    }

    /** {@code trackerVersion} orders the tracker's retried pushes; an older or repeated one is accepted and changes nothing. */
    public record TrackerStatusRequest(String status, Long trackerVersion) { }

    @GetMapping("/{orderId}/draft-images")
    public List<Map<String, Object>> draftImages(@PathVariable String orderId) {
        return forms.draftImagesForTracker(orderId);
    }

    @PutMapping("/{orderId}/tracker-status")
    public ResponseEntity<Void> trackerStatus(@PathVariable String orderId, @RequestBody TrackerStatusRequest request) {
        forms.applyTrackerStatus(orderId, request == null ? null : request.status(), request == null ? null : request.trackerVersion());
        return ResponseEntity.noContent().build();
    }
}
