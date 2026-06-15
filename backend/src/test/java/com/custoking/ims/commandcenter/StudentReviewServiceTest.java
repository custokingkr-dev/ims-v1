package com.custoking.ims.commandcenter;

import com.custoking.ims.audit.AuditLogService;
import com.custoking.ims.commandcenter.dto.*;
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
@DisplayName("StudentReviewService")
class StudentReviewServiceTest {

    @Mock StudentReviewCampaignRepository campaignRepository;
    @Mock StudentReviewItemRepository itemRepository;
    @Mock StudentRepository studentRepository;
    @Mock AcademicYearRepository academicYearRepository;
    @Mock AuditLogService auditLogService;

    @InjectMocks StudentReviewService service;

    private static final Long SCHOOL_ID = 10L;
    private static final AuthUser ACTOR = AuthUser.identity(7L, "Admin", "admin@school.com", "ADMIN", null, null);

    // ── helpers ───────────────────────────────────────────────────────────────

    private AcademicYearEntity activeYear() {
        AcademicYearEntity ay = new AcademicYearEntity();
        ay.setId("ay-2025");
        ay.setLabel("2025–26");
        ay.setActive(true);
        return ay;
    }

    private StudentEntity student(Long id) {
        SchoolEntity school = new SchoolEntity();
        school.setId(SCHOOL_ID);
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
        s.setSchool(school);
        s.setSchoolClass(cls);
        s.setSection(sec);
        return s;
    }

    private StudentReviewCampaignEntity activeCampaign(String id, String reviewType, Long schoolId) {
        StudentReviewCampaignEntity c = new StudentReviewCampaignEntity();
        c.setId(id);
        c.setSchoolId(schoolId);
        c.setReviewType(reviewType);
        c.setTitle("Test campaign");
        c.setStatus("ACTIVE");
        c.setVerifier("TEACHER");
        return c;
    }

    private StudentReviewItemEntity reviewItem(String id, StudentReviewCampaignEntity campaign,
                                                StudentEntity student) {
        StudentReviewItemEntity item = new StudentReviewItemEntity();
        item.setId(id);
        item.setCampaign(campaign);
        item.setStudent(student);
        item.setSchoolId(SCHOOL_ID);
        item.setStatus("PENDING");
        item.setCurrentFullName(student.getFullName());
        return item;
    }

    // ── test 1: ID card review initiation creates campaign and items ──────────

    @Test
    @DisplayName("initiateIdCardReview creates campaign and one item per student")
    void initiateIdCardReview_createsCampaignAndItems() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(campaignRepository.existsBySchoolIdAndReviewTypeAndStatus(
                SCHOOL_ID, StudentReviewService.REVIEW_TYPE_ID_CARD, "ACTIVE")).thenReturn(false);
        when(studentRepository.findBySchool_IdOrderByFullNameAsc(SCHOOL_ID))
                .thenReturn(List.of(student(1L), student(2L)));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByCampaign_Id(any())).thenReturn(2L);
        when(itemRepository.countByCampaign_IdAndStatus(any(), eq("COMPLETED"))).thenReturn(0L);
        when(itemRepository.countByCampaign_IdAndStatus(any(), eq("NEEDS_CORRECTION"))).thenReturn(0L);

        var req = new InitiateIdCardReviewRequest(null, null, LocalDate.now().plusDays(14), null);
        var result = service.initiateIdCardReview(SCHOOL_ID, ACTOR, req);

        assertThat(result.totalStudents()).isEqualTo(2L);
        assertThat(result.completed()).isZero();
        verify(itemRepository, times(2)).save(any(StudentReviewItemEntity.class));
        verify(auditLogService).recordEvent(eq("ID_CARD_REVIEW_INITIATED"), anyLong(),
                eq(SCHOOL_ID), anyString(), anyString(), isNull(), anyString());
    }

    // ── test 2: duplicate active ID card campaign is blocked ─────────────────

    @Test
    @DisplayName("initiateIdCardReview throws 409 when active campaign already exists")
    void initiateIdCardReview_duplicateActive_throws409() {
        when(campaignRepository.existsBySchoolIdAndReviewTypeAndStatus(
                SCHOOL_ID, StudentReviewService.REVIEW_TYPE_ID_CARD, "ACTIVE")).thenReturn(true);

        var req = new InitiateIdCardReviewRequest(null, null, null, null);
        assertThatThrownBy(() -> service.initiateIdCardReview(SCHOOL_ID, ACTOR, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // ── test 3: checklist update changes item status to COMPLETED ─────────────

    @Test
    @DisplayName("updateReviewItem auto-marks COMPLETED when all required checks pass")
    void updateReviewItem_allChecksPass_marksCompleted() {
        StudentReviewCampaignEntity campaign = activeCampaign("cmp-1", StudentReviewService.REVIEW_TYPE_ID_CARD, SCHOOL_ID);
        StudentReviewItemEntity item = reviewItem("item-1", campaign, student(3L));
        when(itemRepository.findByIdAndSchoolId("item-1", SCHOOL_ID)).thenReturn(Optional.of(item));

        var req = new UpdateReviewItemRequest(
                true, true, true, true, true, true, true, true, false,
                null, null);
        var result = service.updateReviewItem(SCHOOL_ID, "item-1", ACTOR, req);

        assertThat(result.status()).isEqualTo("COMPLETED");
    }

    // ── test 4: full name verification initiation creates campaign ────────────

    @Test
    @DisplayName("initiateFullNameVerification creates campaign and items for all school students")
    void initiateFullNameVerification_createsCampaignAndItems() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(campaignRepository.existsBySchoolIdAndReviewTypeAndStatus(
                SCHOOL_ID, StudentReviewService.REVIEW_TYPE_FULL_NAME, "ACTIVE")).thenReturn(false);
        when(studentRepository.findBySchool_IdOrderByFullNameAsc(SCHOOL_ID))
                .thenReturn(List.of(student(4L), student(5L), student(6L)));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByCampaign_Id(any())).thenReturn(3L);
        when(itemRepository.countByCampaign_IdAndStatus(any(), anyString())).thenReturn(0L);

        var req = new InitiateFullNameVerificationRequest(null, null, "TEACHER", LocalDate.now().plusDays(7));
        var result = service.initiateFullNameVerification(SCHOOL_ID, ACTOR, req);

        assertThat(result.totalStudents()).isEqualTo(3L);
        verify(itemRepository, times(3)).save(any(StudentReviewItemEntity.class));
    }

    // ── test 5: full name confirm updates status ──────────────────────────────

    @Test
    @DisplayName("verifyFullName with confirmed=true and TEACHER verifier marks item COMPLETED")
    void verifyFullName_confirmed_marksCompleted() {
        StudentReviewCampaignEntity campaign = activeCampaign("cmp-2", StudentReviewService.REVIEW_TYPE_FULL_NAME, SCHOOL_ID);
        StudentReviewItemEntity item = reviewItem("item-2", campaign, student(7L));
        when(itemRepository.findByIdAndSchoolId("item-2", SCHOOL_ID)).thenReturn(Optional.of(item));

        var req = new VerifyFullNameRequest(true, null, null);
        var result = service.verifyFullName(SCHOOL_ID, "item-2", ACTOR, req);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.teacherConfirmed()).isTrue();
    }

    // ── test 6: full name correction request saves suggested name ─────────────

    @Test
    @DisplayName("verifyFullName with confirmed=false saves correction request")
    void verifyFullName_correctionRequested_savesSuggestedName() {
        StudentReviewCampaignEntity campaign = activeCampaign("cmp-3", StudentReviewService.REVIEW_TYPE_FULL_NAME, SCHOOL_ID);
        StudentReviewItemEntity item = reviewItem("item-3", campaign, student(8L));
        when(itemRepository.findByIdAndSchoolId("item-3", SCHOOL_ID)).thenReturn(Optional.of(item));

        var req = new VerifyFullNameRequest(false, "Rahul Kumar Singh", "Middle name missing");
        var result = service.verifyFullName(SCHOOL_ID, "item-3", ACTOR, req);

        assertThat(result.status()).isEqualTo("NEEDS_CORRECTION");
        assertThat(result.correctionRequested()).isTrue();
        assertThat(result.suggestedFullName()).isEqualTo("Rahul Kumar Singh");
    }

    // ── test 7: review status calculations are correct ────────────────────────

    @Test
    @DisplayName("getIdCardReviewStatus returns correct completion percent")
    void getIdCardReviewStatus_correctCompletionPercent() {
        StudentReviewCampaignEntity campaign = activeCampaign("cmp-4", StudentReviewService.REVIEW_TYPE_ID_CARD, SCHOOL_ID);
        when(campaignRepository.findFirstBySchoolIdAndReviewTypeAndStatus(
                SCHOOL_ID, StudentReviewService.REVIEW_TYPE_ID_CARD, "ACTIVE"))
                .thenReturn(Optional.of(campaign));
        when(itemRepository.countByCampaign_Id("cmp-4")).thenReturn(100L);
        when(itemRepository.countByCampaign_IdAndStatus("cmp-4", "COMPLETED")).thenReturn(25L);
        when(itemRepository.countByCampaign_IdAndStatus("cmp-4", "NEEDS_CORRECTION")).thenReturn(5L);

        var result = service.getIdCardReviewStatus(SCHOOL_ID);

        assertThat(result.totalStudents()).isEqualTo(100L);
        assertThat(result.completed()).isEqualTo(25L);
        assertThat(result.needsCorrection()).isEqualTo(5L);
        assertThat(result.pending()).isEqualTo(70L);
        assertThat(result.completionPercent()).isEqualTo(25.0);
    }

    // ── test 8: cross-school access is blocked ────────────────────────────────

    @Test
    @DisplayName("updateReviewItem throws 404 when item belongs to different school")
    void updateReviewItem_crossSchool_notFound() {
        when(itemRepository.findByIdAndSchoolId("item-x", SCHOOL_ID)).thenReturn(Optional.empty());

        var req = new UpdateReviewItemRequest(true, null, null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.updateReviewItem(SCHOOL_ID, "item-x", ACTOR, req))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
