package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.application.VendorDirectoryService;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.CategoryChange;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorCategoryView;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorInput;
import com.custoking.ims.operationsservice.application.VendorDirectoryService.VendorView;
import com.custoking.ims.operationsservice.application.orderworkflow.rules.WorkflowRuleViolation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

import java.util.List;
import java.util.Map;

/**
 * The vendor directory (design D31, D32, §4.5; contract {@code vendorDirectoryClient.v1}). Reads are for superadmin and
 * operators holding {@code notebook-tracker:read}, who see active vendors only; every write is superadmin's. Every call
 * carries the gateway's workflow service token.
 */
@RestController
@RequestMapping("/api/v1/workflows")
public class VendorDirectoryController {
    private final VendorDirectoryService directory;
    private final String workflowToken;

    public VendorDirectoryController(VendorDirectoryService directory, @Value("${workflow.read-token:}") String workflowToken) {
        this.directory = directory;
        this.workflowToken = workflowToken == null ? "" : workflowToken.trim();
    }

    /** A category change; each null keeps that part. */
    public record VendorCategoryRequest(String name, Boolean active, Integer sortOrder) { }

    /** A vendor's fields (D31): name and at least one category are required, and a phone for every real vendor. */
    public record VendorRequest(String name, List<String> categories, String contactPerson, String phone, String altPhone,
                                String email, String city, String whatsappGroupUrl, String notes, Boolean active) {
        VendorInput input() {
            return new VendorInput(name, categories, contactPerson, phone, altPhone, email, city, whatsappGroupUrl, notes, active);
        }
    }

    @GetMapping("/vendor-categories")
    public List<VendorCategoryView> categories(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return directory.categories();
    }

    @PutMapping("/vendor-categories/{code}")
    public VendorCategoryView updateCategory(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                             @PathVariable String code, @RequestBody VendorCategoryRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        return directory.updateCategory(code, request == null ? null : new CategoryChange(request.name(), request.active(), request.sortOrder()));
    }

    @GetMapping("/vendors")
    public List<VendorView> vendors(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                    @RequestParam(required = false) String category, @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "false") boolean includeInactive) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:read");
        return directory.vendors(category, q, includeInactive);
    }

    @PostMapping("/vendors")
    public ResponseEntity<VendorView> create(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                                             @RequestBody VendorRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        return ResponseEntity.status(HttpStatus.CREATED).body(directory.create(request == null ? null : request.input()));
    }

    @PutMapping("/vendors/{id}")
    public VendorView update(@RequestHeader(value = "X-Workflow-Service-Token", required = false) String token,
                             @PathVariable long id, @RequestBody VendorRequest request) {
        WorkflowRefusals.requireToken(workflowToken, token, "workflow:write");
        if (id < 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vendor not found");
        return directory.update(id, request == null ? null : request.input());
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
        return WorkflowRefusals.unreadable("Send the vendor as JSON: name, categories, phone and the optional fields");
    }
}
