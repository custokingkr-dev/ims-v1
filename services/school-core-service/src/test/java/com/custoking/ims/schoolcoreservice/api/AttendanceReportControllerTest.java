package com.custoking.ims.schoolcoreservice.api;

import com.custoking.ims.schoolcoreservice.persistence.AttendanceReadRepository;
import com.custoking.ims.schoolcoreservice.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /attendance/report/{register,student,summary}} and their {@code /export} twins:
 * permission gate, school-scope resolution, error mapping, and the CSV/PDF response envelope.
 * The repository is mocked; the formatters are real so the export bodies are genuine output.
 */
class AttendanceReportControllerTest {

    private static final String TOKEN = "attendance-token";
    private static final LocalDate FROM = LocalDate.parse("2026-04-01");
    private static final LocalDate TO = LocalDate.parse("2026-04-30");

    private final AttendanceReadRepository attendance = mock(AttendanceReadRepository.class);
    private final AttendanceReadController controller = new AttendanceReadController(attendance, TOKEN);

    @BeforeEach
    void schoolAdmin() {
        TenantContext.set(new TenantContext(42L, "admin@x", "ADMIN", 10L, null, Set.of(), Set.of("attendance:read")));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    // ── JSON endpoints ──────────────────────────────────────────────────────────

    @Test
    void reportRegister_locksScopeToTheAuthenticatedSchool_whenNoneRequested() {
        Map<String, Object> report = registerReport();
        when(attendance.registerReport("2026-04", "c1", "s1", 10L)).thenReturn(report);

        assertThat(controller.reportRegister(TOKEN, null, "2026-04", "c1", "s1")).isSameAs(report);
        verify(attendance).registerReport("2026-04", "c1", "s1", 10L);
    }

    @Test
    void reportRegister_crossTenantSchoolId_isForbiddenBeforeQuerying() {
        assertThatThrownBy(() -> controller.reportRegister(TOKEN, 99L, "2026-04", "c1", "s1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(attendance, never()).registerReport(any(), any(), any(), any());
    }

    @Test
    void reportRegister_requiresAttendanceRead() {
        TenantContext.set(new TenantContext(42L, "staff@x", "STAFF", 10L, null, Set.of(), Set.of("fee:read")));
        assertThatThrownBy(() -> controller.reportRegister(TOKEN, null, "2026-04", "c1", "s1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ResponseStatusException) e).getReason()).contains("attendance:read");
                });
        verify(attendance, never()).registerReport(any(), any(), any(), any());
    }

    @Test
    void reportRegister_badMonth_isMappedToBadRequest() {
        when(attendance.registerReport("2026-4", "c1", "s1", 10L))
                .thenThrow(new DateTimeParseException("Text '2026-4' could not be parsed", "2026-4", 5));
        assertThatThrownBy(() -> controller.reportRegister(TOKEN, null, "2026-4", "c1", "s1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) e).getReason()).isEqualTo("Invalid date: 2026-4");
                });
    }

    @Test
    void reportStudent_crossSchoolStudent_isMappedToForbidden() {
        when(attendance.studentHistory(7L, FROM, TO, 10L))
                .thenThrow(new SecurityException("You do not have access to this student"));
        assertThatThrownBy(() -> controller.reportStudent(TOKEN, null, 7L, FROM, TO))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(((ResponseStatusException) e).getReason()).isEqualTo("You do not have access to this student");
                });
    }

    @Test
    void reportSummary_invertedRange_isMappedToBadRequest() {
        when(attendance.sectionSummary(TO, FROM, 10L))
                .thenThrow(new IllegalArgumentException("from must be on or before to"));
        assertThatThrownBy(() -> controller.reportSummary(TOKEN, null, TO, FROM))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) e).getReason()).isEqualTo("from must be on or before to");
                });
    }

    @Test
    void reportSummary_superadmin_mayWidenToAllSchools() {
        TenantContext.set(new TenantContext(1L, "root@x", "SUPERADMIN", null, null));
        when(attendance.sectionSummary(FROM, TO, null)).thenReturn(Map.of("sections", List.of()));
        assertThat(controller.reportSummary(TOKEN, null, FROM, TO)).containsKey("sections");
        verify(attendance).sectionSummary(FROM, TO, null);
    }

    // ── export envelope ─────────────────────────────────────────────────────────

    @Test
    void exportRegister_csv_isAnAttachmentWithTheRealCsvBody() {
        when(attendance.registerReport("2026-04", "c1", "s1", 10L)).thenReturn(registerReport());

        ResponseEntity<byte[]> response = controller.exportRegister(TOKEN, null, "2026-04", "c1", "s1", "csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=register-2026-04.csv");
        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("Roll,Admission No,Name,1,2,Present,Late,Leave,Absent,Present%\r\n");
        assertThat(body).contains("1,ADM1,Asha,P,A,1,0,0,1,50.0\r\n");
        assertThat(body).endsWith(",,Section total,,,1,0,0,1,50.0\r\n");
    }

    @Test
    void exportRegister_defaultsToCsv_andFormatIsCaseInsensitive() {
        when(attendance.registerReport("2026-04", "c1", "s1", 10L)).thenReturn(registerReport());

        assertThat(controller.exportRegister(TOKEN, null, "2026-04", "c1", "s1", null)
                .getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/csv");
        assertThat(controller.exportRegister(TOKEN, null, "2026-04", "c1", "s1", "PDF")
                .getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
    }

    @Test
    void exportRegister_pdf_isAnAttachmentWithAPdfBody() {
        when(attendance.registerReport("2026-04", "c1", "s1", 10L)).thenReturn(registerReport());

        ResponseEntity<byte[]> response = controller.exportRegister(TOKEN, null, "2026-04", "c1", "s1", "pdf");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=register-2026-04.pdf");
        assertThat(new String(response.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void export_unsupportedFormat_isBadRequest_afterTheReportIsAuthorised() {
        when(attendance.registerReport("2026-04", "c1", "s1", 10L)).thenReturn(registerReport());
        assertThatThrownBy(() -> controller.exportRegister(TOKEN, null, "2026-04", "c1", "s1", "xlsx"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((ResponseStatusException) e).getReason()).isEqualTo("Unsupported format: xlsx");
                });
    }

    @Test
    void exportStudent_filenameCarriesStudentAndRange() {
        when(attendance.studentHistory(7L, FROM, TO, 10L)).thenReturn(studentReport());

        ResponseEntity<byte[]> response = controller.exportStudent(TOKEN, null, 7L, FROM, TO, "csv");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=student-7-2026-04-01_2026-04-30.csv");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .startsWith("Student,Asha Rao\r\nAdmission No,ADM1\r\n");
    }

    @Test
    void exportSummary_filenameCarriesRange_andPdfRenders() {
        when(attendance.sectionSummary(FROM, TO, 10L)).thenReturn(summaryReport());

        ResponseEntity<byte[]> csv = controller.exportSummary(TOKEN, null, FROM, TO, "csv");
        assertThat(csv.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=summary-2026-04-01_2026-04-30.csv");
        assertThat(new String(csv.getBody(), StandardCharsets.UTF_8))
                .contains("Section,Teacher,Present,Late,Leave,Absent,Present%,Days Recorded\r\n");

        ResponseEntity<byte[]> pdf = controller.exportSummary(TOKEN, null, FROM, TO, "pdf");
        assertThat(new String(pdf.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void export_crossTenantSchoolId_isForbidden_andNothingIsRendered() {
        assertThatThrownBy(() -> controller.exportSummary(TOKEN, 99L, FROM, TO, "csv"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(attendance, never()).sectionSummary(any(), any(), any());
    }

    @Test
    void export_invalidToken_isUnauthorized() {
        assertThatThrownBy(() -> controller.exportRegister("nope", null, "2026-04", "c1", "s1", "csv"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(attendance, never()).registerReport(any(), any(), any(), any());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /** Two-day register with one student: Asha P, A -> 50.0. */
    private static Map<String, Object> registerReport() {
        List<Map<String, Object>> days = new ArrayList<>();
        days.add(map("date", "2026-04-01", "dayOfMonth", 1, "weekday", "Wed", "nonWorkingDay", false));
        days.add(map("date", "2026-04-02", "dayOfMonth", 2, "weekday", "Thu", "nonWorkingDay", false));
        List<Map<String, Object>> cells = new ArrayList<>();
        cells.add(map("date", "2026-04-01", "status", "PRESENT"));
        cells.add(map("date", "2026-04-02", "status", "ABSENT"));
        List<Map<String, Object>> students = new ArrayList<>();
        students.add(map("studentId", 1L, "admissionNo", "ADM1", "rollNo", "1", "fullName", "Asha",
                "cells", cells, "presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 1,
                "presentPercent", 50.0));
        List<Map<String, Object>> dayTotals = new ArrayList<>();
        dayTotals.add(map("date", "2026-04-01", "presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 0));
        dayTotals.add(map("date", "2026-04-02", "presentCount", 0, "lateCount", 0, "leaveCount", 0, "absentCount", 1));
        return map("month", "2026-04", "monthLabel", "April 2026", "classId", "c1", "sectionId", "s1",
                "sectionName", "Class 1-A", "teacherName", "Ms Rao",
                "days", days, "students", students, "dayTotals", dayTotals,
                "totals", map("presentCount", 1, "lateCount", 0, "leaveCount", 0, "absentCount", 1, "presentPercent", 50.0));
    }

    private static Map<String, Object> studentReport() {
        return map("student", map("studentId", 7L, "admissionNo", "ADM1", "rollNo", "1",
                        "fullName", "Asha Rao", "sectionName", "Class 1-A"),
                "from", "2026-04-01", "to", "2026-04-30",
                "days", List.of(map("date", "2026-04-01", "weekday", "Wed", "status", "PRESENT",
                        "remarks", "", "nonWorkingDay", false)),
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

    private static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}
