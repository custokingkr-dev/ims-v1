package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.SendMeetingInvitesRequest;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LowAttendanceService")
class LowAttendanceServiceTest {

    @Mock AttendanceDailyRepository attendanceDailyRepository;
    @Mock AcademicYearRepository academicYearRepository;
    @Mock StudentRepository studentRepository;
    @Mock NotificationLogRepository notificationLogRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks LowAttendanceService service;

    private static final Long SCHOOL_ID = 5L;
    private static final Long OTHER_SCHOOL_ID = 99L;
    private static final AuthUser ACTOR = AuthUser.identity(3L, "Admin", "admin@school.com", "ADMIN", null, null);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    // ── helpers ───────────────────────────────────────────────────────────────

    private AcademicYearEntity activeYear() {
        AcademicYearEntity ay = new AcademicYearEntity();
        ay.setId("ay-2025");
        ay.setActive(true);
        return ay;
    }

    private AttendanceDailyEntity sectionRecord(String sectionId, String sectionName, String className,
                                                  int present, int total) {
        SchoolEntity school = new SchoolEntity();
        school.setId(SCHOOL_ID);

        SchoolClassEntity cls = new SchoolClassEntity();
        cls.setId("c1");
        cls.setName(className);

        SchoolSectionEntity sec = new SchoolSectionEntity();
        sec.setId(sectionId);
        sec.setName(sectionName);
        sec.setSchoolClass(cls);
        sec.setSchool(school);

        AttendanceDailyEntity a = new AttendanceDailyEntity();
        a.setId(UUID.randomUUID().toString());
        a.setAttendanceDate(TODAY);
        a.setSection(sec);
        a.setPresentCount(present);
        a.setTotalEnrolled(total);
        return a;
    }

    private StudentEntity student(Long id, String sectionId, Long schoolId, Double attendancePct) {
        SchoolEntity school = new SchoolEntity();
        school.setId(schoolId);
        SchoolClassEntity cls = new SchoolClassEntity();
        cls.setId("c1");
        cls.setName("Class 8");
        SchoolSectionEntity sec = new SchoolSectionEntity();
        sec.setId(sectionId);
        sec.setName("A");
        sec.setSchoolClass(cls);

        StudentEntity s = new StudentEntity();
        s.setId(id);
        s.setFullName("Student " + id);
        s.setAdmissionNo("A0" + id);
        s.setFatherContact("99990000" + id);
        s.setFatherName("Father " + id);
        s.setAttendancePercent(attendancePct);
        s.setSchool(school);
        s.setSchoolClass(cls);
        s.setSection(sec);
        return s;
    }

    // Using a quick UUID hack since java.util.UUID is available
    private static class UUID {
        static String randomUUID() { return java.util.UUID.randomUUID().toString(); }
    }

    // ── test 1: sections below threshold are returned ─────────────────────────

    @Test
    @DisplayName("getLowAttendanceSections returns sections below threshold with correct pct")
    void getLowAttendanceSections_returnsBelowThresholdSections() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(attendanceDailyRepository.findSectionsBelowThresholdList(TODAY, "ay-2025", SCHOOL_ID, 0.75))
                .thenReturn(List.of(sectionRecord("s1", "A", "Class 8", 10, 20)));
        when(studentRepository.findBySection_IdAndSchool_IdOrderByFullNameAsc("s1", SCHOOL_ID))
                .thenReturn(List.of(student(1L, "s1", SCHOOL_ID, 40.0), student(2L, "s1", SCHOOL_ID, 70.0)));

        var result = service.getLowAttendanceSections(SCHOOL_ID, TODAY);

        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).attendancePct()).isEqualTo(50.0);
        assertThat(result.sections().get(0).studentsBelowThreshold()).isEqualTo(2L);
        assertThat(result.thresholdPercent()).isEqualTo(75);
    }

    // ── test 2: no active year returns empty list ─────────────────────────────

    @Test
    @DisplayName("getLowAttendanceSections returns empty list when no active academic year")
    void getLowAttendanceSections_noActiveYear_returnsEmpty() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.empty());

        var result = service.getLowAttendanceSections(SCHOOL_ID, TODAY);

        assertThat(result.sections()).isEmpty();
        verifyNoInteractions(attendanceDailyRepository);
    }

    // ── test 3: invites are saved to notification_logs ────────────────────────

    @Test
    @DisplayName("sendMeetingInvites creates one notification log per student")
    void sendMeetingInvites_createsNotificationLogs() {
        when(studentRepository.findById(10L)).thenReturn(Optional.of(student(10L, "s1", SCHOOL_ID, 60.0)));
        when(studentRepository.findById(11L)).thenReturn(Optional.of(student(11L, "s1", SCHOOL_ID, 55.0)));

        var req = new SendMeetingInvitesRequest(List.of(10L, 11L), "SMS",
                "Dear parent, please attend meeting.", null);
        var result = service.sendMeetingInvites(SCHOOL_ID, req, ACTOR);

        assertThat(result.sentCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        verify(notificationLogRepository, times(2)).save(any());
    }

    // ── test 4: cross-school student throws 403 ────────────────────────────────

    @Test
    @DisplayName("sendMeetingInvites throws 403 when a student belongs to a different school")
    void sendMeetingInvites_crossSchoolStudent_throws403() {
        when(studentRepository.findById(20L))
                .thenReturn(Optional.of(student(20L, "s-other", OTHER_SCHOOL_ID, 50.0)));

        var req = new SendMeetingInvitesRequest(List.of(20L), "SMS", "Message", null);
        assertThatThrownBy(() -> service.sendMeetingInvites(SCHOOL_ID, req, ACTOR))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── test 5: audit log is recorded ────────────────────────────────────────

    @Test
    @DisplayName("sendMeetingInvites records an audit log entry")
    void sendMeetingInvites_recordsAuditLog() {
        when(studentRepository.findById(30L)).thenReturn(Optional.of(student(30L, "s1", SCHOOL_ID, 65.0)));

        var req = new SendMeetingInvitesRequest(List.of(30L), "WhatsApp", "Meeting notice", null);
        service.sendMeetingInvites(SCHOOL_ID, req, ACTOR);

        verify(auditLogService).recordEvent(
                eq("ATTENDANCE_MEETING_INVITE_SENT"),
                eq(ACTOR.userId()),
                eq(SCHOOL_ID),
                anyString(), isNull(), isNull(), anyString());
    }
}
