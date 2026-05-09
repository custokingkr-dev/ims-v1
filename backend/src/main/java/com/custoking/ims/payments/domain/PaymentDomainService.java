package com.custoking.ims.payments.domain;

import com.custoking.ims.entity.PaymentRecordEntity;
import com.custoking.ims.repo.PaymentRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class PaymentDomainService {

    private final PaymentRecordRepository paymentRecordRepository;

    public PaymentDomainService(PaymentRecordRepository paymentRecordRepository) {
        this.paymentRecordRepository = paymentRecordRepository;
    }

    public void validatePaymentMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("Payment mode is required");
        }
    }

    public void validatePaymentAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
    }

    public long sumTotalCollected(Long schoolId) {
        return paymentRecordRepository.sumAmountBySchoolId(schoolId);
    }

    public long sumTotalCollectedAll() {
        return paymentRecordRepository.sumAmount();
    }

    public List<PaymentRecordEntity> findPaymentsByStudent(Long studentId) {
        return paymentRecordRepository.findByStudent_IdOrderByPaidAtDesc(studentId);
    }

    public boolean isReceiptNumberUnique(String receiptNumber) {
        return paymentRecordRepository.findByReceiptNumber(receiptNumber).isEmpty();
    }
}
