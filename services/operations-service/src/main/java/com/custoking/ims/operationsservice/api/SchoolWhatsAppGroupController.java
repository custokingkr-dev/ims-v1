package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A school's own WhatsApp group links (design D34): the school group, and the internal approval group that overrides the
 * global one for this school. The order screens resolve them per order, with the item-level school group and the
 * vendors' groups, through {@code /item-orders/{orderId}/whatsapp-groups}; these are the school-level rows that resolution
 * falls back on. Who may see or change them is enforced in {@link OrderWorkflowService}. Every call carries the gateway's
 * workflow service token.
 *
 * <p>The literal {@code school-whatsapp-groups} segment is more specific than {@link WorkflowReadController}'s
 * {@code /{instanceId}/actions}, the only other two-segment GET under {@code /api/v1/workflows}, so neither can take the
 * other's requests.
 */
@RestController
@RequestMapping("/api/v1/workflows/school-whatsapp-groups")
public class SchoolWhatsAppGroupController {
    private final OrderWorkflowService workflows;
    private final String workflowToken;

    public SchoolWhatsAppGroupController(OrderWorkflowService workflows, @Value("${workflow.read-token:}") String workflowToken) {
        this.workflows = workflows;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /** A group's invite link; blank removes the group. */
    public record WhatsAppGroupLinkRequest(String url) { }

    /**
     * The school's groups as target key ({@code SCHOOL_GROUP}, {@code INTERNAL_APPROVAL}) to invite link; superadmin, or
     * an operator assigned to the whole school.
     */
    @GetMapping("/{schoolId}")
    public Map<String, String> links(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable long schoolId) {
        requireToken(token, "workflow:read");
        return workflows.schoolWhatsAppGroupLinks(schoolId);
    }

    /** Superadmin only. A blank url removes the group; the answer is the school's links after the change. */
    @PutMapping("/{schoolId}/{target}")
    public Map<String, String> save(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                    @PathVariable long schoolId, @PathVariable String target,
                                    @RequestBody WhatsAppGroupLinkRequest request) {
        requireToken(token, "workflow:write");
        return workflows.setSchoolWhatsAppGroupLink(schoolId, target, request == null ? null : request.url());
    }

    // ---- errors: every refusal reaches the screen as {"code"?, "message"} -----------------------------------------------

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException refusal) {
        String reason = refusal.getReason();
        if (reason == null || reason.isBlank()) {
            reason = refusal.getStatusCode() instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(refusal.getStatusCode()).headers(refusal.getHeaders()).body(Map.of("message", reason));
    }

    /** {@code INVALID_INVITE} (400) for a link that is not a WhatsApp group invite, and the role refusals (403). */
    @ExceptionHandler(WorkflowRuleViolation.class)
    public ResponseEntity<Map<String, String>> ruleViolation(WorkflowRuleViolation violation) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", violation.code());
        body.put("message", violation.getMessage());
        return ResponseEntity.status(violation.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> malformedPath(MethodArgumentTypeMismatchException mismatch) {
        return ResponseEntity.badRequest().body(Map.of("message", "schoolId".equals(mismatch.getName())
                ? "schoolId must be a number" : "Invalid " + mismatch.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpMessageNotReadableException unreadable) {
        return ResponseEntity.badRequest().body(Map.of("message", "Send the WhatsApp group link as {\"url\": \"...\"}"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (workflowToken.isEmpty() || !constantTimeEquals(workflowToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid workflow service token");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
