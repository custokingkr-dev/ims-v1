package com.custoking.ims.repo;
import com.custoking.ims.entity.PaymentRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PaymentRecordRepository extends JpaRepository<PaymentRecordEntity, String> { List<PaymentRecordEntity> findByStudent_IdOrderByPaidAtDesc(Long studentId); List<PaymentRecordEntity> findByStudent_SchoolClass_IdAndStudent_Section_IdOrderByPaidAtDesc(String classId, String sectionId); Optional<PaymentRecordEntity> findByReceiptNumber(String receiptNumber); }
