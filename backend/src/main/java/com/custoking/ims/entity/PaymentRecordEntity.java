package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_records")
public class PaymentRecordEntity {
    @Id private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "student_id") private StudentEntity student;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assignment_id") private FeeAssignmentEntity assignment;
    private long amount; private String mode; @Lob private String notes; private OffsetDateTime paidAt; private Long recordedBy; private String receiptNumber; private OffsetDateTime createdAt = OffsetDateTime.now();
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public StudentEntity getStudent() { return student; } public void setStudent(StudentEntity student) { this.student = student; }
    public FeeAssignmentEntity getAssignment() { return assignment; } public void setAssignment(FeeAssignmentEntity assignment) { this.assignment = assignment; }
    public long getAmount() { return amount; } public void setAmount(long amount) { this.amount = amount; }
    public String getMode() { return mode; } public void setMode(String mode) { this.mode = mode; }
    public String getNotes() { return notes; } public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getPaidAt() { return paidAt; } public void setPaidAt(OffsetDateTime paidAt) { this.paidAt = paidAt; }
    public Long getRecordedBy() { return recordedBy; } public void setRecordedBy(Long recordedBy) { this.recordedBy = recordedBy; }
    public String getReceiptNumber() { return receiptNumber; } public void setReceiptNumber(String receiptNumber) { this.receiptNumber = receiptNumber; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
