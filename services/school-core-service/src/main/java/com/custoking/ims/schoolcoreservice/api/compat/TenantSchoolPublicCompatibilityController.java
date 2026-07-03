package com.custoking.ims.schoolcoreservice.api.compat;

import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class TenantSchoolPublicCompatibilityController {

    private final SchoolStructureReadRepository schools;
    private final String readToken;

    public TenantSchoolPublicCompatibilityController(
            SchoolStructureReadRepository schools,
            @Value("${tenant-school.read-token:}") String readToken) {
        this.schools = schools;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @PostMapping("/api/v1/workspace/staff")
    public Map<String, Object> addStaffFromWorkspace(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        // Body schoolId is the requested school; TenantScope sandboxes it to the authenticated school.
        Long resolvedSchoolId = TenantScope.resolveSchoolId(longValue(request.get("schoolId")));
        if (resolvedSchoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        try {
            return schools.addStaff(resolvedSchoolId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/v1/workspace/timetable")
    public Map<String, Object> addTimetableFromWorkspace(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestHeader(value = "X-Authenticated-School-Id", required = false) String authenticatedSchoolId,
            @RequestBody Map<String, Object> request) {
        requireToken(token, "tenant-school:write");
        // Body schoolId is the requested school; TenantScope (via TenantContextFilter) resolves and sandboxes it.
        // The explicit authenticatedSchoolId header is kept for backward-compat signature but TenantContextFilter
        // already populated TenantContext from that header, so TenantScope.resolveSchoolId handles the fallback.
        Long resolvedSchoolId = TenantScope.resolveSchoolId(longValue(request.get("schoolId")));
        if (resolvedSchoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        try {
            return schools.addTimetableEntry(resolvedSchoolId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope) || !StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant-school service token");
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
