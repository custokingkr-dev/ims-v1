package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.SendFeeRemindersRequest;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FeeDefaulterService")
class FeeDefaulterServiceTest {

    @Mock AcademicYearRepository academicYearRepository;
    @Mock FeeAssignmentRepository feeAssignmentRepository;
    @Mock NotificationLogRepository notificationLogRepository;
    @Mock StudentRepository studentRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks FeeDefaulterService service;

    private static final Long SCHOOL_ID = 10L;
    private static final Long OTHER_SCHOOL_ID = 99L;
    private static final String AY_ID = "AY-2025";
    private static final AuthUser ACTOR = AuthUser.identity(7L, "Admin User", "admin@school.com", "ADMIN", null, null);

    // ── helpers ──────────────────────────────────────────────────────────────

    private AcademicYearEntity activeYear() {
        var ay = new AcademicYearEntity();
        ay.setId(AY_ID);
        ay.setActive(true);
        return ay;
    }

    private StudentEntity student(Long id, Long schoolId, String contact) {
        var school = new SchoolEntity();
        school.setId(schoolId);

        var cls = new SchoolClassEntity();
        cls.setId("c1");
        cls.setName("Class 5");

        var sec = new SchoolSectionEntity();
        sec.setId("s1");
        sec.setName("A");

        var s = new StudentEntity();
        s.setId(id);
        s.setFullName("Test Student " + id);
        s.setAdmissionNo("ADM00" + id);
        s.setFatherContact(contact);
        s.setFatherName("Father " + id);
        s.setSchool(school);
        s.setSchoolClass(cls);
        s.setSection(sec);
        return s;
    }

    private FeeAssignmentEntity feeAssignment(StudentEntity student, long netPayable, long paidAmount) {
        var fa = new FeeAssignmentEntity();
        fa.setId("fa-" + student.getId());
        fa.setStudent(student);
        fa.setNetPayable(netPayable);
        fa.setPaidAmount(paidAmount);
        fa.setAssignedAt(OffsetDateTime.now().minusDays(30));
        return fa;
    }

    // ── test: list is school-scoped ───────────────────────────────────────────

    @Test
    @DisplayName("fee defaulter list returns only active year's overdue students")
    void listDefaulters_returnsOverdueStudents() {
        var student = student(1L, SCHOOL_ID, "9999999999");
        var fa = feeAssignment(student, 50000L, 20000L);

        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(feeAssignmentRepository.findOverdueBySchool(eq(AY_ID), eq(SCHOOL_ID), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(fa)));
        when(notificationLogRepository.findByStudentIdInAndNotificationType(any(), any()))
                .thenReturn(List.of());
        when(feeAssignmentRepository.sumOverdueAmountByYearAndSchool(AY_ID, SCHOOL_ID)).thenReturn(30000L);
        when(feeAssignmentRepository.findOldestOverdueAssignedAt(AY_ID, SCHOOL_ID)).thenReturn(null);

        var result = service.listDefaulters(SCHOOL_ID, null, null, null, null, 0, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).studentId()).isEqualTo(1L);
        assertThat(result.items().get(0).dueAmount()).isEqualTo(30000L);
    }

    // ── test: backend calculates due amount ───────────────────────────────────

    @Test
    @DisplayName("due amount is calculated by backend as netPayable minus paidAmount")
    void listDefaulters_dueAmountCalculatedByBackend() {
        var student = student(2L, SCHOOL_ID, "8888888888");
        var fa = feeAssignment(student, 100000L, 40000L); // due = 60000

        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(feeAssignmentRepository.findOverdueBySchool(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(fa)));
        when(notificationLogRepository.findByStudentIdInAndNotificationType(any(), any()))
                .thenReturn(List.of());
        when(feeAssignmentRepository.sumOverdueAmountByYearAndSchool(any(), any())).thenReturn(60000L);
        when(feeAssignmentRepository.findOldestOverdueAssignedAt(any(), any())).thenReturn(null);

        var result = service.listDefaulters(SCHOOL_ID, null, null, null, null, 0, 20);

        assertThat(result.items().get(0).dueAmount()).isEqualTo(60000L);
    }

    // ── test: reminder creates notification log ───────────────────────────────

    @Test
    @DisplayName("sending reminder creates a notification log with SENT status")
    void sendReminders_createsNotificationLog() {
        var student = student(3L, SCHOOL_ID, "7777777777");
        when(studentRepository.findById(3L)).thenReturn(Optional.of(student));

        var req = new SendFeeRemindersRequest(List.of(3L), "SMS", "Fee overdue", "FEE_OVERDUE");
        var result = service.sendReminders(SCHOOL_ID, ACTOR, req);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();

        var captor = ArgumentCaptor.forClass(NotificationLogEntity.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
        assertThat(captor.getValue().getStudentId()).isEqualTo(3L);
        assertThat(captor.getValue().getChannel()).isEqualTo("SMS");
    }

    // ── test: reminder creates audit log ─────────────────────────────────────

    @Test
    @DisplayName("sending reminder records an audit event")
    void sendReminders_createsAuditLog() {
        var student = student(4L, SCHOOL_ID, "6666666666");
        when(studentRepository.findById(4L)).thenReturn(Optional.of(student));

        var req = new SendFeeRemindersRequest(List.of(4L), "WhatsApp", "Pay your fees", "FEE_OVERDUE");
        service.sendReminders(SCHOOL_ID, ACTOR, req);

        verify(auditLogService).recordEvent(
                eq("FEE_DEFAULTER_REMINDER_SENT"),
                eq(ACTOR.userId()),
                eq(SCHOOL_ID),
                eq("notification_logs"),
                contains("4"),
                isNull(),
                contains("DASHBOARD_COMMAND_CENTER")
        );
    }

    // ── test: missing parent contact is handled ───────────────────────────────

    @Test
    @DisplayName("student with no parent contact is failed gracefully")
    void sendReminders_missingContact_isFailedGracefully() {
        var student = student(5L, SCHOOL_ID, null); // no contact
        when(studentRepository.findById(5L)).thenReturn(Optional.of(student));

        var req = new SendFeeRemindersRequest(List.of(5L), "SMS", "msg", null);
        var result = service.sendReminders(SCHOOL_ID, ACTOR, req);

        assertThat(result.sentCount()).isZero();
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.failedItems().get(0).reason()).contains("parent contact");
        verify(notificationLogRepository, never()).save(any());
    }

    // ── test: cross-school access is rejected ─────────────────────────────────

    @Test
    @DisplayName("student belonging to different school throws 403")
    void sendReminders_crossSchool_throwsForbidden() {
        var student = student(6L, OTHER_SCHOOL_ID, "5555555555"); // different school
        when(studentRepository.findById(6L)).thenReturn(Optional.of(student));

        var req = new SendFeeRemindersRequest(List.of(6L), "SMS", "msg", null);

        assertThatThrownBy(() -> service.sendReminders(SCHOOL_ID, ACTOR, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
