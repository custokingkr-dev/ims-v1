package com.custoking.ims.controller;

import com.custoking.ims.dto.school.SchoolAdminRequest;
import com.custoking.ims.dto.school.SchoolCreateRequest;
import com.custoking.ims.dto.school.SchoolUpdateRequest;
import com.custoking.ims.service.DatabaseStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/schools")
public class SchoolController {
    private final DatabaseStore store;

    public SchoolController(DatabaseStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization) {
        store.requireSuperAdmin(authorization);
        return store.listSchools();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @Valid @RequestBody SchoolCreateRequest request) {
        store.requireSuperAdmin(authorization);
        return store.createSchool(request);
    }

    @PostMapping("/{schoolId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createOrResetAdmin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable Long schoolId,
                                                   @Valid @RequestBody SchoolAdminRequest request) {
        store.requireSuperAdmin(authorization);
        return store.createOrResetSchoolAdmin(schoolId, request);
    }

    @GetMapping("/{schoolId}/admin")
    public Map<String, Object> getAdmin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable Long schoolId) {
        store.requireSuperAdmin(authorization);
        return store.getSchoolAdmin(schoolId);
    }

    @PatchMapping("/{schoolId}")
    public Map<String, Object> update(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable Long schoolId,
                                      @RequestBody SchoolUpdateRequest request) {
        store.requireSuperAdmin(authorization);
        return store.updateSchool(schoolId, request);
    }
}
