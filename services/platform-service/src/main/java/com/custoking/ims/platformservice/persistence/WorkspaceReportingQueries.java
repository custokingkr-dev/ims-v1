package com.custoking.ims.platformservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.custoking.ims.platformservice.persistence.ReportingPresentation.*;

/** Owns workspace reporting queries; tenant filters, SQL and response shapes are preserved. */
@Repository
public class WorkspaceReportingQueries {
    private final JdbcClient jdbc;
    private final ReportingSchoolContext schools;
    private final ReportingScalarQueries scalars;

    public WorkspaceReportingQueries(JdbcClient jdbc, ReportingSchoolContext schools, ReportingScalarQueries scalars) {
        this.jdbc = jdbc;
        this.schools = schools;
        this.scalars = scalars;
    }

    public Map<String, Object> summary(Long schoolId) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        if (schoolId == null) {
            result.put("openActions", scalars.count("SELECT count(*) FROM reporting.command_center_actions WHERE status = 'OPEN'"));
            result.put("feedItems", scalars.count("SELECT count(*) FROM reporting.command_center_feed"));
            result.put("invoices", scalars.count("SELECT count(*) FROM reporting.billing_invoice_read"));
            result.put("academicEvents", scalars.count("SELECT count(*) FROM reporting.academic_events"));
            result.put("broadcasts", scalars.count("SELECT count(*) FROM notification.notification_broadcasts"));
            addWorkspaceDashboardSummary(result, null);
            return result;
        }
        result.put("openActions", scalars.count("SELECT count(*) FROM reporting.command_center_actions WHERE status = 'OPEN' AND school_id = :schoolId", schoolId));
        result.put("feedItems", scalars.count("SELECT count(*) FROM reporting.command_center_feed WHERE school_id = :schoolId", schoolId));
        result.put("invoices", scalars.count("SELECT count(*) FROM reporting.billing_invoice_read WHERE school_id = :schoolId", schoolId));
        result.put("academicEvents", scalars.count("SELECT count(*) FROM reporting.academic_events WHERE school_id = :schoolId", schoolId));
        result.put("broadcasts", scalars.count("SELECT count(*) FROM notification.notification_broadcasts WHERE school_id = :schoolId", schoolId));
        addWorkspaceDashboardSummary(result, schoolId);
        return result;
    }

    public Map<String, Object> workspaceDashboardSummary(Long schoolId) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        addWorkspaceDashboardSummary(result, schoolId);
        return result;
    }

    private void addWorkspaceDashboardSummary(Map<String, Object> result, Long schoolId) {
        String yearId = schools.currentAcademicYearId(schoolId);
        int academicYearStartMonth = schools.schoolYearStartMonth(schoolId, "academic_year_start_month");
        int financialYearStartMonth = schools.schoolYearStartMonth(schoolId, "financial_year_start_month");
        Map<String, String> localization = schools.schoolLocalization(schoolId);
        String schoolName = schoolId == null
                ? "All Schools"
                : jdbc.sql("SELECT name FROM reporting.dim_school WHERE id = :schoolId")
                    .param("schoolId", schoolId)
                    .query(String.class)
                    .optional()
                    .orElse("School");
        String academicYearLabel = yearId == null
                ? "No active academic year"
                : jdbc.sql("SELECT label FROM reporting.dim_academic_year WHERE id = :yearId")
                    .param("yearId", yearId)
                    .query(String.class)
                    .optional()
                    .orElse(academicYearLabel(yearId));
        long students = schoolId == null
                ? scalars.count("SELECT count(*) FROM reporting.dim_student WHERE active IS DISTINCT FROM false")
                : scalars.count("SELECT count(*) FROM reporting.dim_student WHERE school_id = :schoolId AND active IS DISTINCT FROM false", schoolId);
        long sections = schoolId == null
                ? scalars.count("SELECT count(*) FROM reporting.dim_section WHERE active IS DISTINCT FROM false")
                : scalars.count("""
                        SELECT count(DISTINCT section_id)
                        FROM reporting.dim_student
                        WHERE school_id = :schoolId
                          AND active IS DISTINCT FROM false
                          AND section_id IS NOT NULL
                        """, schoolId);

        long attendancePresent = 0L;
        long attendanceTotal = 0L;
        long attendanceSubmittedSections = 0L;
        long feeCollected = 0L;
        long feeTarget = 0L;
        long feeOverdueCount = 0L;
        long firefightingActive = schoolId == null
                ? scalars.count("SELECT count(*) FROM reporting.fact_firefighting_request WHERE status <> 'FULFILLED'")
                : scalars.count("SELECT count(*) FROM reporting.fact_firefighting_request WHERE school_id = :schoolId AND status <> 'FULFILLED'", schoolId);
        long pendingApprovals = schoolId == null
                ? scalars.count("SELECT count(*) FROM reporting.fact_firefighting_request WHERE status IN ('AWAITING_PRINCIPAL', 'AWAITING_BURSAR')")
                : scalars.count("SELECT count(*) FROM reporting.fact_firefighting_request WHERE school_id = :schoolId AND status IN ('AWAITING_PRINCIPAL', 'AWAITING_BURSAR')", schoolId);

        if (yearId != null) {
            if (schoolId == null) {
                attendancePresent = scalars.countAmount("""
                        SELECT COALESCE(SUM(present_count), 0)
                        FROM reporting.fact_attendance_daily
                        WHERE attendance_date = CURRENT_DATE
                          AND academic_year_id = :yearId
                        """, "yearId", yearId);
                attendanceTotal = scalars.countAmount("""
                        SELECT COALESCE(SUM(total_enrolled), 0)
                        FROM reporting.fact_attendance_daily
                        WHERE attendance_date = CURRENT_DATE
                          AND academic_year_id = :yearId
                        """, "yearId", yearId);
                attendanceSubmittedSections = scalars.count("""
                        SELECT count(DISTINCT section_id)
                        FROM reporting.fact_attendance_daily
                        WHERE attendance_date = CURRENT_DATE
                          AND academic_year_id = :yearId
                        """, yearId);
                feeCollected = scalars.countAmount("""
                        SELECT COALESCE(SUM(p.amount), 0)
                        FROM reporting.fact_payment p
                        JOIN reporting.fact_fee_assignment fa ON fa.id = p.assignment_id
                        WHERE fa.academic_year_id = :yearId
                        """, "yearId", yearId);
                feeTarget = scalars.countAmount("""
                        SELECT COALESCE(SUM(net_payable), 0)
                        FROM reporting.fact_fee_assignment
                        WHERE academic_year_id = :yearId
                        """, "yearId", yearId);
                feeOverdueCount = scalars.count("""
                        SELECT count(*)
                        FROM reporting.fact_fee_assignment
                        WHERE academic_year_id = :yearId
                          AND net_payable > paid_amount
                        """, yearId);
            } else {
                attendancePresent = scalars.countAmount("""
                        SELECT COALESCE(SUM(present_count), 0)
                        FROM reporting.fact_attendance_daily
                        WHERE attendance_date = CURRENT_DATE
                          AND academic_year_id = :yearId
                          AND school_id = :schoolId
                        """, yearId, schoolId);
                attendanceTotal = scalars.countAmount("""
                        SELECT COALESCE(SUM(total_enrolled), 0)
                        FROM reporting.fact_attendance_daily
                        WHERE attendance_date = CURRENT_DATE
                          AND academic_year_id = :yearId
                          AND school_id = :schoolId
                        """, yearId, schoolId);
                attendanceSubmittedSections = scalars.count("""
                        SELECT count(DISTINCT section_id)
                        FROM reporting.fact_attendance_daily
                        WHERE attendance_date = CURRENT_DATE
                          AND academic_year_id = :yearId
                          AND school_id = :schoolId
                        """, yearId, schoolId);
                feeCollected = scalars.countAmount("""
                        SELECT COALESCE(SUM(p.amount), 0)
                        FROM reporting.fact_payment p
                        JOIN reporting.fact_fee_assignment fa ON fa.id = p.assignment_id
                        WHERE fa.academic_year_id = :yearId
                          AND p.school_id = :schoolId
                        """, yearId, schoolId);
                feeTarget = scalars.countAmount("""
                        SELECT COALESCE(SUM(net_payable), 0)
                        FROM reporting.fact_fee_assignment
                        WHERE academic_year_id = :yearId
                          AND school_id = :schoolId
                        """, yearId, schoolId);
                feeOverdueCount = scalars.count("""
                        SELECT count(*)
                        FROM reporting.fact_fee_assignment
                        WHERE academic_year_id = :yearId
                          AND school_id = :schoolId
                          AND net_payable > paid_amount
                        """, yearId, schoolId);
            }
        }

        long attendancePercent = attendanceTotal == 0 ? 0 : Math.round(attendancePresent * 100.0 / attendanceTotal);
        String attendanceState = attendanceSubmittedSections == 0
                ? "NOT_STARTED"
                : attendanceSubmittedSections < sections ? "PARTIAL" : "SUBMITTED";
        result.put("schoolName", schoolName);
        result.put("schoolMeta", academicYearLabel);
        result.put("academicYearStartMonth", academicYearStartMonth);
        result.put("financialYearStartMonth", financialYearStartMonth);
        result.putAll(localization);
        result.put("students", students);
        result.put("sections", sections);
        result.put("attendancePercent", attendancePercent);
        result.put("attendancePresent", attendancePresent);
        result.put("attendanceSubmittedSections", attendanceSubmittedSections);
        result.put("attendanceState", attendanceState);
        result.put("feeCollectedPaise", feeCollected);
        result.put("feeTargetPaise", feeTarget);
        result.put("feesConfigured", feeTarget > 0);
        result.put("feeCollectedLakh", lakh(feeCollected));
        result.put("feeTargetLakh", lakh(feeTarget));
        result.put("feeOverdueCount", feeOverdueCount);
        result.put("firefightingActive", firefightingActive);
        result.put("pendingApprovals", pendingApprovals);
    }
}
