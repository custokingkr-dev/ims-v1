package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** Owns attendance reporting queries; tenant filters, SQL and response shapes are preserved. */
@Repository
public class AttendanceReportingQueries {
    private final JdbcClient jdbc;
    private final ReportingSchoolContext schools;

    public AttendanceReportingQueries(JdbcClient jdbc, ReportingSchoolContext schools) {
        this.jdbc = jdbc;
        this.schools = schools;
    }

    public Map<String, Object> lowAttendanceSections(Long schoolId, LocalDate date) {
        LocalDate effectiveDate = date == null ? LocalDate.now() : date;
        if (schoolId == null) {
            return row("date", effectiveDate, "thresholdPercent", 75, "sections", List.of());
        }
        String yearId = schools.currentAcademicYearId(schoolId);
        if (yearId == null) {
            return row("date", effectiveDate, "thresholdPercent", 75, "sections", List.of());
        }
        List<Map<String, Object>> sections = jdbc.sql("""
                SELECT ad.section_id, sec.name AS section_name, sec.class_name AS class_name,
                       ad.present_count, ad.total_enrolled,
                       ROUND((ad.present_count * 10000.0 / ad.total_enrolled)) / 100.0 AS attendance_pct,
                       (
                         SELECT count(*)
                         FROM reporting.dim_student
                         WHERE section_id = ad.section_id
                           AND school_id = :schoolId
                           AND (attendance_percent IS NULL OR attendance_percent < :threshold)
                       ) AS students_below_threshold
                FROM reporting.fact_attendance_daily ad
                JOIN reporting.dim_section sec ON sec.id = ad.section_id
                WHERE ad.attendance_date = :date
                  AND ad.academic_year_id = :yearId
                  AND sec.school_id = :schoolId
                  AND ad.total_enrolled > 0
                  AND (ad.present_count * 1.0 / ad.total_enrolled) < 0.75
                ORDER BY (ad.present_count * 1.0 / ad.total_enrolled) ASC
                """)
                .param("threshold", 75)
                .param("date", effectiveDate)
                .param("yearId", yearId)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "sectionId", rs.getString("section_id"),
                        "sectionName", rs.getString("section_name"),
                        "className", rs.getString("class_name"),
                        "presentCount", rs.getInt("present_count"),
                        "totalEnrolled", rs.getInt("total_enrolled"),
                        "attendancePct", rs.getDouble("attendance_pct"),
                        "studentsBelowThreshold", rs.getLong("students_below_threshold")))
                .list();
        return row("date", effectiveDate, "thresholdPercent", 75, "sections", sections);
    }

    public List<Map<String, Object>> lowAttendanceStudents(Long schoolId, String sectionId) {
        if (schoolId == null || sectionId == null || sectionId.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT ds.id, ds.full_name, ds.admission_no, sec.class_name AS class_name, sec.name AS section_name,
                       ds.father_name, ds.parent_contact, ds.attendance_percent,
                       (
                         SELECT MAX(nl.sent_at)
                         FROM notification.notification_logs nl
                         WHERE nl.school_id = :schoolId
                           AND nl.student_id = ds.id
                           AND nl.notification_type = 'LOW_ATTENDANCE_MEETING_INVITE'
                       ) AS last_invite_sent_at
                FROM reporting.dim_student ds
                JOIN reporting.dim_section sec ON sec.id = ds.section_id
                WHERE ds.school_id = :schoolId
                  AND ds.section_id = :sectionId
                  AND (ds.attendance_percent IS NULL OR ds.attendance_percent < 75)
                ORDER BY ds.full_name ASC
                """)
                .param("schoolId", schoolId)
                .param("sectionId", sectionId)
                .query((rs, rowNum) -> row(
                        "studentId", rs.getLong("id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNo", rs.getString("admission_no"),
                        "className", rs.getString("class_name"),
                        "sectionName", rs.getString("section_name"),
                        "fatherName", rs.getString("father_name"),
                        "fatherContact", rs.getString("parent_contact"),
                        "attendancePercent", rs.getObject("attendance_percent"),
                        "lastInviteSentAt", rs.getObject("last_invite_sent_at", OffsetDateTime.class)))
                .list();
    }
}
