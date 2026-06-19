package com.custoking.ims.repo;

import com.custoking.ims.entity.StudentReviewCampaignEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StudentReviewCampaignRepository extends JpaRepository<StudentReviewCampaignEntity, String> {

    Optional<StudentReviewCampaignEntity> findFirstBySchoolIdAndReviewTypeAndStatus(
            Long schoolId, String reviewType, String status);

    boolean existsBySchoolIdAndReviewTypeAndStatus(Long schoolId, String reviewType, String status);
}
