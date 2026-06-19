package com.custoking.ims.service;

import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.entity.SchoolModuleEntitlementEntity;
import com.custoking.ims.repo.SchoolModuleEntitlementRepository;
import com.custoking.ims.repo.SchoolRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages school-level module entitlements.
 *
 * A user must hold both the RBAC permission (e.g. fee:collect) AND the school
 * must have the relevant module enabled (e.g. FEES) to access a feature.
 * This service answers "is module X enabled for school Y right now?"
 */
@Service
@Transactional(readOnly = true)
public class ModuleEntitlementService {

    /** All valid module codes. */
    public enum Module {
        STUDENTS, ATTENDANCE, FEES, INVOICES, PAYMENTS, ORDERS, FIREFIGHTING, REPORTS
    }

    private final SchoolModuleEntitlementRepository entitlementRepo;
    private final SchoolRepository schoolRepo;

    public ModuleEntitlementService(SchoolModuleEntitlementRepository entitlementRepo,
                                    SchoolRepository schoolRepo) {
        this.entitlementRepo = entitlementRepo;
        this.schoolRepo = schoolRepo;
    }

    /** True if the module is enabled for the school today. */
    public boolean isEnabled(Long schoolId, String moduleCode) {
        return entitlementRepo.isModuleEnabled(schoolId, moduleCode.toUpperCase(), LocalDate.now());
    }

    /** True if the module is enabled for the school today. */
    public boolean isEnabled(Long schoolId, Module module) {
        return isEnabled(schoolId, module.name());
    }

    /**
     * Enforces that the module is enabled for the school.
     * Null schoolId (platform admin context) bypasses the check.
     * Throws 403 if the school has the module disabled.
     */
    public void requireModule(Long schoolId, Module module) {
        if (schoolId == null) return;
        if (!isEnabled(schoolId, module)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    module.name() + " module is not enabled for this school");
        }
    }

    /** All entitlements (active and inactive) for a school. */
    public List<Map<String, Object>> listEntitlements(Long schoolId) {
        return entitlementRepo.findBySchool_Id(schoolId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    /** Set of module codes that are currently active for a school. */
    public List<String> activeModules(Long schoolId) {
        return entitlementRepo.findBySchool_IdAndEnabledTrue(schoolId).stream()
                .filter(e -> {
                    LocalDate today = LocalDate.now();
                    boolean afterStart = e.getStartDate() == null || !today.isBefore(e.getStartDate());
                    boolean beforeEnd  = e.getEndDate()   == null || !today.isAfter(e.getEndDate());
                    return afterStart && beforeEnd;
                })
                .map(SchoolModuleEntitlementEntity::getModuleCode)
                .collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> upsertEntitlement(Long schoolId, String moduleCode,
                                                  boolean enabled, String plan,
                                                  LocalDate startDate, LocalDate endDate,
                                                  String notes, Long actorId) {
        String code = moduleCode.toUpperCase();
        validateModuleCode(code);

        SchoolEntity school = schoolRepo.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found: " + schoolId));

        SchoolModuleEntitlementEntity e = entitlementRepo
                .findBySchool_IdAndModuleCode(schoolId, code)
                .orElseGet(() -> {
                    SchoolModuleEntitlementEntity n = new SchoolModuleEntitlementEntity();
                    n.setSchool(school);
                    n.setModuleCode(code);
                    n.setCreatedBy(actorId);
                    return n;
                });

        e.setEnabled(enabled);
        e.setPlan(plan);
        e.setStartDate(startDate);
        e.setEndDate(endDate);
        e.setNotes(notes);
        e.setUpdatedAt(OffsetDateTime.now());

        return toView(entitlementRepo.save(e));
    }

    @Transactional
    public void disableModule(Long schoolId, String moduleCode) {
        entitlementRepo.findBySchool_IdAndModuleCode(schoolId, moduleCode.toUpperCase())
                .ifPresent(e -> {
                    e.setEnabled(false);
                    e.setUpdatedAt(OffsetDateTime.now());
                    entitlementRepo.save(e);
                });
    }

    private static void validateModuleCode(String code) {
        try {
            Module.valueOf(code);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown module code: " + code +
                    ". Valid codes: STUDENTS, ATTENDANCE, FEES, INVOICES, PAYMENTS, ORDERS, FIREFIGHTING, REPORTS");
        }
    }

    private Map<String, Object> toView(SchoolModuleEntitlementEntity e) {
        var view = new java.util.LinkedHashMap<String, Object>();
        view.put("id", e.getId());
        view.put("schoolId", e.getSchool().getId());
        view.put("moduleCode", e.getModuleCode());
        view.put("enabled", e.isEnabled());
        view.put("plan", e.getPlan());
        view.put("startDate", e.getStartDate());
        view.put("endDate", e.getEndDate());
        view.put("notes", e.getNotes());
        view.put("updatedAt", e.getUpdatedAt());
        return view;
    }
}
