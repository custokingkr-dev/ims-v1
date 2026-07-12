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
}
