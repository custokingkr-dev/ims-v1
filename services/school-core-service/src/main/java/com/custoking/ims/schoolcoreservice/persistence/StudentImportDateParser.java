package com.custoking.ims.schoolcoreservice.persistence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;

final class StudentImportDateParser {
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final LocalDate EARLIEST_STUDENT_DOB = LocalDate.of(1900, 1, 1);
    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("uuuu/M/d").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT)
    );

    private StudentImportDateParser() {
    }

    static LocalDate parseOptional(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-'
                && (text.length() == 10 || text.charAt(10) == 'T' || text.charAt(10) == ' ')) {
            text = text.substring(0, 10);
        }
        LocalDate excelDate = parseExcelSerial(text);
        if (excelDate != null) return excelDate;
        for (DateTimeFormatter formatter : FORMATS) {
            try {
                return LocalDate.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next accepted import date format.
            }
        }
        throw new IllegalArgumentException("Date must be a valid date in YYYY-MM-DD, YYYY/MM/DD, DD/MM/YYYY, or DD-MM-YYYY format");
    }

    static LocalDate parseDateOfBirth(String value) {
        return parseDateOfBirth(value, LocalDate.now());
    }

    static LocalDate parseDateOfBirth(String value, LocalDate today) {
        LocalDate parsed = parseOptional(value);
        if (parsed == null) return null;
        if (parsed.isBefore(EARLIEST_STUDENT_DOB)) {
            throw new IllegalArgumentException("Date of birth cannot be before 1900-01-01");
        }
        if (parsed.isAfter(today)) {
            throw new IllegalArgumentException("Date of birth cannot be in the future");
        }
        return parsed;
    }

    private static LocalDate parseExcelSerial(String text) {
        if (!text.matches("\\d{5}(\\.0+)?")) return null;
        long days = Long.parseLong(text.split("\\.")[0]);
        if (days < 20000 || days > 60000) return null;
        return EXCEL_EPOCH.plusDays(days);
    }
}
