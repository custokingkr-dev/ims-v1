package com.custoking.ims.schoolcoreservice.application.report;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** Static OpenPDF table formatters for the three attendance reports. */
public final class AttendanceReportPdf {

    private AttendanceReportPdf() {}

    private static final Font TITLE = new Font(Font.HELVETICA, 13, Font.BOLD);
    private static final Font SUB = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.DARK_GRAY);
    private static final Font HEAD = new Font(Font.HELVETICA, 7, Font.BOLD, Color.WHITE);
    private static final Font BODY = new Font(Font.HELVETICA, 7, Font.NORMAL);
    private static final Color HEAD_BG = new Color(26, 79, 168);          // --b
    private static final Map<String, Color> LETTER_COLOR = Map.of(
            "P", new Color(26, 104, 64), "L", new Color(179, 92, 0), "E", new Color(26, 79, 168), "A", new Color(192, 49, 43));
    private static final Map<String, String> LETTER = Map.of("PRESENT", "P", "LATE", "L", "LEAVE", "E", "ABSENT", "A");

    @SuppressWarnings("unchecked")
    public static byte[] register(Map<String, Object> r) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        List<Map<String, Object>> students = (List<Map<String, Object>>) r.get("students");
        Map<String, Object> totals = (Map<String, Object>) r.get("totals");
        return build(PageSize.A4.rotate(), String.valueOf(r.get("sectionName")) + " · " + r.get("monthLabel"), doc -> {
            int cols = 3 + days.size() + 5;
            PdfPTable t = new PdfPTable(cols);
            t.setWidthPercentage(100);
            headCell(t, "Roll"); headCell(t, "Adm"); headCell(t, "Name");
            for (Map<String, Object> d : days) headCell(t, String.valueOf(d.get("dayOfMonth")));
            for (String h : new String[] {"P", "L", "E", "A", "%"}) headCell(t, h);
            for (Map<String, Object> s : students) {
                bodyCell(t, s.get("rollNo")); bodyCell(t, s.get("admissionNo")); bodyCell(t, s.get("fullName"));
                for (Map<String, Object> cell : (List<Map<String, Object>>) s.get("cells")) {
                    String letter = LETTER.getOrDefault(String.valueOf(cell.get("status")), "");
                    letterCell(t, letter);
                }
                bodyCell(t, s.get("presentCount")); bodyCell(t, s.get("lateCount"));
                bodyCell(t, s.get("leaveCount")); bodyCell(t, s.get("absentCount")); bodyCell(t, s.get("presentPercent"));
            }
            // totals row
            bodyCell(t, ""); bodyCell(t, ""); bodyCell(t, "Total");
            for (int i = 0; i < days.size(); i++) bodyCell(t, "");
            bodyCell(t, totals.get("presentCount")); bodyCell(t, totals.get("lateCount"));
            bodyCell(t, totals.get("leaveCount")); bodyCell(t, totals.get("absentCount")); bodyCell(t, totals.get("presentPercent"));
            doc.add(t);
        });
    }

    @SuppressWarnings("unchecked")
    public static byte[] student(Map<String, Object> r) {
        Map<String, Object> st = (Map<String, Object>) r.get("student");
        List<Map<String, Object>> days = (List<Map<String, Object>>) r.get("days");
        return build(PageSize.A4, String.valueOf(st.get("fullName")) + " · " + st.get("sectionName"), doc -> {
            doc.add(new Paragraph(r.get("from") + " to " + r.get("to")
                    + "  ·  Present " + r.get("presentPercent") + "%"
                    + "  (P " + r.get("presentCount") + " / L " + r.get("lateCount")
                    + " / E " + r.get("leaveCount") + " / A " + r.get("absentCount") + ")", SUB));
            doc.add(new Paragraph(" ", SUB));
            PdfPTable t = new PdfPTable(new float[] {2, 2, 2, 6});
            t.setWidthPercentage(100);
            for (String h : new String[] {"Date", "Weekday", "Status", "Remarks"}) headCell(t, h);
            for (Map<String, Object> d : days) {
                bodyCell(t, d.get("date")); bodyCell(t, d.get("weekday")); bodyCell(t, d.get("status")); bodyCell(t, d.get("remarks"));
            }
            doc.add(t);
        });
    }

    @SuppressWarnings("unchecked")
    public static byte[] summary(Map<String, Object> r) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) r.get("sections");
        Map<String, Object> overall = (Map<String, Object>) r.get("overall");
        return build(PageSize.A4, "Attendance summary · " + r.get("from") + " to " + r.get("to"), doc -> {
            PdfPTable t = new PdfPTable(new float[] {5, 4, 2, 2, 2, 2, 3, 3});
            t.setWidthPercentage(100);
            for (String h : new String[] {"Section", "Teacher", "P", "L", "E", "A", "Present%", "Days"}) headCell(t, h);
            for (Map<String, Object> s : sections) {
                bodyCell(t, s.get("sectionName")); bodyCell(t, s.get("teacherName"));
                bodyCell(t, s.get("presentCount")); bodyCell(t, s.get("lateCount"));
                bodyCell(t, s.get("leaveCount")); bodyCell(t, s.get("absentCount"));
                bodyCell(t, s.get("presentPercent")); bodyCell(t, s.get("daysRecorded"));
            }
            bodyCell(t, "Overall"); bodyCell(t, "");
            bodyCell(t, overall.get("presentCount")); bodyCell(t, overall.get("lateCount"));
            bodyCell(t, overall.get("leaveCount")); bodyCell(t, overall.get("absentCount"));
            bodyCell(t, overall.get("presentPercent")); bodyCell(t, "");
            doc.add(t);
        });
    }

    private interface Body { void fill(Document doc) throws Exception; }

    private static byte[] build(com.lowagie.text.Rectangle size, String title, Body body) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document doc = new Document(size, 24, 24, 24, 24);
            PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(new Paragraph(title, TITLE));
            doc.add(new Paragraph(" ", SUB));
            body.fill(doc);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF: " + e.getMessage(), e);
        }
    }

    private static void headCell(PdfPTable t, String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, HEAD));
        c.setBackgroundColor(HEAD_BG);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(2f);
        t.addCell(c);
    }

    private static void bodyCell(PdfPTable t, Object value) {
        PdfPCell c = new PdfPCell(new Phrase(value == null ? "" : String.valueOf(value), BODY));
        c.setPadding(2f);
        t.addCell(c);
    }

    private static void letterCell(PdfPTable t, String letter) {
        Color color = LETTER_COLOR.getOrDefault(letter, Color.BLACK);
        PdfPCell c = new PdfPCell(new Phrase(letter, new Font(Font.HELVETICA, 7, Font.BOLD, color)));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(2f);
        t.addCell(c);
    }
}
