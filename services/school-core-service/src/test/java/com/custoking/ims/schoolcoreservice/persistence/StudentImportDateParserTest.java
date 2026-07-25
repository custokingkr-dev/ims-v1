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
}
