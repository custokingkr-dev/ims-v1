package com.custoking.ims.repo;

import com.custoking.ims.entity.AttendanceDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceDailyRepository extends JpaRepository<AttendanceDailyEntity, String> {

    List<AttendanceDailyEntity> findByAttendanceDateAndAcademicYear_Id(LocalDate date, String academicYearId);

    Optional<AttendanceDailyEntity> findByAttendanceDateAndSection_IdAndAcademicYear_Id(
            LocalDate date, String sectionId, String academicYearId);

    @Query("SELECT COUNT(a) FROM AttendanceDailyEntity a " +
           "WHERE a.attendanceDate = :date AND a.academicYear.id = :yearId " +
           "AND a.section.school.id = :schoolId " +
           "AND a.totalEnrolled > 0 " +
           "AND (a.presentCount * 1.0 / a.totalEnrolled) < :threshold")
    long countSectionsBelowThreshold(
            @Param("date") LocalDate date,
            @Param("yearId") String yearId,
            @Param("schoolId") Long schoolId,
            @Param("threshold") double threshold);

    @Query("SELECT a FROM AttendanceDailyEntity a " +
           "JOIN FETCH a.section s " +
           "JOIN FETCH s.schoolClass " +
           "WHERE a.attendanceDate = :date AND a.academicYear.id = :yearId " +
           "AND s.school.id = :schoolId " +
           "AND a.totalEnrolled > 0 " +
           "AND (a.presentCount * 1.0 / a.totalEnrolled) < :threshold " +
           "ORDER BY (a.presentCount * 1.0 / a.totalEnrolled) ASC")
    List<AttendanceDailyEntity> findSectionsBelowThresholdList(
            @Param("date") LocalDate date,
            @Param("yearId") String yearId,
            @Param("schoolId") Long schoolId,
            @Param("threshold") double threshold);
}
