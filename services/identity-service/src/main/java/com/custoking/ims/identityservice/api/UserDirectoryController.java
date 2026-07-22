package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.api.dto.PasswordResetRequest;
import com.custoking.ims.identityservice.persistence.UserDirectoryReadRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantScope;
import jakarta.validation.Valid;
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

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserDirectoryController {

    private static final String USER_READ = "user:read";
    private static final String USER_UPDATE = "user:update";
    private static final String USER_DISABLE = "user:disable";
    private static final String USER_RESET_PASSWORD = "user:reset_password";

    private final UserDirectoryReadRepository users;
    private final String serviceToken;

    public UserDirectoryController(
            UserDirectoryReadRepository users,
            @Value("${identity.introspection-token:}") String serviceToken) {
        this.users = users;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @GetMapping
    public Object users(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) Long zoneId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "100") int limit) {
        requireToken(token, "identity:read");
        TenantScope.requirePermissionIfAuthenticated(USER_READ);
        branchId = TenantScope.resolveSchoolId(branchId);
        return users.users(role, branchId, zoneId, active, limit);
    }

    @GetMapping("/{id}")
    public Object user(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "identity:read");
        TenantScope.requirePermissionIfAuthenticated(USER_READ);
        var user = users.user(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        // Enforce tenant scope: non-superadmin may only read users in their own school.
        // branchId=null (platform user) resolves to the caller's own school — no exception.
        TenantScope.resolveSchoolId(user.branchId());
        return user;
    }

    @PostMapping("/{id}/password-reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long id,
            @Valid @RequestBody PasswordResetRequest req) {
        requireToken(token, "identity:write");
        TenantScope.requirePermissionIfAuthenticated(USER_RESET_PASSWORD);
        TenantScope.requireSuperAdmin();
        users.resetPassword(id, req.password(), TenantContext.get().userId(), TenantContext.get().email());
    }

    @PostMapping("/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableUser(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "identity:write");
        TenantScope.requirePermissionIfAuthenticated(USER_DISABLE);
        TenantScope.requireSuperAdmin();
        users.disableUser(id, TenantContext.get().userId(), TenantContext.get().email());
    }

    @PostMapping("/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enableUser(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "identity:write");
        TenantScope.requirePermissionIfAuthenticated(USER_UPDATE);
        TenantScope.requireSuperAdmin();
        users.enableUser(id, TenantContext.get().userId(), TenantContext.get().email());
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
        }
    }

    private String text(Map<String, Object> body, String field) {
        if (body == null || body.get(field) == null) {
            return null;
        }
        String value = String.valueOf(body.get(field)).trim();
        return value.isBlank() ? null : value;
    }

    private Long longValue(Map<String, Object> body, String field) {
        if (body == null || body.get(field) == null) {
            return null;
        }
        Object value = body.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}

