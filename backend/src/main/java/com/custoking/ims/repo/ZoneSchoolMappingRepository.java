package com.custoking.ims.repo;

import com.custoking.ims.entity.ZoneSchoolMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZoneSchoolMappingRepository extends JpaRepository<ZoneSchoolMappingEntity, Long> {
    List<ZoneSchoolMappingEntity> findByZone_Id(Long zoneId);
    List<ZoneSchoolMappingEntity> findBySchool_Id(Long schoolId);
    Optional<ZoneSchoolMappingEntity> findByZone_IdAndSchool_Id(Long zoneId, Long schoolId);
    long countByZone_Id(Long zoneId);
}
