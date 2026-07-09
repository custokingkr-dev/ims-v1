package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.SchoolEntity;
import com.custoking.ims.schoolcoreservice.persistence.SchoolRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import com.custoking.ims.schoolcoreservice.security.TenantScope;
import com.custoking.ims.schoolcoreservice.persistence.SchoolStructureReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.StructureInUseException;
import com.custoking.ims.schoolcoreservice.persistence.ModuleEntitlementReadRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneEntity;
import com.custoking.ims.schoolcoreservice.persistence.ZoneCommandRepository;
import com.custoking.ims.schoolcoreservice.persistence.ZoneRepository;
import com.custoking.ims.schoolcoreservice.security.ModuleEntitlementGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TenantSchoolController {

    private final SchoolRepository schools;
    private final ZoneRepository zones;
    private final ModuleEntitlementReadRepository modules;
    private final ModuleEntitlementGuard moduleGuard;
    private final SchoolStructureReadRepository structure;
    private final ZoneCommandRepository zoneCommands;
    private final String readToken;

    public TenantSchoolController(
            SchoolRepository schools,
            ZoneRepository zones,
            ModuleEntitlementReadRepository modules,
            ModuleEntitlementGuard moduleGuard,
            SchoolStructureReadRepository structure,
            ZoneCommandRepository zoneCommands,
            @Value("${tenant-school.read-token:}") String readToken) {
        this.schools = schools;
        this.zones = zones;
        this.modules = modules;
        this.moduleGuard = moduleGuard;
        this.structure = structure;
        this.zoneCommands = zoneCommands;
        this.readToken = readToken == null ? "" : readToken.trim();
    }

    @GetMapping("/schools")
    public List<SchoolResponse> schools(@RequestHeader(value = "X-Tenant-School-Token", required = false) String token) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return schools.findAllByOrderByNameAsc().stream().map(SchoolResponse::from).toList();
    }

    @GetMapping("/schools/{id}")
    public SchoolResponse school(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "tenant-school:read");
        Long resolvedId = TenantScope.resolveSchoolId(id);
        return schools.findById(resolvedId).map(SchoolResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "school not found"));
    }

    @GetMapping("/schools/{id}/admin")
    public Object schoolAdmin(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "tenant-school:read");
        Long resolvedId = TenantScope.resolveSchoolId(id);
        return structure.schoolStats().stream()
                .filter(row -> resolvedId.equals(row.id()))
                .findFirst()
                .<Object>map(row -> Map.of(
                        "schoolId", row.id(),
                        "schoolName", row.name(),
                        "email", row.adminEmail() == null ? "" : row.adminEmail()))
                .orElse(Map.of("schoolId", resolvedId, "email", ""));
    }

    @PostMapping("/schools")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createSchool(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        return runCommand(() -> structure.createSchool(body));
    }

    @PatchMapping("/schools/{id}")
    public Map<String, Object> updateSchool(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        return runCommand(() -> structure.updateSchool(id, body));
    }

    @PutMapping("/schools/{id}/structure")
    public Map<String, Object> updateSchoolStructure(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        Long resolvedId = TenantScope.resolveSchoolId(id); // superadmin bypass; own-school admin; else 403
        TenantScope.requireSchoolAdmin();
        moduleGuard.requireErpEnabled(resolvedId);
        int classCount = intInRange(body.get("classCount"), 1, 12, "classCount");
        int sectionCount = intInRange(body.get("sectionCount"), 1, 26, "sectionCount");
        try {
            return runCommand(() -> structure.updateStructure(resolvedId, classCount, sectionCount));
        } catch (StructureInUseException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @GetMapping("/schools/{id}/modules")
    public Object schoolModules(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "tenant-school:read");
        Long resolvedId = TenantScope.resolveSchoolId(id);
        return modules.list(resolvedId, null);
    }

    @GetMapping("/schools/{id}/modules/active")
    public Object activeSchoolModules(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "tenant-school:read");
        Long resolvedId = TenantScope.resolveSchoolId(id);
        return modules.list(resolvedId, true);
    }

    @PutMapping("/schools/{id}/modules/{moduleCode}")
    public Object upsertSchoolModule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @PathVariable String moduleCode,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        try {
            return modules.upsert(
                    id,
                    moduleCode,
                    body.containsKey("enabled") ? Boolean.TRUE.equals(body.get("enabled")) : true,
                    body.containsKey("plan") ? String.valueOf(body.get("plan")) : null,
                    parseDate(body.get("startDate"), "startDate"),
                    parseDate(body.get("endDate"), "endDate"),
                    body.containsKey("notes") ? String.valueOf(body.get("notes")) : null,
                    TenantContext.get().userId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/schools/{id}/modules/{moduleCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableSchoolModule(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @PathVariable String moduleCode) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        try {
            modules.disable(id, moduleCode);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/schools/{id}/sections")
    public Object schoolSections(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) Boolean active) {
        requireToken(token, "tenant-school:read");
        Long resolvedId = TenantScope.resolveSchoolId(id);
        moduleGuard.requireErpEnabled(resolvedId);
        return structure.sections(resolvedId, classId, active == null ? Boolean.TRUE : active);
    }

    @GetMapping("/schools/{id}/staff")
    public Object schoolStaff(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "tenant-school:read");
        Long resolvedId = TenantScope.resolveSchoolId(id);
        moduleGuard.requireErpEnabled(resolvedId);
        return structure.staff(resolvedId);
    }

    @PostMapping("/schools/{id}/staff")
    public Map<String, Object> addSchoolStaff(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        moduleGuard.requireErpEnabled(id);
        return runCommand(() -> structure.addStaff(id, body));
    }

    @GetMapping("/classes")
    public Object classes(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId) {
        // Scoped to the caller's school (first-N configured classes); superadmin with no
        // schoolId gets the full global list.
        requireToken(token, "tenant-school:read");
        Long scope = TenantScope.resolveSchoolId(schoolId);
        moduleGuard.requireErpEnabled(scope);
        return structure.classes(scope);
    }

    @GetMapping("/sections")
    public Object sections(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) String classId,
            @RequestParam(required = false) Boolean active) {
        requireToken(token, "tenant-school:read");
        schoolId = TenantScope.resolveSchoolId(schoolId);
        moduleGuard.requireErpEnabled(schoolId);
        return structure.sections(schoolId, classId, active == null ? Boolean.TRUE : active);
    }

    @GetMapping("/classes/{classId}/sections")
    public Object sectionsForClass(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable String classId,
            @RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) Boolean active) {
        requireToken(token, "tenant-school:read");
        schoolId = TenantScope.resolveSchoolId(schoolId);
        moduleGuard.requireErpEnabled(schoolId);
        return structure.sections(schoolId, classId, active == null ? Boolean.TRUE : active);
    }

    @GetMapping("/academic-years")
    public Object academicYears(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestParam(required = false) Boolean active) {
        // Global academic year definitions accessible to all authenticated users.
        requireToken(token, "tenant-school:read");
        return structure.academicYears(active);
    }

    @GetMapping("/superadmin/schools/stats")
    public Object superadminSchoolStats(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return structure.schoolStats();
    }

    @GetMapping("/sa/schools")
    public Object superadminSchools(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return structure.schoolStats();
    }

    @GetMapping("/zones")
    public List<ZoneResponse> zones(@RequestHeader(value = "X-Tenant-School-Token", required = false) String token) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return zones.findAllByOrderByNameAsc().stream().map(ZoneResponse::from).toList();
    }

    @PostMapping("/zones")
    public Map<String, Object> createZone(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        return runCommand(() -> zoneCommands.createZone(body));
    }

    @GetMapping("/zones/{id}")
    public ZoneResponse zone(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return zones.findById(id).map(ZoneResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "zone not found"));
    }

    @PatchMapping("/zones/{id}")
    public Map<String, Object> updateZone(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        return runCommand(() -> zoneCommands.updateZone(id, body));
    }

    @PostMapping("/zones/{id}/schools/{schoolId}")
    public void assignZoneSchool(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @PathVariable Long schoolId,
            @RequestBody(required = false) Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        runCommand(() -> {
            zoneCommands.assignSchool(id, schoolId, TenantContext.get().userId());
            return Map.of("ok", true);
        });
    }

    @DeleteMapping("/zones/{id}/schools/{schoolId}")
    public void removeZoneSchool(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @PathVariable Long schoolId) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        runCommand(() -> {
            zoneCommands.removeSchool(id, schoolId);
            return Map.of("ok", true);
        });
    }

    @GetMapping("/zones/{id}/schools")
    public Object zoneSchools(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestParam(required = false) Boolean active) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return structure.zoneSchools(id, active);
    }

    @GetMapping("/zones/{id}/admins")
    public Object zoneAdmins(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestParam(required = false) Boolean active) {
        requireToken(token, "tenant-school:read");
        TenantScope.requireSuperAdmin();
        return structure.zoneAdmins(id, active);
    }

    @PostMapping("/zones/{id}/admins")
    public void assignZoneAdmin(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        runCommand(() -> {
            zoneCommands.assignZoneAdmin(id, parseLong(body.get("userId")), TenantContext.get().userId());
            return Map.of("ok", true);
        });
    }

    @PostMapping("/zones/{id}/admins/retire")
    public void retireZoneAdmins(
            @RequestHeader(value = "X-Tenant-School-Token", required = false) String token,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireToken(token, "tenant-school:write");
        TenantScope.requireSuperAdmin();
        runCommand(() -> {
            zoneCommands.retireZoneAdmins(id, parseLongList(body.get("userIds")));
            return Map.of("ok", true);
        });
    }

    private void requireToken(String token, String requiredScope) {
        if (!StringUtils.hasText(requiredScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing internal route scope" );
        }
        if (!StringUtils.hasText(readToken) || !readToken.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid tenant-school token");
        }
    }

    private LocalDate parseDate(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date for field " + field + ": " + value);
        }
    }

    private Long parseLong(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid actorId: " + value);
        }
    }

    private int intInRange(Object value, int min, int max, String field) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        int parsed;
        try {
            parsed = (value instanceof Number number) ? number.intValue()
                    : Integer.parseInt(String.valueOf(value).trim());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field + ": " + value);
        }
        if (parsed < min || parsed > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be between " + min + " and " + max);
        }
        return parsed;
    }

    private List<Long> parseLongList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid userIds: " + value);
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : iterable) {
            ids.add(parseLong(item));
        }
        return ids;
    }

    private <T> T runCommand(Command<T> command) {
        try {
            return command.run();
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
            HttpStatus status = message.toLowerCase().contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, message, ex);
        }
    }

    private interface Command<T> {
        T run();
    }

    public record SchoolResponse(
            Long id,
            String name,
            String shortCode,
            String city,
            String state,
            String contactEmail,
            String contactPhone,
            boolean active,
            Integer configuredClassCount,
            Integer configuredSectionCount,
            OffsetDateTime createdAt) {
        static SchoolResponse from(SchoolEntity school) {
            return new SchoolResponse(
                    school.getId(),
                    school.getName(),
                    school.getShortCode(),
                    school.getCity(),
                    school.getState(),
                    school.getContactEmail(),
                    school.getContactPhone(),
                    school.isActive(),
                    school.getConfiguredClassCount(),
                    school.getConfiguredSectionCount(),
                    school.getCreatedAt());
        }
    }

    public record ZoneResponse(
            Long id,
            String name,
            String code,
            String city,
            String state,
            String description,
            boolean active,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Long createdBy) {
        static ZoneResponse from(ZoneEntity zone) {
            return new ZoneResponse(
                    zone.getId(),
                    zone.getName(),
                    zone.getCode(),
                    zone.getCity(),
                    zone.getState(),
                    zone.getDescription(),
                    zone.isActive(),
                    zone.getCreatedAt(),
                    zone.getUpdatedAt(),
                    zone.getCreatedBy());
        }
    }
}

