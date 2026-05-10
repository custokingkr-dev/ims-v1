package com.custoking.ims.controller;

import com.custoking.ims.dto.school.SchoolAdminRequest;
import com.custoking.ims.dto.school.SchoolCreateRequest;
import com.custoking.ims.dto.school.SchoolOperationsUserRequest;
import com.custoking.ims.dto.school.SchoolUpdateRequest;
import com.custoking.ims.service.SchoolService;
import com.custoking.ims.service.UserContextService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schools")
@PreAuthorize("hasRole('SUPERADMIN')")
public class SchoolController {
    private final UserContextService userContext;
    private final SchoolService schoolService;

    public SchoolController(UserContextService userContext, SchoolService schoolService) {
        this.userContext = userContext;
        this.schoolService = schoolService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.listSchools();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @Valid @RequestBody SchoolCreateRequest request) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.createSchool(request);
    }

    @PostMapping("/{schoolId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrResetAdmin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable Long schoolId,
                                                   @Valid @RequestBody SchoolAdminRequest request) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.createOrResetSchoolAdmin(schoolId, request);
    }

    @GetMapping("/{schoolId}/admin")
    public Map<String, Object> getAdmin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable Long schoolId) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.getSchoolAdmin(schoolId);
    }

    @PatchMapping("/{schoolId}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable Long schoolId,
                                      @RequestBody SchoolUpdateRequest request) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.updateSchool(schoolId, request);
    }

    @PostMapping("/{schoolId}/operations-user")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrResetOperationsUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long schoolId,
            @Valid @RequestBody SchoolOperationsUserRequest request) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.createOrResetSchoolOperationsUser(schoolId, request);
    }

    @GetMapping("/{schoolId}/operations-user")
    public Map<String, Object> getOperationsUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long schoolId) {
        userContext.requireSuperAdmin(authorization);
        return schoolService.getSchoolOperationsUser(schoolId);
    }
}
