package com.custoking.ims.operationsservice.api;

import com.custoking.ims.operationsservice.api.dto.ApproveNoteRequest;
import com.custoking.ims.operationsservice.api.dto.ApprovePrincipalRequest;
import com.custoking.ims.operationsservice.api.dto.CreateFirefightingRequestRequest;
import com.custoking.ims.operationsservice.api.dto.CreateQuotationRequest;
import com.custoking.ims.operationsservice.api.dto.RejectRequest;
import com.custoking.ims.operationsservice.api.dto.UpdateFirefightingRequestRequest;
import com.custoking.ims.operationsservice.api.dto.UpdateQuotationRequest;
import com.custoking.ims.operationsservice.api.dto.VendorPaidRequest;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository.FirefightingRequestRow;
import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository.QuotationRow;
import com.custoking.ims.operationsservice.security.TenantContext;
import com.custoking.ims.operationsservice.security.TenantScope;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ff")
public class FirefightingReadController {

    private final FirefightingReadRepository firefighting;
    private final String readToken;

    public FirefightingReadController(
            FirefightingReadRepository firefighting,
            @Value("${firefighting.read-token:}") String readToken) {
        this.firefighting = firefighting;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/requests")
    public List<FirefightingRequestRow> requests(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "firefighting:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return firefighting.requests(scope, status, limit);
    }

    @GetMapping("/requests/pending-approvals")
    public List<FirefightingRequestRow> pending(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "firefighting:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return firefighting.pending(scope, limit);
    }

    @GetMapping("/requests/stats")
    public List<Map<String, Object>> stats(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        requireToken(token, "firefighting:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        return firefighting.stats(scope);
    }

    @GetMapping("/requests/{code}")
    public Map<String, Object> request(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token, "firefighting:read");
        return execute(() -> firefighting.detail(code));
    }

    @GetMapping("/requests/{code}/timeline")
    public List<Map<String, Object>> timeline(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token, "firefighting:read");
        try {
            return firefighting.timeline(code);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/requests")
    public Map<String, Object> create(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @Valid @RequestBody CreateFirefightingRequestRequest req) {
        requireToken(token, "firefighting:write");
        Map<String, Object> body = new HashMap<>();
        body.put("title", req.title());
        if (req.category() != null) body.put("category", req.category());
        if (req.urgency() != null) body.put("urgency", req.urgency());
        if (req.requiredByDate() != null) body.put("requiredByDate", req.requiredByDate());
        if (req.estimatedBudget() != null) body.put("estimatedBudget", req.estimatedBudget());
        if (req.schoolId() != null) body.put("schoolId", req.schoolId());
        if (req.description() != null) body.put("description", req.description());
        if (req.summary() != null) body.put("summary", req.summary());
        if (req.referenceFileUrl() != null) body.put("referenceFileUrl", req.referenceFileUrl());
        body.put("actorId", TenantContext.get().userId());
        body.put("actorEmail", TenantContext.get().email());
        applyResolvedSchool(body);
        return execute(() -> firefighting.createRequest(body));
    }

    @PatchMapping("/requests/{code}")
    public Map<String, Object> update(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @Valid @RequestBody UpdateFirefightingRequestRequest req) {
        requireToken(token, "firefighting:write");
        // Null-gate every put to preserve containsKey partial-update semantics in the repo.
        Map<String, Object> body = new HashMap<>();
        if (req.title() != null) body.put("title", req.title());
        if (req.category() != null) body.put("category", req.category());
        if (req.urgency() != null) body.put("urgency", req.urgency());
        if (req.requiredByDate() != null) body.put("requiredByDate", req.requiredByDate());
        if (req.estimatedBudget() != null) body.put("estimatedBudget", req.estimatedBudget());
        if (req.description() != null) body.put("description", req.description());
        body.put("actorEmail", TenantContext.get().email());
        return execute(() -> firefighting.updateRequest(code, body));
    }

    @GetMapping("/requests/{code}/quotations")
    public List<QuotationRow> quotations(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token, "firefighting:read");
        return firefighting.quotations(code);
    }

    @PostMapping("/requests/{code}/quotations")
    public Map<String, Object> addQuotation(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @Valid @RequestBody CreateQuotationRequest req) {
        requireToken(token, "firefighting:write");
        Map<String, Object> body = new HashMap<>();
        body.put("vendorName", req.vendorName());
        if (req.amount() != null) body.put("amount", req.amount());
        if (req.deliveryTimeline() != null) body.put("deliveryTimeline", req.deliveryTimeline());
        if (req.notes() != null) body.put("notes", req.notes());
        if (req.documentUrl() != null) body.put("documentUrl", req.documentUrl());
        return execute(() -> firefighting.addQuotation(code, body));
    }

    @PatchMapping("/requests/{code}/quotations/{quotationId}")
    public Map<String, Object> updateQuotation(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @PathVariable String quotationId,
            @Valid @RequestBody UpdateQuotationRequest req) {
        requireToken(token, "firefighting:write");
        // Null-gate every put to preserve containsKey partial-update semantics in the repo.
        Map<String, Object> body = new HashMap<>();
        if (req.vendorName() != null) body.put("vendorName", req.vendorName());
        if (req.amount() != null) body.put("amount", req.amount());
        if (req.deliveryTimeline() != null) body.put("deliveryTimeline", req.deliveryTimeline());
        if (req.notes() != null) body.put("notes", req.notes());
        if (req.documentUrl() != null) body.put("documentUrl", req.documentUrl());
        return execute(() -> firefighting.updateQuotation(code, quotationId, body));
    }

    @DeleteMapping("/requests/{code}/quotations/{quotationId}")
    public Map<String, Object> deleteQuotation(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @PathVariable String quotationId) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.deleteQuotation(code, quotationId));
    }

    @PostMapping("/requests/{code}/submit")
    public Map<String, Object> submit(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.submit(code));
    }

    @PostMapping("/requests/{code}/approve-bursar")
    public Map<String, Object> approveBursar(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @Valid @RequestBody(required = false) ApproveNoteRequest req) {
        requireToken(token, "firefighting:write");
        Map<String, Object> body = new HashMap<>();
        if (req != null && req.note() != null) body.put("note", req.note());
        return execute(() -> firefighting.approveBursar(code, body));
    }

    @PostMapping("/requests/{code}/approve-principal")
    public Map<String, Object> approvePrincipal(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @Valid @RequestBody(required = false) ApprovePrincipalRequest req) {
        requireToken(token, "firefighting:write");
        Map<String, Object> body = new HashMap<>();
        if (req != null && req.selectedQuotationId() != null) body.put("selectedQuotationId", req.selectedQuotationId());
        if (req != null && req.note() != null) body.put("note", req.note());
        return execute(() -> firefighting.approvePrincipal(code, body));
    }

    @PostMapping("/requests/{code}/approve-custoking")
    public Map<String, Object> approveCustoking(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token, "firefighting:write");
        TenantScope.requireSuperAdmin();
        return execute(() -> firefighting.approveCustoking(code));
    }

    @PostMapping("/requests/{code}/reject")
    public Map<String, Object> reject(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @Valid @RequestBody(required = false) RejectRequest req) {
        requireToken(token, "firefighting:write");
        Map<String, Object> body = new HashMap<>();
        body.put("actorName", TenantContext.get().email());
        if (req != null && req.reason() != null) body.put("reason", req.reason());
        if (req != null && req.rejectedReason() != null) body.put("rejectedReason", req.rejectedReason());
        return execute(() -> firefighting.reject(code, body));
    }

    @PatchMapping("/requests/{code}/fulfill")
    public Map<String, Object> fulfill(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.fulfill(code));
    }

    @PostMapping("/requests/{code}/vendor-paid")
    public Map<String, Object> markVendorPaid(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @Valid @RequestBody(required = false) VendorPaidRequest req) {
        requireToken(token, "firefighting:write");
        Map<String, Object> body = new HashMap<>();
        if (req != null && req.schoolId() != null) body.put("schoolId", req.schoolId());
        body.put("paidBy", TenantContext.get().userId());
        if (req != null && req.notes() != null) body.put("notes", req.notes());
        applyResolvedSchool(body);
        return execute(() -> firefighting.markVendorPaid(code, body));
    }

    private void applyResolvedSchool(Map<String, Object> request) {
        Long requested;
        if (request.get("schoolId") == null) {
            requested = null;
        } else {
            try {
                requested = Long.valueOf(String.valueOf(request.get("schoolId")));
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId");
            }
        }
        request.put("schoolId", TenantScope.resolveSchoolId(requested));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid firefighting service token");
        }
    }

    private Map<String, Object> execute(Command command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private interface Command {
        Map<String, Object> run();
    }
}

