package com.custoking.ims.firefightingservice.api;

import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository;
import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository.FirefightingRequestRow;
import com.custoking.ims.firefightingservice.persistence.FirefightingReadRepository.QuotationRow;
import com.custoking.ims.firefightingservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
            @RequestBody Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        Map<String, Object> mutableRequest = new HashMap<>(request);
        applyResolvedSchool(mutableRequest);
        return execute(() -> firefighting.createRequest(mutableRequest));
    }

    @PatchMapping("/requests/{code}")
    public Map<String, Object> update(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.updateRequest(code, request));
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
            @RequestBody Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.addQuotation(code, request));
    }

    @PatchMapping("/requests/{code}/quotations/{quotationId}")
    public Map<String, Object> updateQuotation(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @PathVariable String quotationId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.updateQuotation(code, quotationId, request));
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
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.approveBursar(code, request == null ? Map.of() : request));
    }

    @PostMapping("/requests/{code}/approve-principal")
    public Map<String, Object> approvePrincipal(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.approvePrincipal(code, request == null ? Map.of() : request));
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
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.reject(code, request == null ? Map.of() : request));
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
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        return execute(() -> firefighting.markVendorPaid(code, request == null ? Map.of() : request));
    }

    private void applyResolvedSchool(Map<String, Object> request) {
        Long requested = request.get("schoolId") == null ? null
                : Long.valueOf(String.valueOf(request.get("schoolId")));
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

