package com.custoking.ims.schoolcoreservice.persistence;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentImportDateParserTest {
    @Test
    void acceptsExcelSlashFormattedYearFirstDates() {
        assertThat(StudentImportDateParser.parseOptional("2022/06/01"))
                .isEqualTo(LocalDate.of(2022, 6, 1));
    }

    @Test
    void acceptsIsoDateAndIsoDateTimeStrings() {
        assertThat(StudentImportDateParser.parseOptional("2022-06-01"))
                .isEqualTo(LocalDate.of(2022, 6, 1));
        assertThat(StudentImportDateParser.parseOptional("2022-06-01T00:00:00.000Z"))
                .isEqualTo(LocalDate.of(2022, 6, 1));
    }

    @Test
    void acceptsCommonDayFirstDatesAndExcelSerials() {
        assertThat(StudentImportDateParser.parseOptional("01/06/2022"))
                .isEqualTo(LocalDate.of(2022, 6, 1));
        assertThat(StudentImportDateParser.parseOptional("01-06-2022"))
                .isEqualTo(LocalDate.of(2022, 6, 1));
        assertThat(StudentImportDateParser.parseOptional("44713"))
                .isEqualTo(LocalDate.of(2022, 6, 1));
    }

    @Test
    void rejectsUnrecognizedDatesWithImportMessage() {
        assertThatThrownBy(() -> StudentImportDateParser.parseOptional("06.01.2022"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Date must");
    }

    @Test
    void validatesDateOfBirthWithoutDependingOnARegionalTimezone() {
        LocalDate today = LocalDate.of(2026, 8, 5);

        assertThat(StudentImportDateParser.parseDateOfBirth("2010-05-12", today))
                .isEqualTo(LocalDate.of(2010, 5, 12));
        assertThat(StudentImportDateParser.parseDateOfBirth("", today)).isNull();
        assertThatThrownBy(() -> StudentImportDateParser.parseDateOfBirth("2026-08-06", today))
                .hasMessageContaining("future");
        assertThatThrownBy(() -> StudentImportDateParser.parseDateOfBirth("1899-12-31", today))
                .hasMessageContaining("1900-01-01");
    }
}
