package com.custoking.ims.events;

import org.springframework.context.ApplicationEvent;
import java.math.BigDecimal;

/** Published when a fee payment is recorded for a student. */
public class FeeCollectedEvent extends ApplicationEvent {

    private final long schoolId;
    private final long studentId;
    private final BigDecimal amount;
    private final String receiptNumber;
    private final Long collectedBy;

    public FeeCollectedEvent(Object source, long schoolId, long studentId,
                              BigDecimal amount, String receiptNumber, Long collectedBy) {
        super(source);
        this.schoolId = schoolId;
        this.studentId = studentId;
        this.amount = amount;
        this.receiptNumber = receiptNumber;
        this.collectedBy = collectedBy;
    }

    public long getSchoolId() { return schoolId; }
    public long getStudentId() { return studentId; }
    public BigDecimal getAmount() { return amount; }
    public String getReceiptNumber() { return receiptNumber; }
    public Long getCollectedBy() { return collectedBy; }
}
