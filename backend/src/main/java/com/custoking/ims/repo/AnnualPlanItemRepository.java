package com.custoking.ims.repo;

import com.custoking.ims.entity.AnnualPlanItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnnualPlanItemRepository extends JpaRepository<AnnualPlanItemEntity, String> {
    List<AnnualPlanItemEntity> findBySchool_Id(Long schoolId);
    List<AnnualPlanItemEntity> findBySchool_IdAndStatus(Long schoolId, String status);
}
