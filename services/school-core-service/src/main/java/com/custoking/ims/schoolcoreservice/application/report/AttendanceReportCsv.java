package com.custoking.ims.schoolcoreservice.application.report;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Static CSV formatters for the three attendance reports. Input is the same Map the JSON endpoints return. */
public final class AttendanceReportCsv {

    private AttendanceReportCsv() {}

    private static final Map<String, String> LETTER = Map.of("PRESENT", "P", "LATE", "L", "LEAVE", "E", "ABSENT", "A");

    @SuppressWarnings("unchecked")
    public static byte[] register(Map<String, Object> r) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        List<Map<String, Object>> students = (List<Map<String, Object>>) r.get("students");
        List<Map<String, Object>> dayTotals = (List<Map<String, Object>>) r.get("dayTotals");
        Map<String, Object> totals = (Map<String, Object>) r.get("totals");
        StringBuilder sb = new StringBuilder();

        StringBuilder header = new StringBuilder("Roll,Admission No,Name");
        for (Map<String, Object> d : days) header.append(',').append(d.get("dayOfMonth"));
        header.append(",Present,Late,Leave,Absent,Present%");
        line(sb, header.toString());

        for (Map<String, Object> s : students) {
            List<Map<String, Object>> cells = (List<Map<String, Object>>) s.get("cells");
            StringBuilder row = new StringBuilder(String.join(",",
                    esc(str(s.get("rollNo"))), esc(str(s.get("admissionNo"))), esc(str(s.get("fullName")))));
            for (Map<String, Object> cell : cells) row.append(',').append(LETTER.getOrDefault(String.valueOf(cell.get("status")), ""));
            row.append(',').append(s.get("presentCount")).append(',').append(s.get("lateCount"))
               .append(',').append(s.get("leaveCount")).append(',').append(s.get("absentCount"))
               .append(',').append(s.get("presentPercent"));
            line(sb, row.toString());
        }

        StringBuilder tot = new StringBuilder(",,Day totals");
        for (Map<String, Object> dt : dayTotals) {
            tot.append(',').append(num(dt.get("presentCount")) + num(dt.get("lateCount")) + num(dt.get("leaveCount")) + num(dt.get("absentCount")));
        }
        tot.append(",,,,,");
        line(sb, tot.toString());

        StringBuilder grand = new StringBuilder(",,Section total");
        for (int i = 0; i < days.size(); i++) grand.append(',');
        grand.append(totals.get("presentCount")).append(',').append(totals.get("lateCount"))
             .append(',').append(totals.get("leaveCount")).append(',').append(totals.get("absentCount"))
             .append(',').append(totals.get("presentPercent"));
        line(sb, grand.toString());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static byte[] student(Map<String, Object> r) {
        Map<String, Object> st = (Map<String, Object>) r.get("student");
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        StringBuilder sb = new StringBuilder();
        line(sb, "Student," + esc(String.valueOf(st.get("fullName"))));
        line(sb, "Admission No," + esc(String.valueOf(st.get("admissionNo"))));
        line(sb, "Section," + esc(String.valueOf(st.get("sectionName"))));
        line(sb, "Range," + r.get("from") + " to " + r.get("to"));
        line(sb, "Present," + r.get("presentCount") + ",Late," + r.get("lateCount") + ",Leave," + r.get("leaveCount")
                + ",Absent," + r.get("absentCount") + ",Present%," + r.get("presentPercent"));
        line(sb, "");
        line(sb, "Date,Weekday,Status,Remarks");
        for (Map<String, Object> d : days) {
            line(sb, String.join(",",
                    esc(str(d.get("date"))), esc(str(d.get("weekday"))), esc(str(d.get("status"))), esc(str(d.get("remarks")))));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static byte[] summary(Map<String, Object> r) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) r.get("sections");
        Map<String, Object> overall = (Map<String, Object>) r.get("overall");
        StringBuilder sb = new StringBuilder();
        line(sb, "Range," + r.get("from") + " to " + r.get("to"));
        line(sb, "");
        line(sb, "Section,Teacher,Present,Late,Leave,Absent,Present%,Days Recorded");
        for (Map<String, Object> s : sections) {
            StringBuilder row = new StringBuilder(String.join(",",
                    esc(str(s.get("sectionName"))), esc(str(s.get("teacherName")))));
            row.append(',').append(s.get("presentCount")).append(',').append(s.get("lateCount"))
               .append(',').append(s.get("leaveCount")).append(',').append(s.get("absentCount"))
               .append(',').append(s.get("presentPercent")).append(',').append(s.get("daysRecorded"));
            line(sb, row.toString());
        }
        line(sb, "Overall,," + overall.get("presentCount") + ',' + overall.get("lateCount") + ','
                + overall.get("leaveCount") + ',' + overall.get("absentCount") + ',' + overall.get("presentPercent") + ',');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static void line(StringBuilder sb, String s) { sb.append(s).append("\r\n"); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String esc(String v) {
        String s = v;
        // CSV formula-injection guard: a value a spreadsheet could execute as a formula
        // (leading = + - @, tab, or CR) is neutralized with a leading apostrophe. Applied to
        // user-controlled text (names, remarks, admission/roll numbers) before quoting.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
