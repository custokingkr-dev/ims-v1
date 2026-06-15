package com.custoking.ims.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "attendance_student_records",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_attendance_student_daily_student", columnNames = {"attendance_daily_id", "student_id"}),
           @UniqueConstraint(name = "uk_attendance_student_date_year", columnNames = {"student_id", "attendance_date", "academic_year_id"})
       },
       indexes = {
           @Index(name = "idx_attendance_student_records_school_date", columnList = "school_id, attendance_date"),
           @Index(name = "idx_attendance_student_records_section_date", columnList = "section_id, attendance_date"),
           @Index(name = "idx_attendance_student_records_daily", columnList = "attendance_daily_id"),
           @Index(name = "idx_attendance_student_records_student_date", columnList = "student_id, attendance_date"),
           @Index(name = "idx_attendance_student_records_academic_year", columnList = "academic_year_id, attendance_date")
       })
public class AttendanceStudentRecordEntity {
    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attendance_daily_id")
    private AttendanceDailyEntity attendanceDaily;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id")
    private StudentEntity student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "school_id")
    private SchoolEntity school;

    private LocalDate attendanceDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academic_year_id")
    private AcademicYearEntity academicYear;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_id")
    private SchoolClassEntity schoolClass;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id")
    private SchoolSectionEntity section;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    private Long recordedBy;
    private OffsetDateTime recordedAt;
    private Long updatedBy;
    private OffsetDateTime updatedAt;

    // ── Accessors ────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public AttendanceDailyEntity getAttendanceDaily() { return attendanceDaily; }
    public void setAttendanceDaily(AttendanceDailyEntity attendanceDaily) { this.attendanceDaily = attendanceDaily; }

    public StudentEntity getStudent() { return student; }
    public void setStudent(StudentEntity student) { this.student = student; }

    public SchoolEntity getSchool() { return school; }
    public void setSchool(SchoolEntity school) { this.school = school; }

    public LocalDate getAttendanceDate() { return attendanceDate; }
    public void setAttendanceDate(LocalDate attendanceDate) { this.attendanceDate = attendanceDate; }

    public AcademicYearEntity getAcademicYear() { return academicYear; }
    public void setAcademicYear(AcademicYearEntity academicYear) { this.academicYear = academicYear; }

    public SchoolClassEntity getSchoolClass() { return schoolClass; }
    public void setSchoolClass(SchoolClassEntity schoolClass) { this.schoolClass = schoolClass; }

    public SchoolSectionEntity getSection() { return section; }
    public void setSection(SchoolSectionEntity section) { this.section = section; }

    public AttendanceStatus getStatus() { return status; }
    public void setStatus(AttendanceStatus status) { this.status = status; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public Long getRecordedBy() { return recordedBy; }
    public void setRecordedBy(Long recordedBy) { this.recordedBy = recordedBy; }

    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    // ── Enum for attendance status ────────────────────────────────────

    public enum AttendanceStatus {
        PRESENT,
        ABSENT
    }
}
