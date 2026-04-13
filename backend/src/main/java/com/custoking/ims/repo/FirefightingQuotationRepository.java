package com.custoking.ims.repo;

import com.custoking.ims.entity.FirefightingQuotationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FirefightingQuotationRepository extends JpaRepository<FirefightingQuotationEntity, String> {
    List<FirefightingQuotationEntity> findByRequest_Code(String requestId);
}
