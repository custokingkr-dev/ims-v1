package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.GroupLinkHistoryService;
import com.custoking.ims.operationsservice.application.GroupLinkHistoryService.GroupLinkChangePage;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The WhatsApp group link change log (design D34, Workflow set-up → Groups): who changed which group's link, when, from
 * where, and the link before and after, newest first. Superadmin only. The system only stores invite links; it sends
 * nothing.
 *
 * <p>{@code /whatsapp-groups/changes} is a literal two-segment GET, so {@link WorkflowReadController}'s
 * {@code /{instanceId}/actions} never takes it.
 */
@RestController
@RequestMapping("/api/v1/workflows/whatsapp-groups")
public class WhatsAppGroupChangeController {
    private final GroupLinkHistoryService history;
    private final String workflowToken;

    public WhatsAppGroupChangeController(GroupLinkHistoryService history, @Value("${workflow.read-token:}") String workflowToken) {
        this.history = history;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /**
     * @param schoolId only that school's own groups and item groups
     * @param before   only changes older than this id: the previous page's {@code nextBefore}
     * @param limit    1 to 200, 50 when absent
     */
    @GetMapping("/changes")
    public GroupLinkChangePage changes(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                       @RequestParam(required = false) Long schoolId,
                                       @RequestParam(required = false) Long before,
                                       @RequestParam(required = false) Integer limit) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return history.changes(schoolId, before, limit);
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
