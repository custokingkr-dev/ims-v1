package com.custoking.ims.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    Page<AuditLogEntity> findBySchoolIdOrderByTimestampDesc(Long schoolId, Pageable pageable);

    Page<AuditLogEntity> findByUserIdOrderByTimestampDesc(Long userId, Pageable pageable);

    @Query("""
            SELECT a FROM AuditLogEntity a
            WHERE (:schoolId IS NULL OR a.schoolId = :schoolId)
              AND (:userId   IS NULL OR a.userId   = :userId)
              AND (:action   IS NULL OR a.action   = :action)
              AND (:from     IS NULL OR a.timestamp >= :from)
              AND (:to       IS NULL OR a.timestamp <= :to)
            ORDER BY a.timestamp DESC
            """)
    Page<AuditLogEntity> search(
            @Param("schoolId") Long schoolId,
            @Param("userId")   Long userId,
            @Param("action")   String action,
            @Param("from")     OffsetDateTime from,
            @Param("to")       OffsetDateTime to,
            Pageable pageable);
}
