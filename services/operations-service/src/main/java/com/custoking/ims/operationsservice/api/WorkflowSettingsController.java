package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.GroupLinkService;
import com.custoking.ims.operationsservice.application.WorkflowSettingsService;
import com.custoking.ims.operationsservice.application.WorkflowSettingsService.InternalApprovalGroupView;
import com.custoking.ims.operationsservice.application.orderworkflow.GroupLinkResolver.ResolvedGroups;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The order workflow's WhatsApp groups and global settings (design D34, §4.7; contract {@code orderWorkflowClient.v1}).
 * The system only stores invite links and opens them; it sends nothing.
 * <ul>
 *   <li>{@code /settings/internal-approval-group}: the global internal approval group. Superadmin and operators read it;
 *       superadmin sets or removes it.</li>
 *   <li>{@code /item-orders/{orderId}/whatsapp-groups}: the groups an order shows, resolved (school: item, else school,
 *       else none; internal approval: the school's own, else the global one; and the selected vendors). Anyone who may
 *       see the order reads them; superadmin or the operator assigned the current stage sets the item-level school
 *       group.</li>
 * </ul>
 * Every call carries the gateway's workflow service token; the caller's role and scope are checked by the services.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowSettingsController {
    private final WorkflowSettingsService settings;
    private final GroupLinkService groups;
    private final String workflowToken;

    public WorkflowSettingsController(WorkflowSettingsService settings, GroupLinkService groups,
                                      @Value("${workflow.read-token:}") String workflowToken) {
        this.settings = settings;
        this.groups = groups;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /** An invite link to save; blank or null removes the link (contract {@code InviteLinkRequest}). */
    public record InviteLinkRequest(String inviteUrl) { }

    @GetMapping("/settings/internal-approval-group")
    public InternalApprovalGroupView internalApprovalGroup(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return settings.internalApprovalGroup();
    }

    @PutMapping("/settings/internal-approval-group")
    public InternalApprovalGroupView setInternalApprovalGroup(
            @RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
            @RequestBody(required = false) InviteLinkRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        return settings.setInternalApprovalGroup(inviteUrl(request));
    }

    @GetMapping("/item-orders/{orderId}/whatsapp-groups")
    public ResolvedGroups orderGroups(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                      @PathVariable String orderId) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return groups.orderGroups(WorkflowRefusals.orderId(orderId));
    }

    @PutMapping("/item-orders/{orderId}/whatsapp-groups/school")
    public ResolvedGroups setOrderSchoolGroup(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                              @PathVariable String orderId, @RequestBody(required = false) InviteLinkRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        return groups.setOrderSchoolGroup(WorkflowRefusals.orderId(orderId), inviteUrl(request));
    }

    /** A missing body is refused rather than read as "remove the link": removing is an explicit blank or null. */
    private static String inviteUrl(InviteLinkRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send {\"inviteUrl\": ...}");
        return request.inviteUrl();
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
        return WorkflowRefusals.unreadable("Send {\"inviteUrl\": \"https://chat.whatsapp.com/...\"}, or null to remove the link");
    }
}
