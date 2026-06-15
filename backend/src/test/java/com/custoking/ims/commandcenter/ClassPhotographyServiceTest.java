package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.SendEventPaymentRemindersRequest;
import com.custoking.ims.entity.*;
import com.custoking.ims.model.AuthUser;
import com.custoking.ims.repo.AcademicEventRepository;
import com.custoking.ims.repo.EventStudentContributionRepository;
import com.custoking.ims.repo.NotificationLogRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClassPhotographyService")
class ClassPhotographyServiceTest {

    @Mock AcademicEventRepository academicEventRepository;
    @Mock EventStudentContributionRepository contributionRepository;
    @Mock NotificationLogRepository notificationLogRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks ClassPhotographyService service;

    private static final Long SCHOOL_ID = 10L;
    private static final Long OTHER_SCHOOL_ID = 99L;
    private static final String EVENT_ID = "evt-photo-test";
    private static final AuthUser ACTOR = AuthUser.identity(7L, "Admin User", "admin@school.com", "ADMIN", null, null);

    // ── helpers ───────────────────────────────────────────────────────────────

    private AcademicEventEntity activeEvent(Long schoolId) {
        AcademicEventEntity e = new AcademicEventEntity();
        e.setId(EVENT_ID);
        e.setSchoolId(schoolId);
        e.setTitle("Class Photography 2025–26");
        e.setEventType(ClassPhotographyService.EVENT_TYPE_CLASS_PHOTOGRAPHY);
        e.setEventDate(LocalDate.now().plusDays(18));
        e.setTotalBudget(4500000L);
        e.setSchoolContribution(2000000L);
        e.setStudentContributionTarget(2500000L);
        e.setStatus("ACTIVE");
        return e;
    }

    private AcademicEventEntity cancelledEvent(Long schoolId) {
        AcademicEventEntity e = activeEvent(schoolId);
        e.setStatus("CANCELLED");
        return e;
    }

    private StudentEntity student(Long id, Long schoolId, String contact) {
        SchoolEntity school = new SchoolEntity();
        school.setId(schoolId);

        SchoolClassEntity cls = new SchoolClassEntity();
        cls.setId("c1");
        cls.setName("Class 5");

        SchoolSectionEntity sec = new SchoolSectionEntity();
        sec.setId("s1");
        sec.setName("A");

        StudentEntity s = new StudentEntity();
        s.setId(id);
        s.setFullName("Student " + id);
        s.setAdmissionNo("ADM00" + id);
        s.setFatherContact(contact);
        s.setFatherName("Father " + id);
        s.setSchool(school);
        s.setSchoolClass(cls);
        s.setSection(sec);
        return s;
    }

    private EventStudentContributionEntity contribution(
            AcademicEventEntity event, StudentEntity student, long expected, long paid) {
        EventStudentContributionEntity c = new EventStudentContributionEntity();
        c.setId("ctr-" + student.getId());
        c.setEvent(event);
        c.setStudent(student);
        c.setSchoolId(SCHOOL_ID);
        c.setExpectedAmount(expected);
        c.setPaidAmount(paid);
        c.setStatus(paid >= expected ? "PAID" : (paid > 0 ? "PARTIAL" : "PENDING"));
        return c;
    }

    // ── test: pending amount is calculated by backend ─────────────────────────

    @Test
    @DisplayName("getPaymentStatus calculates pending amount as target minus collected")
    void getPaymentStatus_calculatesPendingAmount() {
        AcademicEventEntity event = activeEvent(SCHOOL_ID);
        StudentEntity s = student(1L, SCHOOL_ID, "9999999999");
        EventStudentContributionEntity c = contribution(event, s, 200000L, 80000L);

        when(academicEventRepository.findFirstBySchoolIdAndEventTypeAndStatus(
                SCHOOL_ID, ClassPhotographyService.EVENT_TYPE_CLASS_PHOTOGRAPHY, "ACTIVE"))
                .thenReturn(Optional.of(event));
        when(contributionRepository.findByEventAndSchool(
                eq(EVENT_ID), eq(SCHOOL_ID), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(c)));
        when(contributionRepository.sumPaidAmount(EVENT_ID)).thenReturn(80000L);

        var result = service.getPaymentStatus(SCHOOL_ID, null, null, null, 0, 20);

        assertThat(result.collectedAmount()).isEqualTo(80000L);
        assertThat(result.pendingAmount()).isEqualTo(2420000L); // 2500000 - 80000
        assertThat(result.students()).hasSize(1);
        assertThat(result.students().get(0).pendingAmount()).isEqualTo(120000L); // 200000 - 80000
    }

    // ── test: list is school-scoped ───────────────────────────────────────────

    @Test
    @DisplayName("getPaymentStatus returns empty when no active event exists for school")
    void getPaymentStatus_noActiveEvent_returnsEmpty() {
        when(academicEventRepository.findFirstBySchoolIdAndEventTypeAndStatus(
                SCHOOL_ID, ClassPhotographyService.EVENT_TYPE_CLASS_PHOTOGRAPHY, "ACTIVE"))
                .thenReturn(Optional.empty());

        var result = service.getPaymentStatus(SCHOOL_ID, null, null, null, 0, 20);

        assertThat(result.eventId()).isNull();
        assertThat(result.students()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    // ── test: reminder creates notification log ───────────────────────────────

    @Test
    @DisplayName("sendPaymentReminders creates notification log with SENT status")
    void sendPaymentReminders_createsNotificationLog() {
        AcademicEventEntity event = activeEvent(SCHOOL_ID);
        StudentEntity s = student(2L, SCHOOL_ID, "8888888888");
        EventStudentContributionEntity c = contribution(event, s, 200000L, 0L);

        when(academicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));
        when(contributionRepository.findByEvent_IdAndStudent_IdAndSchoolId(EVENT_ID, 2L, SCHOOL_ID))
                .thenReturn(Optional.of(c));

        var req = new SendEventPaymentRemindersRequest(List.of(2L), "SMS", "Photography payment due");
        var result = service.sendPaymentReminders(SCHOOL_ID, EVENT_ID, ACTOR, req);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();

        ArgumentCaptor<NotificationLogEntity> captor = ArgumentCaptor.forClass(NotificationLogEntity.class);
        verify(notificationLogRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
        assertThat(captor.getValue().getStudentId()).isEqualTo(2L);
        assertThat(captor.getValue().getChannel()).isEqualTo("SMS");
    }

    // ── test: inactive/cancelled event cannot send reminder ──────────────────

    @Test
    @DisplayName("sendPaymentReminders throws 422 for a CANCELLED event")
    void sendPaymentReminders_cancelledEvent_throws422() {
        AcademicEventEntity event = cancelledEvent(SCHOOL_ID);
        when(academicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        var req = new SendEventPaymentRemindersRequest(List.of(3L), "SMS", "msg");

        assertThatThrownBy(() -> service.sendPaymentReminders(SCHOOL_ID, EVENT_ID, ACTOR, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── test: cross-school event access is blocked ────────────────────────────

    @Test
    @DisplayName("sendPaymentReminders throws 403 when event belongs to different school")
    void sendPaymentReminders_crossSchoolEvent_throwsForbidden() {
        AcademicEventEntity event = activeEvent(OTHER_SCHOOL_ID); // different school
        when(academicEventRepository.findById(EVENT_ID)).thenReturn(Optional.of(event));

        var req = new SendEventPaymentRemindersRequest(List.of(4L), "SMS", "msg");

        assertThatThrownBy(() -> service.sendPaymentReminders(SCHOOL_ID, EVENT_ID, ACTOR, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
