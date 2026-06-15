package com.custoking.ims.commandcenter;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface CommandCenterFeedRepository extends JpaRepository<CommandCenterFeedEntity, UUID> {

    List<CommandCenterFeedEntity> findBySchoolIdOrderByCreatedAtDesc(Long schoolId, Pageable pageable);

    @Query("SELECT f FROM CommandCenterFeedEntity f ORDER BY f.createdAt DESC")
    List<CommandCenterFeedEntity> findAllOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT f FROM CommandCenterFeedEntity f WHERE f.schoolId = :schoolId OR f.schoolId IS NULL ORDER BY f.createdAt DESC")
    List<CommandCenterFeedEntity> findBySchoolIdOrGlobalOrderByCreatedAtDesc(@Param("schoolId") Long schoolId, Pageable pageable);

    long countBySchoolId(Long schoolId);
}
