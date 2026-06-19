package com.custoking.ims.repo;

import com.custoking.ims.entity.RbacAuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RbacAuditLogRepository extends JpaRepository<RbacAuditLogEntity, Long> {

    Page<RbacAuditLogEntity> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId, Pageable pageable);

    Page<RbacAuditLogEntity> findByTargetUserIdOrderByCreatedAtDesc(Long targetUserId, Pageable pageable);

    Page<RbacAuditLogEntity> findBySchoolIdOrderByCreatedAtDesc(Long schoolId, Pageable pageable);

    Page<RbacAuditLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
