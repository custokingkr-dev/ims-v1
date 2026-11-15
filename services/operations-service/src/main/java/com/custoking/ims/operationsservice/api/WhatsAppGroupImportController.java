package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.GroupLinkService;
import com.custoking.ims.operationsservice.application.GroupLinkService.ImportRequest;
import com.custoking.ims.operationsservice.application.GroupLinkService.ImportResult;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Imports many WhatsApp group links at once (design D34, Workflow set-up → Groups): the schools' groups, their item
 * groups and the vendors' groups, from a spreadsheet the set-up screen reads in the browser. Superadmin only. A dry run
 * (the default) answers what would change; {@code "dryRun": false} applies the file, whole or not at all. The system
 * only stores invite links; it sends nothing.
 *
 * <p>{@code /whatsapp-groups/import} is a literal two-segment POST, so {@link WorkflowReadController}'s
 * {@code /{id}/submit}, {@code /{id}/approve} and {@code /{id}/reject} never take it.
 */
@RestController
@RequestMapping("/api/v1/workflows/whatsapp-groups")
public class WhatsAppGroupImportController {
    private final GroupLinkService groups;
    private final String workflowToken;

    public WhatsAppGroupImportController(GroupLinkService groups, @Value("${workflow.read-token:}") String workflowToken) {
        this.groups = groups;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    @PostMapping("/import")
    public ImportResult importLinks(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                    @RequestBody(required = false) ImportRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        return groups.importGroupLinks(request);
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException unreadable) {
        return WorkflowRefusals.unreadable("Send {\"dryRun\": true|false, \"rows\": [{\"kind\": ..., \"inviteUrl\": ...}]}");
    }
}
