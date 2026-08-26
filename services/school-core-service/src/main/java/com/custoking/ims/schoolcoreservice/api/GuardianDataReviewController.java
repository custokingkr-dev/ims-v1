package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.GuardianDataReviewRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/guardian-data-review")
public class GuardianDataReviewController {

    private final GuardianDataReviewRepository reviews;
    private final ModuleEntitlementGuard moduleGuard;
    private final String studentToken;

    public GuardianDataReviewController(GuardianDataReviewRepository reviews,
                                        ModuleEntitlementGuard moduleGuard,
                                        @Value("${student.read-token:}") String studentToken) {
        this.reviews = reviews;
        this.moduleGuard = moduleGuard;
        this.studentToken = studentToken == null ? "" : studentToken.trim();
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        authorizeToken(token);
        TenantScope.requirePermissionIfAuthenticated("student:read");
        Long scope = readScope(schoolId);
        requireModule(scope);
        return execute(() -> reviews.summary(scope));
    }

    @GetMapping("/cases")
    public Map<String, Object> cases(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        authorizeToken(token);
        TenantScope.requirePermissionIfAuthenticated("student:read");
        Long scope = readScope(schoolId);
        requireModule(scope);
        return execute(() -> reviews.cases(scope, bucket, status, search, page, size));
    }

    @PostMapping("/cases/{caseId}/decisions")
    public Map<String, Object> decide(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam Long schoolId,
            @PathVariable String caseId,
            @RequestBody Map<String, Object> request) {
        authorizeToken(token);
        TenantScope.requirePermissionIfAuthenticated("student:update");
        Long scope = writeScope(schoolId);
        requireModule(scope);
        return execute(() -> reviews.decide(scope, caseId, request, idempotencyKey));
    }

    private Long readScope(Long requested) {
        return TenantScope.resolvePlatformReadScope(requested);
    }

    private Long writeScope(Long requested) {
        TenantContext context = TenantContext.get();
        return context.isSuperAdmin() || context.isOperations()
                ? TenantScope.resolveOperationsWriteScope(requested)
                : TenantScope.resolveSchoolId(requested);
    }

    private void requireModule(Long schoolId) {
        if (schoolId != null) moduleGuard.requireModuleEnabled(schoolId, "STUDENTS");
    }

    private void authorizeToken(String token) {
        if (!StringUtils.hasText(studentToken) || !studentToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid student service token");
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
