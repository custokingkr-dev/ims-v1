package com.custoking.ims.schools.domain;

import com.custoking.ims.entity.AppUserEntity;
import com.custoking.ims.entity.SchoolEntity;
import com.custoking.ims.repo.AppUserRepository;
import com.custoking.ims.repo.SchoolRepository;
import com.custoking.ims.repo.UserRoleAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class SchoolDomainService {

    private final SchoolRepository schoolRepository;
    private final AppUserRepository userRepository;
    private final UserRoleAssignmentRepository uraRepo;

    public SchoolDomainService(SchoolRepository schoolRepository, AppUserRepository userRepository,
                               UserRoleAssignmentRepository uraRepo) {
        this.schoolRepository = schoolRepository;
        this.userRepository = userRepository;
        this.uraRepo = uraRepo;
    }

    public boolean isSchoolNameUnique(String name) {
        return schoolRepository.findByNameIgnoreCase(name).isEmpty();
    }

    public boolean isSchoolNameUniqueExcludingSchool(String name, Long excludeSchoolId) {
        Optional<SchoolEntity> existing = schoolRepository.findByNameIgnoreCase(name);
        return existing.isEmpty() || existing.get().getId().equals(excludeSchoolId);
    }

    public boolean isShortCodeUnique(String shortCode) {
        return schoolRepository.findByShortCodeIgnoreCase(shortCode).isEmpty();
    }

    public boolean isShortCodeUniqueExcludingSchool(String shortCode, Long excludeSchoolId) {
        Optional<SchoolEntity> existing = schoolRepository.findByShortCodeIgnoreCase(shortCode);
        return existing.isEmpty() || existing.get().getId().equals(excludeSchoolId);
    }

    public boolean hasActiveAdmin(Long schoolId) {
        return uraRepo.existsEffectiveRoleForSchool("ADMIN", schoolId);
    }

    public Optional<AppUserEntity> getSchoolAdmin(Long schoolId) {
        return uraRepo.findEffectiveByRoleAndSchool("ADMIN", schoolId).stream()
                .findFirst()
                .map(ura -> ura.getUser());
    }

    public List<SchoolEntity> findActiveSchools() {
        return schoolRepository.findByActiveTrueOrderByNameAsc();
    }

    public void deactivateSchool(SchoolEntity school) {
        school.setActive(false);
        schoolRepository.save(school);
    }

    public void activateSchool(SchoolEntity school) {
        school.setActive(true);
        schoolRepository.save(school);
    }

    public void validateSchoolAdminCreation(Long schoolId) {
        if (hasActiveAdmin(schoolId)) {
            throw new IllegalStateException("School already has an active admin");
        }
    }

    public void validateSchoolAdminReset(Long schoolId) {
        if (!hasActiveAdmin(schoolId)) {
            throw new IllegalStateException("School does not have an active admin to reset");
        }
    }
}