package com.custoking.ims.schoolcoreservice.application.report;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-level characterisation of the OpenPDF formatters: they must produce a parseable PDF for
 * the same report maps the JSON endpoints return, including the awkward shapes (unmarked
 * student, no students, null remarks) that the CSV tests cover.
 */
class AttendanceReportPdfTest {

    @Test
    void register_rendersOnePageWithTitleStudentsAndTotals() throws Exception {
        byte[] pdf = AttendanceReportPdf.register(register());
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Class 1-A").contains("April 2026");
            assertThat(text).contains("Asha").contains("Bala").contains("Chitra");
            assertThat(text).contains("Total");
            assertThat(text).contains("80.0");
        }
    }

    @Test
    void register_withNoStudentsStillRenders() throws Exception {
        Map<String, Object> report = register();
        report.put("students", new ArrayList<>());
        byte[] pdf = AttendanceReportPdf.register(report);
        try (PdfReader reader = new PdfReader(pdf)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Total");
        }
    }

    @Test
    void student_rendersRangeAndDayRowsWithNullRemarks() throws Exception {
        Map<String, Object> report = map(
                "student", map("studentId", 1L, "admissionNo", "ADM1", "rollNo", "1",
                        "fullName", "Asha Rao", "sectionName", "Class 1-A"),
                "from", "2026-04-01", "to", "2026-04-30",
                "days", List.of(
                        map("date", "2026-04-01", "weekday", "Wed", "status", "PRESENT", "remarks", null),
                        map("date", "2026-04-02", "weekday", "Thu", "status", "LEAVE", "remarks", "sick")),
                "presentCount", 1, "lateCount", 0, "leaveCount", 1, "absentCount", 0,
                "presentPercent", 100.0, "daysRecorded", 2);
        byte[] pdf = AttendanceReportPdf.student(report);
        try (PdfReader reader = new PdfReader(pdf)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Asha Rao").contains("2026-04-01 to 2026-04-30");
            assertThat(text).contains("PRESENT").contains("LEAVE").contains("sick");
        }
    }

    @Test
    void summary_rendersSectionsAndOverall() throws Exception {
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(map("classId", "c1", "sectionId", "s1", "sectionName", "Class 1-A", "teacherName", null,
                "presentCount", 10, "lateCount", 2, "leaveCount", 1, "absentCount", 3,
                "presentPercent", 80.0, "daysRecorded", 5));
        Map<String, Object> report = map("from", "2026-04-01", "to", "2026-04-30", "sections", sections,
                "overall", map("presentCount", 10, "lateCount", 2, "leaveCount", 1, "absentCount", 3, "presentPercent", 80.0));
        byte[] pdf = AttendanceReportPdf.summary(report);
        try (PdfReader reader = new PdfReader(pdf)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("Attendance summary").contains("Class 1-A").contains("Overall");
        }
    }

    private static Map<String, Object> register() {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= 3; d++) {
            days.add(map("date", "2026-04-0" + d, "dayOfMonth", d, "weekday", "Wed", "nonWorkingDay", false));
        }
        List<Map<String, Object>> students = new ArrayList<>();
        students.add(student(1L, "ADM1", "1", "Asha", List.of("PRESENT", "PRESENT", "LATE"), 2, 1, 0, 0, 100.0));
        students.add(student(2L, "ADM2", "2", "Bala", List.of("PRESENT", "ABSENT", "LEAVE"), 1, 0, 1, 1, 50.0));
        students.add(student(3L, "ADM3", "3", "Chitra", Arrays.asList(null, null, null), 0, 0, 0, 0, 0.0));
        List<Map<String, Object>> dayTotals = new ArrayList<>();
        dayTotals.add(map("date", "2026-04-01", "presentCount", 2, "lateCount", 0, "leaveCount", 0, "absentCount", 0));
        dayTotals.add(map("date", "2026-04-02", "presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 1));
        dayTotals.add(map("date", "2026-04-03", "presentCount", 0, "lateCount", 1, "leaveCount", 1, "absentCount", 0));
        return map("month", "2026-04", "monthLabel", "April 2026", "classId", "c1", "sectionId", "s1",
                "sectionName", "Class 1-A", "teacherName", "Ms Rao",
                "days", days, "students", students, "dayTotals", dayTotals,
                "totals", map("presentCount", 3, "lateCount", 1, "leaveCount", 1, "absentCount", 1, "presentPercent", 80.0));
    }

    private static Map<String, Object> student(long id, String admissionNo, String rollNo, String name,
                                               List<String> statuses, int p, int l, int e, int a, double pct) {
        List<Map<String, Object>> cells = new ArrayList<>();
        for (int i = 0; i < statuses.size(); i++) {
            cells.add(map("date", "2026-04-0" + (i + 1), "status", statuses.get(i)));
        }
        return map("studentId", id, "admissionNo", admissionNo, "rollNo", rollNo, "fullName", name,
                "cells", cells, "presentCount", p, "lateCount", l, "leaveCount", e, "absentCount", a,
                "presentPercent", pct);
    }

    private static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
