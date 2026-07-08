package com.custoking.ims.platformservice.api.compat;

import com.custoking.ims.platformservice.persistence.ReportingApprovalRepository;
import com.custoking.ims.platformservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
public class ReportingApprovalsCompatibilityController {

    private final ReportingApprovalRepository approvals;
    private final String readToken;

    public ReportingApprovalsCompatibilityController(
            ReportingApprovalRepository approvals,
            @Value("${reporting.read-token:}") String readToken) {
        this.approvals = approvals;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/api/v1/approvals")
    public List<Map<String, Object>> approvals(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "reporting:read");
        TenantScope.requireSuperAdmin();
        return approvals.approvals(limit);
    }

    @PostMapping("/api/v1/approvals/{id}/{action}")
    public Map<String, Object> decide(
            @RequestHeader(value = "X-Reporting-Service-Token", required = false) String token,
            @PathVariable String id,
            @PathVariable String action,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "reporting:read");
        TenantScope.requireSuperAdmin();
        try {
            return approvals.decide(id, action, request == null ? Map.of() : request);
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid approval request" : ex.getMessage();
            HttpStatus status = message.toLowerCase().contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, message, ex);
        }
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid reporting service token");
        }
    }
}
