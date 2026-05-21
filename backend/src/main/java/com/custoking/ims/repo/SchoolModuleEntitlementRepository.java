package com.custoking.ims.repo;

import com.custoking.ims.entity.SchoolModuleEntitlementEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SchoolModuleEntitlementRepository extends JpaRepository<SchoolModuleEntitlementEntity, Long> {

    List<SchoolModuleEntitlementEntity> findBySchool_Id(Long schoolId);

    Optional<SchoolModuleEntitlementEntity> findBySchool_IdAndModuleCode(Long schoolId, String moduleCode);

    @Query("""
            SELECT CASE WHEN COUNT(e) > 0 THEN true ELSE false END
            FROM SchoolModuleEntitlementEntity e
            WHERE e.school.id = :schoolId
              AND e.moduleCode = :moduleCode
              AND e.enabled = true
              AND (:today BETWEEN COALESCE(e.startDate, :today) AND COALESCE(e.endDate, :today))
            """)
    boolean isModuleEnabled(@Param("schoolId") Long schoolId,
                            @Param("moduleCode") String moduleCode,
                            @Param("today") LocalDate today);

    List<SchoolModuleEntitlementEntity> findBySchool_IdAndEnabledTrue(Long schoolId);
}
