package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Optional;

final class AcademicCalendar {

    private AcademicCalendar() {
    }

    static FinancialYear currentFinancialYear() {
        return currentFinancialYear(LocalDate.now());
    }

    static FinancialYear currentFinancialYear(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return new FinancialYear(startYear, startYear + 1);
    }

    static String currentAcademicYearId() {
        return currentFinancialYear().academicYearId();
    }

    static AcademicYear activeOrCurrentAcademicYear(JdbcClient jdbc) {
        return activeAcademicYear(jdbc)
                .orElseGet(() -> {
                    FinancialYear current = currentFinancialYear();
                    return new AcademicYear(current.academicYearId(), current.label());
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
