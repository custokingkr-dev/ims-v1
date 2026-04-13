package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "fee_bands")
public class FeeBandEntity {
    @Id private String id; private String name; private int classFrom; private int classTo; private double discount; @Lob private String activeSchedulesCsv; private OffsetDateTime createdAt = OffsetDateTime.now(); private OffsetDateTime updatedAt = OffsetDateTime.now();
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "academic_year_id") private AcademicYearEntity academicYear;
    public String getId() { return id; } public void setId(String id) { this.id = id; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public int getClassFrom() { return classFrom; } public void setClassFrom(int classFrom) { this.classFrom = classFrom; }
    public int getClassTo() { return classTo; } public void setClassTo(int classTo) { this.classTo = classTo; }
    public double getDiscount() { return discount; } public void setDiscount(double discount) { this.discount = discount; }
    public String getActiveSchedulesCsv() { return activeSchedulesCsv; } public void setActiveSchedulesCsv(String activeSchedulesCsv) { this.activeSchedulesCsv = activeSchedulesCsv; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public AcademicYearEntity getAcademicYear() { return academicYear; } public void setAcademicYear(AcademicYearEntity academicYear) { this.academicYear = academicYear; }
}
