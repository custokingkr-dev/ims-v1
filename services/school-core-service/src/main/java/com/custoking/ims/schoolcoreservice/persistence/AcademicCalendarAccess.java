package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Narrow public facade for school-aware academic-year resolution used by other
 * school-core modules.
 */
public final class AcademicCalendarAccess {
    private AcademicCalendarAccess() {
    }

    public static AcademicYear currentAcademicYear(JdbcClient jdbc, Long schoolId) {
        var year = AcademicCalendar.currentAcademicYear(jdbc, schoolId);
        return new AcademicYear(year.id(), year.label());
    }

    public record AcademicYear(String id, String label) {
    }
}
