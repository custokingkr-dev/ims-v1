package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "attendance_daily", uniqueConstraints = @UniqueConstraint(name = "uk_attendance_day_section_year", columnNames = {"attendanceDate", "section_id", "academicYear_id"}))
public class AttendanceDailyEntity {
    @Id
    private String id;
    private LocalDate attendanceDate;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private SchoolClassEntity schoolClass;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private SchoolSectionEntity section;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AcademicYearEntity academicYear;
    private int totalEnrolled;
    private int presentCount;
    private int absentCount;
    private Long recordedBy;
    private OffsetDateTime recordedAt;
    private Long updatedBy;
    private OffsetDateTime updatedAt;
    private boolean locked;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public LocalDate getAttendanceDate() { return attendanceDate; }
    public void setAttendanceDate(LocalDate attendanceDate) { this.attendanceDate = attendanceDate; }
    public SchoolClassEntity getSchoolClass() { return schoolClass; }
    public void setSchoolClass(SchoolClassEntity schoolClass) { this.schoolClass = schoolClass; }
    public SchoolSectionEntity getSection() { return section; }
    public void setSection(SchoolSectionEntity section) { this.section = section; }
    public AcademicYearEntity getAcademicYear() { return academicYear; }
    public void setAcademicYear(AcademicYearEntity academicYear) { this.academicYear = academicYear; }
    public int getTotalEnrolled() { return totalEnrolled; }
    public void setTotalEnrolled(int totalEnrolled) { this.totalEnrolled = totalEnrolled; }
    public int getPresentCount() { return presentCount; }
    public void setPresentCount(int presentCount) { this.presentCount = presentCount; }
    public int getAbsentCount() { return absentCount; }
    public void setAbsentCount(int absentCount) { this.absentCount = absentCount; }
    public Long getRecordedBy() { return recordedBy; }
    public void setRecordedBy(Long recordedBy) { this.recordedBy = recordedBy; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
}
