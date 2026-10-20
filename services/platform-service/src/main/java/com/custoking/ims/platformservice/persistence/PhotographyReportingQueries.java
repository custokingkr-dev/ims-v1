package com.custoking.ims.platformservice.persistence;

import com.custoking.ims.platformservice.persistence.ReportingReadRepository.AcademicEventRow;
import com.custoking.ims.platformservice.persistence.ReportingReadRepository.EventContributionRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reporting-owned projections for academic event contributions and photography dashboards. */
@Repository
public class PhotographyReportingQueries {
    private final JdbcClient jdbc;

    public PhotographyReportingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AcademicEventRow> academicEvents(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, academic_year_id, title, event_type, event_date,
                       total_budget, school_contribution, student_contribution_target,
                       status, created_by, created_at, updated_at
                FROM reporting.academic_events
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY event_date DESC NULLS LAST, created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(AcademicEventRow.class).list();
    }

    public List<EventContributionRow> eventContributions(String eventId, Long schoolId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_id, student_id, school_id, expected_amount, paid_amount,
                       status, last_reminder_sent_at, created_at, updated_at
                FROM reporting.event_student_contributions
                WHERE 1=1
                """);
        if (eventId != null && !eventId.isBlank()) sql.append(" AND event_id = :eventId");
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        sql.append(" ORDER BY updated_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 1000)));
        if (eventId != null && !eventId.isBlank()) spec = spec.param("eventId", eventId);
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        return spec.query(EventContributionRow.class).list();
    }

    public Map<String, Object> classPhotographyPaymentStatus(
            Long schoolId, String classId, String sectionId, String status, int page, int size) {
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        if (schoolId == null) {
            return emptyClassPhotographyPaymentStatus(pageNumber, pageSize);
        }
        List<Map<String, Object>> events = jdbc.sql("""
                SELECT id, title, event_date, total_budget, school_contribution, student_contribution_target
                FROM reporting.academic_events
                WHERE school_id = :schoolId
                  AND event_type = 'CLASS_PHOTOGRAPHY'
                  AND status = 'ACTIVE'
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "eventId", rs.getString("id"),
                        "title", rs.getString("title"),
                        "eventDate", rs.getObject("event_date", LocalDate.class),
                        "totalBudget", rs.getLong("total_budget"),
                        "schoolContribution", rs.getLong("school_contribution"),
                        "studentContributionTarget", rs.getLong("student_contribution_target")))
                .list();
        if (events.isEmpty()) {
            return emptyClassPhotographyPaymentStatus(pageNumber, pageSize);
        }
        Map<String, Object> event = events.get(0);
        String eventId = String.valueOf(event.get("eventId"));
        StringBuilder filter = new StringBuilder("""
                FROM reporting.event_student_contributions c
                JOIN reporting.dim_student ds ON ds.id = c.student_id
                JOIN reporting.dim_section sec ON sec.id = ds.section_id
                WHERE c.event_id = :eventId
                  AND c.school_id = :schoolId
                """);
        if (classId != null && !classId.isBlank()) filter.append(" AND ds.class_id = :classId\n");
        if (sectionId != null && !sectionId.isBlank()) filter.append(" AND ds.section_id = :sectionId\n");
        if (status != null && !status.isBlank()) filter.append(" AND c.status = :status\n");

        var countSpec = jdbc.sql("SELECT count(*) " + filter)
                .param("eventId", eventId)
                .param("schoolId", schoolId);
        var paidSpec = jdbc.sql("SELECT COALESCE(SUM(c.paid_amount), 0) " + filter)
                .param("eventId", eventId)
                .param("schoolId", schoolId);
        var itemSpec = jdbc.sql("""
                SELECT ds.id AS student_id, ds.full_name, ds.admission_no,
                       sec.class_name AS class_name, sec.name AS section_name,
                       COALESCE(NULLIF(ds.parent_contact, ''), ds.phone) AS parent_phone,
                       c.expected_amount, c.paid_amount, c.status, c.last_reminder_sent_at
                """ + filter + """
                ORDER BY sec.class_name, sec.name, ds.full_name
                LIMIT :limit OFFSET :offset
                """)
                .param("eventId", eventId)
                .param("schoolId", schoolId)
                .param("limit", pageSize)
                .param("offset", pageNumber * pageSize);
        if (classId != null && !classId.isBlank()) {
            countSpec = countSpec.param("classId", classId);
            paidSpec = paidSpec.param("classId", classId);
            itemSpec = itemSpec.param("classId", classId);
        }
        if (sectionId != null && !sectionId.isBlank()) {
            countSpec = countSpec.param("sectionId", sectionId);
            paidSpec = paidSpec.param("sectionId", sectionId);
            itemSpec = itemSpec.param("sectionId", sectionId);
        }
        if (status != null && !status.isBlank()) {
            countSpec = countSpec.param("status", status);
            paidSpec = paidSpec.param("status", status);
            itemSpec = itemSpec.param("status", status);
        }
        long totalElements = countSpec.query(Long.class).single();
        long collectedAmount = paidSpec.query(Long.class).single();
        long pendingAmount = Math.max(0, longValue(event.get("studentContributionTarget")) - collectedAmount);
        List<Map<String, Object>> students = itemSpec
                .query((rs, rowNum) -> row(
                        "studentId", rs.getLong("student_id"),
                        "studentName", rs.getString("full_name"),
                        "admissionNo", rs.getString("admission_no"),
                        "className", rs.getString("class_name"),
                        "sectionName", rs.getString("section_name"),
                        "parentPhone", rs.getString("parent_phone"),
                        "expectedAmount", rs.getLong("expected_amount"),
                        "paidAmount", rs.getLong("paid_amount"),
                        "pendingAmount", Math.max(0, rs.getLong("expected_amount") - rs.getLong("paid_amount")),
                        "status", rs.getString("status"),
                        "lastReminderSentAt", rs.getObject("last_reminder_sent_at", OffsetDateTime.class)))
                .list();
        return row(
                "eventId", eventId,
                "title", event.get("title"),
                "eventDate", event.get("eventDate"),
                "totalBudget", event.get("totalBudget"),
                "schoolContribution", event.get("schoolContribution"),
                "studentContributionTarget", event.get("studentContributionTarget"),
                "collectedAmount", collectedAmount,
                "pendingAmount", pendingAmount,
                "students", students,
                "page", pageNumber,
                "size", pageSize,
                "totalElements", totalElements);
    }

    public Map<String, Object> dashboardPhotography(Long schoolId) {
        List<Map<String, Object>> events = jdbc.sql("""
                SELECT id, student_contribution_target
                FROM reporting.academic_events
                WHERE school_id = :schoolId
                  AND event_type = 'CLASS_PHOTOGRAPHY'
                  AND status = 'ACTIVE'
                ORDER BY created_at DESC
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "eventId", rs.getString("id"),
                        "targetAmount", rs.getLong("student_contribution_target")))
                .list();
        if (events.isEmpty()) {
            return row("eventId", null, "collectedAmount", 0L, "pendingAmount", 0L, "targetAmount", 0L);
        }
        Map<String, Object> event = events.get(0);
        long collected = countAmount("""
                SELECT COALESCE(SUM(paid_amount), 0)
                FROM reporting.event_student_contributions
                WHERE event_id = :eventId
                """, "eventId", String.valueOf(event.get("eventId")));
        long target = longValue(event.get("targetAmount"));
        return row(
                "eventId", event.get("eventId"),
                "collectedAmount", collected,
                "pendingAmount", Math.max(0, target - collected),
                "targetAmount", target);
    }

    private static Map<String, Object> emptyClassPhotographyPaymentStatus(int page, int size) {
        return row(
                "eventId", null,
                "title", null,
                "eventDate", null,
                "totalBudget", 0L,
                "schoolContribution", 0L,
                "studentContributionTarget", 0L,
                "collectedAmount", 0L,
                "pendingAmount", 0L,
                "students", List.of(),
                "page", page,
                "size", size,
                "totalElements", 0L);
    }

    private static Map<String, Object> row(Object... values) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private static long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return 0L;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private long countAmount(String sql, String paramName, String value) {
        return jdbc.sql(sql).param(paramName, value).query(Long.class).single();
    }

}
