package com.custoking.ims.identityservice.api;

import com.custoking.ims.identityservice.persistence.IdentityUserProvisioningRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/provisioning")
public class IdentityProvisioningController {

    private final IdentityUserProvisioningRepository users;
    private final String serviceToken;

    public IdentityProvisioningController(
            IdentityUserProvisioningRepository users,
            @Value("${identity.introspection-token:}") String serviceToken) {
        this.users = users;
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    @PostMapping("/schools/{schoolId}/users/{role}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> provisionSchoolUser(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long schoolId,
            @PathVariable String role,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "identity:write");
        return users.provisionSchoolUser(schoolId, role, body);
    }

    @PostMapping("/zones/{zoneId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> provisionZoneAdmin(
            @RequestHeader(value = "X-Identity-Service-Token", required = false) String token,
            @PathVariable Long zoneId,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "identity:write");
        return users.provisionZoneAdmin(zoneId, body);
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(serviceToken) || !serviceToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid identity service token");
        }
    }
}

