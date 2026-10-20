package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.application.OperatorDirectory;
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
 * Operators scoped to a school and a catalogue item (spec v4). Superadmin manages the scopes; the machine directory
 * lookups for school-core and operations-service live on the internal route in
 * {@code api.internal.OperatorDirectoryController}.
 */
@RestController
@RequestMapping("/api/v1/rbac")
public class OperatorScopeController {
    private static final String ROLE_ASSIGN = "role:assign";
    private static final String ROLE_READ = "role:read";

    private final RbacCommandRepository commands;
    private final OperatorDirectory directory;
    private final String serviceToken;

    public OperatorScopeController(RbacCommandRepository commands, OperatorDirectory directory,
                                   @Value("${identity.introspection-token:}") String serviceToken) {
        this.commands = commands;
        this.directory = directory;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    public record ScopeRequest(Long schoolId, String catalogCategory) { }

    // ---- superadmin ------------------------------------------------------------------------------------------------

    /** Scope an existing operator to one catalogue item at one school. Revoke through the ordinary assignment revoke. */
    @PostMapping("/users/{userId}/operator-scopes")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assignScope(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long userId, @RequestBody ScopeRequest request) {
        requireToken(token, "identity:write");
        TenantScope.requirePermissionIfAuthenticated(ROLE_ASSIGN);
        TenantScope.requireSuperAdmin();
        return commands.assignOperatorScope(userId, request.schoolId(), request.catalogCategory(), TenantContext.get().userId());
    }

    /** Active operator assignments, optionally for one school and/or one catalogue item. */
    @GetMapping("/operators")
    public List<OperatorRow> operators(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId, @RequestParam(required = false) String catalogCategory) {
        requireToken(token, "identity:read");
        TenantScope.requirePermissionIfAuthenticated(ROLE_READ);
        TenantScope.requireSuperAdmin();
        try {
            return directory.list(schoolId, catalogCategory);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope");
        }
        if (!matches(serviceToken, token)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
    }

    private static boolean matches(String expected, String actual) {
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(actual)) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
