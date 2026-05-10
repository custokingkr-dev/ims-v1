package com.custoking.ims.service;

import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import com.custoking.ims.util.PasswordUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ZoneService {

    private final ZoneRepository zoneRepo;
    private final ZoneSchoolMappingRepository zoneMappingRepo;
    private final ZoneAdminAssignmentRepository zoneAdminRepo;
    private final SchoolRepository schoolRepo;
    private final AppUserRepository userRepo;
    private final AuthSessionRepository authSessionRepo;
    private final PasswordUtil passwordUtil;
    private final RbacService rbacService;

    public ZoneService(ZoneRepository zoneRepo,
                       ZoneSchoolMappingRepository zoneMappingRepo,
                       ZoneAdminAssignmentRepository zoneAdminRepo,
                       SchoolRepository schoolRepo,
                       AppUserRepository userRepo,
                       AuthSessionRepository authSessionRepo,
                       PasswordUtil passwordUtil,
                       RbacService rbacService) {
        this.zoneRepo = zoneRepo;
        this.zoneMappingRepo = zoneMappingRepo;
        this.zoneAdminRepo = zoneAdminRepo;
        this.schoolRepo = schoolRepo;
        this.userRepo = userRepo;
        this.authSessionRepo = authSessionRepo;
        this.passwordUtil = passwordUtil;
        this.rbacService = rbacService;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listZones() {
        return zoneRepo.findAllByActiveTrueOrderByNameAsc().stream()
                .map(zone -> {
                    long schoolCount = zoneMappingRepo.countByZone_Id(zone.getId());
                    AppUserEntity admin = zoneAdminRepo.findByZone_Id(zone.getId()).stream()
                            .findFirst()
                            .map(zaa -> zaa.getUser())
                            .orElse(null);
                    return buildZoneRow(zone, schoolCount, admin);
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createZone(String name, String code, String city, String state, String description, Long createdBy) {
        String trimmedCode = code.trim().toUpperCase(Locale.ROOT);
        zoneRepo.findByCode(trimmedCode).ifPresent(z -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zone code already exists");
        });
        zoneRepo.findByName(name.trim()).ifPresent(z -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zone name already exists");
        });
        ZoneEntity zone = new ZoneEntity();
        zone.setName(name.trim());
        zone.setCode(trimmedCode);
        zone.setCity(trimToNull(city));
        zone.setState(trimToNull(state));
        zone.setDescription(trimToNull(description));
        zone.setCreatedBy(createdBy);
        zoneRepo.save(zone);
        return buildZoneRow(zone, 0, null);
    }

    public Map<String, Object> updateZone(Long zoneId, String name, String city, String state, String description, Boolean active) {
        ZoneEntity zone = findZone(zoneId);
        if (name != null && !name.isBlank()) zone.setName(name.trim());
        if (city != null) zone.setCity(trimToNull(city));
        if (state != null) zone.setState(trimToNull(state));
        if (description != null) zone.setDescription(trimToNull(description));
        if (active != null) zone.setActive(active);
        zone.setUpdatedAt(OffsetDateTime.now());
        zoneRepo.save(zone);
        long schoolCount = zoneMappingRepo.countByZone_Id(zoneId);
        return buildZoneRow(zone, schoolCount, null);
    }

    public void assignSchoolToZone(Long zoneId, Long schoolId, Long assignedBy) {
        ZoneEntity zone = findZone(zoneId);
        SchoolEntity school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        if (zoneMappingRepo.findByZone_IdAndSchool_Id(zoneId, schoolId).isPresent()) return;
        ZoneSchoolMappingEntity mapping = new ZoneSchoolMappingEntity();
        mapping.setZone(zone);
        mapping.setSchool(school);
        mapping.setAddedBy(assignedBy);
        zoneMappingRepo.save(mapping);
    }

    public void removeSchoolFromZone(Long zoneId, Long schoolId) {
        ZoneSchoolMappingEntity mapping = zoneMappingRepo.findByZone_IdAndSchool_Id(zoneId, schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not assigned to this zone"));
        zoneMappingRepo.delete(mapping);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSchoolsInZone(Long zoneId) {
        findZone(zoneId);
        return zoneMappingRepo.findByZone_Id(zoneId).stream()
                .map(m -> {
                    SchoolEntity s = m.getSchool();
                    return Map.<String, Object>of(
                            "id", s.getId(),
                            "name", s.getName(),
                            "shortCode", s.getShortCode(),
                            "city", s.getCity() == null ? "" : s.getCity(),
                            "active", s.isActive()
                    );
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMyZoneSchools(Long userId) {
        return zoneAdminRepo.findByUser_Id(userId).stream()
                .flatMap(zaa -> zoneMappingRepo.findByZone_Id(zaa.getZone().getId()).stream())
                .map(m -> {
                    SchoolEntity s = m.getSchool();
                    return Map.<String, Object>of(
                            "id", s.getId(),
                            "name", s.getName(),
                            "shortCode", s.getShortCode(),
                            "city", s.getCity() == null ? "" : s.getCity(),
                            "active", s.isActive()
                    );
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createOrResetZoneAdmin(Long zoneId, String fullName, String email, String temporaryPassword, Long assignedBy) {
        ZoneEntity zone = findZone(zoneId);

        // Delete any existing zone admin for this zone
        zoneAdminRepo.findByZone_Id(zoneId).forEach(existing -> {
            AppUserEntity existingUser = existing.getUser();
            authSessionRepo.deleteByUser_Id(existingUser.getId());
            zoneAdminRepo.delete(existing);
            userRepo.delete(existingUser);
        });

        AppUserEntity user = new AppUserEntity();
        user.setFullName(fullName.trim());
        user.setEmail(email.trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordUtil.hash(temporaryPassword));
        user.setRole("ZONE_ADMIN");
        user.setZoneId(zone.getId());
        user.setZoneName(zone.getName());
        userRepo.save(user);
        rbacService.assignRole(user.getId(), "ZONE_ADMIN", assignedBy);

        ZoneAdminAssignmentEntity assignment = new ZoneAdminAssignmentEntity();
        assignment.setZone(zone);
        assignment.setUser(user);
        assignment.setAssignedBy(assignedBy);
        zoneAdminRepo.save(assignment);

        return Map.of(
                "userId", user.getId(),
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "zoneId", zone.getId(),
                "zoneName", zone.getName()
        );
    }

    private ZoneEntity findZone(Long zoneId) {
        return zoneRepo.findById(zoneId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zone not found"));
    }

    private Map<String, Object> buildZoneRow(ZoneEntity zone, long schoolCount, AppUserEntity admin) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", zone.getId());
        row.put("name", zone.getName());
        row.put("code", zone.getCode());
        row.put("city", zone.getCity() == null ? "" : zone.getCity());
        row.put("state", zone.getState() == null ? "" : zone.getState());
        row.put("description", zone.getDescription() == null ? "" : zone.getDescription());
        row.put("active", zone.isActive());
        row.put("schoolCount", schoolCount);
        row.put("adminEmail", admin == null ? "" : admin.getEmail());
        return row;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
