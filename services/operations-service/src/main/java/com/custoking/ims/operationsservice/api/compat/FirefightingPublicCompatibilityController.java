package com.custoking.ims.operationsservice.api.compat;

import com.custoking.ims.operationsservice.persistence.FirefightingReadRepository;
import com.custoking.ims.operationsservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@RestController
public class FirefightingPublicCompatibilityController {

    private final FirefightingReadRepository firefighting;
    private final String readToken;

    public FirefightingPublicCompatibilityController(
            FirefightingReadRepository firefighting,
            @Value("${firefighting.read-token:}") String readToken) {
        this.firefighting = firefighting;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @PostMapping("/api/v1/workspace/firefighting")
    public Map<String, Object> createFromWorkspace(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        TenantScope.requirePermissionIfAuthenticated("firefighting:create");
        Map<String, Object> mutableRequest = new HashMap<>(request);
        applyResolvedSchool(mutableRequest);
        return run(() -> firefighting.createRequest(mutableRequest));
    }

    @PostMapping("/api/v1/dashboard/vendor-dues/firefighting/{code}/mark-paid")
    public Map<String, Object> markVendorPaid(
            @RequestHeader(value = "X-Firefighting-Service-Token", required = false) String token,
            @PathVariable String code,
            @RequestBody(required = false) Map<String, Object> request) {
        requireToken(token, "firefighting:write");
        TenantScope.requirePermissionIfAuthenticated("firefighting:update");
        Map<String, Object> mutableRequest = request == null ? new HashMap<>() : new HashMap<>(request);
        applyResolvedSchool(mutableRequest);
        return run(() -> firefighting.markVendorPaid(code, mutableRequest));
    }

    private void applyResolvedSchool(Map<String, Object> request) {
        Long requested;
        if (request.get("schoolId") == null) {
            requested = null;
        } else {
            try {
                requested = Long.valueOf(String.valueOf(request.get("schoolId")));
            } catch (NumberFormatException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid schoolId");
            }
        }
        request.put("schoolId", TenantScope.resolveSchoolId(requested));
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid firefighting service token");
        }
    }

    private Map<String, Object> run(Supplier<Map<String, Object>> command) {
        try {
            return command.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
