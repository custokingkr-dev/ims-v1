package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.dto.zone.ZoneAdminRequest;
import com.custoking.ims.dto.zone.ZoneCreateRequest;
import com.custoking.ims.dto.zone.ZoneUpdateRequest;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.service.UserContextService;
import com.custoking.ims.service.ZoneService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private final UserContextService userContext;
    private final ZoneService zoneService;

    public ZoneController(UserContextService userContext, ZoneService zoneService) {
        this.userContext = userContext;
        this.zoneService = zoneService;
    }

    @GetMapping
    @PreAuthorize(PermissionConstants.ZONE_READ)
    public List<Map<String, Object>> listZones(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        userContext.requireUser(authorization);
        return zoneService.listZones();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionConstants.ZONE_CREATE)
    public Map<String, Object> createZone(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ZoneCreateRequest request) {
        AuthUser actor = userContext.requireSuperAdmin(authorization);
        return zoneService.createZone(
                request.name(), request.code(), request.city(), request.state(),
                request.description(), actor.userId());
    }

    @PatchMapping("/{zoneId}")
    @PreAuthorize(PermissionConstants.ZONE_UPDATE)
    public Map<String, Object> updateZone(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long zoneId,
            @RequestBody ZoneUpdateRequest request) {
        userContext.requireSuperAdmin(authorization);
        return zoneService.updateZone(zoneId, request.name(), request.city(),
                request.state(), request.description(), request.active());
    }

    @PostMapping("/{zoneId}/schools/{schoolId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PermissionConstants.ZONE_ASSIGN_SCHOOL)
    public void assignSchool(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long zoneId,
            @PathVariable Long schoolId) {
        AuthUser actor = userContext.requireSuperAdmin(authorization);
        zoneService.assignSchoolToZone(zoneId, schoolId, actor.userId());
    }

    @DeleteMapping("/{zoneId}/schools/{schoolId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(PermissionConstants.ZONE_ASSIGN_SCHOOL)
    public void removeSchool(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long zoneId,
            @PathVariable Long schoolId) {
        userContext.requireSuperAdmin(authorization);
        zoneService.removeSchoolFromZone(zoneId, schoolId);
    }

    @GetMapping("/{zoneId}/schools")
    @PreAuthorize(PermissionConstants.ZONE_READ)
    public List<Map<String, Object>> getSchoolsInZone(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long zoneId) {
        userContext.requireUser(authorization);
        return zoneService.getSchoolsInZone(zoneId);
    }

    @GetMapping("/my-zone/schools")
    @PreAuthorize(PermissionConstants.ZONE_READ)
    public List<Map<String, Object>> getMyZoneSchools(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthUser actor = userContext.requireUser(authorization);
        return zoneService.getMyZoneSchools(actor.userId());
    }

    @PostMapping("/{zoneId}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(PermissionConstants.ZONE_ASSIGN_ADMIN)
    public Map<String, Object> createOrResetZoneAdmin(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long zoneId,
            @Valid @RequestBody ZoneAdminRequest request) {
        AuthUser actor = userContext.requireSuperAdmin(authorization);
        return zoneService.createOrResetZoneAdmin(
                zoneId, request.fullName(), request.email(),
                request.temporaryPassword(), actor.userId());
    }
}
