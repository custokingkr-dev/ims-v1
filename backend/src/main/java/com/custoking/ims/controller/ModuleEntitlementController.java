package com.custoking.ims.controller;

import com.custoking.ims.common.domain.PermissionConstants;
import com.custoking.ims.security.AppUserDetails;
import com.custoking.ims.service.ModuleEntitlementService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST API for school module entitlements.
 * All endpoints require school:update or higher platform permission.
 */
@RestController
@RequestMapping("/api/v1/schools/{schoolId}/modules")
public class ModuleEntitlementController {

    private final ModuleEntitlementService entitlementService;

    public ModuleEntitlementController(ModuleEntitlementService entitlementService) {
        this.entitlementService = entitlementService;
    }

    /** List all module entitlements for a school. */
    @GetMapping
    @PreAuthorize(PermissionConstants.SCHOOL_READ)
    public List<Map<String, Object>> listModules(@PathVariable Long schoolId) {
        return entitlementService.listEntitlements(schoolId);
    }

    /** Get active modules only. */
    @GetMapping("/active")
    @PreAuthorize(PermissionConstants.SCHOOL_READ)
    public List<String> activeModules(@PathVariable Long schoolId) {
        return entitlementService.activeModules(schoolId);
    }

    /** Create or update a module entitlement. */
    @PutMapping("/{moduleCode}")
    @PreAuthorize(PermissionConstants.SCHOOL_UPDATE)
    @ResponseStatus(HttpStatus.OK)
    @Transactional
    public Map<String, Object> upsertModule(
            @PathVariable Long schoolId,
            @PathVariable String moduleCode,
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        boolean enabled = body.containsKey("enabled") ? Boolean.TRUE.equals(body.get("enabled")) : true;
        String plan = body.containsKey("plan") ? String.valueOf(body.get("plan")) : null;
        LocalDate startDate = parseDate(body, "startDate");
        LocalDate endDate   = parseDate(body, "endDate");
        String notes = body.containsKey("notes") ? String.valueOf(body.get("notes")) : null;
        Long actorId = resolveActorId(authentication);

        return entitlementService.upsertEntitlement(schoolId, moduleCode, enabled, plan,
                startDate, endDate, notes, actorId);
    }

    /** Disable a module for a school. */
    @DeleteMapping("/{moduleCode}")
    @PreAuthorize(PermissionConstants.SCHOOL_UPDATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void disableModule(@PathVariable Long schoolId, @PathVariable String moduleCode) {
        entitlementService.disableModule(schoolId, moduleCode);
    }

    private static LocalDate parseDate(Map<String, Object> body, String field) {
        Object val = body.get(field);
        if (val == null) return null;
        try { return LocalDate.parse(val.toString()); }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date for field " + field + ": " + val);
        }
    }

    private static Long resolveActorId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails d) return d.getUser().getId();
        return null;
    }
}
