package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** Owns fees reporting queries; tenant filters, SQL and response shapes are preserved. */
@Repository
public class FeeReportingQueries {
    private final JdbcClient jdbc;
    private final ReportingSchoolContext schools;
    private final ReportingScalarQueries scalars;

    public FeeReportingQueries(JdbcClient jdbc, ReportingSchoolContext schools, ReportingScalarQueries scalars) {
        this.jdbc = jdbc;
        this.schools = schools;
        this.scalars = scalars;
    }

    public Map<String, Object> feeDefaulters(Long schoolId, String classId, String sectionId,
                                             Integer daysOverdueMin, String reminderStatus,
                                             int page, int size) {
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        if (schoolId == null) {
            return feeDefaulterRow(0L, 0L, 0, List.of(), pageNumber, pageSize, 0L);
        }
        String yearId = schools.currentAcademicYearId(schoolId);
        if (yearId == null) {
            return feeDefaulterRow(0L, 0L, 0, List.of(), pageNumber, pageSize, 0L);
        }
        StringBuilder filter = new StringBuilder("""
                FROM reporting.fact_fee_assignment fa
                JOIN reporting.dim_student ds ON ds.id = fa.student_id
                JOIN reporting.dim_section sec ON sec.id = ds.section_id
                LEFT JOIN LATERAL (
                    SELECT sent_at, status
                    FROM notification.notification_logs
                    WHERE student_id = ds.id
                      AND notification_type = 'FEE_OVERDUE'
                    ORDER BY sent_at DESC NULLS LAST
                    LIMIT 1
                ) nl ON true
                WHERE fa.academic_year_id = :yearId
                  AND fa.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """);
        if (classId != null && !classId.isBlank()) filter.append(" AND ds.class_id = :classId\n");
        if (sectionId != null && !sectionId.isBlank()) filter.append(" AND ds.section_id = :sectionId\n");

        var countSpec = jdbc.sql("SELECT count(*) " + filter)
                .param("yearId", yearId)
                .param("schoolId", schoolId);
        var itemSpec = jdbc.sql("""
                SELECT ds.id AS student_id, ds.full_name, ds.admission_no,
                       sec.class_name AS class_name, sec.name AS section_name,
                       ds.father_name, ds.parent_contact,
                       (fa.net_payable - fa.paid_amount) AS due_amount,
                       fa.assigned_at,
                       nl.sent_at AS last_reminder_sent_at,
                       nl.status AS reminder_status
                """ + filter + """
                ORDER BY fa.assigned_at ASC NULLS FIRST
                LIMIT :limit OFFSET :offset
                """)
                .param("yearId", yearId)
                .param("schoolId", schoolId)
                .param("limit", pageSize)
                .param("offset", pageNumber * pageSize);
        if (classId != null && !classId.isBlank()) {
            countSpec = countSpec.param("classId", classId);
            itemSpec = itemSpec.param("classId", classId);
        }
        if (sectionId != null && !sectionId.isBlank()) {
            countSpec = countSpec.param("sectionId", sectionId);
            itemSpec = itemSpec.param("sectionId", sectionId);
        }
        long totalElements = countSpec.query(Long.class).single();
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> items = itemSpec
                .query((rs, rowNum) -> {
                    OffsetDateTime assignedAt = rs.getObject("assigned_at", OffsetDateTime.class);
                    LocalDate dueDate = assignedAt == null ? today : assignedAt.toLocalDate();
                    int daysOverdue = (int) Math.max(0, ChronoUnit.DAYS.between(dueDate, today));
                    OffsetDateTime lastReminder = rs.getObject("last_reminder_sent_at", OffsetDateTime.class);
                    String reminder = reminderStatus(rs.getString("reminder_status"), lastReminder);
                    return row(
                            "studentId", rs.getLong("student_id"),
                            "studentName", rs.getString("full_name"),
                            "admissionNo", rs.getString("admission_no"),
                            "className", rs.getString("class_name"),
                            "sectionName", rs.getString("section_name"),
                            "parentName", rs.getString("father_name"),
                            "parentPhone", rs.getString("parent_contact"),
                            "dueAmount", rs.getLong("due_amount"),
                            "dueDate", dueDate,
                            "daysOverdue", daysOverdue,
                            "lastReminderSentAt", lastReminder,
                            "reminderStatus", reminder,
                            "paymentStatus", "OVERDUE");
                })
                .list();
        if (daysOverdueMin != null) {
            items = items.stream()
                    .filter(row -> ((Number) row.get("daysOverdue")).intValue() >= daysOverdueMin)
                    .toList();
        }
        if (reminderStatus != null && !reminderStatus.isBlank()) {
            items = items.stream()
                    .filter(row -> reminderStatus.equalsIgnoreCase(String.valueOf(row.get("reminderStatus"))))
                    .toList();
        }
        // The dim_student + dim_section joins mirror the list query above exactly, so the totals
        // cover the same row-set that is displayed (a defaulter whose section has not yet projected
        // into dim_section is excluded from both the list and these totals).
        long totalOverdueAmount = scalars.countAmount("""
                SELECT COALESCE(SUM(fa.net_payable - fa.paid_amount), 0)
                FROM reporting.fact_fee_assignment fa
                JOIN reporting.dim_student ds ON ds.id = fa.student_id
                JOIN reporting.dim_section sec ON sec.id = ds.section_id
                WHERE fa.academic_year_id = :yearId
                  AND fa.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, yearId, schoolId);
        List<OffsetDateTime> oldestRows = jdbc.sql("""
                SELECT MIN(fa.assigned_at)
                FROM reporting.fact_fee_assignment fa
                JOIN reporting.dim_student ds ON ds.id = fa.student_id
                JOIN reporting.dim_section sec ON sec.id = ds.section_id
                WHERE fa.academic_year_id = :yearId
                  AND fa.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """)
                .param("yearId", yearId)
                .param("schoolId", schoolId)
                .query(OffsetDateTime.class)
                .list();
        OffsetDateTime oldestAt = oldestRows.isEmpty() ? null : oldestRows.get(0);
        int oldestDueDays = oldestAt == null ? 0
                : (int) Math.max(0, ChronoUnit.DAYS.between(oldestAt.toLocalDate(), today));
        return feeDefaulterRow(totalElements, totalOverdueAmount, oldestDueDays,
                items, pageNumber, pageSize, totalElements);
    }
}
