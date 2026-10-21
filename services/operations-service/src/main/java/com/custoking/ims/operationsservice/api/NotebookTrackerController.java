package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.NotebookTrackerService;
import com.custoking.ims.operationsservice.application.NotebookTrackerService.SlaRow;
import com.custoking.ims.operationsservice.application.tracker.Sla;
import com.custoking.ims.operationsservice.application.tracker.TrackerRuleViolation;
import com.custoking.ims.operationsservice.application.tracker.TrackerView;
import com.custoking.ims.operationsservice.persistence.NotebookTrackerRepository.DesignerRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Notebook order tracker API (spec v4). Every call carries the gateway's workflow service token; the caller's role
 * and scope are enforced in {@link NotebookTrackerService}. School-core's machine calls (creating a tracker, design
 * draft access) live on the internal route in {@code api.internal.NotebookTrackerMachineController}.
 */
@RestController
@RequestMapping("/api/v1/workflows/notebook-orders")
public class NotebookTrackerController {
    private final NotebookTrackerService tracker;
    private final String workflowToken;

    public NotebookTrackerController(NotebookTrackerService tracker,
                                     @Value("${workflow.read-token:}") String workflowToken) {
        this.tracker = tracker;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    // ---- request bodies ---------------------------------------------------------------------------------------------

    public record DraftRequest(Map<String, Object> data, Long expectedVersion) { }
    public record NoteRequest(String note, Long expectedVersion) { }
    public record VersionRequest(Long expectedVersion) { }
    public record ApproveRequest(Long draftRef, String comment) { }
    public record CommentRequest(String comment) { }
    public record VendorRequest(String name, String phone) { }
    public record DesignerChoice(Long designerId) { }
    public record BodyRequest(String body) { }
    public record AssigneeRequest(Long userId) { }
    public record FollowUpRequest(Integer percentComplete, LocalDate nextFollowUpOn, String contactPhone) { }
    public record DesignerRequest(String name, String phone, Boolean active) { }
    /** Both null means "no timer for this school and step". */
    public record SlaRequest(Integer value, String unit) { }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    /** The worklist: scope=mine (default, the caller's active steps) or scope=all (everything the caller may see). */
    @GetMapping
    public List<TrackerView.ListItem> list(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "mine") String scope,
                                           @org.springframework.web.bind.annotation.RequestParam(required = false) Long schoolId,
                                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean includeFinished,
                                           @org.springframework.web.bind.annotation.RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "workflow:read");
        if (!"mine".equals(scope) && !"all".equals(scope)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be mine or all");
        return tracker.list("mine".equals(scope), schoolId, includeFinished, limit);
    }

    @GetMapping("/{orderId}")
    public TrackerView view(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                            @PathVariable String orderId) {
        requireToken(token, "workflow:read");
        return tracker.view(orderId);
    }

    // ---- step work ----------------------------------------------------------------------------------------------------

    @PutMapping("/{orderId}/steps/{step}/draft")
    public TrackerView saveDraft(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                 @PathVariable String orderId, @PathVariable int step, @RequestBody DraftRequest request) {
        requireToken(token, "workflow:write");
        return tracker.saveDraft(orderId, step, request.data(), version(request.expectedVersion()));
    }

    @PutMapping("/{orderId}/steps/{step}/stage-note")
    public TrackerView saveStageNote(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable String orderId, @PathVariable int step, @RequestBody NoteRequest request) {
        requireToken(token, "workflow:write");
        return tracker.saveStageNote(orderId, step, request.note(), version(request.expectedVersion()));
    }

    @PostMapping("/{orderId}/steps/{step}/complete")
    public TrackerView complete(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                @PathVariable String orderId, @PathVariable int step, @RequestBody VersionRequest request) {
        requireToken(token, "workflow:write");
        return tracker.completeStep(orderId, step, version(request.expectedVersion()));
    }

    @PostMapping("/{orderId}/steps/{step}/reopen")
    public TrackerView reopen(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                              @PathVariable String orderId, @PathVariable int step) {
        requireToken(token, "workflow:write");
        return tracker.reopenStep(orderId, step);
    }

    /** The body carries the step version superadmin's screen loaded: a sign-off approves the data seen, nothing saved since. */
    @PostMapping("/{orderId}/steps/{step}/sign-off")
    public TrackerView signOff(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                               @PathVariable String orderId, @PathVariable int step, @RequestBody VersionRequest request) {
        requireToken(token, "workflow:write");
        return tracker.signOff(orderId, step, version(request.expectedVersion()));
    }

    @PutMapping("/{orderId}/steps/{step}/assignee")
    public TrackerView assignStep(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                  @PathVariable String orderId, @PathVariable int step, @RequestBody AssigneeRequest request) {
        requireToken(token, "workflow:write");
        return tracker.assignStep(orderId, step, assignee(request));
    }

    @PutMapping("/{orderId}/assignee")
    public TrackerView assignOrder(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                   @PathVariable String orderId, @RequestBody AssigneeRequest request) {
        requireToken(token, "workflow:write");
        return tracker.assignOrder(orderId, assignee(request));
    }

    @PostMapping("/{orderId}/follow-ups")
    public TrackerView addFollowUp(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                   @PathVariable String orderId, @RequestBody FollowUpRequest request) {
        requireToken(token, "workflow:write");
        if (request.percentComplete() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "percentComplete is required");
        return tracker.addFollowUp(orderId, request.percentComplete(), request.nextFollowUpOn(), request.contactPhone());
    }

    // ---- design approval ---------------------------------------------------------------------------------------------

    @PostMapping("/{orderId}/design/send")
    public TrackerView sendDesign(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                  @PathVariable String orderId) {
        requireToken(token, "workflow:write");
        return tracker.sendDesign(orderId);
    }

    @PostMapping("/{orderId}/design/approve")
    public TrackerView approveDesign(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable String orderId, @RequestBody ApproveRequest request) {
        requireToken(token, "workflow:write");
        if (request.draftRef() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "draftRef is required");
        return tracker.approveDesign(orderId, request.draftRef(), request.comment());
    }

    @PostMapping("/{orderId}/design/request-changes")
    public TrackerView requestChanges(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                      @PathVariable String orderId, @RequestBody(required = false) CommentRequest request) {
        requireToken(token, "workflow:write");
        return tracker.requestChanges(orderId, request == null ? null : request.comment());
    }

    // ---- people and notes ------------------------------------------------------------------------------------------

    @PutMapping("/{orderId}/vendor")
    public TrackerView setVendor(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                 @PathVariable String orderId, @RequestBody VendorRequest request) {
        requireToken(token, "workflow:write");
        return tracker.setVendor(orderId, request.name(), request.phone());
    }

    @PutMapping("/{orderId}/designer")
    public TrackerView setDesigner(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                   @PathVariable String orderId, @RequestBody DesignerChoice request) {
        requireToken(token, "workflow:write");
        if (request.designerId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "designerId is required");
        return tracker.setDesigner(orderId, request.designerId());
    }

    @PostMapping("/{orderId}/internal-notes")
    public TrackerView addInternalNote(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                       @PathVariable String orderId, @RequestBody BodyRequest request) {
        requireToken(token, "workflow:write");
        return tracker.addInternalNote(orderId, request.body());
    }

    // ---- designers and time slots --------------------------------------------------------------------------------------

    @GetMapping("/designers")
    public List<DesignerRow> designers(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token) {
        requireToken(token, "workflow:read");
        return tracker.designers();
    }

    @PostMapping("/designers")
    public ResponseEntity<DesignerRow> createDesigner(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                                      @RequestBody DesignerRequest request) {
        requireToken(token, "workflow:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(tracker.createDesigner(request.name(), request.phone()));
    }

    @PutMapping("/designers/{id}")
    public DesignerRow updateDesigner(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                      @PathVariable long id, @RequestBody DesignerRequest request) {
        requireToken(token, "workflow:write");
        return tracker.updateDesigner(id, request.name(), request.phone(), request.active() == null || request.active());
    }

    @GetMapping("/time-slots/{schoolId}")
    public List<SlaRow> timeSlots(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                  @PathVariable long schoolId) {
        requireToken(token, "workflow:read");
        return tracker.timeSlots(schoolId);
    }

    @PutMapping("/time-slots/{schoolId}/{step}")
    public ResponseEntity<Void> setTimeSlot(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                            @PathVariable long schoolId, @PathVariable int step, @RequestBody SlaRequest request) {
        requireToken(token, "workflow:write");
        Sla sla = null;
        if (request.value() != null || request.unit() != null) {
            if (request.value() == null || request.unit() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value and unit go together");
            try { sla = new Sla(request.value(), Sla.Unit.valueOf(request.unit())); }
            catch (IllegalArgumentException bad) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A time slot needs a positive value and a unit of HOURS or WORKING_DAYS"); }
        }
        tracker.setTimeSlot(schoolId, step, sla);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/time-slots/{schoolId}/{step}")
    public ResponseEntity<Void> clearTimeSlot(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                              @PathVariable long schoolId, @PathVariable int step) {
        requireToken(token, "workflow:write");
        tracker.clearTimeSlot(schoolId, step);
        return ResponseEntity.noContent().build();
    }

    // ---- errors ----------------------------------------------------------------------------------------------------------

    /**
     * A refused request carries its reason to the screen as {@code {"message": ...}}, where the frontend looks for it;
     * Spring would otherwise drop the text and leave the user with a generic failure.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> refused(ResponseStatusException refusal) {
        String reason = refusal.getReason();
        if (reason == null || reason.isBlank()) {
            reason = refusal.getStatusCode() instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(refusal.getStatusCode()).headers(refusal.getHeaders()).body(Map.of("message", reason));
    }

    /** Rule violations carry a stable code the screen can act on. */
    @ExceptionHandler(TrackerRuleViolation.class)
    public ResponseEntity<Map<String, String>> ruleViolation(TrackerRuleViolation violation) {
        HttpStatus status = switch (violation.code()) {
            case "SUPERADMIN_ONLY" -> HttpStatus.FORBIDDEN;
            case "INVALID_STEP_DATA", "NOTE_TOO_LONG" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("code", violation.code(), "message", violation.getMessage()));
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

    private static long assignee(AssigneeRequest request) {
        if (request == null || request.userId() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        return request.userId();
    }

    private static long version(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedVersion is required");
        return expectedVersion;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (!StringUtils.hasText(actual)) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
