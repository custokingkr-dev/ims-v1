package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.OperatorDirectory;
import com.custoking.ims.identityservice.application.OperatorSelection;
import com.custoking.ims.identityservice.persistence.OperatorDirectoryRepository.OperatorRow;
import com.custoking.ims.identityservice.persistence.RbacCommandRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantScope;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Operators scoped to a school and a catalogue item (spec v4). Superadmin manages the scopes; the two directory
 * lookups are machine-only, for school-core (choosing the operator at order placement) and operations-service
 * (checking that a step is only ever assigned to an eligible operator).
 */
@RestController
@RequestMapping("/api/v1/rbac")
public class OperatorScopeController {
    private static final String ROLE_ASSIGN = "role:assign";
    private static final String ROLE_READ = "role:read";

    private final RbacCommandRepository commands;
    private final OperatorDirectory directory;
    private final String serviceToken;
    private final String directoryToken;

    public OperatorScopeController(RbacCommandRepository commands, OperatorDirectory directory,
                                   @Value("${identity.introspection-token:}") String serviceToken,
                                   @Value("${identity.operator-directory-token:}") String directoryToken) {
        this.commands = commands;
        this.directory = directory;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.directoryToken = directoryToken == null ? "" : directoryToken.trim();
    }

    public record ScopeRequest(Long schoolId, String catalogCategory) { }

    public record Resolved(String outcome, Long userId, List<Long> candidates) { }

    public record Eligibility(boolean eligible) { }

    // ---- superadmin ------------------------------------------------------------------------------------------------

    /** Scope an existing operator to one catalogue item at one school. Revoke through the ordinary assignment revoke. */
    @PostMapping("/users/{userId}/operator-scopes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignScope(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId, @RequestBody ScopeRequest request) {
        requireToken(token);
        TenantScope.requirePermissionIfAuthenticated(ROLE_ASSIGN);
        TenantScope.requireSuperAdmin();
        return commands.assignOperatorScope(userId, request.schoolId(), request.catalogCategory(), TenantContext.get().userId());
    }

    /** Active operator assignments, optionally for one school and/or one catalogue item. */
    @GetMapping("/operators")
    public List<OperatorRow> operators(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId, @RequestParam(required = false) String catalogCategory) {
        requireToken(token);
        TenantScope.requirePermissionIfAuthenticated(ROLE_READ);
        TenantScope.requireSuperAdmin();
        try {
            return directory.list(schoolId, catalogCategory);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    // ---- machine ---------------------------------------------------------------------------------------------------

    /** Which operator gets a new order for this school and catalogue item, or why none was picked. */
    @GetMapping("/operator-directory/resolve")
    public Resolved resolve(
            @RequestHeader(value = "X-Operator-Directory-Service-Token", required = false) String token,
            @RequestParam long schoolId, @RequestParam String catalogCategory) {
        requireDirectoryToken(token);
        try {
            OperatorSelection.Result result = directory.resolve(schoolId, catalogCategory);
            return new Resolved(result.outcome().name(), result.userId(), result.candidates());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    /** Whether a user may be assigned work for this school and catalogue item. */
    @GetMapping("/operator-directory/eligible")
    public Eligibility eligible(
            @RequestHeader(value = "X-Operator-Directory-Service-Token", required = false) String token,
            @RequestParam long userId, @RequestParam long schoolId, @RequestParam String catalogCategory) {
        requireDirectoryToken(token);
        try {
            return new Eligibility(directory.eligible(userId, schoolId, catalogCategory));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void requireToken(String token) {
        if (!matches(serviceToken, token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
    }

    /** The gateway strips every inbound *-service-token header and never injects this one, so only services can send it. */
    private void requireDirectoryToken(String token) {
        if (!matches(directoryToken, token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid operator directory token");
    }

    private static boolean matches(String expected, String actual) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
