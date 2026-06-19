package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "event_student_contributions",
       uniqueConstraints = @UniqueConstraint(name = "uq_event_student", columnNames = {"event_id", "student_id"}))
public class EventStudentContributionEntity {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private AcademicEventEntity event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private StudentEntity student;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "expected_amount", nullable = false)
    private long expectedAmount;

    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "last_reminder_sent_at")
    private OffsetDateTime lastReminderSentAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public AcademicEventEntity getEvent() { return event; }
    public void setEvent(AcademicEventEntity event) { this.event = event; }
    public StudentEntity getStudent() { return student; }
    public void setStudent(StudentEntity student) { this.student = student; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public long getExpectedAmount() { return expectedAmount; }
    public void setExpectedAmount(long expectedAmount) { this.expectedAmount = expectedAmount; }
    public long getPaidAmount() { return paidAmount; }
    public void setPaidAmount(long paidAmount) { this.paidAmount = paidAmount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getLastReminderSentAt() { return lastReminderSentAt; }
    public void setLastReminderSentAt(OffsetDateTime lastReminderSentAt) { this.lastReminderSentAt = lastReminderSentAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
