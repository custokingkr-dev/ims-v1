package com.custoking.ims.repo;

import com.custoking.ims.entity.AttendanceStudentRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceStudentRecordRepository extends JpaRepository<AttendanceStudentRecordEntity, String> {

    /**
     * Find all attendance records for a specific attendance daily entry.
     */
    List<AttendanceStudentRecordEntity> findByAttendanceDaily_IdOrderByStudent_FullNameAsc(String attendanceDailyId);

    /**
     * Find attendance record for a specific student on a specific date and academic year.
     */
    Optional<AttendanceStudentRecordEntity> findByStudent_IdAndAttendanceDateAndAcademicYear_Id(
            Long studentId, LocalDate attendanceDate, String academicYearId);

    /**
     * Find all attendance records for a section on a specific date.
     */
    List<AttendanceStudentRecordEntity> findBySection_IdAndAttendanceDateOrderByStudent_FullNameAsc(
            String sectionId, LocalDate attendanceDate);

    /**
     * Count present records for a section on a specific date.
     */
    @Query("SELECT COUNT(r) FROM AttendanceStudentRecordEntity r " +
           "WHERE r.section.id = :sectionId AND r.attendanceDate = :date AND r.status = 'PRESENT'")
    long countPresentBySection(String sectionId, LocalDate date);

    /**
     * Count absent records for a section on a specific date.
     */
    @Query("SELECT COUNT(r) FROM AttendanceStudentRecordEntity r " +
           "WHERE r.section.id = :sectionId AND r.attendanceDate = :date AND r.status = 'ABSENT'")
    long countAbsentBySection(String sectionId, LocalDate date);

    /**
     * Find all records for a date and academic year.
     */
    List<AttendanceStudentRecordEntity> findByAttendanceDateAndAcademicYear_Id(LocalDate date, String academicYearId);

    /**
     * Delete records for an attendance daily entry (cascade is automatic, but explicit method is useful).
     */
    void deleteByAttendanceDaily_Id(String attendanceDailyId);
}
