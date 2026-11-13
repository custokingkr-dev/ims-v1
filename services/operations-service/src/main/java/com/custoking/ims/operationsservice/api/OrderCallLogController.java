package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.CallLogService;
import com.custoking.ims.operationsservice.application.CallLogService.CallLogEntry;
import com.custoking.ims.operationsservice.application.CallLogService.NewCall;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * An order's call log (design D35, §4.6; contract {@code orderWorkflowClient.v1}, operations {@code listCalls} and
 * {@code logCall}). Anyone who may see the order reads it; anyone who can work the order (an in-scope operator holding
 * {@code notebook-tracker:act}, or superadmin) appends to it. Entries are never changed or removed. Every call carries
 * the gateway's workflow service token; the caller's role and scope are checked by {@link CallLogService}.
 */
@RestController
@RequestMapping("/api/v1/workflows/item-orders")
public class OrderCallLogController {
    private final CallLogService calls;
    private final String workflowToken;

    public OrderCallLogController(CallLogService calls, @Value("${workflow.read-token:}") String workflowToken) {
        this.calls = calls;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /**
     * A call to log (contract {@code CallLogRequest}).
     *
     * @param contactLabel   who was called, at most 200 characters; filled from the vendor or designer when one is named
     *                       and this is blank
     * @param stepOrder      the stage the call was about; null means the order's current stage
     * @param notes          at most 100 words; required for Delay Reported, Changes Requested and Callback Requested
     * @param nextFollowUpAt required for Callback Requested
     */
    public record CallLogRequest(String contacted, String outcome, String contactLabel, Long vendorId, Long designerId,
                                 Integer stepOrder, String notes, OffsetDateTime nextFollowUpAt) {
        NewCall call() {
            return new NewCall(contacted, outcome, contactLabel, vendorId, designerId, stepOrder, notes, nextFollowUpAt);
        }
    }

    /** The order's calls, newest first (default 100, at most 200). */
    @GetMapping("/{orderId}/calls")
    public List<CallLogEntry> list(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                   @PathVariable String orderId, @RequestParam(required = false) Integer limit) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return calls.list(WorkflowRefusals.orderId(orderId), limit);
    }

    /** Appends a call; 201 with the stored entry. */
    @PostMapping("/{orderId}/calls")
    public ResponseEntity<CallLogEntry> log(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                            @PathVariable String orderId, @RequestBody(required = false) CallLogRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        String id = WorkflowRefusals.orderId(orderId);
        return ResponseEntity.status(HttpStatus.CREATED).body(calls.log(id, request == null ? null : request.call()));
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException unreadable) {
        return WorkflowRefusals.unreadable("Send the call as JSON: contacted, outcome and the optional fields; "
                + "nextFollowUpAt is a date and time with its offset, such as 2026-11-14T10:30:00+05:30");
    }
}
