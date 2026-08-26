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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        requireToken(token, "student:read");
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
        requireToken(token, "student:read");
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
        requireToken(token, "student:write");
        TenantScope.requirePermissionIfAuthenticated("student:update");
        Long scope = writeScope(schoolId);
        requireModule(scope);
        return execute(() -> reviews.decide(scope, caseId, request, idempotencyKey));
    }

    @PostMapping("/decisions/bulk")
    public Map<String, Object> decideBulk(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody BulkDecisionRequest request) {
        requireToken(token, "student:write");
        TenantScope.requirePermissionIfAuthenticated("student:update");
        if (request == null || request.cases() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one review case is required");
        }

        List<GuardianDataReviewRepository.BulkDecisionCase> scopedCases =
                new ArrayList<>(request.cases().size());
        Set<Long> checkedSchools = new HashSet<>();
        for (BulkDecisionCaseRequest reviewCase : request.cases()) {
            if (reviewCase == null || reviewCase.schoolId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Every bulk decision case requires a schoolId");
            }
            Long scope = writeScope(reviewCase.schoolId());
            if (checkedSchools.add(scope)) requireModule(scope);
            scopedCases.add(new GuardianDataReviewRepository.BulkDecisionCase(
                    scope, reviewCase.caseId(), reviewCase.caseSnapshotSha256()));
        }
        return execute(() -> reviews.decideBulk(scopedCases, request.decision(),
                request.notes(), idempotencyKey));
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

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)
                || !StringUtils.hasText(studentToken)
                || !studentToken.equals(token)) {
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

    public record BulkDecisionRequest(String decision, String notes,
                                      List<BulkDecisionCaseRequest> cases) {}

    public record BulkDecisionCaseRequest(Long schoolId, String caseId,
                                          String caseSnapshotSha256) {}
}
