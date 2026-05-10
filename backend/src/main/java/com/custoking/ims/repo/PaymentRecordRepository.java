package com.custoking.ims.repo;
import com.custoking.ims.entity.PaymentRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface PaymentRecordRepository extends JpaRepository<PaymentRecordEntity, String> {
    List<PaymentRecordEntity> findByStudent_IdOrderByPaidAtDesc(Long studentId);
    List<PaymentRecordEntity> findByStudent_SchoolClass_IdAndStudent_Section_IdOrderByPaidAtDesc(String classId, String sectionId);
    List<PaymentRecordEntity> findAllByOrderByPaidAtDesc();
    Optional<PaymentRecordEntity> findByReceiptNumber(String receiptNumber);
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecordEntity p WHERE p.student.school.id = :schoolId")
    long sumAmountBySchoolId(@Param("schoolId") Long schoolId);
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecordEntity p")
    long sumAmount();
}
