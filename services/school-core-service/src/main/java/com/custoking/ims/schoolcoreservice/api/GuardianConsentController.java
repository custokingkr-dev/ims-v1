package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.GuardianConsentRepository;
import com.custoking.ims.schoolcoreservice.persistence.StudentReadRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/students/{studentId}")
public class GuardianConsentController {

    private final GuardianConsentRepository guardians;
    private final StudentReadRepository students;
    private final ModuleEntitlementGuard moduleGuard;
    private final String readToken;

    public GuardianConsentController(GuardianConsentRepository guardians,
                                     StudentReadRepository students,
                                     ModuleEntitlementGuard moduleGuard,
                                     @Value("${student.read-token:}") String readToken) {
        this.guardians = guardians;
        this.students = students;
        this.moduleGuard = moduleGuard;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/guardians")
    public Map<String, Object> overview(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long studentId) {
        requireToken(token, "student:read");
        authorizeStudent(studentId, "student:read");
        return execute(() -> guardians.overview(studentId));
    }

    @PostMapping("/guardians")
    public Map<String, Object> addGuardian(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long studentId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        authorizeStudent(studentId, "student:update");
        return execute(() -> guardians.addGuardian(studentId, request));
    }

    @PutMapping("/guardians/{guardianId}")
    public Map<String, Object> updateGuardian(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long studentId,
            @PathVariable String guardianId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        authorizeStudent(studentId, "student:update");
        return execute(() -> guardians.updateGuardian(studentId, guardianId, request));
    }

    @DeleteMapping("/guardians/{guardianId}")
    public Map<String, Object> unlinkGuardian(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @PathVariable Long studentId,
            @PathVariable String guardianId) {
        requireToken(token, "student:write");
        authorizeStudent(studentId, "student:update");
        return execute(() -> guardians.unlinkGuardian(studentId, guardianId));
    }

    @PostMapping("/consents")
    public Map<String, Object> recordConsent(
            @RequestHeader(value = "X-Student-Service-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable Long studentId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "student:write");
        authorizeStudent(studentId, "student:update");
        return execute(() -> guardians.recordConsent(studentId, request, idempotencyKey));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid student service token");
        }
    }

    private void authorizeStudent(Long studentId, String userPermission) {
        TenantScope.requirePermissionIfAuthenticated(userPermission);
        Long schoolId;
        try {
            schoolId = students.schoolIdForStudent(studentId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
        TenantScope.resolveSchoolId(schoolId);
        moduleGuard.requireModuleEnabled(schoolId, "STUDENTS");
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
