package com.custoking.ims.repo;

import com.custoking.ims.entity.SuperadminInvoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SuperadminInvoiceRepository extends JpaRepository<SuperadminInvoiceEntity, String> {
    List<SuperadminInvoiceEntity> findByOrderRefOrderByCreatedAtDesc(String orderRef);
    List<SuperadminInvoiceEntity> findAllByOrderByCreatedAtDesc();
}
