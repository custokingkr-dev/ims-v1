package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AcademicCalendarTest {

    @Test
    void financialYearStartsInApril() {
        AcademicCalendar.FinancialYear year = AcademicCalendar.currentFinancialYear(LocalDate.of(2026, 4, 1));

        assertThat(year.label()).isEqualTo("2026-27");
        assertThat(year.academicYearId()).isEqualTo("ay_2026_27");
    }

    @Test
    void januaryThroughMarchBelongToPreviousFinancialYear() {
        AcademicCalendar.FinancialYear year = AcademicCalendar.currentFinancialYear(LocalDate.of(2027, 3, 31));

        assertThat(year.label()).isEqualTo("2026-27");
        assertThat(year.academicYearId()).isEqualTo("ay_2026_27");
    }

    @Test
    void financialYearCanStartInConfiguredMonth() {
        AcademicCalendar.FinancialYear may = AcademicCalendar.currentFinancialYear(LocalDate.of(2026, 5, 31), 6);
        AcademicCalendar.FinancialYear june = AcademicCalendar.currentFinancialYear(LocalDate.of(2026, 6, 1), 6);

        assertThat(may.label()).isEqualTo("2025-26");
        assertThat(june.label()).isEqualTo("2026-27");
    }

    @Test
    void academicYearCanStartInConfiguredMonth() {
        AcademicCalendar.AcademicYear may = AcademicCalendar.currentAcademicYear(LocalDate.of(2026, 5, 31), 6);
        AcademicCalendar.AcademicYear june = AcademicCalendar.currentAcademicYear(LocalDate.of(2026, 6, 1), 6);

        assertThat(may.label()).isEqualTo("2025-26");
        assertThat(may.id()).isEqualTo("ay_2025_26");
        assertThat(june.label()).isEqualTo("2026-27");
        assertThat(june.id()).isEqualTo("ay_2026_27");
    }

    @Test
    void invalidAcademicStartMonthFallsBackToApril() {
        AcademicCalendar.AcademicYear year = AcademicCalendar.currentAcademicYear(LocalDate.of(2026, 3, 31), 99);

        assertThat(year.label()).isEqualTo("2025-26");
        assertThat(year.id()).isEqualTo("ay_2025_26");
    }
}
