package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.OrderWorkflowService;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ContactsChange;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.ListQuery;
import com.custoking.ims.operationsservice.application.OrderWorkflowService.TimeSlotRow;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.NoteEntry;
import com.custoking.ims.operationsservice.application.orderworkflow.OrderWorkflowView.OrderWorkflowPage;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.Sla;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import com.custoking.ims.operationsservice.application.orderworkflow.template.WorkflowTemplate;
import com.custoking.ims.operationsservice.persistence.OrderWorkflowRepository.DesignerRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The item order workflow API (design §3.5; contract {@code orderWorkflowClient.v1}). Every call carries the gateway's
 * workflow service token; the caller's role, item scope and stage assignment are enforced in {@link OrderWorkflowService}.
 * School-core's machine calls (create a workflow, design-file access) are on the internal route in
 * {@code api.internal.OrderWorkflowMachineController}. The call log, activity, order group links and settings have their
 * own controllers.
 *
 * <p>Refusals reach the screen as {@code {"code"?, "message", "unmet"?}} (the contract's {@code OrderWorkflowError}):
 * a broken workflow rule carries its stable code, and {@code GATE_UNMET} lists every unmet requirement.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class OrderWorkflowController {
    /** The catalogue order id in a path: {@code CK-<n>}, never an order number. */
    private static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    private final OrderWorkflowService workflows;
    private final String workflowToken;

    public OrderWorkflowController(OrderWorkflowService workflows, @Value("${workflow.read-token:}") String workflowToken) {
        this.workflows = workflows;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    // ---- request bodies (component order is the contract's property order) -----------------------------------------------

    /** A partial save of a stage's fields and checklists. */
    public record StepDataRequest(Map<String, Object> data, Long expectedVersion) { }

    /** The stage version the screen loaded. */
    public record StepVersionRequest(Long expectedVersion) { }

    public record StepReopenRequest(String reason, Long expectedVersion) { }

    /** Head Approval's required comment and the Head Approval stage version the screen loaded. */
    public record HeadApprovalRequest(String comment, Long expectedVersion) { }

    public record AssigneeRequest(Long userId) { }

    /** Category code to vendor ids, and the order version the screen loaded. */
    public record VendorSelectionsRequest(Map<String, List<Long>> selections, Long expectedVersion) { }

    /** Null clears the designer. */
    public record DesignerChoiceRequest(Long designerId) { }

    public record OrderContactsRequest(String schoolContactName, String schoolContactPhone, String factoryContactName,
                                       String factoryContactPhone) { }

    /** A stage note with {@code stepOrder}, or an order-level internal note without. */
    public record OrderNoteRequest(String body, Integer stepOrder) { }

    public record DesignerRequest(String name, String phone, Boolean active) { }

    /** Both null means "no timer". */
    public record SlaRequest(Integer value, String unit) { }

    // ---- the list, templates and one order --------------------------------------------------------------------------------

    @GetMapping("/item-orders")
    public OrderWorkflowPage list(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                  @RequestParam(required = false) String item, @RequestParam(required = false) String step,
                                  @RequestParam(required = false) String phase, @RequestParam(required = false) String assignee,
                                  @RequestParam(required = false) Long schoolId, @RequestParam(required = false) String sla,
                                  @RequestParam(required = false) String q, @RequestParam(required = false) Boolean includeFinished,
                                  @RequestParam(required = false) String sort, @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) Integer offset) {
        requireToken(token, "workflow:read");
        return workflows.list(new ListQuery(item, step, phase, assignee, schoolId, sla, q, includeFinished, sort, limit, offset));
    }

    @GetMapping("/item-orders/templates")
    public List<WorkflowTemplate> templates(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token) {
        requireToken(token, "workflow:read");
        return workflows.templates();
    }

    @GetMapping("/item-orders/templates/{definitionId}")
    public WorkflowTemplate template(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable String definitionId) {
        requireToken(token, "workflow:read");
        return workflows.template(definitionId);
    }

    @PutMapping("/item-orders/templates/{definitionId}/steps/{step}/sla")
    public WorkflowTemplate setTemplateStepSla(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                               @PathVariable String definitionId, @PathVariable int step,
                                               @RequestBody(required = false) SlaRequest request) {
        requireToken(token, "workflow:write");
        return workflows.setDefaultSla(definitionId, step(step), sla(request));
    }

    @GetMapping("/item-orders/{orderId}")
    public OrderWorkflowView view(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                  @PathVariable String orderId) {
        requireToken(token, "workflow:read");
        return workflows.view(orderId(orderId));
    }

    // ---- stage work ---------------------------------------------------------------------------------------------------------

    @PutMapping("/item-orders/{orderId}/steps/{step}/data")
    public OrderWorkflowView saveStepData(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                          @PathVariable String orderId, @PathVariable int step,
                                          @RequestBody(required = false) StepDataRequest request) {
        requireToken(token, "workflow:write");
        if (request == null || request.data() == null) throw bad("data is required");
        return workflows.saveStepData(orderId(orderId), step(step), request.data(), version(request.expectedVersion()));
    }

    @PostMapping("/item-orders/{orderId}/steps/{step}/complete")
    public OrderWorkflowView complete(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                      @PathVariable String orderId, @PathVariable int step,
                                      @RequestBody(required = false) StepVersionRequest request) {
        requireToken(token, "workflow:write");
        return workflows.completeStep(orderId(orderId), step(step), version(request == null ? null : request.expectedVersion()));
    }

    @PostMapping("/item-orders/{orderId}/steps/{step}/reopen")
    public OrderWorkflowView reopen(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                    @PathVariable String orderId, @PathVariable int step,
                                    @RequestBody(required = false) StepReopenRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("A reason and expectedVersion are required");
        return workflows.reopenStep(orderId(orderId), step(step), request.reason(), version(request.expectedVersion()));
    }

    /** The body carries the stage version superadmin's screen loaded: a sign-off approves the data seen, nothing saved since. */
    @PostMapping("/item-orders/{orderId}/steps/{step}/sign-off")
    public OrderWorkflowView signOff(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable String orderId, @PathVariable int step,
                                     @RequestBody(required = false) StepVersionRequest request) {
        requireToken(token, "workflow:write");
        return workflows.signOff(orderId(orderId), step(step), version(request == null ? null : request.expectedVersion()));
    }

    @PostMapping("/item-orders/{orderId}/head-approval/approve")
    public OrderWorkflowView approve(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                     @PathVariable String orderId, @RequestBody(required = false) HeadApprovalRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("A comment and expectedVersion are required");
        return workflows.approveDesign(orderId(orderId), request.comment(), version(request.expectedVersion()));
    }

    @PostMapping("/item-orders/{orderId}/head-approval/request-changes")
    public OrderWorkflowView requestChanges(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                            @PathVariable String orderId, @RequestBody(required = false) HeadApprovalRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("A comment and expectedVersion are required");
        return workflows.requestDesignChanges(orderId(orderId), request.comment(), version(request.expectedVersion()));
    }

    // ---- assignment ---------------------------------------------------------------------------------------------------------

    @PutMapping("/item-orders/{orderId}/steps/{step}/assignee")
    public OrderWorkflowView assignStep(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                        @PathVariable String orderId, @PathVariable int step,
                                        @RequestBody(required = false) AssigneeRequest request) {
        requireToken(token, "workflow:write");
        return workflows.assignStep(orderId(orderId), step(step), assignee(request));
    }

    @PutMapping("/item-orders/{orderId}/assignee")
    public OrderWorkflowView assignOrder(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                         @PathVariable String orderId, @RequestBody(required = false) AssigneeRequest request) {
        requireToken(token, "workflow:write");
        return workflows.assignOrder(orderId(orderId), assignee(request));
    }

    /** "Take this order": an eligible operator takes an order nobody holds. */
    @PostMapping("/item-orders/{orderId}/claim")
    public OrderWorkflowView claim(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                   @PathVariable String orderId) {
        requireToken(token, "workflow:write");
        return workflows.claim(orderId(orderId));
    }

    // ---- vendors, designer, contacts, notes -----------------------------------------------------------------------------

    @PutMapping("/item-orders/{orderId}/vendor-selections")
    public OrderWorkflowView setVendorSelections(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                                 @PathVariable String orderId,
                                                 @RequestBody(required = false) VendorSelectionsRequest request) {
        requireToken(token, "workflow:write");
        if (request == null || request.selections() == null) throw bad("selections is required");
        return workflows.setVendorSelections(orderId(orderId), request.selections(), version(request.expectedVersion()));
    }

    @PutMapping("/item-orders/{orderId}/designer")
    public OrderWorkflowView setDesigner(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                         @PathVariable String orderId, @RequestBody(required = false) DesignerChoiceRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("Send the designer as {\"designerId\": <id or null>}");
        return workflows.setDesigner(orderId(orderId), request.designerId());
    }

    @PutMapping("/item-orders/{orderId}/contacts")
    public OrderWorkflowView setContacts(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                         @PathVariable String orderId, @RequestBody(required = false) OrderContactsRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("The contacts are required");
        return workflows.setContacts(orderId(orderId), new ContactsChange(request.schoolContactName(), request.schoolContactPhone(),
                request.factoryContactName(), request.factoryContactPhone()));
    }

    @PostMapping("/item-orders/{orderId}/notes")
    public ResponseEntity<NoteEntry> addNote(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                             @PathVariable String orderId, @RequestBody(required = false) OrderNoteRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("A note is required");
        if (request.stepOrder() != null) step(request.stepOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(workflows.addNote(orderId(orderId), request.body(), request.stepOrder()));
    }

    // ---- designers and time slots ---------------------------------------------------------------------------------------

    @GetMapping("/designers")
    public List<DesignerRow> designers(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token) {
        requireToken(token, "workflow:read");
        return workflows.designers();
    }

    @PostMapping("/designers")
    public ResponseEntity<DesignerRow> createDesigner(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                                      @RequestBody(required = false) DesignerRequest request) {
        requireToken(token, "workflow:write");
        if (request == null) throw bad("A designer's name and phone are required");
        return ResponseEntity.status(HttpStatus.CREATED).body(workflows.createDesigner(request.name(), request.phone()));
    }

    @PutMapping("/designers/{id}")
    public DesignerRow updateDesigner(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                      @PathVariable long id, @RequestBody(required = false) DesignerRequest request) {
        requireToken(token, "workflow:write");
        if (id < 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Designer not found");
        if (request == null) throw bad("A designer's name and phone are required");
        return workflows.updateDesigner(id, request.name(), request.phone(), request.active() == null || request.active());
    }

    @GetMapping("/time-slots/{schoolId}")
    public List<TimeSlotRow> timeSlots(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                       @PathVariable long schoolId, @RequestParam String definitionId) {
        requireToken(token, "workflow:read");
        return workflows.timeSlots(schoolId, definitionId);
    }

    @PutMapping("/time-slots/{schoolId}/{definitionId}/{step}")
    public ResponseEntity<Void> setTimeSlot(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                            @PathVariable long schoolId, @PathVariable String definitionId, @PathVariable int step,
                                            @RequestBody(required = false) SlaRequest request) {
        requireToken(token, "workflow:write");
        workflows.setTimeSlot(schoolId, definitionId, step(step), sla(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/time-slots/{schoolId}/{definitionId}/{step}")
    public ResponseEntity<Void> clearTimeSlot(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                              @PathVariable long schoolId, @PathVariable String definitionId, @PathVariable int step) {
        requireToken(token, "workflow:write");
        workflows.clearTimeSlot(schoolId, definitionId, step(step));
        return ResponseEntity.noContent().build();
    }

    // ---- errors -------------------------------------------------------------------------------------------------------------

    /** A refused request carries its reason to the screen as {@code {"message": ...}}, where the frontend looks for it. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(ResponseStatusException refusal) {
        String reason = refusal.getReason();
        if (reason == null || reason.isBlank()) {
            reason = refusal.getStatusCode() instanceof HttpStatus known ? known.getReasonPhrase() : "Request refused";
        }
        return ResponseEntity.status(refusal.getStatusCode()).headers(refusal.getHeaders()).body(Map.of("message", reason));
    }

    /** A broken workflow rule carries its stable code; {@code GATE_UNMET} also lists every unmet requirement (D19). */
    @ExceptionHandler(WorkflowRuleViolation.class)
    public ResponseEntity<Map<String, Object>> ruleViolation(WorkflowRuleViolation violation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", violation.code());
        body.put("message", violation.getMessage());
        if (!violation.unmet().isEmpty()) body.put("unmet", violation.unmet());
        return ResponseEntity.status(violation.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> malformedParameter(MethodArgumentTypeMismatchException mismatch) {
        return ResponseEntity.badRequest().body(Map.of("message", "Invalid " + mismatch.getName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParameter(MissingServletRequestParameterException missing) {
        return ResponseEntity.badRequest().body(Map.of("message", missing.getParameterName() + " is required"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException unreadable) {
        return ResponseEntity.badRequest().body(Map.of("message", "The request body is not valid JSON of the expected shape"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------------

    /** Every call carries the gateway's workflow service token; the route's scope must be named (read or write). */
    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        if (workflowToken.isEmpty() || !StringUtils.hasText(token)
                || !MessageDigest.isEqual(workflowToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid workflow service token");
        }
    }

    private static String orderId(String orderId) {
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()) throw bad("Invalid order id");
        return orderId;
    }

    private static int step(int step) {
        if (step < 1 || step > 99) throw bad("A step is a number from 1 to 99");
        return step;
    }

    private static long assignee(AssigneeRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0) throw bad("userId is required");
        return request.userId();
    }

    private static long version(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) throw bad("expectedVersion is required");
        return expectedVersion;
    }

    /** Both parts, or neither for "no timer". */
    private static Sla sla(SlaRequest request) {
        if (request == null || (request.value() == null && (request.unit() == null || request.unit().isBlank()))) return null;
        if (request.value() == null || request.unit() == null) throw bad("value and unit go together");
        try {
            return new Sla(request.value(), Sla.Unit.valueOf(request.unit()));
        } catch (IllegalArgumentException invalid) {
            throw bad("A time slot needs a value from 1 to " + Sla.MAX_VALUE + " and a unit of HOURS or WORKING_DAYS");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
