package com.custoking.ims.repo;

import com.custoking.ims.entity.CatalogOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CatalogOrderRepository extends JpaRepository<CatalogOrderEntity, String> {
    List<CatalogOrderEntity> findBySchool_Id(Long schoolId);
    List<CatalogOrderEntity> findBySchool_IdAndStatus(Long schoolId, String status);
    List<CatalogOrderEntity> findByStatusOrderByCreatedAtDesc(String status);
}
