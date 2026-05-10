package com.custoking.ims.repo;

import com.custoking.ims.entity.ZoneAdminAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ZoneAdminAssignmentRepository extends JpaRepository<ZoneAdminAssignmentEntity, Long> {
    List<ZoneAdminAssignmentEntity> findByUser_Id(Long userId);
    List<ZoneAdminAssignmentEntity> findByZone_Id(Long zoneId);
    Optional<ZoneAdminAssignmentEntity> findByZone_IdAndUser_Id(Long zoneId, Long userId);
    boolean existsByZone_IdAndUser_Id(Long zoneId, Long userId);
}
