package com.custoking.ims.service;

import com.custoking.ims.context.TenantContext;
import com.custoking.ims.context.TenantScope;
import com.custoking.ims.dto.school.SchoolAdminRequest;
import com.custoking.ims.dto.school.SchoolCreateRequest;
import com.custoking.ims.dto.school.SchoolOperationsUserRequest;
import com.custoking.ims.dto.school.SchoolUpdateRequest;
import com.custoking.ims.entity.*;
import com.custoking.ims.repo.*;
import com.custoking.ims.util.PasswordUtil;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SchoolService {

    private final SchoolRepository schoolRepository;
    private final AppUserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;
    private final SchoolClassRepository classRepository;
    private final SchoolSectionRepository sectionRepository;
    private final CatalogOrderRepository catalogOrderRepository;
    private final UserRoleAssignmentRepository uraRepo;
    private final RbacService rbacService;
    private final PasswordUtil passwordUtil;

    public SchoolService(SchoolRepository schoolRepository,
                         AppUserRepository userRepository,
                         AuthSessionRepository authSessionRepository,
                         SchoolClassRepository classRepository,
                         SchoolSectionRepository sectionRepository,
                         CatalogOrderRepository catalogOrderRepository,
                         UserRoleAssignmentRepository uraRepo,
                         @Lazy RbacService rbacService,
                         PasswordUtil passwordUtil) {
        this.schoolRepository = schoolRepository;
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
        this.classRepository = classRepository;
        this.sectionRepository = sectionRepository;
        this.catalogOrderRepository = catalogOrderRepository;
        this.uraRepo = uraRepo;
        this.rbacService = rbacService;
        this.passwordUtil = passwordUtil;
    }

    public List<Map<String, Object>> listSchools() {
        TenantScope scope = TenantContext.getScope();
        List<SchoolEntity> schools = (scope != null && !scope.isSuperadmin()
                && scope.accessibleSchoolIds() != null && !scope.accessibleSchoolIds().isEmpty())
                ? schoolRepository.findAllByIdInOrderByNameAsc(scope.accessibleSchoolIds())
                : schoolRepository.findAllByOrderByNameAsc();
        return schools.stream()
                .map(school -> {
                    AppUserEntity admin = findFirstSchoolUserByRole("ADMIN", school.getId()).orElse(null);
                    AppUserEntity ops = findFirstSchoolUserByRole("OPERATIONS", school.getId()).orElse(null);
                    return row(
                            "id", school.getId(),
                            "name", school.getName(),
                            "shortCode", school.getShortCode(),
                            "city", school.getCity() == null ? "" : school.getCity(),
                            "active", school.isActive(),
                            "adminEmail", admin == null ? "" : admin.getEmail(),
                            "operationsEmail", ops == null ? "" : ops.getEmail(),
                            "configuredClassCount", school.getConfiguredClassCount() == null ? 12 : school.getConfiguredClassCount(),
                            "configuredSectionCount", school.getConfiguredSectionCount() == null ? 2 : school.getConfiguredSectionCount()
                    );
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createSchool(SchoolCreateRequest request) {
        String shortCode = request.shortCode().trim().toUpperCase(Locale.ROOT);
        schoolRepository.findByShortCodeIgnoreCase(shortCode).ifPresent(existing -> {
            throw new IllegalArgumentException("School short code already exists");
        });
        SchoolEntity school = new SchoolEntity();
        school.setName(request.name().trim());
        school.setShortCode(shortCode);
        school.setCity(trimToNull(request.city()));
        school.setState(trimToNull(request.state()));
        school.setContactEmail(trimToNull(request.contactEmail()));
        school.setContactPhone(trimToNull(request.contactPhone()));
        school.setConfiguredClassCount(request.classCount() == null ? 12 : Math.max(1, Math.min(12, request.classCount())));
        school.setConfiguredSectionCount(request.sectionCount() == null ? 2 : Math.max(1, Math.min(26, request.sectionCount())));
        school.setActive(true);
        schoolRepository.save(school);
        ensureSchoolSections(school, school.getConfiguredClassCount(), school.getConfiguredSectionCount());
        return schoolDetails(school);
    }

    public Map<String, Object> updateSchool(Long schoolId, SchoolUpdateRequest request) {
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        if (request.name() != null && !request.name().isBlank()) school.setName(request.name().trim());
        if (request.city() != null) school.setCity(trimToNull(request.city()));
        if (request.active() != null) school.setActive(request.active());
        schoolRepository.save(school);
        return schoolDetails(school);
    }

    public Map<String, Object> createOrResetSchoolAdmin(Long schoolId, SchoolAdminRequest request) {
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        // Revoke existing RBAC assignments and remove old user account
        uraRepo.findEffectiveByRoleAndSchool("ADMIN", schoolId).forEach(ura -> {
            rbacService.revokeRoleAssignment(ura.getId(), null);
            authSessionRepository.deleteByUser_Id(ura.getUser().getId());
            userRepository.delete(ura.getUser());
        });
        AppUserEntity user = new AppUserEntity();
        user.setFullName(request.fullName().trim());
        user.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordUtil.hash(request.temporaryPassword()));
        user.setRole("ADMIN");           // display/legacy metadata
        user.setBranchId(school.getId()); // display/legacy metadata
        user.setBranchName(school.getName());
        userRepository.save(user);
        // Create RBAC school-scoped assignment
        rbacService.assignSchoolRole(user.getId(), "ADMIN", schoolId, null);
        return adminDetails(user);
    }

    public Map<String, Object> getSchoolAdmin(Long schoolId) {
        schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        AppUserEntity admin = findFirstSchoolUserByRole("ADMIN", schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ADMIN not found for school"));
        return adminDetails(admin);
    }

    public List<Map<String, Object>> listSchoolsWithStats() {
        List<CatalogOrderEntity> allOrders = catalogOrderRepository.findAll();
        return schoolRepository.findAllByOrderByNameAsc().stream()
                .map(school -> {
                    List<CatalogOrderEntity> schoolOrders = allOrders.stream()
                            .filter(o -> o.getSchool() != null && school.getId().equals(o.getSchool().getId()))
                            .toList();
                    long gmv = schoolOrders.stream().mapToLong(CatalogOrderEntity::getTotalAmount).sum();
                    AppUserEntity admin = findFirstSchoolUserByRole("ADMIN", school.getId()).orElse(null);
                    return row(
                            "id", school.getId(),
                            "name", school.getName(),
                            "shortCode", school.getShortCode(),
                            "city", school.getCity() == null ? "" : school.getCity(),
                            "active", school.isActive(),
                            "adminEmail", admin == null ? "" : admin.getEmail(),
                            "ordersYTD", (long) schoolOrders.size(),
                            "gmvYTD", gmv,
                            "erpSince", school.getCreatedAt() == null ? "" :
                                    school.getCreatedAt().getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " " + school.getCreatedAt().getYear()
                    );
                })
                .collect(Collectors.toList());
    }

    public Map<String, Object> createOrResetSchoolOperationsUser(Long schoolId, SchoolOperationsUserRequest request) {
        SchoolEntity school = schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        // Revoke existing RBAC assignments and remove old user account
        uraRepo.findEffectiveByRoleAndSchool("OPERATIONS", schoolId).forEach(ura -> {
            rbacService.revokeRoleAssignment(ura.getId(), null);
            authSessionRepository.deleteByUser_Id(ura.getUser().getId());
            userRepository.delete(ura.getUser());
        });
        AppUserEntity user = new AppUserEntity();
        user.setFullName(request.fullName().trim());
        user.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordUtil.hash(request.temporaryPassword()));
        user.setRole("OPERATIONS");       // display/legacy metadata
        user.setBranchId(school.getId());  // display/legacy metadata
        user.setBranchName(school.getName());
        userRepository.save(user);
        // Create RBAC school-scoped assignment
        rbacService.assignSchoolRole(user.getId(), "OPERATIONS", schoolId, null);
        return operationsUserDetails(user);
    }

    public Map<String, Object> getSchoolOperationsUser(Long schoolId) {
        schoolRepository.findById(schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "School not found"));
        AppUserEntity ops = findFirstSchoolUserByRole("OPERATIONS", schoolId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OPERATIONS user not found for school"));
        return operationsUserDetails(ops);
    }

    public void ensureSchoolSections(SchoolEntity school, Integer classCountInput, Integer sectionCountInput) {
        if (school == null) return;
        int classCount = Math.max(1, Math.min(12, classCountInput == null ? 12 : classCountInput));
        int sectionCount = Math.max(1, Math.min(26, sectionCountInput == null ? 2 : sectionCountInput));
        List<SchoolClassEntity> classes = classRepository.findAllByOrderBySortOrderAsc();
        if (classes.isEmpty()) {
            for (int i = 1; i <= 12; i++) {
                SchoolClassEntity schoolClass = new SchoolClassEntity();
                schoolClass.setId(String.valueOf(i));
                schoolClass.setName("Class " + i);
                schoolClass.setSortOrder(i);
                classRepository.save(schoolClass);
            }
            classes = classRepository.findAllByOrderBySortOrderAsc();
        }
        List<SchoolClassEntity> scopedClasses = classes.stream()
                .sorted(Comparator.comparingInt(SchoolClassEntity::getSortOrder))
                .limit(classCount)
                .toList();
        for (SchoolClassEntity schoolClass : scopedClasses) {
            for (int idx = 0; idx < sectionCount; idx++) {
                char sectionLetter = (char) ('A' + idx);
                getOrCreateSectionForSchool(school, schoolClass, String.valueOf(sectionLetter));
            }
        }
    }

    public SchoolSectionEntity getOrCreateSectionForSchool(SchoolEntity school, SchoolClassEntity schoolClass, String requestedSectionName) {
        if (school == null) throw new IllegalArgumentException("School not found");
        if (schoolClass == null) throw new IllegalArgumentException("Class not found");
        String sectionName = str(requestedSectionName, "A").trim();
        if (sectionName.isBlank()) sectionName = "A";
        final String normalizedSectionName = sectionName.toUpperCase(Locale.ROOT);
        return sectionRepository.findBySchool_IdAndSchoolClass_IdAndNameIgnoreCase(school.getId(), schoolClass.getId(), normalizedSectionName)
                .orElseGet(() -> {
                    SchoolSectionEntity section = new SchoolSectionEntity();
                    section.setId(school.getId() + "-" + schoolClass.getId() + "-" + normalizedSectionName);
                    section.setSchool(school);
                    section.setSchoolClass(schoolClass);
                    section.setName(normalizedSectionName);
                    section.setTeacherName("");
                    section.setActive(true);
                    return sectionRepository.save(section);
                });
    }

    /**
     * Finds the first user with an effective school-scoped RBAC assignment for the given role.
     * Replaces the legacy app_users.role + branchId lookup.
     */
    private Optional<AppUserEntity> findFirstSchoolUserByRole(String roleName, Long schoolId) {
        return uraRepo.findEffectiveByRoleAndSchool(roleName.toUpperCase(Locale.ROOT), schoolId)
                .stream()
                .findFirst()
                .map(UserRoleAssignmentEntity::getUser);
    }

    private Map<String, Object> schoolDetails(SchoolEntity school) {
        return Map.of(
                "id", school.getId(),
                "name", school.getName(),
                "shortCode", school.getShortCode(),
                "city", school.getCity() == null ? "" : school.getCity(),
                "state", school.getState() == null ? "" : school.getState(),
                "active", school.isActive(),
                "configuredClassCount", school.getConfiguredClassCount() == null ? 12 : school.getConfiguredClassCount(),
                "configuredSectionCount", school.getConfiguredSectionCount() == null ? 2 : school.getConfiguredSectionCount()
        );
    }

    private Map<String, Object> adminDetails(AppUserEntity user) {
        return Map.of(
                "userId", user.getId(),
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "branchId", user.getBranchId(),
                "branchName", user.getBranchName()
        );
    }

    private Map<String, Object> operationsUserDetails(AppUserEntity user) {
        return Map.of(
                "userId", user.getId(),
                "fullName", user.getFullName(),
                "email", user.getEmail(),
                "branchId", user.getBranchId(),
                "branchName", user.getBranchName()
        );
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private Map<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put(String.valueOf(kv[i]), kv[i + 1]);
        return map;
    }
}
