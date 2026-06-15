package com.custoking.ims.commandcenter;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface CommandCenterActionRepository extends JpaRepository<CommandCenterActionEntity, UUID> {

    List<CommandCenterActionEntity> findBySchoolIdAndStatusOrderByCreatedAtDesc(Long schoolId, String status);

    List<CommandCenterActionEntity> findBySchoolIdIsNullAndStatusOrderByCreatedAtDesc(String status);

    List<CommandCenterActionEntity> findByStatusOrderByCreatedAtDesc(String status);

    boolean existsBySourceTypeAndSourceIdAndStatus(String sourceType, String sourceId, String status);
}
