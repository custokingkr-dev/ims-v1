package com.custoking.ims.operationsservice.api.internal;

import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.CreateWorkflow;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.DesignFileAccess;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ImportedStep;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * School-core's calls to the order workflow engine (design §3.5): create an order's workflow at placement, and ask
 * whether a user may change an order's design files. Cloud Run IAM and the exact signed MachineCallerFilter authorize
 * the caller (only school-core's service account is on NOTEBOOK_TRACKER_CALLER_SERVICE_ACCOUNTS); no shared token or user
 * context.
 *
 * <p>The notebook-tracker paths stay as aliases for one release (design D6), so a school-core deployed before this
 * service keeps creating workflows; each alias is its own exact route in the caller filter.
 */
@RestController
@RequestMapping("/api/v1/internal")
public class OrderWorkflowMachineController {
    private final OrderWorkflowService workflows;

    public OrderWorkflowMachineController(OrderWorkflowService workflows) {
        this.workflows = workflows;
    }

    /**
     * Today's notebook fields plus the template, item and number (all optional for an older school-core, which then
     * creates a notebook workflow) and a retired production workflow's progress.
     */
    public record CreateRequest(String orderId, Long schoolId, Boolean customised, Integer orderedCount, OffsetDateTime placedAt,
                                String timeZone, Long placedBy, Long assigneeUserId, String schoolName, String definitionId,
                                String categoryCode, String itemCode, String itemNoun, String orderNumber,
                                List<ImportedStepRequest> importedProgress) { }

    public record ImportedStepRequest(String stepCode, String sourceStageCode, Integer stepOrder, String status,
                                      OffsetDateTime completedAt, Long completedBy, String details, String note) { }

    public record CreateResponse(long instanceId, boolean created) { }

    public record DesignFileAccessRequest(Long userId, String role) { }

    /** Idempotent: 201 for a new workflow, 200 with the existing one (its missing order number filled in). */
    @PostMapping("/order-workflows")
    public ResponseEntity<CreateResponse> create(@RequestBody(required = false) CreateRequest request) {
        return created(request);
    }

    /** The old path, for one release (design D6). */
    @PostMapping("/notebook-trackers")
    public ResponseEntity<CreateResponse> createAlias(@RequestBody(required = false) CreateRequest request) {
        return created(request);
    }

    /** Asked by school-core before it stores or deletes a design file of any workflow order. */
    @PostMapping("/order-workflows/{orderId}/design-file-access")
    public DesignFileAccess designFileAccess(@PathVariable String orderId, @RequestBody(required = false) DesignFileAccessRequest request) {
        return access(orderId, request);
    }

    /** The old path, for one release (design D6). */
    @PostMapping("/notebook-trackers/{orderId}/image-access")
    public DesignFileAccess imageAccess(@PathVariable String orderId, @RequestBody(required = false) DesignFileAccessRequest request) {
        return access(orderId, request);
    }

    // ---- errors: every refusal carries its reason as {"message": ...}, which school-core keeps as the queue's last error -----

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException refusal) {
        String reason = refusal.getReason();
        if (reason == null || reason.isBlank()) {
            reason = refusal.getStatusCode() instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(refusal.getStatusCode()).body(Map.of("message", reason));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException unreadable) {
        return ResponseEntity.badRequest().body(Map.of("message", "The request body is not valid JSON of the expected shape"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    private ResponseEntity<CreateResponse> created(CreateRequest request) {
        if (request == null || request.schoolId() == null || request.customised() == null || request.orderedCount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId, customised and orderedCount are required");
        }
        List<ImportedStep> imported = request.importedProgress() == null ? List.of() : request.importedProgress().stream()
                .map(step -> step == null ? null : new ImportedStep(step.stepCode(), step.sourceStageCode(), step.stepOrder(), step.status(),
                        step.completedAt(), step.completedBy(), step.details(), step.note()))
                .toList();
        OrderWorkflowService.Created created = workflows.create(new CreateWorkflow(request.orderId(), request.schoolId(),
                request.customised(), request.orderedCount(), request.placedAt(), request.timeZone(), request.placedBy(),
                request.assigneeUserId(), request.schoolName(), request.definitionId(), request.categoryCode(), request.itemCode(),
                request.itemNoun(), request.orderNumber(), imported));
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new CreateResponse(created.instanceId(), created.created()));
    }

    private DesignFileAccess access(String orderId, DesignFileAccessRequest request) {
        if (request == null || request.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        return workflows.designFileAccess(orderId, request.userId(), request.role());
    }
}
