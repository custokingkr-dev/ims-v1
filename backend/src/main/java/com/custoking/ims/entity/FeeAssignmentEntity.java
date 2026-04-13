package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "fee_assignments", uniqueConstraints = @UniqueConstraint(name = "uk_fee_assignment_student_year", columnNames = {"student_id", "academicYear_id"}))
public class FeeAssignmentEntity {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private StudentEntity student;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private FeeBandEntity band;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AcademicYearEntity academicYear;
    private String schedule;
    private double bandDiscount;
    private double manualDiscount;
    private double surcharge;
    private long netPayable;
    private long paidAmount;
    private Long assignedBy;
    private OffsetDateTime assignedAt;
    private Long updatedBy;
    private OffsetDateTime updatedAt;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public StudentEntity getStudent() { return student; }
    public void setStudent(StudentEntity student) { this.student = student; }
    public FeeBandEntity getBand() { return band; }
    public void setBand(FeeBandEntity band) { this.band = band; }
    public AcademicYearEntity getAcademicYear() { return academicYear; }
    public void setAcademicYear(AcademicYearEntity academicYear) { this.academicYear = academicYear; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public double getBandDiscount() { return bandDiscount; }
    public void setBandDiscount(double bandDiscount) { this.bandDiscount = bandDiscount; }
    public double getManualDiscount() { return manualDiscount; }
    public void setManualDiscount(double manualDiscount) { this.manualDiscount = manualDiscount; }
    public double getSurcharge() { return surcharge; }
    public void setSurcharge(double surcharge) { this.surcharge = surcharge; }
    public long getNetPayable() { return netPayable; }
    public void setNetPayable(long netPayable) { this.netPayable = netPayable; }
    public long getPaidAmount() { return paidAmount; }
    public void setPaidAmount(long paidAmount) { this.paidAmount = paidAmount; }
    public Long getAssignedBy() { return assignedBy; }
    public void setAssignedBy(Long assignedBy) { this.assignedBy = assignedBy; }
    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(OffsetDateTime assignedAt) { this.assignedAt = assignedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
