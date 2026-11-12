package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.NotebookTrackerController.WhatsAppGroupLinkRequest;
import com.custoking.ims.operationsservice.application.NotebookTrackerService;
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
import java.util.Map;

/**
 * A school's WhatsApp group links, addressed by school instead of by order: product orders (every catalogue item except
 * notebooks) have no notebook tracker to reach them through. These are the same rows the tracker's per-order endpoints
 * ({@code /api/v1/workflows/notebook-orders/{orderId}/whatsapp-groups}) read and write; who may see or change them is
 * enforced in {@link NotebookTrackerService}. Every call carries the gateway's workflow service token.
 *
 * <p>The literal {@code school-whatsapp-groups} segment is more specific than {@link WorkflowReadController}'s
 * {@code /{instanceId}/actions}, the only other two-segment GET under {@code /api/v1/workflows}, so neither can take the
 * other's requests.
 */
@RestController
@RequestMapping("/api/v1/workflows/school-whatsapp-groups")
public class SchoolWhatsAppGroupController {
    private final NotebookTrackerService tracker;
    private final String workflowToken;

    public SchoolWhatsAppGroupController(NotebookTrackerService tracker,
                                         @Value("${workflow.read-token:}") String workflowToken) {
        this.tracker = tracker;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /**
     * The school's configured groups as target key to invite link; superadmin, or an operator assigned to the whole school.
     * An operator scoped there to one item only is refused, as school-core hides that school's product orders from them.
     */
    @GetMapping("/{schoolId}")
    public Map<String, String> links(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable long schoolId) {
        requireToken(token, "workflow:read");
        return tracker.schoolWhatsAppGroupLinks(schoolId);
    }

    /** Superadmin only. A blank url removes the group; the answer is the school's links after the change. */
    @PutMapping("/{schoolId}/{target}")
    public Map<String, String> save(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                    @PathVariable long schoolId, @PathVariable String target,
                                    @RequestBody WhatsAppGroupLinkRequest request) {
        requireToken(token, "workflow:write");
        return tracker.setSchoolWhatsAppGroupLink(schoolId, target, request == null ? null : request.url());
    }

    // ---- errors: every refusal reaches the screen as {"message": ...} ------------------------------------------------

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException refusal) {
        String reason = refusal.getReason();
        if (reason == null || reason.isBlank()) {
            reason = refusal.getStatusCode() instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(refusal.getStatusCode()).headers(refusal.getHeaders()).body(Map.of("message", reason));
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
