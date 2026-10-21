package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.application.NotebookTrackerService;
import com.custoking.ims.operationsservice.application.NotebookTrackerService.CreateTracker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

/**
 * School-core's calls to the notebook order tracker: create a tracker at order placement, and ask whether a user may
 * change an order's design drafts. Cloud Run IAM and the exact signed MachineCallerFilter authorize the caller (only
 * school-core's service account is on NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS); no shared token or user context.
 */
@RestController
@RequestMapping("/api/v1/internal/notebook-trackers")
public class NotebookTrackerMachineController {
    private final NotebookTrackerService tracker;

    public NotebookTrackerMachineController(NotebookTrackerService tracker) {
        this.tracker = tracker;
    }

    public record CreateRequest(String orderId, Long schoolId, Boolean customised, Integer orderedCount,
                                OffsetDateTime placedAt, String timeZone, Long placedBy, Long assigneeUserId, String schoolName) { }
    public record CreateResponse(long instanceId, boolean created) { }
    public record ImageAccessRequest(Long userId, String role) { }

    /** Idempotent: 201 for a new tracker, 200 with the existing one when the order already has a tracker. */
    @PostMapping("")
    public ResponseEntity<CreateResponse> create(@RequestBody CreateRequest request) {
        if (request.schoolId() == null || request.customised() == null || request.orderedCount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId, customised and orderedCount are required");
        }
        NotebookTrackerService.Created created = tracker.create(new CreateTracker(request.orderId(), request.schoolId(),
                request.customised(), request.orderedCount(), request.placedAt(), request.timeZone(), request.placedBy(), request.assigneeUserId(),
                request.schoolName()));
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new CreateResponse(created.instanceId(), created.created()));
    }

    /** Asked by school-core before it stores or deletes a design draft. */
    @PostMapping("/{orderId}/image-access")
    public NotebookTrackerService.ImageAccessResult imageAccess(@PathVariable String orderId, @RequestBody ImageAccessRequest request) {
        if (request == null || request.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        return tracker.designImageAccess(orderId, request.userId(), request.role());
    }
}
