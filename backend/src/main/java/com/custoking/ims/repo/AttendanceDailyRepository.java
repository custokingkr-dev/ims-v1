package com.custoking.ims.repo;
import com.custoking.ims.entity.AttendanceDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;import java.util.*;
public interface AttendanceDailyRepository extends JpaRepository<AttendanceDailyEntity, String> { List<AttendanceDailyEntity> findByAttendanceDateAndAcademicYear_Id(LocalDate date, String academicYearId); Optional<AttendanceDailyEntity> findByAttendanceDateAndSection_IdAndAcademicYear_Id(LocalDate date, String sectionId, String academicYearId); }
