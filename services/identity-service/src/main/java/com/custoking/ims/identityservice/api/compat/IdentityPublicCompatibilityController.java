package com.custoking.ims.identityservice.api.compat;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import com.custoking.ims.identityservice.security.TenantContext;
import com.custoking.ims.identityservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
public class IdentityPublicCompatibilityController {

    private final IdentityUserProvisioningRepository users;
    private final String serviceToken;

    public IdentityPublicCompatibilityController(
            IdentityUserProvisioningRepository users,
            @Value("${identity.introspection-token:}") String serviceToken) {
        this.users = users;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @PostMapping("/api/v1/schools/{schoolId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSchoolAdmin(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "identity:write");
        TenantScope.requireSuperAdmin();
        Map<String, Object> stamped = new HashMap<>(body == null ? Map.of() : body);
        stamped.put("assignedBy", TenantContext.get().userId());
        return run(() -> users.provisionSchoolUser(schoolId, "ADMIN", stamped));
    }

    @PostMapping("/api/v1/schools/{schoolId}/operations-user")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOperationsUser(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "identity:write");
        TenantScope.requireSuperAdmin();
        Map<String, Object> stamped = new HashMap<>(body == null ? Map.of() : body);
        stamped.put("assignedBy", TenantContext.get().userId());
        return run(() -> users.provisionSchoolUser(schoolId, "OPERATIONS", stamped));
    }

    @PostMapping("/api/v1/zones/{zoneId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createZoneAdmin(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long zoneId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "identity:write");
        TenantScope.requireSuperAdmin();
        Map<String, Object> stamped = new HashMap<>(body == null ? Map.of() : body);
        stamped.put("assignedBy", TenantContext.get().userId());
        return run(() -> users.provisionZoneAdmin(zoneId, stamped));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
        }
    }

    private Map<String, Object> run(java.util.function.Supplier<Map<String, Object>> command) {
        try {
            return command.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
