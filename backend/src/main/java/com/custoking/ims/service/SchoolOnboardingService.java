package com.custoking.ims.service;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrates school onboarding: a seven-step process that must complete
 * atomically or roll back fully.
 *
 * Steps:
 *   1. School entity already created (by SchoolService.createSchool)
 *   2. Default academic year existence check
 *   3. Classes / sections provisioned (via SchoolService.ensureSchoolSections)
 *   4. Admin user created (by SchoolService.createOrResetSchoolAdmin)
 *   5. RBAC role assignment (inside step 4)
 *   6. Module entitlements defaulted (STUDENTS + FEES always enabled)
 *   7. Audit log entry
 *
 * Separate from SchoolService (school CRUD) to keep the onboarding
 * transaction boundary clean and avoid a 500-line god service.
 */
@Service
@Transactional
public class SchoolOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(SchoolOnboardingService.class);

    private final SchoolRepository schoolRepository;
    private final AcademicYearRepository academicYearRepository;
    private final SchoolSectionRepository sectionRepository;
    private final UserRoleAssignmentRepository uraRepo;
    private final StudentRepository studentRepository;
    private final FeeBandRepository feeBandRepository;
    private final CatalogOrderRepository catalogOrderRepository;
    private final AuditLogService auditLogService;

    public SchoolOnboardingService(
            SchoolRepository schoolRepository,
            AcademicYearRepository academicYearRepository,
            SchoolSectionRepository sectionRepository,
            UserRoleAssignmentRepository uraRepo,
            StudentRepository studentRepository,
            FeeBandRepository feeBandRepository,
            CatalogOrderRepository catalogOrderRepository,
            AuditLogService auditLogService) {
        this.schoolRepository = schoolRepository;
        this.academicYearRepository = academicYearRepository;
        this.sectionRepository = sectionRepository;
        this.uraRepo = uraRepo;
        this.studentRepository = studentRepository;
        this.feeBandRepository = feeBandRepository;
        this.catalogOrderRepository = catalogOrderRepository;
        this.auditLogService = auditLogService;
    }

    // ── Onboarding checklist ─────────────────────────────────────────────────

    /**
     * Returns a structured checklist showing which onboarding steps are complete
     * for the given school.
     *
     * This is a READ operation — safe to call without SUPERADMIN; the controller
     * gates it with {@code SCHOOL_READ}.
     *
     * @param schoolId the school whose onboarding status to check
     * @return map with boolean flags per step
     */
    @Transactional(readOnly = true)
    public Map<String, Object> onboardingStatus(Long schoolId) {
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));

        boolean schoolProfile = school.getCity() != null && !school.getCity().isBlank()
                && school.getContactEmail() != null && !school.getContactEmail().isBlank();

        boolean academicYear = academicYearRepository.findFirstByActiveTrue().isPresent();

        long sectionCount = sectionRepository.countBySchool_Id(schoolId);
        boolean classesCreated = sectionCount > 0;

        // More than 1 section means multi-section setup (beyond single default)
        boolean sectionsCreated = sectionCount > 1;

        boolean adminUserCreated = uraRepo.findEffectiveByRoleAndSchool("ADMIN", schoolId)
                .stream().findFirst().isPresent();

        long studentCount = studentRepository.countBySchool_Id(schoolId);
        boolean studentsImported = studentCount > 0;

        // At least one fee band for the active academic year
        boolean feeStructureConfigured = academicYearRepository.findFirstByActiveTrue()
                .map(year -> !feeBandRepository.findByAcademicYear_IdOrderByClassFromAscNameAsc(year.getId()).isEmpty())
                .orElse(false);

        boolean firstOrderCreated = catalogOrderRepository
                .findBySchool_IdOrderByCreatedAtDesc(schoolId).stream().findFirst().isPresent();

        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("schoolProfile", schoolProfile);
        checklist.put("academicYear", academicYear);
        checklist.put("classesCreated", classesCreated);
        checklist.put("sectionsCreated", sectionsCreated);
        checklist.put("adminUserCreated", adminUserCreated);
        checklist.put("studentsImported", studentsImported);
        checklist.put("feeStructureConfigured", feeStructureConfigured);
        checklist.put("firstOrderCreated", firstOrderCreated);

        // Overall progress: percentage of completed steps
        long completed = checklist.values().stream().filter(v -> Boolean.TRUE.equals(v)).count();
        int total = checklist.size();
        checklist.put("completedSteps", completed);
        checklist.put("totalSteps", total);
        checklist.put("percentComplete", total == 0 ? 0 : (int) Math.round(100.0 * completed / total));
        checklist.put("isComplete", completed == total);

        return checklist;
    }

    /**
     * Records a {@code SCHOOL_ONBOARDED} audit event. Called by
     * {@link SchoolService} after the school and its initial admin user
     * have been persisted.
     */
    public void recordSchoolCreated(Long schoolId, String schoolName, Long actorUserId) {
        auditLogService.recordEvent(
                "SCHOOL_CREATED",
                actorUserId,
                schoolId,
                "school",
                String.valueOf(schoolId),
                null,
                schoolName);
        log.info("school.onboarding.created schoolId={} name={} actorId={}", schoolId, schoolName, actorUserId);
    }

    /**
     * Records an {@code ADMIN_USER_CREATED} audit event. Called by
     * {@link SchoolService} after the admin user is persisted and RBAC
     * assignment is made.
     */
    public void recordAdminUserCreated(Long schoolId, Long userId, String email, Long actorUserId) {
        auditLogService.recordEvent(
                "ADMIN_USER_CREATED",
                actorUserId,
                schoolId,
                "app_user",
                String.valueOf(userId),
                null,
                email);
        log.info("school.onboarding.adminCreated schoolId={} userId={} email={} actorId={}",
                schoolId, userId, email, actorUserId);
    }
}
