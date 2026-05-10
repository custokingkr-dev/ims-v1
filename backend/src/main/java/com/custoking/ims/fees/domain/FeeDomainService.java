package com.custoking.ims.fees.domain;

import com.custoking.ims.entity.FeeAssignmentEntity;
import com.custoking.ims.repo.FeeAssignmentRepository;
import com.custoking.ims.repo.PaymentRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FeeDomainService {

    private final FeeAssignmentRepository feeAssignmentRepository;
    private final PaymentRecordRepository paymentRecordRepository;

    public FeeDomainService(FeeAssignmentRepository feeAssignmentRepository,
                            PaymentRecordRepository paymentRecordRepository) {
        this.feeAssignmentRepository = feeAssignmentRepository;
        this.paymentRecordRepository = paymentRecordRepository;
    }

    public long calculateOutstandingDues(Long studentId, String academicYearId) {
        return feeAssignmentRepository
                .findByStudent_IdAndAcademicYear_Id(studentId, academicYearId)
                .map(a -> a.getNetPayable() - a.getPaidAmount())
                .orElse(0L);
    }

    public long calculateTotalCollected(Long schoolId) {
        return paymentRecordRepository.sumAmountBySchoolId(schoolId);
    }

    public long countStudentsWithOutstandingDues(String academicYearId, Long schoolId) {
        return feeAssignmentRepository.countOverdueByYearAndSchool(academicYearId, schoolId);
    }

    public void applyManualDiscount(FeeAssignmentEntity assignment, double discountAmount) {
        if (discountAmount < 0) {
            throw new IllegalArgumentException("Discount amount cannot be negative");
        }
        long newNetPayable = assignment.getNetPayable() - (long) discountAmount;
        if (newNetPayable < 0) {
            throw new IllegalArgumentException("Discount cannot exceed net payable amount");
        }
        assignment.setManualDiscount(assignment.getManualDiscount() + discountAmount);
        assignment.setNetPayable(newNetPayable);
        feeAssignmentRepository.save(assignment);
    }

    public void validatePaymentAmount(long paymentAmount, long outstandingAmount) {
        if (paymentAmount <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        if (paymentAmount > outstandingAmount) {
            throw new IllegalArgumentException("Payment amount cannot exceed outstanding dues");
        }
    }
}
