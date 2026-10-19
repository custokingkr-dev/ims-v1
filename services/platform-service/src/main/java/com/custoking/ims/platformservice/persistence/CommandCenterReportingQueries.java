package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** Owns commandCenter reporting queries; tenant filters, SQL and response shapes are preserved. */
@Repository
public class CommandCenterReportingQueries {
    private final JdbcClient jdbc;
    private final ReportingSchoolContext schools;
    private final ReportingScalarQueries scalars;
    private final ProcurementReportingQueries procurement;
    private final PhotographyReportingQueries photography;

    public CommandCenterReportingQueries(JdbcClient jdbc, ReportingSchoolContext schools, ReportingScalarQueries scalars, ProcurementReportingQueries procurement, PhotographyReportingQueries photography) {
        this.jdbc = jdbc;
        this.schools = schools;
        this.scalars = scalars;
        this.procurement = procurement;
        this.photography = photography;
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
        String yearId = schools.currentAcademicYearId(schoolId);
        if (yearId == null) {
            return dashboardCommandCenterRow(
                    0L, 0L, 0,
                    null, 0L, 0L, 0L,
                    0, 0,
                    0, attendanceThreshold,
                    procurement.vendorDues(schoolId),
                    procurement.reorderSignals(schoolId));
        }
        long defaulterCount = scalars.count("""
                SELECT count(*)
                FROM reporting.fact_fee_assignment fa
                WHERE fa.academic_year_id = :yearId
                  AND fa.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, yearId, schoolId);
        long totalOverdue = scalars.countAmount("""
                SELECT COALESCE(SUM(fa.net_payable - fa.paid_amount), 0)
                FROM reporting.fact_fee_assignment fa
                WHERE fa.academic_year_id = :yearId
                  AND fa.school_id = :schoolId
                  AND fa.net_payable > fa.paid_amount
                """, yearId, schoolId);
        List<OffsetDateTime> oldestRows = jdbc.sql("""
                SELECT MIN(fa.assigned_at)
                FROM reporting.fact_fee_assignment fa
                WHERE fa.academic_year_id = :yearId
                  AND fa.school_id = :schoolId
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
        long belowThreshold = scalars.count("""
                SELECT count(*)
                FROM reporting.fact_attendance_daily ad
                WHERE ad.attendance_date = CURRENT_DATE
                  AND ad.academic_year_id = :yearId
                  AND ad.school_id = :schoolId
                  AND ad.total_enrolled > 0
                  AND (ad.present_count * 1.0 / ad.total_enrolled) < 0.75
                """, yearId, schoolId);
        Map<String, Object> photo = photography.dashboardPhotography(schoolId);
        // pendingReviewCount counts only ACTIVE campaigns' pending items. campaign_status is
        // projected from student-review-campaign.completed.v1 (reporting V21), so completed
        // campaigns' still-PENDING items no longer inflate this KPI.
        long pendingReviewCount = scalars.count("""
                SELECT count(*)
                FROM reporting.fact_student_review_item
                WHERE school_id = :schoolId
                  AND status = 'PENDING'
                  AND campaign_status = 'ACTIVE'
                """, schoolId);
        return dashboardCommandCenterRow(
                defaulterCount, totalOverdue, oldestDueDays,
                (String) photo.get("eventId"),
                longValue(photo.get("collectedAmount")),
                longValue(photo.get("pendingAmount")),
                longValue(photo.get("targetAmount")),
                (int) pendingReviewCount, 0,
                (int) belowThreshold, attendanceThreshold,
                procurement.vendorDues(schoolId),
                procurement.reorderSignals(schoolId));
    }

    public Map<String, Object> commandCenterSummary(Long schoolId, boolean platform) {
        if (platform) {
            long totalSchools = scalars.count("SELECT count(*) FROM reporting.dim_school");
            long totalActions = scalars.count("SELECT count(*) FROM reporting.command_center_actions WHERE status = 'OPEN'");
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
        String yearId = schools.currentAcademicYearId(schoolId);
        long feesPaid = yearId == null ? 0L : scalars.countAmount("""
                SELECT COALESCE(SUM(p.amount), 0)
                FROM reporting.fact_payment p
                JOIN reporting.fact_fee_assignment fa ON fa.id = p.assignment_id
                WHERE p.school_id = :schoolId
                  AND fa.academic_year_id = :yearId
                """, yearId, schoolId);
        long overdueCount = yearId == null ? 0L : scalars.count("""
                SELECT count(*)
                FROM reporting.fact_fee_assignment
                WHERE academic_year_id = :yearId
                  AND school_id = :schoolId
                  AND net_payable > paid_amount
                """, yearId, schoolId);
        long openFF = scalars.count("""
                SELECT count(*)
                FROM reporting.fact_firefighting_request
                WHERE school_id = :schoolId
                  AND status <> 'FULFILLED'
                """, schoolId);
        long pendingFFApprovals = scalars.count("""
                SELECT count(*)
                FROM reporting.fact_firefighting_request
                WHERE school_id = :schoolId
                  AND status IN ('AWAITING_PRINCIPAL', 'AWAITING_BURSAR')
                """, schoolId);
        long activeOrders = scalars.count("""
                SELECT count(*)
                FROM reporting.fact_catalog_order
                WHERE school_id = :schoolId
                  AND (status IN ('SUBMITTED', 'AWAITING_APPROVAL', 'IN_TRANSIT',
                                  'AWAITING_DESIGN_APPROVAL', 'DESIGN_APPROVED', 'PROCESSING',
                                  'DESIGN_APPROVAL', 'DESIGN_APPROVED_PROCESSING')
                       -- A structured notebook order's status is its tracker step, so it is in progress until the tracker ends.
                       OR (UPPER(category) = 'NOTEBOOKS' AND form_version >= 2
                           AND status NOT IN ('DRAFT', 'CANCELLED', 'COMPLETED')))
                """, schoolId);
        long attendanceSections = yearId == null ? 0L : scalars.count("""
                SELECT count(*)
                FROM reporting.fact_attendance_daily
                WHERE attendance_date = CURRENT_DATE
                  AND academic_year_id = :yearId
                  AND school_id = :schoolId
                """, yearId, schoolId);
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
}
