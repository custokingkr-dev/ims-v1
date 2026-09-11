package com.custoking.ims.schoolcoreservice.application.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formatter characterisation for the three attendance CSV exports.
 *
 * <p>The register export once shipped with a grand-total row that was one column short
 * (commit 9501589d fixed it by inspection). {@link #register_everyRowIsExactlyHeaderWidth}
 * and {@link #register_sectionTotalRowLandsUnderTheTotalsHeaders} are the tests that would
 * have caught it: every row of a register must be exactly {@code 3 + N + 5} cells wide and
 * the totals must sit under the {@code Present..Present%} headers.</p>
 */
class AttendanceReportCsvTest {

    // ── register ────────────────────────────────────────────────────────────────

    @Test
    void register_everyRowIsExactlyHeaderWidth() {
        Map<String, Object> report = register(3);
        List<List<String>> rows = parse(AttendanceReportCsv.register(report));

        int expectedWidth = 3 + 3 + 5;
        assertThat(rows).hasSize(1 + 2 + 1 + 1); // header, 2 students, day totals, section total
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i))
                    .as("row %d: %s", i, rows.get(i))
                    .hasSize(expectedWidth);
        }
    }

    @Test
    void register_sectionTotalRowLandsUnderTheTotalsHeaders() {
        Map<String, Object> report = register(3);
        List<List<String>> rows = parse(AttendanceReportCsv.register(report));
        List<String> header = rows.getFirst();
        List<String> grand = rows.getLast();

        int present = header.indexOf("Present");
        int late = header.indexOf("Late");
        int leave = header.indexOf("Leave");
        int absent = header.indexOf("Absent");
        int percent = header.indexOf("Present%");
        assertThat(List.of(present, late, leave, absent, percent)).doesNotContain(-1);

        // Totals in the fixture: P=3 L=1 E=1 A=1 -> 80.0
        assertThat(grand.get(2)).isEqualTo("Section total");
        assertThat(grand.get(present)).isEqualTo("3");
        assertThat(grand.get(late)).isEqualTo("1");
        assertThat(grand.get(leave)).isEqualTo("1");
        assertThat(grand.get(absent)).isEqualTo("1");
        assertThat(grand.get(percent)).isEqualTo("80.0");
        // Every day column of the grand row is empty — nothing bleeds left.
        for (int i = 3; i < 3 + 3; i++) {
            assertThat(grand.get(i)).as("grand row day col %d", i).isEmpty();
        }
    }

    @Test
    void register_dayTotalsRowSumsEachDayUnderItsOwnColumn() {
        Map<String, Object> report = register(3);
        List<List<String>> rows = parse(AttendanceReportCsv.register(report));
        List<String> dayTotals = rows.get(3);

        assertThat(dayTotals.get(2)).isEqualTo("Day totals");
        // fixture dayTotals: day1 P2, day2 P1+A1, day3 L1+E1 -> 2, 2, 2
        assertThat(dayTotals.subList(3, 6)).containsExactly("2", "2", "2");
        // The five summary columns are blank on the day-totals row.
        assertThat(dayTotals.subList(6, 11)).containsExactly("", "", "", "", "");
    }

    @Test
    void register_headerListsDaysOfMonthBetweenIdentityAndTotals() {
        List<List<String>> rows = parse(AttendanceReportCsv.register(register(3)));
        assertThat(rows.getFirst()).containsExactly(
                "Roll", "Admission No", "Name", "1", "2", "3",
                "Present", "Late", "Leave", "Absent", "Present%");
    }

    @Test
    void register_studentCellsUseStatusLettersAndBlankForUnmarked() {
        List<List<String>> rows = parse(AttendanceReportCsv.register(register(3)));
        // student 1: P, P, L ; student 2: P, A, E — see fixture
        assertThat(rows.get(1).subList(0, 6)).containsExactly("1", "ADM1", "Asha", "P", "P", "L");
        assertThat(rows.get(2).subList(0, 6)).containsExactly("2", "ADM2", "Bala", "P", "A", "E");

        // A student with no marks: every day cell is blank and counts are zero.
        Map<String, Object> report = register(3);
        students(report).add(student(3L, "ADM3", "3", "Chitra",
                Arrays.asList(null, null, null), 0, 0, 0, 0, 0.0));
        List<List<String>> withUnmarked = parse(AttendanceReportCsv.register(report));
        assertThat(withUnmarked.get(3)).containsExactly("3", "ADM3", "Chitra", "", "", "", "0", "0", "0", "0", "0.0");
    }

    @Test
    void register_unknownStatusRendersBlankRatherThanLeaking() {
        Map<String, Object> report = register(1);
        students(report).clear();
        students(report).add(student(9L, "ADM9", "9", "Zed", List.of("HALF_DAY"), 0, 0, 0, 0, 0.0));
        List<List<String>> rows = parse(AttendanceReportCsv.register(report));
        assertThat(rows.get(1).get(3)).isEmpty();
    }

    @Test
    void register_withNoStudentsStillEmitsHeaderAndBothTotalRows() {
        Map<String, Object> report = register(2);
        students(report).clear();
        List<List<String>> rows = parse(AttendanceReportCsv.register(report));
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).get(2)).isEqualTo("Day totals");
        assertThat(rows.get(2).get(2)).isEqualTo("Section total");
        assertThat(rows.get(2)).hasSize(3 + 2 + 5);
    }

    @Test
    void register_usesCrlfLineEndings() {
        String text = new String(AttendanceReportCsv.register(register(1)), StandardCharsets.UTF_8);
        assertThat(text).endsWith("\r\n");
        assertThat(text.split("\r\n")).hasSize(5);
        assertThat(text.replace("\r\n", "")).doesNotContain("\n");
    }

    // ── formula-injection guard (esc) ───────────────────────────────────────────

    @ParameterizedTest(name = "leading {0} is neutralised")
    @ValueSource(strings = {"=", "+", "-", "@"})
    void esc_neutralisesSpreadsheetFormulaPrefixes(String prefix) {
        String hostile = prefix + "CMD|'/C calc'!A0";
        List<List<String>> rows = parse(AttendanceReportCsv.student(studentReport(hostile)));
        String remarks = rows.get(7).get(3);
        assertThat(remarks).startsWith("'" + prefix);
        assertThat(remarks).isEqualTo("'" + hostile);
    }

    @Test
    void esc_neutralisesLeadingTab() {
        String hostile = "\t=1+1";
        List<List<String>> rows = parse(AttendanceReportCsv.student(studentReport(hostile)));
        assertThat(rows.get(7).get(3)).isEqualTo("'" + hostile);
    }

    @Test
    void esc_neutralisesLeadingCarriageReturnAndQuotesIt() {
        String hostile = "\r=1+1";
        byte[] csv = AttendanceReportCsv.student(studentReport(hostile));
        List<List<String>> rows = parse(csv);
        // The apostrophe goes in first, then the field is quoted because it contains CR.
        assertThat(rows.get(7).get(3)).isEqualTo("'" + hostile);
        String raw = new String(csv, StandardCharsets.UTF_8);
        assertThat(raw).contains(",\"'\r=1+1\"\r\n");
    }

    @Test
    void esc_guardsNamesAndIdentifiersOnTheRegisterToo() {
        Map<String, Object> report = register(1);
        students(report).clear();
        students(report).add(student(1L, "=HYPERLINK(\"http://evil\")", "-7", "@import",
                List.of("PRESENT"), 1, 0, 0, 0, 100.0));
        List<List<String>> rows = parse(AttendanceReportCsv.register(report));
        assertThat(rows.get(1).get(0)).isEqualTo("'-7");
        assertThat(rows.get(1).get(1)).isEqualTo("'=HYPERLINK(\"http://evil\")");
        assertThat(rows.get(1).get(2)).isEqualTo("'@import");
    }

    @Test
    void esc_leavesOrdinaryValuesUntouched() {
        List<List<String>> rows = parse(AttendanceReportCsv.student(studentReport("Doctor's note")));
        assertThat(rows.get(7).get(3)).isEqualTo("Doctor's note");
    }

    @Test
    void esc_quotesCommasAndDoublesEmbeddedQuotes() {
        byte[] csv = AttendanceReportCsv.student(studentReport("late, said \"traffic\""));
        String raw = new String(csv, StandardCharsets.UTF_8);
        assertThat(raw).contains(",\"late, said \"\"traffic\"\"\"\r\n");
        assertThat(parse(csv).get(7).get(3)).isEqualTo("late, said \"traffic\"");
    }

    @Test
    void esc_emptyValueStaysEmpty() {
        List<List<String>> rows = parse(AttendanceReportCsv.student(studentReport("")));
        assertThat(rows.get(7).get(3)).isEmpty();
    }

    // ── student ─────────────────────────────────────────────────────────────────

    @Test
    void student_emitsPreambleThenDayRows() {
        List<List<String>> rows = parse(AttendanceReportCsv.student(studentReport("ok")));
        assertThat(rows.get(0)).containsExactly("Student", "Asha Rao");
        assertThat(rows.get(1)).containsExactly("Admission No", "ADM1");
        assertThat(rows.get(2)).containsExactly("Section", "Class 1-A");
        assertThat(rows.get(3)).containsExactly("Range", "2026-04-01 to 2026-04-30");
        assertThat(rows.get(4)).containsExactly("Present", "1", "Late", "0", "Leave", "0", "Absent", "0", "Present%", "100.0");
        assertThat(rows.get(5)).containsExactly("");
        assertThat(rows.get(6)).containsExactly("Date", "Weekday", "Status", "Remarks");
        assertThat(rows.get(7)).containsExactly("2026-04-01", "Wed", "PRESENT", "ok");
    }

    // ── summary ─────────────────────────────────────────────────────────────────

    @Test
    void summary_everyRowHasEightColumnsAndOverallAlignsUnderHeaders() {
        Map<String, Object> report = summaryReport();
        List<List<String>> rows = parse(AttendanceReportCsv.summary(report));
        assertThat(rows.get(0)).containsExactly("Range", "2026-04-01 to 2026-04-30");
        List<String> header = rows.get(2);
        assertThat(header).containsExactly("Section", "Teacher", "Present", "Late", "Leave", "Absent", "Present%", "Days Recorded");
        assertThat(rows.get(3)).containsExactly("Class 1-A", "Ms Rao", "10", "2", "1", "3", "80.0", "5");
        List<String> overall = rows.getLast();
        assertThat(overall).hasSize(8);
        assertThat(overall.get(0)).isEqualTo("Overall");
        assertThat(overall.get(header.indexOf("Present"))).isEqualTo("10");
        assertThat(overall.get(header.indexOf("Absent"))).isEqualTo("3");
        assertThat(overall.get(header.indexOf("Present%"))).isEqualTo("80.0");
        assertThat(overall.get(header.indexOf("Days Recorded"))).isEmpty();
    }

    @Test
    void summary_teacherNameIsFormulaGuarded() {
        Map<String, Object> report = summaryReport();
        sections(report).getFirst().put("teacherName", "=cmd");
        List<List<String>> rows = parse(AttendanceReportCsv.summary(report));
        assertThat(rows.get(3).get(1)).isEqualTo("'=cmd");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /**
     * Three-day register with two students: Asha (P,P,L) and Bala (P,A,E).
     * Totals: P=3 L=1 E=1 A=1 -> 3+1 / 3+1+1 = 80.0.
     */
    private static Map<String, Object> register(int dayCount) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (int d = 1; d <= dayCount; d++) {
            days.add(map("date", "2026-04-0" + d, "dayOfMonth", d, "weekday", "Wed", "nonWorkingDay", false));
        }
        List<String> asha = List.of("PRESENT", "PRESENT", "LATE");
        List<String> bala = List.of("PRESENT", "ABSENT", "LEAVE");
        List<Map<String, Object>> students = new ArrayList<>();
        students.add(student(1L, "ADM1", "1", "Asha", asha.subList(0, dayCount), 2, 1, 0, 0, 100.0));
        students.add(student(2L, "ADM2", "2", "Bala", bala.subList(0, dayCount), 1, 0, 1, 1, 50.0));
        List<Map<String, Object>> dayTotals = new ArrayList<>();
        int[][] perDay = {{2, 0, 0, 0}, {1, 0, 0, 1}, {0, 1, 1, 0}};
        for (int d = 0; d < dayCount; d++) {
            dayTotals.add(map("date", "2026-04-0" + (d + 1),
                    "presentCount", perDay[d][0], "lateCount", perDay[d][1],
                    "leaveCount", perDay[d][2], "absentCount", perDay[d][3]));
        }
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

    private static Map<String, Object> studentReport(String remarks) {
        return map("student", map("studentId", 1L, "admissionNo", "ADM1", "rollNo", "1",
                        "fullName", "Asha Rao", "sectionName", "Class 1-A"),
                "from", "2026-04-01", "to", "2026-04-30",
                "days", List.of(map("date", "2026-04-01", "weekday", "Wed", "status", "PRESENT",
                        "remarks", remarks, "nonWorkingDay", false)),
                "presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 0,
                "presentPercent", 100.0, "daysRecorded", 1);
    }

    private static Map<String, Object> summaryReport() {
        List<Map<String, Object>> sections = new ArrayList<>();
        sections.add(map("classId", "c1", "sectionId", "s1", "sectionName", "Class 1-A", "teacherName", "Ms Rao",
                "presentCount", 10, "lateCount", 2, "leaveCount", 1, "absentCount", 3,
                "presentPercent", 80.0, "daysRecorded", 5));
        return map("from", "2026-04-01", "to", "2026-04-30", "sections", sections,
                "overall", map("presentCount", 10, "lateCount", 2, "leaveCount", 1, "absentCount", 3, "presentPercent", 80.0));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> students(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("students");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sections(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("sections");
    }

    private static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Minimal RFC 4180 reader: CRLF records, double-quote escaping. Enough to assert cell layout. */
    private static List<List<String>> parse(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString()); cell.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
                i++;
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
