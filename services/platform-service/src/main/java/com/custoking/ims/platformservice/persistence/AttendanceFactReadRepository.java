package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Read model projected from {@code attendance-daily.upserted.v1} events (school-core-service
 * outbox), per Reporting Decoupling SP3. Rows are upserted idempotently by {@code id} so
 * replaying the same or a later event for the same attendance-daily row never duplicates state.
 */
@Repository
public class AttendanceFactReadRepository {

    private final JdbcClient jdbc;

    public AttendanceFactReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void upsert(String id, Long schoolId, LocalDate attendanceDate, String classId, String sectionId,
                        String academicYearId, Integer presentCount, Integer absentCount, Integer lateCount,
                        Integer leaveCount, Integer totalEnrolled) {
        jdbc.sql("""
                        INSERT INTO reporting.fact_attendance_daily (
                            id, school_id, attendance_date, class_id, section_id, academic_year_id,
                            present_count, absent_count, late_count, leave_count, total_enrolled, updated_at
                        ) VALUES (
                            :id, :schoolId, :attendanceDate, :classId, :sectionId, :academicYearId,
                            :presentCount, :absentCount, :lateCount, :leaveCount, :totalEnrolled, now()
                        )
                        ON CONFLICT (id) DO UPDATE SET
                            school_id = EXCLUDED.school_id,
                            attendance_date = EXCLUDED.attendance_date,
                            class_id = EXCLUDED.class_id,
                            section_id = EXCLUDED.section_id,
                            academic_year_id = EXCLUDED.academic_year_id,
                            present_count = EXCLUDED.present_count,
                            absent_count = EXCLUDED.absent_count,
                            late_count = EXCLUDED.late_count,
                            leave_count = EXCLUDED.leave_count,
                            total_enrolled = EXCLUDED.total_enrolled,
                            updated_at = now()
                        """)
                .param("id", id)
                .param("schoolId", schoolId)
                .param("attendanceDate", attendanceDate)
                .param("classId", classId)
                .param("sectionId", sectionId)
                .param("academicYearId", academicYearId)
                .param("presentCount", presentCount)
                .param("absentCount", absentCount)
                .param("lateCount", lateCount)
                .param("leaveCount", leaveCount)
                .param("totalEnrolled", totalEnrolled)
                .update();
    }
}
