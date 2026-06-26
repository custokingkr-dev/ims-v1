package com.custoking.ims.reportingservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class ReportingReadRepository {

    private final JdbcClient jdbc;

    public ReportingReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CommandCenterFeedRow> feed(Long schoolId, String module, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, event_type, title, message, severity,
                       entity_type, entity_id, actor_user_id, created_at
                FROM reporting.command_center_feed
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND (school_id = :schoolId OR school_id IS NULL)");
        if (module != null && !module.isBlank()) sql.append(" AND module = :module");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (module != null && !module.isBlank()) spec = spec.param("module", module);
        return spec.query(CommandCenterFeedRow.class).list();
    }

    public List<CommandCenterActionRow> actions(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, urgency, confidence, title, reason, impact,
                       current_state, target_state, cta_label, status, source_type, source_id,
                       accepted_by, accepted_at, dismissed_by, dismissed_reason, dismissed_at,
                       created_at, updated_at
                FROM reporting.command_center_actions
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(CommandCenterActionRow.class).list();
    }

    public List<InvoiceRow> invoices(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, order_ref, school, school_id, description, qty, rate, amount,
                       gst_amount, total, status, issued_at, due_at, notes, created_at
                FROM billing.superadmin_invoices
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(InvoiceRow.class).list();
    }

    public Map<String, Object> invoiceStats(Long schoolId) {
        if (schoolId == null) {
            return Map.of(
                    "sentThisMonth", count("SELECT count(*) FROM billing.superadmin_invoices"),
                    "paid", count("SELECT count(*) FROM billing.superadmin_invoices WHERE LOWER(status) = 'paid'"),
                    "pending", count("SELECT count(*) FROM billing.superadmin_invoices WHERE LOWER(status) = 'awaiting payment'"),
                    "totalInvoiced", countAmount("SELECT COALESCE(SUM(total), 0) FROM billing.superadmin_invoices"));
        }
        return Map.of(
                "sentThisMonth", count("SELECT count(*) FROM billing.superadmin_invoices WHERE school_id = :schoolId", schoolId),
                "paid", count("SELECT count(*) FROM billing.superadmin_invoices WHERE school_id = :schoolId AND LOWER(status) = 'paid'", schoolId),
                "pending", count("SELECT count(*) FROM billing.superadmin_invoices WHERE school_id = :schoolId AND LOWER(status) = 'awaiting payment'", schoolId),
                "totalInvoiced", countAmount("SELECT COALESCE(SUM(total), 0) FROM billing.superadmin_invoices WHERE school_id = :schoolId", schoolId));
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
                JOIN student.students s ON s.id = c.student_id
                JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                WHERE c.event_id = :eventId
                  AND c.school_id = :schoolId
                """);
        if (classId != null && !classId.isBlank()) filter.append(" AND s.class_id = :classId\n");
        if (sectionId != null && !sectionId.isBlank()) filter.append(" AND s.section_id = :sectionId\n");
        if (status != null && !status.isBlank()) filter.append(" AND c.status = :status\n");

        var countSpec = jdbc.sql("SELECT count(*) " + filter)
                .param("eventId", eventId)
                .param("schoolId", schoolId);
        var paidSpec = jdbc.sql("SELECT COALESCE(SUM(c.paid_amount), 0) " + filter)
                .param("eventId", eventId)
                .param("schoolId", schoolId);
        var itemSpec = jdbc.sql("""
                SELECT s.id AS student_id, s.full_name, s.admission_no,
                       sc.name AS class_name, ss.name AS section_name,
                       COALESCE(NULLIF(s.father_contact, ''), s.phone) AS parent_phone,
                       c.expected_amount, c.paid_amount, c.status, c.last_reminder_sent_at
                """ + filter + """
                ORDER BY sc.sort_order, ss.name, s.full_name
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

    public List<BroadcastRow> broadcasts(Long schoolId, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, school_id, module, title, message, audience_type, channels, status,
                       scheduled_at, approved_by, approved_at, sent_by, sent_at, created_by,
                       created_at, updated_at
                FROM notification.notification_broadcasts
                WHERE 1=1
                """);
        if (schoolId != null) sql.append(" AND school_id = :schoolId");
        if (status != null && !status.isBlank()) sql.append(" AND status = :status");
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var spec = jdbc.sql(sql.toString()).param("limit", Math.max(1, Math.min(limit, 500)));
        if (schoolId != null) spec = spec.param("schoolId", schoolId);
        if (status != null && !status.isBlank()) spec = spec.param("status", status);
        return spec.query(BroadcastRow.class).list();
    }

    public Map<String, Object> summary(Long schoolId) {
        if (schoolId == null) {
            return Map.of(
                    "openActions", count("SELECT count(*) FROM reporting.command_center_actions WHERE status = 'OPEN'"),
                    "feedItems", count("SELECT count(*) FROM reporting.command_center_feed"),
                    "invoices", count("SELECT count(*) FROM billing.superadmin_invoices"),
                    "academicEvents", count("SELECT count(*) FROM reporting.academic_events"),
                    "broadcasts", count("SELECT count(*) FROM notification.notification_broadcasts"));
        }
        return Map.of(
                "openActions", count("SELECT count(*) FROM reporting.command_center_actions WHERE status = 'OPEN' AND school_id = :schoolId", schoolId),
                "feedItems", count("SELECT count(*) FROM reporting.command_center_feed WHERE school_id = :schoolId", schoolId),
                "invoices", count("SELECT count(*) FROM billing.superadmin_invoices WHERE school_id = :schoolId", schoolId),
                "academicEvents", count("SELECT count(*) FROM reporting.academic_events WHERE school_id = :schoolId", schoolId),
                "broadcasts", count("SELECT count(*) FROM notification.notification_broadcasts WHERE school_id = :schoolId", schoolId));
    }

    public Map<String, Object> vendorDues(Long schoolId) {
        if (schoolId == null) {
            return Map.of(
                    "catalogOrderCount", 0L,
                    "catalogOrderTotalPaise", 0L,
                    "firefightingCount", 0L,
                    "firefightingTotalPaise", 0L,
                    "totalDuesPaise", 0L,
                    "items", List.of());
        }
        List<Map<String, Object>> catalog = jdbc.sql("""
                SELECT 'CATALOG_ORDER' AS source_type, id, category AS title, category,
                       NULL::varchar AS vendor_name, total_amount AS amount_paise,
                       status, created_at
                FROM catalog.catalog_orders
                WHERE school_id = :schoolId
                  AND status IN ('APPROVED', 'FULFILLED')
                  AND vendor_paid_at IS NULL
                ORDER BY created_at DESC NULLS LAST
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "sourceType", rs.getString("source_type"),
                        "id", rs.getString("id"),
                        "title", rs.getString("title"),
                        "category", rs.getString("category"),
                        "vendorName", rs.getString("vendor_name"),
                        "amountPaise", rs.getLong("amount_paise"),
                        "status", rs.getString("status"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        List<Map<String, Object>> firefighting = jdbc.sql("""
                SELECT 'FIREFIGHTING' AS source_type, code AS id, title, category,
                       winner_vendor AS vendor_name, winner_amount AS amount_paise,
                       status, created_at
                FROM firefighting.firefighting_requests
                WHERE school_id = :schoolId
                  AND status = 'APPROVED'
                  AND winner_amount IS NOT NULL
                  AND vendor_paid_at IS NULL
                ORDER BY created_at DESC NULLS LAST
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "sourceType", rs.getString("source_type"),
                        "id", rs.getString("id"),
                        "title", rs.getString("title"),
                        "category", rs.getString("category"),
                        "vendorName", rs.getString("vendor_name"),
                        "amountPaise", rs.getLong("amount_paise"),
                        "status", rs.getString("status"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        long catalogTotal = catalog.stream().mapToLong(row -> longValue(row.get("amountPaise"))).sum();
        long firefightingTotal = firefighting.stream().mapToLong(row -> longValue(row.get("amountPaise"))).sum();
        java.util.ArrayList<Map<String, Object>> items = new java.util.ArrayList<>();
        items.addAll(catalog);
        items.addAll(firefighting);
        return Map.of(
                "catalogOrderCount", (long) catalog.size(),
                "catalogOrderTotalPaise", catalogTotal,
                "firefightingCount", (long) firefighting.size(),
                "firefightingTotalPaise", firefightingTotal,
                "totalDuesPaise", catalogTotal + firefightingTotal,
                "items", items);
    }

    public Map<String, Object> reorderSignals(Long schoolId) {
        if (schoolId == null) {
            return Map.of("alertCount", 0, "items", List.of());
        }
        List<Map<String, Object>> orders = jdbc.sql("""
                SELECT category, created_at
                FROM catalog.catalog_orders
                WHERE school_id = :schoolId
                  AND status IN ('APPROVED', 'FULFILLED')
                  AND created_at IS NOT NULL
                ORDER BY created_at ASC
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "category", rs.getString("category"),
                        "createdAt", rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        Map<String, List<Map<String, Object>>> byCategory = orders.stream()
                .collect(Collectors.groupingBy(row -> String.valueOf(row.get("category"))));
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byCategory.entrySet()) {
            List<Map<String, Object>> catOrders = new ArrayList<>(entry.getValue());
            catOrders.sort(Comparator.comparing(row -> (OffsetDateTime) row.get("createdAt")));
            LocalDate lastDate = ((OffsetDateTime) catOrders.get(catOrders.size() - 1).get("createdAt")).toLocalDate();
            int daysSinceLast = (int) Math.max(0, ChronoUnit.DAYS.between(lastDate, today));
            Integer avgInterval = null;
            LocalDate predictedNext = null;
            if (catOrders.size() >= 2) {
                LocalDate firstDate = ((OffsetDateTime) catOrders.get(0).get("createdAt")).toLocalDate();
                long span = ChronoUnit.DAYS.between(firstDate, lastDate);
                int computed = (int) (span / (catOrders.size() - 1));
                if (computed > 0) {
                    avgInterval = computed;
                    predictedNext = lastDate.plusDays(computed);
                }
            }
            String alertLevel;
            if (avgInterval != null) {
                if (daysSinceLast > avgInterval * 1.2) {
                    alertLevel = "RED";
                } else if (daysSinceLast > avgInterval * 0.7) {
                    alertLevel = "YELLOW";
                } else {
                    alertLevel = "OK";
                }
            } else {
                alertLevel = daysSinceLast >= 180 ? "YELLOW" : "OK";
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("category", entry.getKey());
            item.put("lastOrderDate", lastDate);
            item.put("daysSinceLastOrder", daysSinceLast);
            item.put("avgIntervalDays", avgInterval);
            item.put("predictedNextOrderDate", predictedNext);
            item.put("alertLevel", alertLevel);
            items.add(item);
        }
        items.sort(Comparator
                .<Map<String, Object>, Integer>comparing(row -> alertOrder(String.valueOf(row.get("alertLevel"))))
                .thenComparing(row -> -((Number) row.get("daysSinceLastOrder")).intValue()));
        long alertCount = items.stream()
                .filter(row -> List.of("RED", "YELLOW").contains(String.valueOf(row.get("alertLevel"))))
                .count();
        return Map.of("alertCount", (int) alertCount, "items", items);
    }

    public Map<String, Object> dashboardCommandCenter(Long schoolId) {
        int attendanceThreshold = 75;
        if (schoolId == null) {
            return dashboardCommandCenterRow(
                    0L, 0L, 0,
                    null, 0L, 0L, 0L,
                    0, 0,
                    0, attendanceThreshold,
                    Map.of("catalogOrderCount", 0L, "catalogOrderTotalPaise", 0L,
                            "firefightingCount", 0L, "firefightingTotalPaise", 0L, "totalDuesPaise", 0L),
                    Map.of("alertCount", 0));
        }
        List<String> activeYears = jdbc.sql("SELECT id FROM tenant_school.academic_years WHERE active = true LIMIT 1")
                .query(String.class)
                .list();
        if (activeYears.isEmpty()) {
            return dashboardCommandCenterRow(
                    0L, 0L, 0,
                    null, 0L, 0L, 0L,
                    0, 0,
                    0, attendanceThreshold,
                    vendorDues(schoolId),
                    reorderSignals(schoolId));
        }
        String yearId = activeYears.get(0);
        long defaulterCount = count("""
                SELECT count(*)
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.academic_year_id = :yearId
                  AND s.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, yearId, schoolId);
        long totalOverdue = countAmount("""
                SELECT COALESCE(SUM(fa.net_payable - fa.paid_amount), 0)
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.academic_year_id = :yearId
                  AND s.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, yearId, schoolId);
        List<OffsetDateTime> oldestRows = jdbc.sql("""
                SELECT MIN(fa.assigned_at)
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.academic_year_id = :yearId
                  AND s.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """)
                .param("yearId", yearId)
                .param("schoolId", schoolId)
                .query(OffsetDateTime.class)
                .list();
        OffsetDateTime oldestAt = oldestRows.isEmpty() ? null : oldestRows.get(0);
        int oldestDueDays = oldestAt != null
                ? (int) Math.max(0, ChronoUnit.DAYS.between(oldestAt.toLocalDate(), LocalDate.now()))
                : 0;
        long belowThreshold = count("""
                SELECT count(*)
                FROM attendance.attendance_daily ad
                JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
                WHERE ad.attendance_date = CURRENT_DATE
                  AND ad.academic_year_id = :yearId
                  AND ss.school_id = :schoolId
                  AND ad.total_enrolled > 0
                  AND (ad.present_count * 1.0 / ad.total_enrolled) < 0.75
                """, yearId, schoolId);
        Map<String, Object> photo = dashboardPhotography(schoolId);
        long pendingReviewCount = count("""
                SELECT count(*)
                FROM student.student_review_items i
                JOIN student.student_review_campaigns c ON c.id = i.campaign_id
                WHERE c.school_id = :schoolId
                  AND c.status = 'ACTIVE'
                  AND i.status = 'PENDING'
                """, schoolId);
        return dashboardCommandCenterRow(
                defaulterCount, totalOverdue, oldestDueDays,
                (String) photo.get("eventId"),
                longValue(photo.get("collectedAmount")),
                longValue(photo.get("pendingAmount")),
                longValue(photo.get("targetAmount")),
                (int) pendingReviewCount, 0,
                (int) belowThreshold, attendanceThreshold,
                vendorDues(schoolId),
                reorderSignals(schoolId));
    }

    public Map<String, Object> lowAttendanceSections(Long schoolId, LocalDate date) {
        LocalDate effectiveDate = date == null ? LocalDate.now() : date;
        if (schoolId == null) {
            return row("date", effectiveDate, "thresholdPercent", 75, "sections", List.of());
        }
        List<String> activeYears = jdbc.sql("SELECT id FROM tenant_school.academic_years WHERE active = true LIMIT 1")
                .query(String.class)
                .list();
        if (activeYears.isEmpty()) {
            return row("date", effectiveDate, "thresholdPercent", 75, "sections", List.of());
        }
        String yearId = activeYears.get(0);
        List<Map<String, Object>> sections = jdbc.sql("""
                SELECT ad.section_id, ss.name AS section_name, sc.name AS class_name,
                       ad.present_count, ad.total_enrolled,
                       ROUND((ad.present_count * 10000.0 / ad.total_enrolled)) / 100.0 AS attendance_pct,
                       (
                         SELECT count(*)
                         FROM student.students s
                         WHERE s.section_id = ad.section_id
                           AND s.school_id = :schoolId
                           AND (s.attendance_percent IS NULL OR s.attendance_percent < 75)
                       ) AS students_below_threshold
                FROM attendance.attendance_daily ad
                JOIN tenant_school.school_sections ss ON ss.id = ad.section_id
                JOIN tenant_school.school_classes sc ON sc.id = ad.school_class_id
                WHERE ad.attendance_date = :date
                  AND ad.academic_year_id = :yearId
                  AND ss.school_id = :schoolId
                  AND ad.total_enrolled > 0
                  AND (ad.present_count * 1.0 / ad.total_enrolled) < 0.75
                ORDER BY (ad.present_count * 1.0 / ad.total_enrolled) ASC
                """)
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
                SELECT s.id, s.full_name, s.admission_no, sc.name AS class_name, ss.name AS section_name,
                       s.father_name, s.father_contact, s.attendance_percent,
                       (
                         SELECT MAX(nl.sent_at)
                         FROM notification.notification_logs nl
                         WHERE nl.school_id = :schoolId
                           AND nl.student_id = s.id
                           AND nl.notification_type = 'LOW_ATTENDANCE_MEETING_INVITE'
                       ) AS last_invite_sent_at
                FROM student.students s
                JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                WHERE s.school_id = :schoolId
                  AND s.section_id = :sectionId
                  AND (s.attendance_percent IS NULL OR s.attendance_percent < 75)
                ORDER BY s.full_name ASC
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
                        "fatherContact", rs.getString("father_contact"),
                        "attendancePercent", rs.getObject("attendance_percent"),
                        "lastInviteSentAt", rs.getObject("last_invite_sent_at", OffsetDateTime.class)))
                .list();
    }

    public Map<String, Object> feeDefaulters(Long schoolId, String classId, String sectionId,
                                             Integer daysOverdueMin, String reminderStatus,
                                             int page, int size) {
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, 200));
        if (schoolId == null) {
            return feeDefaulterRow(0L, 0L, 0, List.of(), pageNumber, pageSize, 0L);
        }
        List<String> activeYears = jdbc.sql("SELECT id FROM tenant_school.academic_years WHERE active = true LIMIT 1")
                .query(String.class)
                .list();
        if (activeYears.isEmpty()) {
            return feeDefaulterRow(0L, 0L, 0, List.of(), pageNumber, pageSize, 0L);
        }
        String yearId = activeYears.get(0);
        StringBuilder filter = new StringBuilder("""
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                JOIN tenant_school.school_classes sc ON sc.id = s.class_id
                JOIN tenant_school.school_sections ss ON ss.id = s.section_id
                LEFT JOIN LATERAL (
                    SELECT sent_at, status
                    FROM notification.notification_logs
                    WHERE student_id = s.id
                      AND notification_type = 'FEE_OVERDUE'
                    ORDER BY sent_at DESC NULLS LAST
                    LIMIT 1
                ) nl ON true
                WHERE fa.academic_year_id = :yearId
                  AND s.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """);
        if (classId != null && !classId.isBlank()) filter.append(" AND s.class_id = :classId\n");
        if (sectionId != null && !sectionId.isBlank()) filter.append(" AND s.section_id = :sectionId\n");

        var countSpec = jdbc.sql("SELECT count(*) " + filter)
                .param("yearId", yearId)
                .param("schoolId", schoolId);
        var itemSpec = jdbc.sql("""
                SELECT s.id AS student_id, s.full_name, s.admission_no,
                       sc.name AS class_name, ss.name AS section_name,
                       s.father_name, s.father_contact,
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
                            "parentPhone", rs.getString("father_contact"),
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
        long totalOverdueAmount = countAmount("""
                SELECT COALESCE(SUM(fa.net_payable - fa.paid_amount), 0)
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.academic_year_id = :yearId
                  AND s.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, yearId, schoolId);
        List<OffsetDateTime> oldestRows = jdbc.sql("""
                SELECT MIN(fa.assigned_at)
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.academic_year_id = :yearId
                  AND s.school_id = :schoolId
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

    private Map<String, Object> dashboardPhotography(Long schoolId) {
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
                """, String.valueOf(event.get("eventId")));
        long target = longValue(event.get("targetAmount"));
        return row(
                "eventId", event.get("eventId"),
                "collectedAmount", collected,
                "pendingAmount", Math.max(0, target - collected),
                "targetAmount", target);
    }

    public Map<String, Object> commandCenterSummary(Long schoolId, boolean platform) {
        if (platform) {
            long totalSchools = count("SELECT count(*) FROM tenant_school.schools");
            long totalActions = count("SELECT count(*) FROM reporting.command_center_actions WHERE status = 'OPEN'");
            List<Map<String, Object>> kpis = List.of(
                    kpi("active_schools", "Active Schools", String.valueOf(totalSchools), "platform-wide", "success", "schools", "active"),
                    kpi("open_actions", "Open Actions", String.valueOf(totalActions), "across all schools", totalActions > 10 ? "warning" : "success", "command-centre", "open"),
                    kpi("platform_orders", "Orders", "Active", "all schools", "info", "orders", "all"),
                    kpi("firefighting_sla", "Firefighting SLA", "Monitoring", "platform-wide", "info", "firefighting", "all"));
            List<Map<String, Object>> alerts = jdbc.sql("""
                    SELECT title, module, urgency
                    FROM reporting.command_center_actions
                    WHERE status = 'OPEN' AND upper(urgency) = 'CRITICAL'
                    ORDER BY created_at DESC
                    LIMIT 3
                    """)
                    .query((rs, rowNum) -> row(
                            "title", rs.getString("title"),
                            "module", rs.getString("module"),
                            "severity", "critical"))
                    .list();
            return row("schoolId", null, "scope", "PLATFORM", "generatedAt", OffsetDateTime.now(),
                    "kpis", kpis, "criticalAlerts", alerts);
        }
        if (schoolId == null) {
            return row("schoolId", null, "scope", "SCHOOL", "generatedAt", OffsetDateTime.now(),
                    "kpis", List.of(), "criticalAlerts", List.of());
        }
        long feesPaid = countAmount("""
                SELECT COALESCE(SUM(p.amount), 0)
                FROM fee.payment_records p
                JOIN student.students s ON s.id = p.student_id
                WHERE s.school_id = :schoolId
                """, schoolId);
        long overdueCount = count("""
                SELECT count(*)
                FROM fee.fee_assignments fa
                JOIN student.students s ON s.id = fa.student_id
                WHERE fa.academic_year_id = 'ay_2025_26'
                  AND s.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, schoolId);
        long openFF = count("""
                SELECT count(*)
                FROM firefighting.firefighting_requests
                WHERE school_id = :schoolId
                  AND status <> 'FULFILLED'
                """, schoolId);
        long pendingFFApprovals = count("""
                SELECT count(*)
                FROM firefighting.firefighting_requests
                WHERE school_id = :schoolId
                  AND status IN ('AWAITING_PRINCIPAL', 'AWAITING_BURSAR')
                """, schoolId);
        long activeOrders = count("""
                SELECT count(*)
                FROM catalog.catalog_orders
                WHERE school_id = :schoolId
                  AND status IN ('SUBMITTED', 'AWAITING_APPROVAL', 'IN_TRANSIT',
                                 'AWAITING_DESIGN_APPROVAL', 'DESIGN_APPROVED', 'PROCESSING')
                """, schoolId);
        long attendanceSections = count("""
                SELECT count(*)
                FROM attendance.attendance_daily
                WHERE attendance_date = CURRENT_DATE
                  AND academic_year_id = 'ay_2025_26'
                """);
        List<Map<String, Object>> kpis = List.of(
                kpi("fees_collected", "Fees Collected", formatLakh(feesPaid), overdueCount + " students overdue",
                        overdueCount > 20 ? "warning" : "success", "fees", "collected"),
                kpi("attendance_today", "Attendance Today", attendanceSections + " sections",
                        attendanceSections > 0 ? "submitted today" : "pending",
                        attendanceSections > 0 ? "success" : "warning", "attendance", "today"),
                kpi("open_firefighting", "Open Firefighting", String.valueOf(openFF), pendingFFApprovals + " need approval",
                        pendingFFApprovals > 0 ? "critical" : "success", "firefighting", "urgent"),
                kpi("orders_in_progress", "Orders In Progress", String.valueOf(activeOrders), activeOrders + " active",
                        activeOrders > 5 ? "warning" : "success", "orders", "active"));
        List<Map<String, Object>> alerts = jdbc.sql("""
                SELECT title, module, urgency
                FROM reporting.command_center_actions
                WHERE school_id = :schoolId
                  AND status = 'OPEN'
                  AND upper(urgency) IN ('CRITICAL', 'HIGH')
                ORDER BY created_at DESC
                LIMIT 3
                """)
                .param("schoolId", schoolId)
                .query((rs, rowNum) -> row(
                        "title", rs.getString("title"),
                        "module", rs.getString("module"),
                        "severity", rs.getString("urgency").toLowerCase()))
                .list();
        return row("schoolId", schoolId, "scope", "SCHOOL", "generatedAt", OffsetDateTime.now(),
                "kpis", kpis, "criticalAlerts", alerts);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long count(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    private long count(String sql, String yearId, Long schoolId) {
        return jdbc.sql(sql).param("yearId", yearId).param("schoolId", schoolId).query(Long.class).single();
    }

    private long countAmount(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private long countAmount(String sql, Long schoolId) {
        return jdbc.sql(sql).param("schoolId", schoolId).query(Long.class).single();
    }

    private long countAmount(String sql, String yearId, Long schoolId) {
        return jdbc.sql(sql).param("yearId", yearId).param("schoolId", schoolId).query(Long.class).single();
    }

    private long countAmount(String sql, String eventId) {
        return jdbc.sql(sql).param("eventId", eventId).query(Long.class).single();
    }

    private static long longValue(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return 0L;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static Map<String, Object> row(Object... values) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private static int alertOrder(String level) {
        return switch (level) {
            case "RED" -> 0;
            case "YELLOW" -> 1;
            default -> 2;
        };
    }

    private static Map<String, Object> kpi(String key, String label, String value, String delta,
                                           String status, String drilldownTarget, String drilldownFilter) {
        return row("key", key, "label", label, "value", value, "delta", delta, "status", status,
                "drilldownTarget", drilldownTarget, "drilldownFilter", drilldownFilter);
    }

    private static Map<String, Object> dashboardCommandCenterRow(
            long defaulterCount,
            long totalOverdueAmountPaise,
            int oldestDueDays,
            String eventId,
            long collectedAmount,
            long pendingAmount,
            long targetAmount,
            int pendingReviewCount,
            int longAbsenceCount,
            int sectionsBelowThresholdCount,
            int thresholdPercent,
            Map<String, Object> vendorDues,
            Map<String, Object> reorderSignals) {
        return row(
                "fees", row(
                        "defaulterCount", defaulterCount,
                        "totalOverdueAmountPaise", totalOverdueAmountPaise,
                        "oldestDueDays", oldestDueDays),
                "photography", row(
                        "eventId", eventId,
                        "collectedAmount", collectedAmount,
                        "pendingAmount", pendingAmount,
                        "targetAmount", targetAmount),
                "lifecycle", row(
                        "pendingReviewCount", pendingReviewCount,
                        "longAbsenceCount", longAbsenceCount),
                "attendance", row(
                        "sectionsBelowThresholdCount", sectionsBelowThresholdCount,
                        "thresholdPercent", thresholdPercent),
                "vendorDues", row(
                        "catalogOrderCount", longValue(vendorDues.get("catalogOrderCount")),
                        "catalogOrderTotalPaise", longValue(vendorDues.get("catalogOrderTotalPaise")),
                        "firefightingCount", longValue(vendorDues.get("firefightingCount")),
                        "firefightingTotalPaise", longValue(vendorDues.get("firefightingTotalPaise")),
                        "totalDuesPaise", longValue(vendorDues.get("totalDuesPaise"))),
                "reorderSignals", row(
                        "alertCount", (int) longValue(reorderSignals.get("alertCount"))));
    }

    private static Map<String, Object> feeDefaulterRow(long totalDefaulters,
                                                       long totalOverdueAmount,
                                                       int oldestDueDays,
                                                       List<Map<String, Object>> items,
                                                       int page,
                                                       int size,
                                                       long totalElements) {
        return row(
                "totalDefaulters", totalDefaulters,
                "totalOverdueAmount", totalOverdueAmount,
                "oldestDueDays", oldestDueDays,
                "items", items,
                "page", page,
                "size", size,
                "totalElements", totalElements);
    }

    private static String reminderStatus(String rawStatus, OffsetDateTime sentAt) {
        if (sentAt == null && (rawStatus == null || rawStatus.isBlank())) {
            return "NOT_SENT";
        }
        if ("SENT".equalsIgnoreCase(rawStatus)) return "SENT";
        if ("FAILED".equalsIgnoreCase(rawStatus)) return "FAILED";
        return "PENDING";
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

    private static String formatLakh(long paise) {
        double rupees = paise / 100.0;
        if (rupees >= 100000) return String.format("₹%.1fL", rupees / 100000);
        if (rupees >= 1000) return String.format("₹%.0fK", rupees / 1000);
        return String.format("₹%.0f", rupees);
    }

    public record CommandCenterFeedRow(UUID id, Long schoolId, String module, String eventType, String title,
                                       String message, String severity, String entityType, String entityId,
                                       Long actorUserId, OffsetDateTime createdAt) {}

    public record CommandCenterActionRow(UUID id, Long schoolId, String module, String urgency, Integer confidence,
                                         String title, String reason, String impact, String currentState,
                                         String targetState, String ctaLabel, String status, String sourceType,
                                         String sourceId, Long acceptedBy, OffsetDateTime acceptedAt,
                                         Long dismissedBy, String dismissedReason, OffsetDateTime dismissedAt,
                                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record InvoiceRow(String id, String orderRef, String school, Long schoolId, String description,
                             Integer qty, Long rate, Long amount, Long gstAmount, Long total, String status,
                             String issuedAt, String dueAt, String notes, OffsetDateTime createdAt) {}

    public record AcademicEventRow(String id, Long schoolId, String academicYearId, String title, String eventType,
                                   LocalDate eventDate, Long totalBudget, Long schoolContribution,
                                   Long studentContributionTarget, String status, Long createdBy,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record EventContributionRow(String id, String eventId, Long studentId, Long schoolId, Long expectedAmount,
                                       Long paidAmount, String status, OffsetDateTime lastReminderSentAt,
                                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record BroadcastRow(UUID id, Long schoolId, String module, String title, String message,
                               String audienceType, String channels, String status, OffsetDateTime scheduledAt,
                               Long approvedBy, OffsetDateTime approvedAt, Long sentBy, OffsetDateTime sentAt,
                               Long createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
