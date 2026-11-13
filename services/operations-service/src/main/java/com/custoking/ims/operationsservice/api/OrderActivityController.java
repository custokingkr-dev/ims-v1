package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.ActivityService;
import com.custoking.ims.operationsservice.application.ActivityService.ActivityPage;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * An order's activity history (design D40, §4.3; contract {@code orderWorkflowClient.v1}, operation
 * {@code getOrderActivity}): its workflow actions, stage and internal notes, call log and legacy follow-ups as one
 * newest-first timeline, read a page at a time. Anyone who may see the order may read it. Every call carries the gateway's
 * workflow service token; the caller's role and scope are checked by {@link ActivityService}.
 */
@RestController
@RequestMapping("/api/v1/workflows/item-orders")
public class OrderActivityController {
    private final ActivityService activity;
    private final String workflowToken;

    public OrderActivityController(ActivityService activity, @Value("${workflow.read-token:}") String workflowToken) {
        this.activity = activity;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /**
     * One page of the timeline.
     *
     * @param before only entries strictly older than this: the previous page's {@code nextBefore}, ISO-8601 with its offset
     * @param limit  entries per page, 1 to 200 (default 50)
     * @param step   only this stage's entries
     */
    @GetMapping("/{orderId}/activity")
    public ActivityPage activity(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                 @PathVariable String orderId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
                                 @RequestParam(required = false) Integer limit, @RequestParam(required = false) Integer step) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return activity.activity(WorkflowRefusals.orderId(orderId), before, limit, step);
    }

    // ---- errors ----------------------------------------------------------------------------------------------------

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(ResponseStatusException refusal) {
        return WorkflowRefusals.refused(refusal);
    }

    @ExceptionHandler(WorkflowRuleViolation.class)
    public ResponseEntity<Map<String, Object>> violation(WorkflowRuleViolation violation) {
        return WorkflowRefusals.violation(violation);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> badParameter(MethodArgumentTypeMismatchException mismatch) {
        return WorkflowRefusals.badParameter(mismatch);
    }
}
