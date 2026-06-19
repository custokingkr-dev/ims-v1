package com.custoking.ims.commandcenter;

import com.custoking.ims.entity.AcademicYearEntity;
import com.custoking.ims.repo.AcademicEventRepository;
import com.custoking.ims.repo.AcademicYearRepository;
import com.custoking.ims.repo.AttendanceDailyRepository;
import com.custoking.ims.repo.EventStudentContributionRepository;
import com.custoking.ims.repo.FeeAssignmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardCommandCenterService")
class DashboardCommandCenterServiceTest {

    @Mock AcademicYearRepository academicYearRepository;
    @Mock FeeAssignmentRepository feeAssignmentRepository;
    @Mock AttendanceDailyRepository attendanceDailyRepository;
    @Mock AcademicEventRepository academicEventRepository;
    @Mock EventStudentContributionRepository contributionRepository;
    @Mock com.custoking.ims.repo.StudentReviewItemRepository reviewItemRepository;
    @Mock VendorPaymentService vendorPaymentService;
    @Mock ReorderPredictionService reorderPredictionService;

    @InjectMocks DashboardCommandCenterService service;

    private static final Long SCHOOL_ID = 42L;
    private static final String AY_ID = "AY-2025-26";

    private AcademicYearEntity activeYear() {
        var ay = new AcademicYearEntity();
        ay.setId(AY_ID);
        ay.setLabel("2025–26");
        ay.setActive(true);
        return ay;
    }

    @Test
    @DisplayName("returns real fee metrics when active year and school exist")
    void getCommandCenter_withActiveYear_returnsFeeMetrics() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(feeAssignmentRepository.countOverdueByYearAndSchool(AY_ID, SCHOOL_ID)).thenReturn(12L);
        when(feeAssignmentRepository.sumOverdueAmountByYearAndSchool(AY_ID, SCHOOL_ID)).thenReturn(580000L);
        when(attendanceDailyRepository.countSectionsBelowThreshold(any(LocalDate.class), eq(AY_ID), eq(SCHOOL_ID), anyDouble()))
                .thenReturn(3L);

        var result = service.getCommandCenter(SCHOOL_ID);

        assertThat(result.fees().defaulterCount()).isEqualTo(12);
        assertThat(result.fees().totalOverdueAmountPaise()).isEqualTo(580000L);
    }

    @Test
    @DisplayName("returns low-attendance section count from repository")
    void getCommandCenter_returnsAttendanceBelowThreshold() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(feeAssignmentRepository.countOverdueByYearAndSchool(anyString(), eq(SCHOOL_ID))).thenReturn(0L);
        when(feeAssignmentRepository.sumOverdueAmountByYearAndSchool(anyString(), eq(SCHOOL_ID))).thenReturn(null);
        when(attendanceDailyRepository.countSectionsBelowThreshold(any(), eq(AY_ID), eq(SCHOOL_ID), anyDouble()))
                .thenReturn(5L);

        var result = service.getCommandCenter(SCHOOL_ID);

        assertThat(result.attendance().sectionsBelowThresholdCount()).isEqualTo(5);
        assertThat(result.attendance().thresholdPercent()).isEqualTo(75);
    }

    @Test
    @DisplayName("returns zeros when no active academic year exists")
    void getCommandCenter_noActiveYear_returnsZeros() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.empty());

        var result = service.getCommandCenter(SCHOOL_ID);

        assertThat(result.fees().defaulterCount()).isZero();
        assertThat(result.fees().totalOverdueAmountPaise()).isZero();
        assertThat(result.attendance().sectionsBelowThresholdCount()).isZero();
        verifyNoInteractions(feeAssignmentRepository, attendanceDailyRepository);
    }

    @Test
    @DisplayName("returns zeros for platform admin (null schoolId) without querying repositories")
    void getCommandCenter_platformAdmin_returnsZerosWithoutDbQueries() {
        var result = service.getCommandCenter(null);

        assertThat(result.fees().defaulterCount()).isZero();
        assertThat(result.fees().totalOverdueAmountPaise()).isZero();
        assertThat(result.attendance().sectionsBelowThresholdCount()).isZero();
        verifyNoInteractions(academicYearRepository, feeAssignmentRepository, attendanceDailyRepository);
    }

    @Test
    @DisplayName("handles null sum (no overdue rows) without NPE")
    void getCommandCenter_nullSumFromRepo_treatedAsZero() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(feeAssignmentRepository.countOverdueByYearAndSchool(AY_ID, SCHOOL_ID)).thenReturn(0L);
        when(feeAssignmentRepository.sumOverdueAmountByYearAndSchool(AY_ID, SCHOOL_ID)).thenReturn(null);
        when(attendanceDailyRepository.countSectionsBelowThreshold(any(), any(), eq(SCHOOL_ID), anyDouble()))
                .thenReturn(0L);

        var result = service.getCommandCenter(SCHOOL_ID);

        assertThat(result.fees().totalOverdueAmountPaise()).isZero();
    }

    @Test
    @DisplayName("photography section reflects active event data; lifecycle is always zero")
    void getCommandCenter_photographyFromEvent_lifecycleZero() {
        when(academicYearRepository.findFirstByActiveTrue()).thenReturn(Optional.of(activeYear()));
        when(feeAssignmentRepository.countOverdueByYearAndSchool(any(), any())).thenReturn(0L);
        when(feeAssignmentRepository.sumOverdueAmountByYearAndSchool(any(), any())).thenReturn(null);
        when(attendanceDailyRepository.countSectionsBelowThreshold(any(), any(), any(), anyDouble())).thenReturn(0L);
        when(academicEventRepository.findFirstBySchoolIdAndEventTypeAndStatus(
                any(), anyString(), anyString())).thenReturn(Optional.empty());

        var result = service.getCommandCenter(SCHOOL_ID);

        assertThat(result.photography().eventId()).isNull();
        assertThat(result.photography().collectedAmount()).isZero();
        assertThat(result.photography().pendingAmount()).isZero();
        assertThat(result.lifecycle().pendingReviewCount()).isZero();
        assertThat(result.lifecycle().longAbsenceCount()).isZero();
    }
}
