package com.custoking.ims.repo;

import com.custoking.ims.entity.StudentReviewItemEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StudentReviewItemRepository extends JpaRepository<StudentReviewItemEntity, String> {

    @Query("SELECT i FROM StudentReviewItemEntity i " +
           "JOIN FETCH i.student s " +
           "JOIN FETCH s.schoolClass " +
           "JOIN FETCH s.section " +
           "WHERE i.campaign.id = :campaignId AND i.schoolId = :schoolId " +
           "AND (:status IS NULL OR i.status = :status) " +
           "AND (:classId IS NULL OR s.schoolClass.id = :classId) " +
           "AND (:sectionId IS NULL OR s.section.id = :sectionId) " +
           "ORDER BY s.fullName ASC")
    Page<StudentReviewItemEntity> findByCampaignAndSchool(
            @Param("campaignId") String campaignId,
            @Param("schoolId") Long schoolId,
            @Param("classId") String classId,
            @Param("sectionId") String sectionId,
            @Param("status") String status,
            Pageable pageable);

    long countByCampaign_IdAndStatus(String campaignId, String status);

    long countByCampaign_Id(String campaignId);

    @Query("SELECT COUNT(i) FROM StudentReviewItemEntity i " +
           "JOIN i.campaign c " +
           "WHERE c.schoolId = :schoolId AND c.status = 'ACTIVE' AND i.status = 'PENDING'")
    long countPendingItemsForActiveSchoolCampaigns(@Param("schoolId") Long schoolId);

    Optional<StudentReviewItemEntity> findByIdAndSchoolId(String id, Long schoolId);
}
