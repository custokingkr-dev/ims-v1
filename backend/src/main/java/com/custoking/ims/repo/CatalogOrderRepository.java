package com.custoking.ims.repo;

import com.custoking.ims.entity.CatalogOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CatalogOrderRepository extends JpaRepository<CatalogOrderEntity, String> {
    List<CatalogOrderEntity> findBySchool_Id(Long schoolId);
    List<CatalogOrderEntity> findBySchool_IdAndStatus(Long schoolId, String status);
    List<CatalogOrderEntity> findBySchool_IdAndStatusIn(Long schoolId, List<String> statuses);
    List<CatalogOrderEntity> findByStatusOrderByCreatedAtDesc(String status);
    List<CatalogOrderEntity> findAllByOrderByCreatedAtDesc();
    List<CatalogOrderEntity> findBySchool_IdOrderByCreatedAtDesc(Long schoolId);
}
