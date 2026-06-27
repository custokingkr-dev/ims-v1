package com.custoking.ims.tenantschoolservice.api.compat;

import com.custoking.ims.tenantschoolservice.persistence.SchoolStructureReadRepository;
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
        requireToken(token);
        Long schoolId = longValue(request.get("schoolId"));
        if (schoolId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schoolId is required");
        }
        try {
            return schools.addStaff(schoolId, request);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    private void requireToken(String token) {
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant-school service token");
        }
    }

    private Long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
