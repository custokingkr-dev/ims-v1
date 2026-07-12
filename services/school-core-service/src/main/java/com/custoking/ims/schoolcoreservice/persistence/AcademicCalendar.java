package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Optional;

final class AcademicCalendar {

    static final int DEFAULT_ACADEMIC_YEAR_START_MONTH = 4;
    static final int DEFAULT_FINANCIAL_YEAR_START_MONTH = 4;

    private AcademicCalendar() {
    }

    static FinancialYear currentFinancialYear() {
        return currentFinancialYear(LocalDate.now());
    }

    static FinancialYear currentFinancialYear(LocalDate date) {
        return currentFinancialYear(date, DEFAULT_FINANCIAL_YEAR_START_MONTH);
    }

    static FinancialYear currentFinancialYear(LocalDate date, int startMonth) {
        int normalizedStartMonth = normalizeMonth(startMonth);
        int startYear = date.getMonthValue() >= normalizedStartMonth ? date.getYear() : date.getYear() - 1;
        return new FinancialYear(startYear, startYear + 1);
    }

    static FinancialYear currentFinancialYear(JdbcClient jdbc, Long schoolId) {
        return currentFinancialYear(LocalDate.now(), financialYearStartMonth(jdbc, schoolId));
    }

    static String currentAcademicYearId() {
        return currentAcademicYear(DEFAULT_ACADEMIC_YEAR_START_MONTH).id();
    }

    static AcademicYear currentAcademicYear(int startMonth) {
        return currentAcademicYear(LocalDate.now(), startMonth);
    }

    static AcademicYear currentAcademicYear(LocalDate date, int startMonth) {
        int normalizedStartMonth = normalizeMonth(startMonth);
        int startYear = date.getMonthValue() >= normalizedStartMonth ? date.getYear() : date.getYear() - 1;
        int endYear = startYear + 1;
        String suffix = String.valueOf(endYear).substring(2);
        return new AcademicYear("ay_" + startYear + "_" + suffix, startYear + "-" + suffix);
    }

    static AcademicYear currentAcademicYear(JdbcClient jdbc, Long schoolId) {
        return ensureAcademicYear(jdbc, currentAcademicYear(academicYearStartMonth(jdbc, schoolId)));
    }

    static String currentAcademicYearId(JdbcClient jdbc, Long schoolId) {
        return currentAcademicYear(jdbc, schoolId).id();
    }

    static AcademicYear activeOrCurrentAcademicYear(JdbcClient jdbc) {
        return activeAcademicYear(jdbc)
                .orElseGet(() -> {
                    AcademicYear current = currentAcademicYear(DEFAULT_ACADEMIC_YEAR_START_MONTH);
                    return ensureAcademicYear(jdbc, current);
                });
    }

    static String activeOrCurrentAcademicYearId(JdbcClient jdbc) {
        return activeOrCurrentAcademicYear(jdbc).id();
    }

    static Optional<AcademicYear> activeAcademicYear(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT id, label
                FROM tenant_school.academic_years
                WHERE active = true
                ORDER BY id DESC
                LIMIT 1
                """)
                .query((rs, rowNum) -> new AcademicYear(rs.getString("id"), rs.getString("label")))
                .optional();
    }

    static Optional<AcademicYear> academicYear(JdbcClient jdbc, String academicYearId) {
        if (academicYearId == null || academicYearId.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT id, label
                FROM tenant_school.academic_years
                WHERE id = :academicYearId
                LIMIT 1
                """)
                .param("academicYearId", academicYearId)
                .query((rs, rowNum) -> new AcademicYear(rs.getString("id"), rs.getString("label")))
                .optional();
    }

    static AcademicYear ensureAcademicYear(JdbcClient jdbc, AcademicYear year) {
        int updated = jdbc.sql("""
                UPDATE tenant_school.academic_years
                SET label = :label
                WHERE id = :id
                """)
                .param("id", year.id())
                .param("label", year.label())
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("""
                        INSERT INTO tenant_school.academic_years (id, label, active)
                        VALUES (:id, :label, false)
                        """)
                        .param("id", year.id())
                        .param("label", year.label())
                        .update();
            } catch (DataIntegrityViolationException ignored) {
                jdbc.sql("""
                        UPDATE tenant_school.academic_years
                        SET label = :label
                        WHERE id = :id
                        """)
                        .param("id", year.id())
                        .param("label", year.label())
                        .update();
            }
        }
        return year;
    }

    static int academicYearStartMonth(JdbcClient jdbc, Long schoolId) {
        if (schoolId == null) {
            return DEFAULT_ACADEMIC_YEAR_START_MONTH;
        }
        return jdbc.sql("""
                SELECT academic_year_start_month
                FROM tenant_school.schools
                WHERE id = :schoolId
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .map(AcademicCalendar::normalizeMonth)
                .orElse(DEFAULT_ACADEMIC_YEAR_START_MONTH);
    }

    static int financialYearStartMonth(JdbcClient jdbc, Long schoolId) {
        if (schoolId == null) {
            return DEFAULT_FINANCIAL_YEAR_START_MONTH;
        }
        return jdbc.sql("""
                SELECT financial_year_start_month
                FROM tenant_school.schools
                WHERE id = :schoolId
                LIMIT 1
                """)
                .param("schoolId", schoolId)
                .query(Integer.class)
                .optional()
                .map(AcademicCalendar::normalizeMonth)
                .orElse(DEFAULT_FINANCIAL_YEAR_START_MONTH);
    }

    private static int normalizeMonth(int month) {
        return month >= 1 && month <= 12 ? month : DEFAULT_ACADEMIC_YEAR_START_MONTH;
    }

    record AcademicYear(String id, String label) {
    }

    record FinancialYear(int startYear, int endYear) {
        String label() {
            return startYear + "-" + String.valueOf(endYear).substring(2);
        }

        String academicYearId() {
            return "ay_" + startYear + "_" + String.valueOf(endYear).substring(2);
        }
    }
}
