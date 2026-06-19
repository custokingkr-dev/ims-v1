package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "academic_events")
public class AcademicEventEntity {

    @Id
    private String id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "academic_year_id")
    private String academicYearId;

    @Column(nullable = false)
    private String title;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "total_budget", nullable = false)
    private long totalBudget;

    @Column(name = "school_contribution", nullable = false)
    private long schoolContribution;

    @Column(name = "student_contribution_target", nullable = false)
    private long studentContributionTarget;

    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getSchoolId() { return schoolId; }
    public void setSchoolId(Long schoolId) { this.schoolId = schoolId; }
    public String getAcademicYearId() { return academicYearId; }
    public void setAcademicYearId(String academicYearId) { this.academicYearId = academicYearId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }
    public long getTotalBudget() { return totalBudget; }
    public void setTotalBudget(long totalBudget) { this.totalBudget = totalBudget; }
    public long getSchoolContribution() { return schoolContribution; }
    public void setSchoolContribution(long schoolContribution) { this.schoolContribution = schoolContribution; }
    public long getStudentContributionTarget() { return studentContributionTarget; }
    public void setStudentContributionTarget(long studentContributionTarget) { this.studentContributionTarget = studentContributionTarget; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
